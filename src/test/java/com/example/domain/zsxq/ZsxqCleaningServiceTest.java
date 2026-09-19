package com.example.domain.zsxq;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.example.domain.zsxq.classify.HeuristicTopicGuard;
import com.example.domain.zsxq.classify.LangchainTopicGuard;
import com.example.domain.zsxq.classify.TopicGuard;
import com.example.domain.zsxq.classify.ZsxqTopicGuardConfig;
import com.example.domain.zsxq.clean.ZsxqCleaningService;
import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.CrawledReply;
import com.example.domain.zsxq.model.ZsxqCleanedDoc;
import com.example.domain.zsxq.model.ZsxqCleaningResult;

/**
 * ZsxqCleaningService 离线单测（启发式闸，不连 LLM、不读文件）：
 * 覆盖 S2 丢弃、S3 字段、Q2 资源聚合、S5/S6 不崩溃。
 */
class ZsxqCleaningServiceTest {

    private static CrawledPost member(String content) {
        CrawledPost p = new CrawledPost();
        p.author = "某星友";
        p.authorRole = "星友";
        p.publishedAt = "2026-07-25 10:29";
        p.content = content;
        return p;
    }

    @Test
    void offTopic_dropped_memberPost_kept() {
        ZsxqCleaningService svc = new ZsxqCleaningService(new HeuristicTopicGuard());
        CrawledPost off = member("避雷！我实习被坑了，大家擦亮眼睛");
        off.column = "col-off";
        CrawledPost keep = member("今天把项目跑起来了，记录一下自己的心得，后面方便复盘");
        keep.column = "col-keep";

        ZsxqCleaningResult r = svc.run(List.of(off, keep));
        assertEquals(1, r.dropped().size());
        assertEquals(1, r.kept().size());
        assertEquals("member_post", r.kept().get(0).postType);
        assertEquals(0.1, r.kept().get(0).authorityScore, 1e-9);
    }

    @Test
    void resourceShare_aggregated_to_single_lowAuthorityDoc() {
        ZsxqCleaningService svc = new ZsxqCleaningService(new HeuristicTopicGuard());
        CrawledPost r1 = member("发现宝藏开源项目 https://github.com/x/y，配合文档学习效率翻倍");
        r1.column = "c1";
        CrawledPost r2 = member("另一个好用工具 https://github.com/a/b");
        r2.column = "c2";

        ZsxqCleaningResult r = svc.run(List.of(r1, r2));
        // 两篇 resource_share -> 聚合为 1 篇 resource_share_aggregate
        assertEquals(1, r.kept().size());
        assertEquals("resource_share_aggregate", r.kept().get(0).postType);
        assertEquals(0.3, r.kept().get(0).authorityScore, 1e-9);
        assertEquals(false, r.kept().get(0).keepImages);
    }

    @Test
    void starTechArticle_keepsImages_and_highAuthority() {
        ZsxqCleaningService svc = new ZsxqCleaningService(new HeuristicTopicGuard());
        CrawledPost p = new CrawledPost();
        p.author = "马丁";
        p.authorRole = "星主";
        p.publishedAt = "2026-09-17 16:25";
        p.column = "c";
        p.content = "下面是 RAG 落地：\n```java\npublic static void main(String[] a) {}\n```\n详解检索链路。";

        ZsxqCleaningResult r = svc.run(List.of(p));
        assertEquals(1, r.kept().size());
        ZsxqCleanedDoc d = r.kept().get(0);
        assertEquals("tech_article", d.postType);
        assertEquals(0.9, d.authorityScore, 1e-9);
        assertEquals(true, d.keepImages);
        assertEquals(true, d.starMasterVerified);
    }

    /** 轻量上下文冒烟：不 boot 整个 Spring Boot（无 WebFlux），按环境变量装配分类闸。 */
    @Test
    void lightweightContext_wiresGuard_withoutWebFlux() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(ZsxqTopicGuardConfig.class, ZsxqCleaningService.class)) {
            ZsxqCleaningService svc = ctx.getBean(ZsxqCleaningService.class);
            TopicGuard guard = ctx.getBean(TopicGuard.class);
            assertNotNull(svc);
            assertNotNull(guard);
            // 有 DEEPSEEK_WIN_KEY 走 LLM 闸，否则启发式——两者都是合法 TopicGuard，且都不需要启动 Web 容器
            boolean hasKey = System.getenv("DEEPSEEK_WIN_KEY") != null
                    && !System.getenv("DEEPSEEK_WIN_KEY").isBlank();
            assertTrue(hasKey ? guard instanceof LangchainTopicGuard : guard instanceof HeuristicTopicGuard,
                    "轻量上下文应直接装配分类闸，而非启动 WebFlux 容器");
        }
    }
}
