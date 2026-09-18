-- ZSXQ 清洗管道库表 DDL（PostgreSQL + pgvector）
-- 落库日期：2026-09-18
-- 字段增补：cleaned_doc.question / cleaned_doc.raw_post_id
-- 修正：src_column 改名（原 column 为 SQL 保留字）、raw_post.post_id 设 UNIQUE 以承载外键
-- 图片策略（2026-09-18 22:55）：0 存储，zsxq_image 仅存 original_url + 多模态 description（向量独立召回），不落本地文件
-- 前置：pgvector 已安装（CREATE EXTENSION vector 需 superuser 或已授权）

CREATE EXTENSION IF NOT EXISTS vector;

-- 1) 原始爬取：血缘/审计，不进 RAG
CREATE TABLE zsxq_raw_post (
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
CREATE INDEX ON zsxq_raw_post (post_id);
CREATE INDEX ON zsxq_raw_post (src_column);

-- 2) 回复：星主问答追溯；单独存可重跑清洗而不重爬
CREATE TABLE zsxq_reply (
  id              BIGSERIAL PRIMARY KEY,
  post_id         TEXT REFERENCES zsxq_raw_post(post_id),
  commenter       TEXT,
  commenter_role  TEXT,
  reply_text      TEXT,
  reply_time      TIMESTAMPTZ,
  is_star_master  BOOLEAN,
  reply_idx       INT
);
CREATE INDEX ON zsxq_reply (post_id);
CREATE INDEX ON zsxq_reply (is_star_master);

-- 3) 清洗后题库：可查询 / RAG 检索
CREATE TABLE cleaned_doc (
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
CREATE INDEX ON cleaned_doc USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON cleaned_doc (topic_key, post_type, published_at);
CREATE INDEX ON cleaned_doc (superseded) WHERE superseded = FALSE;
CREATE INDEX ON cleaned_doc (raw_post_id);

-- 4) 图片血缘：0 存储，仅保留 original_url + 多模态概括，独立进向量库可召回查看
CREATE TABLE zsxq_image (
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
CREATE INDEX ON zsxq_image USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON zsxq_image (post_id);
