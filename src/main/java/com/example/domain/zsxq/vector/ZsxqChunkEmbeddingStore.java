package com.example.domain.zsxq.vector;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * {@code zsxq_chunk} 的 {@link EmbeddingStore} 适配层——整个 S7 里唯一自己写的一层。
 *
 * <p>为什么不直接用官方的 {@code PgVectorEmbeddingStore}：它的建表 SQL 是硬编码的
 * （{@code embedding_id UUID / embedding / text / metadata JSONB}，列名不可配），套不进我们
 * 已经建好的 {@code zsxq_chunk}。而这张表的三个设计是不能让步的：
 * <ul>
 *   <li><b>chunk_id 确定性</b>（{@code <doc_id>#<seq>}）：重跑是 upsert，不会越跑越多；</li>
 *   <li><b>heading 独立列</b>：召回结果要能说清「出自哪一节」；</li>
 *   <li><b>embedded_at</b>：NULL 表示待向量化，中断后接着跑不用从头再来。</li>
 * </ul>
 * 写了这一层，上层就全是官方组件了：入库走 {@code EmbeddingStoreIngestor}，检索走
 * {@code EmbeddingStoreContentRetriever}，将来面试 agent 直接 {@code @AiService(contentRetriever=...)}。
 *
 * <p>块 id 不靠 {@link #generateIds} 的随机 UUID——重写了 {@link #addAll(List, List)}，
 * 从 {@link TextSegment} 的元数据里取 {@code doc_id} 和 {@code index} 拼成确定性 id。
 * 官方 ingestor 内部调的就是这个方法，所以整条链都还是官方的。
 */
@Service
public class ZsxqChunkEmbeddingStore implements EmbeddingStore<TextSegment> {

    private static final String UPSERT = """
            INSERT INTO zsxq_chunk (chunk_id, doc_id, seq, heading, content, char_len, embedding, embedded_at)
            VALUES (?,?,?,?,?,?,?::vector, now())
            ON CONFLICT (chunk_id) DO UPDATE SET
              doc_id     = EXCLUDED.doc_id,
              seq        = EXCLUDED.seq,
              heading    = EXCLUDED.heading,
              content    = EXCLUDED.content,
              char_len   = EXCLUDED.char_len,
              embedding  = EXCLUDED.embedding,
              embedded_at = now()
            """;

    /**
     * 向量召回。元数据（作者/权威分/类型）不冗余存在 chunk 里，join {@code cleaned_doc} 取，
     * 这样权威分改了不用重跑向量化。
     */
    private static final String SEARCH = """
            SELECT c.chunk_id, c.doc_id, c.seq, c.heading, c.content,
                   1 - (c.embedding <=> ?::vector) AS score,
                   d.post_type, d.author, d.authority_score, d.topic_key, d.source_url
            FROM zsxq_chunk c
            JOIN cleaned_doc d ON d.doc_id = c.doc_id
            WHERE c.embedding IS NOT NULL
            ORDER BY c.embedding <=> ?::vector
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public ZsxqChunkEmbeddingStore(JdbcTemplate zsxqJdbcTemplate) {
        this.jdbc = zsxqJdbcTemplate;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        if (embeddings == null || embeddings.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            TextSegment s = segments.get(i);
            String chunkId = chunkIdOf(s);
            jdbc.update(UPSERT, chunkId, s.metadata().getString(MarkdownChunker.MD_DOC_ID),
                    s.metadata().getInteger(MarkdownChunker.MD_INDEX),
                    s.metadata().getString(MarkdownChunker.MD_HEADING),
                    s.text(), s.text().length(), toVectorLiteral(embeddings.get(i)));
            ids.add(chunkId);
        }
        return ids;
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        if (request.filter() != null) {
            // 宁可明着报错，也不能悄悄忽略过滤条件——那会变成查不准还查不出原因的 bug
            throw new UnsupportedOperationException(
                    "zsxq 检索暂不支持 Filter，需要时按 post_type / doc_id 等值条件扩展 SEARCH 里的 WHERE");
        }
        String vector = toVectorLiteral(request.queryEmbedding());
        int maxResults = request.maxResults();
        double minScore = request.minScore();

        List<EmbeddingMatch<TextSegment>> matches = jdbc.query(SEARCH,
                (rs, row) -> {
                    double score = rs.getDouble("score");
                    TextSegment segment = TextSegment.from(rs.getString("content"),
                            dev.langchain4j.data.document.Metadata.from(java.util.Map.<String, Object>of(
                                    "doc_id", rs.getString("doc_id"),
                                    "chunk_id", rs.getString("chunk_id"),
                                    "heading", rs.getString("heading") == null ? "" : rs.getString("heading"),
                                    "post_type", rs.getString("post_type"),
                                    "author", rs.getString("author"),
                                    "authority_score", rs.getDouble("authority_score"),
                                    "topic_key", rs.getString("topic_key"),
                                    "source_url", rs.getString("source_url"))));
                    return new EmbeddingMatch<TextSegment>(score, rs.getString("chunk_id"), null, segment);
                },
                vector, vector, maxResults);

        List<EmbeddingMatch<TextSegment>> kept = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : matches) {
            if (m.score() >= minScore) {
                kept.add(m);
            }
        }
        return new EmbeddingSearchResult<>(kept);
    }

    /** 删除某个 id 的块。 */
    @Override
    public void remove(String id) {
        jdbc.update("DELETE FROM zsxq_chunk WHERE chunk_id = ?", id);
    }

    @Override
    public void removeAll(java.util.Collection<String> ids) {
        for (String id : ids) {
            remove(id);
        }
    }

    @Override
    public void removeAll() {
        jdbc.update("DELETE FROM zsxq_chunk");
    }

    // 下面三个是接口要求实现、但这个库用不上的形态：块必须带 doc_id 元数据才能入库，
    // 单独塞一个向量进来没有归属，宁可报错也不要写进无主的行
    /** 单个块入库，等价于 addAll 的一条。 */
    @Override
    public String add(Embedding embedding, TextSegment segment) {
        return addAll(List.of(embedding), List.of(segment)).get(0);
    }

    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException("zsxq_chunk 的块必须带 doc_id 元数据，请用 addAll(embeddings, segments)");
    }

    @Override
    public void add(String id, Embedding embedding) {
        throw new UnsupportedOperationException("zsxq_chunk 的块必须带 doc_id 元数据，请用 addAll(embeddings, segments)");
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw new UnsupportedOperationException("zsxq_chunk 的块必须带 doc_id 元数据，请用 addAll(embeddings, segments)");
    }

    /** 块 id = {@code <doc_id>#<seq>}，跟分块序号绑定，所以重跑是同一批 id。 */
    static String chunkIdOf(TextSegment s) {
        String docId = s.metadata().getString(MarkdownChunker.MD_DOC_ID);
        Integer index = s.metadata().getInteger(MarkdownChunker.MD_INDEX);
        return docId + "#" + (index == null ? 0 : index);
    }

    /** pgvector 的字面量写法：[v1,v2,...]，写库时用 ?::vector 转型。 */
    static String toVectorLiteral(Embedding embedding) {
        float[] v = embedding.vector();
        StringBuilder sb = new StringBuilder(v.length * 12 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
