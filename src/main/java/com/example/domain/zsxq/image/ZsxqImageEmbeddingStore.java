package com.example.domain.zsxq.image;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * {@code zsxq_image} 的 {@link EmbeddingStore} 适配层 —— 图片召回的<b>唯一通路</b>。
 *
 * <p>与 {@link com.example.domain.zsxq.vector.ZsxqChunkEmbeddingStore} 同一套路数：
 * 自己写一层薄的 SQL 适配，把上层留给官方组件。检索侧拿到这个 store 就能交给
 * {@code EmbeddingStoreContentRetriever}，或者由 agent 直接当 tool 用。
 *
 * <p><b>为什么图片要有独立通路，而不是靠正文顺带召回</b>（2026-09-19 拍的）：
 * 正文里的图片只留标识 {@code ![图片.png](url)}，描述不回填。若把描述塞进正文块里，
 * 向量中心会变成「整块文本的语义中心」，一张图讲 A、周围文字讲 B 就被稀释了；
 * 切块还可能把描述切到与它无关的块里。独立成向量后，{@code embedding} 就是从
 * 「这张图是什么」算出来的，向量中心就是图本身。
 *
 * <p>所以文本召回走 {@code zsxq_chunk}、图片召回走 {@code zsxq_image}，两条独立跑道，
 * 互不干扰。命中的图返回 {@code description} + {@code original_url}，点 URL 即看原图。
 *
 * <p>元数据（post_type / authority_score / source_url）不冗余存在 {@code zsxq_image} 里，
 * 检索时 join {@code cleaned_doc} 取 —— 这样权威分改了不用重跑向量化，与 chunk 侧一致。
 */
@Service
public class ZsxqImageEmbeddingStore implements EmbeddingStore<TextSegment> {

    /**
     * 向量召回。<b>只召回有描述的图</b>（{@code embedded_at IS NOT NULL} 等价于
     * {@code embedding} 非空），没向量化的图不参与。
     *
     * <p>{@code text} 列返回的是 {@code description} —— 对上层来说「这张图的文本表示」
     * 就是它的描述，与 {@code zsxq_chunk} 返回块正文是同一个角色。
     */
    private static final String SEARCH = """
            SELECT i.id, i.doc_id, i.original_url, i.description, i.seq,
                   1 - (i.embedding <=> ?::vector) AS score,
                   d.post_type, d.author, d.authority_score, d.topic_key, d.source_url AS doc_source_url
            FROM zsxq_image i
            JOIN cleaned_doc d ON d.doc_id = i.doc_id
            WHERE i.embedded_at IS NOT NULL
            ORDER BY i.embedding <=> ?::vector
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public ZsxqImageEmbeddingStore(JdbcTemplate zsxqJdbcTemplate) {
        this.jdbc = zsxqJdbcTemplate;
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        if (request.filter() != null) {
            // 与 chunk 侧一致：宁可明着报错，也不悄悄忽略过滤条件
            throw new UnsupportedOperationException(
                    "zsxq 图片检索暂不支持 Filter，需要时按 post_type / doc_id 等值条件扩展 SEARCH 里的 WHERE");
        }
        String vector = toVectorLiteral(request.queryEmbedding());
        int maxResults = request.maxResults();
        double minScore = request.minScore();

        List<EmbeddingMatch<TextSegment>> matches = jdbc.query(SEARCH,
                (rs, row) -> {
                    String description = rs.getString("description");
                    TextSegment segment = TextSegment.from(description == null ? "" : description,
                            Metadata.from(Map.<String, Object>of(
                                    "image_id", rs.getLong("id"),
                                    "doc_id", rs.getString("doc_id"),
                                    "seq", rs.getInt("seq"),
                                    "original_url", nullToEmpty(rs.getString("original_url")),
                                    "post_type", nullToEmpty(rs.getString("post_type")),
                                    "author", nullToEmpty(rs.getString("author")),
                                    "authority_score", rs.getDouble("authority_score"),
                                    "topic_key", nullToEmpty(rs.getString("topic_key")),
                                    "source_url", nullToEmpty(rs.getString("doc_source_url")))));
                    return new EmbeddingMatch<TextSegment>(
                            rs.getDouble("score"), String.valueOf(rs.getLong("id")), null, segment);
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

    /**
     * 这个库不用 {@code add(...)}：图片向量由 {@link ZsxqImageService} 在落描述时
     * 一并算出写入（那条路要顺带写 {@code embedded_at}），走这里会绕过完成标记。
     * 明着拒绝比写进一行「有向量没标记」的半成品强。
     */
    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException("zsxq_image 的向量由 ZsxqImageService 写入，请用 --describe / --embed");
    }

    @Override
    public void add(String id, Embedding embedding) {
        throw new UnsupportedOperationException("zsxq_image 的向量由 ZsxqImageService 写入，请用 --describe / --embed");
    }

    @Override
    public String add(Embedding embedding, TextSegment segment) {
        throw new UnsupportedOperationException("zsxq_image 的向量由 ZsxqImageService 写入，请用 --describe / --embed");
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw new UnsupportedOperationException("zsxq_image 的向量由 ZsxqImageService 写入，请用 --describe / --embed");
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        throw new UnsupportedOperationException("zsxq_image 的向量由 ZsxqImageService 写入，请用 --describe / --embed");
    }

    @Override
    public void remove(String id) {
        jdbc.update("UPDATE zsxq_image SET embedding = NULL, embedded_at = NULL WHERE id = ?::bigint", id);
    }

    @Override
    public void removeAll(Collection<String> ids) {
        for (String id : ids) {
            remove(id);
        }
    }

    /** 清空所有图片向量（保留 description）—— 换嵌入模型后重算时用。 */
    @Override
    public void removeAll() {
        jdbc.update("UPDATE zsxq_image SET embedding = NULL, embedded_at = NULL WHERE embedded_at IS NOT NULL");
    }

    /** pgvector 的字面量写法：[v1,v2,...]，查询时用 ?::vector 转型。 */
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

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
