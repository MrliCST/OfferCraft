-- 短期记忆落库表：一行 = 一条消息
-- langchain4j 的 ChatMemoryStore 只有「读整个会话 / 覆盖整个会话 / 删整个会话」三个动作，
-- 所以表结构按 memory_id + 顺序存原始消息，读出来照原样反序列化即可。
CREATE TABLE IF NOT EXISTS chat_message
(
    id           BIGSERIAL   PRIMARY KEY,
    memory_id    VARCHAR(64) NOT NULL,
    msg_seq      INTEGER     NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    content      TEXT        NOT NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT now()
);

COMMENT ON TABLE chat_message IS 'langchain4j 短期记忆（对话窗口）落库表';
COMMENT ON COLUMN chat_message.memory_id IS '会话标识，对应 ChatMemory 的 memoryId';
COMMENT ON COLUMN chat_message.msg_seq IS '消息在会话内的顺序，从 0 开始';
COMMENT ON COLUMN chat_message.message_type IS 'SYSTEM / USER / AI / TOOL_EXECUTION_RESULT / CUSTOM';
COMMENT ON COLUMN chat_message.content IS '单条消息的 json，由 ChatMessageSerializer 生成，读出时原样反序列化';

CREATE INDEX IF NOT EXISTS idx_chat_message_memory_seq ON chat_message (memory_id, msg_seq);

-- ============================================================
-- 知识星球爬取 → 题库清洗管道表（PostgreSQL + pgvector）
-- 设计见 docs/zsxq-cleaning-pipeline-design.md §12
-- 图片策略：0 存储，zsxq_image 仅存 original_url + 多模态 description（向量独立召回）
-- 全部 IF NOT EXISTS，可随应用启动反复执行
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- 1) 原始爬取：血缘/审计，不进 RAG
CREATE TABLE IF NOT EXISTS zsxq_raw_post (
  id            BIGSERIAL PRIMARY KEY,
  group_id      TEXT,
  post_id       TEXT UNIQUE,
  src_column    TEXT,
  author        TEXT,
  author_role   TEXT,
  published_at  TIMESTAMPTZ,
  raw_markdown  TEXT,
  topic_tags    JSONB,
  source_url    TEXT,
  crawled_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_zsxq_raw_post_post_id ON zsxq_raw_post (post_id);
CREATE INDEX IF NOT EXISTS idx_zsxq_raw_post_src_column ON zsxq_raw_post (src_column);

-- 2) 回复：星主问答追溯；单独存可重跑清洗而不重爬
CREATE TABLE IF NOT EXISTS zsxq_reply (
  id              BIGSERIAL PRIMARY KEY,
  post_id         TEXT REFERENCES zsxq_raw_post(post_id),
  commenter       TEXT,
  commenter_role  TEXT,
  reply_text      TEXT,
  reply_time      TIMESTAMPTZ,
  is_star_master  BOOLEAN,
  reply_idx       INT
);
CREATE INDEX IF NOT EXISTS idx_zsxq_reply_post_id ON zsxq_reply (post_id);
CREATE INDEX IF NOT EXISTS idx_zsxq_reply_star ON zsxq_reply (is_star_master);

