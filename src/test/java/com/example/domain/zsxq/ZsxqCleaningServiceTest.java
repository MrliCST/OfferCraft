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
import com.example.domain.zsxq.model.Classification;
import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.CrawledReply;
import com.example.domain.zsxq.model.PostType;
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

    /** 星友帖即便被判成「架构金句」，权威分也要被身份护栏压到 0.1。 */
    @Test
    void memberPostAuthority_cappedAtLowScore() {
        CrawledPost p = member("聊聊 2.0 的架构选型，我觉得 RAG 这一块应该重构一下");
        ZsxqCleaningService svc = new ZsxqCleaningService(post -> new Classification(
                PostType.ARCHITECTURE_NOTE, "测试：星友帖被判成架构金句", "", "金句"));

        ZsxqCleaningResult r = svc.run(List.of(p));

        assertEquals(1, r.kept().size());
        assertEquals(0.1, r.kept().get(0).authorityScore, 1e-9);
    }

    /** 星友提问 + 马丁实质作答：权威来源正当，这篇按 0.9 计（价值在马丁的答里）。 */
    @Test
    void memberAsked_martinAnswered_authorityFromAnswer() {
        CrawledPost p = member("马哥，Ragent 的意图识别与记忆管理是不是还要优化？面试官觉得这些设计平庸");
        ZsxqCleaningService svc = new ZsxqCleaningService(post -> new Classification(
                PostType.INTERVIEW_QA, "测试：星友问 + 马丁实质答",
                "当前，Ragent 正基于 AgentScope 框架，围绕 React、上下文管理、短中长期记忆、Skills 设计、"
                        + "写操作工具确认机制、Langfuse 链路追踪编写 v2 版本，基本上是当前企业落地里比较主流的架构。",
                ""));

        ZsxqCleaningResult r = svc.run(List.of(p));

        assertEquals(1, r.kept().size());
        assertEquals(0.9, r.kept().get(0).authorityScore, 1e-9);
    }

    /** 同一篇帖出现在两个栏目，只能入库一次。 */
    @Test    void samePostInTwoColumns_ingestedOnce() {
        CrawledPost a = member("百度二面 介绍一下秒杀领券流程？redis 失败以后怎么办？手撕：数组奇偶排序");
        a.column = "面试相关";
        a.postId = "111";
        CrawledPost b = member("百度二面 介绍一下秒杀领券流程？redis 失败以后怎么办？手撕：数组奇偶排序");
        b.column = "优质面经";
        b.postId = "111";

        ZsxqCleaningResult r = new ZsxqCleaningService(new HeuristicTopicGuard()).run(List.of(a, b));

        assertEquals(1, r.kept().size(), "同一篇帖不该入库两次");
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

    /**
     * 话题标签是分类不是主题：同一个标签下的帖子是不同的主题，不能互相抑制。
     * 这条是踩坑回归 —— 按标签分组抑制时，「面试相关」栏 6 篇面经被标掉 5 篇。
     */
    @Test
    void sameTagDifferentTopics_notSuppressed() {
        ZsxqCleaningService svc = new ZsxqCleaningService(
                post -> new Classification(PostType.PEER_INTERVIEW, "测试", "", ""));
        CrawledPost baidu1 = member("百度一面： 介绍一下xxx是一个什么平台？用户都是哪些人？ 80W用户是如何统计的");
        baidu1.column = "面试相关";
        baidu1.author = "范特西";
        baidu1.topicTags = List.of("🌈面试相关", "💫优质面经");
        baidu1.publishedAt = "2026-09-10 10:00";
        CrawledPost baidu2 = member("百度二面 介绍一下秒杀领券流程？ 有没有做异常处理？ 强依赖redis？");
        baidu2.column = "面试相关";
        baidu2.author = "范特西";
        baidu2.topicTags = List.of("🌈面试相关", "💫优质面经");
        baidu2.publishedAt = "2026-09-11 10:00";

        ZsxqCleaningResult r = svc.run(List.of(baidu1, baidu2));

        assertEquals(2, r.kept().size());
        assertEquals(0, r.kept().stream().filter(d -> d.superseded).count());
    }

    /** 同一批次里出现「同一篇的修订版」才算版本：同作者 + 开头对得上 → 旧的被抑制。 */
    @Test
    void sameAuthorRevisedHead_olderSuperseded() {
        ZsxqCleaningService svc = new ZsxqCleaningService(
                post -> new Classification(PostType.TECH_ARTICLE, "测试", "", ""));
        CrawledPost v1 = member("《Ragent 2.0 项目结构说明》\n\n第一版：目录结构先这么定。".repeat(3));
        v1.column = "只看星主";
        v1.author = "马丁";
        v1.authorRole = "星主";
        v1.publishedAt = "2026-08-01 10:00";
        CrawledPost v2 = member("《Ragent 2.0 项目结构说明》\n\n第二版：目录结构改了，模块拆细了。".repeat(3));
        v2.column = "只看星主";
        v2.author = "马丁";
        v2.authorRole = "星主";
        v2.publishedAt = "2026-08-15 10:00";

        ZsxqCleaningResult r = svc.run(List.of(v1, v2));

        assertEquals(2, r.kept().size());
        assertEquals(1, r.kept().stream().filter(d -> d.superseded).count());
        assertEquals("2026-08-01 10:00",
                r.kept().stream().filter(d -> d.superseded).findFirst().orElseThrow().publishedAt);
    }
}
