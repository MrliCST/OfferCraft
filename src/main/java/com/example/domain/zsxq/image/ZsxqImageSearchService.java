package com.example.domain.zsxq.image;

import java.util.List;

import org.springframework.stereotype.Service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;

/**
 * 图片检索：自然语言 → 图片描述 → 命中图（description + original_url）。
 *
 * <p>这是「图片描述向量」的下游消费点。装配在
 * {@link ZsxqImageEmbeddingStore}（跑 SQL）+ {@code EmbeddingModel}（查询向量化）之上，
 * 对调用方只暴露一个方法 —— agent 侧要接成 tool 或 retriever 都很薄。
 *
 * <p>为什么单独有这一层而不是让调用方自己拼 store + model：查询向量化和搜索是
 * <b>必须一起做对</b>的两步（用错模型算查询向量 = 全库乱配），收在一处就不会有人漏。
 * 与 {@code ZsxqVectorService} 只管文本侧、{@link ZsxqImageService} 只管写侧是同一个分工。
 */
@Service
public class ZsxqImageSearchService {

    private final ZsxqImageEmbeddingStore store;
    private final EmbeddingModel embeddingModel;

    public ZsxqImageSearchService(ZsxqImageEmbeddingStore zsxqImageEmbeddingStore,
                                  EmbeddingModel zsxqEmbeddingModel) {
        this.store = zsxqImageEmbeddingStore;
        this.embeddingModel = zsxqEmbeddingModel;
    }

    /**
     * 按自然语言查图。
     *
     * @param query      自然语言问题，例「初始化流程有哪些步骤」
     * @param maxResults 最多返回几张
     * @param minScore   相似度下限，低于它的丢掉
     * @return 命中的图，按分数从高到低；每项带 description + original_url 与所属文档元数据
     */
    public EmbeddingSearchResult<TextSegment> search(String query, int maxResults, double minScore) {
        return store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .maxResults(maxResults)
                .minScore(minScore)
                .build());
    }

    /** 便捷形态：只要命中的图，不要外层的 result 包装。 */
    public List<EmbeddingMatch<TextSegment>> topImages(String query, int maxResults, double minScore) {
        return search(query, maxResults, minScore).matches();
    }
}