-- 3) 清洗后题库：可查询 / RAG 检索
CREATE TABLE IF NOT EXISTS cleaned_doc (
  doc_id               TEXT PRIMARY KEY,
  topic_key            TEXT,
  post_type            TEXT,
  author               TEXT,
  author_role          TEXT,
  published_at         TIMESTAMPTZ,
  content              TEXT,
  question             TEXT,
  authority_score      REAL,
  star_master_verified BOOLEAN,
  star_master_answer   TEXT,
  series_id            TEXT,
  series_prev          TEXT,
  series_next          TEXT,
  keep_images          BOOLEAN,
  raw_post_id          TEXT REFERENCES zsxq_raw_post(post_id),
  source_url           TEXT,
  -- 分块后向量统一落在 zsxq_chunk，此列保留备用（将来若要整篇级召回或换回单向量方案）
  embedding            vector(1024),
  superseded           BOOLEAN DEFAULT FALSE,
  ingest_at            TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cleaned_doc_keys ON cleaned_doc (topic_key, post_type, published_at);
CREATE INDEX IF NOT EXISTS idx_cleaned_doc_superseded ON cleaned_doc (superseded) WHERE superseded = FALSE;
CREATE INDEX IF NOT EXISTS idx_cleaned_doc_raw_post_id ON cleaned_doc (raw_post_id);

-- 4) 图片血缘：0 存储，仅保留 original_url + 多模态概括，独立进向量库可召回查看
--    doc_id 是指向 cleaned_doc 的血缘键（可为空：帖子被丢弃时图不登记）。
--    有它才能「按文档重登记」（重跑先删该文档的图）和「从图回查权威分」。
CREATE TABLE IF NOT EXISTS zsxq_image (
  id           BIGSERIAL PRIMARY KEY,
  post_id      TEXT REFERENCES zsxq_raw_post(post_id),
  doc_id       TEXT REFERENCES cleaned_doc(doc_id) ON DELETE CASCADE,
  seq          INT,
  original_url TEXT,
  kind         TEXT,
  description  TEXT,
  kept         BOOLEAN,
  embedding    vector(1024),
  ingest_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_zsxq_image_post_id ON zsxq_image (post_id);
CREATE INDEX IF NOT EXISTS idx_zsxq_image_doc_id ON zsxq_image (doc_id);

-- 5) 分块：星主长文实测 1.4~2.5 万字，远超 embedding 模型单次输入上限，
--    整篇做一个向量会截断丢内容、且召回粒度太粗，所以按 Markdown 标题切成多块，
--    短帖切成 1 块。检索走 chunk，命中后再回关联 cleaned_doc 取权威分做加权。
--    embedded_at 为 NULL 表示还没向量化，断点续跑就挑这些。
CREATE TABLE IF NOT EXISTS zsxq_chunk (
  chunk_id     TEXT PRIMARY KEY,
  doc_id       TEXT REFERENCES cleaned_doc(doc_id) ON DELETE CASCADE,
  seq          INT,
  heading      TEXT,
  content      TEXT,
  char_len     INT,
  embedding    vector(1024),
  embedded_at  TIMESTAMPTZ,
  ingest_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_zsxq_chunk_embedding ON zsxq_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_zsxq_chunk_doc ON zsxq_chunk (doc_id);
CREATE INDEX IF NOT EXISTS idx_zsxq_chunk_pending ON zsxq_chunk (doc_id) WHERE embedded_at IS NULL;

-- ============================================================
-- 维度迁移：1536 → 1024（百炼 text-embedding-v3 的维度）
-- 表已存在时 CREATE TABLE IF NOT EXISTS 不会改列类型，只能显式 ALTER；
-- 而 hnsw 索引绑定列类型，必须删了再建。用 DO block 判当前维度，幂等可反复跑。
-- ============================================================
DO $$
DECLARE
    cur_dim INT;
BEGIN
    SELECT atttypmod INTO cur_dim
      FROM pg_attribute
     WHERE attrelid = 'cleaned_doc'::regclass AND attname = 'embedding' AND attnum > 0;
    IF cur_dim IS NOT NULL AND cur_dim <> 1024 THEN
        DROP INDEX IF EXISTS idx_cleaned_doc_embedding;
        ALTER TABLE cleaned_doc ALTER COLUMN embedding TYPE vector(1024);
    END IF;

    SELECT atttypmod INTO cur_dim
      FROM pg_attribute
     WHERE attrelid = 'zsxq_image'::regclass AND attname = 'embedding' AND attnum > 0;
    IF cur_dim IS NOT NULL AND cur_dim <> 1024 THEN
        DROP INDEX IF EXISTS idx_zsxq_image_embedding;
        ALTER TABLE zsxq_image ALTER COLUMN embedding TYPE vector(1024);
    END IF;
END $$;

-- ============================================================
-- 结构迁移：zsxq_image 补 doc_id 血缘列
-- 老库上表已存在，CREATE TABLE IF NOT EXISTS 不会补列，只能显式 ALTER。
-- DO block 判列是否存在，幂等可反复跑。
-- ============================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'zsxq_image' AND column_name = 'doc_id') THEN
        ALTER TABLE zsxq_image ADD COLUMN doc_id TEXT REFERENCES cleaned_doc(doc_id) ON DELETE CASCADE;
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_zsxq_image_doc_id ON zsxq_image (doc_id);
-- 待概括的图：有地址、没描述。部分索引，避免全表扫
CREATE INDEX IF NOT EXISTS idx_zsxq_image_pending ON zsxq_image (id) WHERE description IS NULL;

-- 索引在迁移之后建：迁移里可能刚把索引删掉，这里保证最终存在
CREATE INDEX IF NOT EXISTS idx_cleaned_doc_embedding ON cleaned_doc USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_zsxq_image_embedding ON zsxq_image USING hnsw (embedding vector_cosine_ops);
