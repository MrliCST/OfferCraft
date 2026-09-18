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
