package com.example.domain.zsxq.vector;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;

/**
 * S7 向量化的装配：向量模型 + 切块器 + 块存储 + 官方入库链。
 *
 * <p>连接参数从 {@link Environment} 读而不是用 {@code @ConfigurationProperties}：
 * CLI 走的是轻量 {@code AnnotationConfigApplicationContext}（不 boot WebFlux），
 * 没有 Boot 的属性绑定基础设施，yml 也不会被加载。所以这里「环境变量优先、yml 兜底」——
 * 直接跑 CLI 时靠环境变量 {@code DASHSCOPE_API_KEY}，在应用里跑时 {@code llm.dashscope.*}
 * 已由 yml + ~/.config/JLRADemo/secret.yml 解析好，两条路都能取到。
 * 跟 {@code ZsxqIngestConfig} 读 PG_* 是同一个套路。
 *
 * <p>两个值有讲究，不是随手填：
 * <ul>
 *   <li>{@code dimensions}：百炼 v3 支持 1024/768/512，schema 里列是 {@code vector(1024)}，
 *       必须显式传，否则模型给默认维度，写库时长度对不上直接报错；</li>
 *   <li>{@code maxSegmentsPerBatch}：百炼兼容模式单次请求最多 10 条，langchain4j 默认 16，
 *       不压到 10 会被服务端拒。</li>
 * </ul>
 */
@Configuration
public class ZsxqVectorConfig {

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String DEFAULT_MODEL = "text-embedding-v3";
    private static final int DEFAULT_DIMENSIONS = 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** 百炼兼容模式单批上限 10 条。 */
    private static final int MAX_SEGMENTS_PER_BATCH = 10;
    private static final int MAX_RETRIES = 2;

    private final Environment env;
    private final JdbcTemplate jdbc;

    /** jdbc 由 {@code ZsxqIngestConfig} 提供，两个配置类要一起注册进上下文。 */
    public ZsxqVectorConfig(Environment env, JdbcTemplate zsxqJdbcTemplate) {
        this.env = env;
        this.jdbc = zsxqJdbcTemplate;
    }

    @Bean
    public EmbeddingModel zsxqEmbeddingModel() {
        String apiKey = firstNonBlank(
                env.getProperty("llm.dashscope.api-key"),
                env.getProperty("DASHSCOPE_API_KEY"),
                // 本机 ragent 项目就用的这个名字，值一样（百炼兼容端点，1024 维）——
                // 不强制再导出一次 DASHSCOPE_API_KEY，两个名字都认
                env.getProperty("BAILIAN_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            // 早点说清楚缺什么，比让接口返回 401 再让人猜强
            throw new IllegalStateException("缺少百炼密钥：设 DASHSCOPE_API_KEY 或 BAILIAN_API_KEY 环境变量，"
                    + "或写在 ~/.config/JLRADemo/secret.yml 里（仓库外，权限 600）。");
        }
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(firstNonBlank(env.getProperty("llm.dashscope.base-url"), DEFAULT_BASE_URL))
                .modelName(firstNonBlank(env.getProperty("llm.dashscope.model-name"), DEFAULT_MODEL))
                .dimensions(Integer.parseInt(
                        firstNonBlank(env.getProperty("llm.dashscope.dimensions"), String.valueOf(DEFAULT_DIMENSIONS))))
                .maxSegmentsPerBatch(MAX_SEGMENTS_PER_BATCH)
                .maxRetries(MAX_RETRIES)
                .timeout(TIMEOUT)
                // 请求/响应日志都关：向量化一次几百段，打日志会刷屏，embedding 原文也没有排查价值
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    /** 切块器：标题分节是我们自己的实现，对外就是官方 DocumentSplitter 接口。 */
    @Bean
    public DocumentSplitter zsxqDocumentSplitter() {
        return new MarkdownChunker();
    }

    /** 块存储：zsxq_chunk 的薄适配层。 */
    @Bean
    public EmbeddingStore<TextSegment> zsxqChunkStore() {
        return new ZsxqChunkEmbeddingStore(jdbc);
    }

    /** 官方入库链：切块 → 批量向量化 → 写库，省掉自己写批量和重试的胶水。 */
    @Bean
    public EmbeddingStoreIngestor zsxqIngestor() {
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(zsxqDocumentSplitter())
                .embeddingModel(zsxqEmbeddingModel())
                .embeddingStore(zsxqChunkStore())
                .build();
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
