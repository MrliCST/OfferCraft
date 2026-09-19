package com.example.domain.zsxq.image;

import java.util.List;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.example.domain.zsxq.ingest.ZsxqIngestConfig;
import com.example.domain.zsxq.vector.ZsxqVectorConfig;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

/**
 * 图片召回的自检入口：拿几个自然语言问题查图，打印命中的描述与分数。
 *
 * <p>用法: java ...ZsxqImageSearchCli [查询1] [查询2] ...
 * 例：java ...ZsxqImageSearchCli "初始化流程有哪些步骤" "订单创建的工具调用长什么样"
 *
 * <p>不给查询则跑一组默认问题。这个 CLI 的定位是<b>链路自检</b>：
 * 图片描述有没有真的进向量库、向量库能不能被自然语言查到、分数梯度健不健康。
 * 它是「图片支线数据是否到位」的验收工具，不是给人日常使用的入口
 * （那个入口将来是 agent 里的 retriever / tool）。
 *
 * <p>轻量上下文：注册落库配置（数据源）+ 向量配置（embedding 模型）+ 图片配置
 * （图片 store）+ 检索服务。
 */
public final class ZsxqImageSearchCli {

    private static final int MAX_RESULTS = 3;

    /** 低于这个分数的命中不展示，避免打印一堆不相关的噪音。 */
    private static final double MIN_SCORE = 0.4;

    private static final List<String> DEFAULT_QUERIES = List.of(
            "Agent 调用 RAG 的链路是怎么走的",
            "初始化流程有哪些步骤",
            "ReAct 循环的中间件拦截点在哪",
            "订单创建的工具调用长什么样");

    public static void main(String[] args) {
        List<String> queries = args.length > 0 ? List.of(args) : DEFAULT_QUERIES;

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(
                ZsxqIngestConfig.class, ZsxqVectorConfig.class, ZsxqImageConfig.class,
                ZsxqImageSearchService.class)) {
            ZsxqImageSearchService svc = ctx.getBean(ZsxqImageSearchService.class);

            for (String q : queries) {
                System.out.println("=== 查询: " + q);
                List<EmbeddingMatch<TextSegment>> hits = svc.topImages(q, MAX_RESULTS, MIN_SCORE);
                if (hits.isEmpty()) {
                    System.out.println("  （无命中，检查图片是否已 describe + embed）");
                    continue;
                }
                for (EmbeddingMatch<TextSegment> h : hits) {
                    System.out.printf("  [%.3f] %s%n", h.score(), oneLine(h.embedded().text()));
                    System.out.println("          url: " + h.embedded().metadata().getString("original_url"));
                }
            }
        }
    }

    /** 描述压成一行、截断，避免终端被长文本刷屏。 */
    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 100 ? t.substring(0, 100) + "…" : t;
    }
}
