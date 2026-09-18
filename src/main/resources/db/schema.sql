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
  embedding            vector(1536),
  superseded           BOOLEAN DEFAULT FALSE,
  ingest_at            TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cleaned_doc_embedding ON cleaned_doc USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_cleaned_doc_keys ON cleaned_doc (topic_key, post_type, published_at);
CREATE INDEX IF NOT EXISTS idx_cleaned_doc_superseded ON cleaned_doc (superseded) WHERE superseded = FALSE;
CREATE INDEX IF NOT EXISTS idx_cleaned_doc_raw_post_id ON cleaned_doc (raw_post_id);

-- 4) 图片血缘：0 存储，仅保留 original_url + 多模态概括，独立进向量库可召回查看
CREATE TABLE IF NOT EXISTS zsxq_image (
  id           BIGSERIAL PRIMARY KEY,
  post_id      TEXT REFERENCES zsxq_raw_post(post_id),
  seq          INT,
  original_url TEXT,
  kind         TEXT,
  description  TEXT,
  kept         BOOLEAN,
  embedding    vector(1536),
  ingest_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_zsxq_image_embedding ON zsxq_image USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_zsxq_image_post_id ON zsxq_image (post_id);
