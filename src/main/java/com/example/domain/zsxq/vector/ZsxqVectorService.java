package com.example.domain.zsxq.vector;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;

/**
 * S7 的编排：挑出还没向量化的文档 → 组装成 {@link Document}（正文 + 元数据）→ 交给官方
 * {@link EmbeddingStoreIngestor}（切块 → 批量向量化 → 写 zsxq_chunk）。
 *
 * <p>这里刻意不自己写「循环切块、分批调接口、失败重算」那套胶水——那些官方 ingestor 都做了。
 * 我们只负责两件 langchain4j 不知道的事：
 * <ol>
 *   <li><b>挑谁要跑</b>：库里查「还没有块」或「有块没向量」的文档，断点续跑靠这个；</li>
 *   <li><b>带上元数据</b>：作者、权威分、类型等挂到 Document 上，会被每个块继承，
 *       检索时由 {@link ZsxqChunkEmbeddingStore} 从 cleaned_doc join 回来。</li>
 * </ol>
 *
 * <p>重跑一篇文档前会先删掉它的旧块：内容改短了可能少切出一块，不删就会留下孤儿。
 * 代价是这篇的向量整篇重算——反正只有内容变了的文档才会被挑出来，几百块的量不值得精算。
 */
@Service
public class ZsxqVectorService {

    /** 待向量化的文档：一篇块都没有，或者有块但还有没嵌入的。 */
    private static final String PENDING_DOCS = """
            SELECT doc_id, content, post_type, author, author_role, authority_score, topic_key, published_at
            FROM cleaned_doc d
            WHERE d.superseded = FALSE
              AND (NOT EXISTS (SELECT 1 FROM zsxq_chunk c WHERE c.doc_id = d.doc_id)
                   OR EXISTS (SELECT 1 FROM zsxq_chunk c WHERE c.doc_id = d.doc_id AND c.embedded_at IS NULL))
            ORDER BY d.published_at
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final EmbeddingStoreIngestor ingestor;

    public ZsxqVectorService(JdbcTemplate zsxqJdbcTemplate, EmbeddingStoreIngestor zsxqIngestor) {
        this.jdbc = zsxqJdbcTemplate;
        this.ingestor = zsxqIngestor;
    }

    /**
     * 向量化最多 {@code limit} 篇待处理文档。
     *
     * @return 本次处理的篇数，以及处理后的块总数 / 已嵌入数
     */
    public VectorizeStats vectorize(int limit) {
        List<DocRow> docs = jdbc.query(PENDING_DOCS,
                (rs, i) -> new DocRow(rs.getString("doc_id"), rs.getString("content"), rs.getString("post_type"),
                        rs.getString("author"), rs.getString("author_role"), rs.getDouble("authority_score"),
                        rs.getString("topic_key"), rs.getString("published_at")),
                limit);

        for (DocRow d : docs) {
            jdbc.update("DELETE FROM zsxq_chunk WHERE doc_id = ?", d.docId());
            ingestor.ingest(Document.from(d.content(), metadataOf(d)));
        }
        return new VectorizeStats(docs.size(), countChunks(), countEmbedded());
    }

    /** 文档级元数据，会被切出来的每个块继承。 */
    private static Metadata metadataOf(DocRow d) {
        return Metadata.from(Map.<String, Object>of(
                MarkdownChunker.MD_DOC_ID, d.docId(),
                "post_type", nullToEmpty(d.postType()),
                "author", nullToEmpty(d.author()),
                "author_role", nullToEmpty(d.authorRole()),
                "authority_score", d.authorityScore(),
                "topic_key", nullToEmpty(d.topicKey()),
                "published_at", nullToEmpty(d.publishedAt())));
    }

    public int countChunks() {
        return jdbc.queryForObject("SELECT count(*) FROM zsxq_chunk", Integer.class);
    }

    public int countEmbedded() {
        return jdbc.queryForObject("SELECT count(*) FROM zsxq_chunk WHERE embedded_at IS NOT NULL", Integer.class);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 一篇待处理的文档。 */
    private record DocRow(String docId, String content, String postType, String author, String authorRole,
                          double authorityScore, String topicKey, String publishedAt) {
    }

    /** 本次向量化的结果。 */
    public record VectorizeStats(int docs, int chunks, int embedded) {
    }
}
