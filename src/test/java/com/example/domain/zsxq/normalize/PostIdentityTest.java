package com.example.domain.zsxq.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.domain.zsxq.model.CrawledPost;

/** 跨栏目去重：同一篇帖会同时出现在多个栏目，入库和指标都只能算一次。 */
class PostIdentityTest {

    @Test
    void samePostIdInTwoColumns_deduped() {
        CrawledPost a = post("111", "范特西", "2026-09-19 11:09", "面试相关", "百度三面：讲讲整体流程");
        CrawledPost b = post("111", "范特西", "2026-09-19 11:09", "优质面经", "百度三面：讲讲整体流程");

        assertEquals(1, PostIdentity.dedupe(List.of(a, b)).size());
    }

    @Test
    void differentPosts_sameMinute_kept() {
        CrawledPost a = post("201", "范特西", "2026-09-19 11:09", "面试相关", "百度三面：讲讲整体流程");
        CrawledPost b = post("202", "范特西", "2026-09-19 11:09", "面试相关", "百度二面：介绍秒杀领券流程");

        assertEquals(2, PostIdentity.dedupe(List.of(a, b)).size());
    }

    @Test
    void noPostId_fallsBackToFingerprint() {
        CrawledPost a = post(null, "范特西", "2026-09-19 11:08", "面试相关", "百度一面：介绍平台");
        CrawledPost b = post(null, "范特西", "2026-09-19 11:08", "优质面经", "百度一面：介绍平台");
        CrawledPost c = post(null, "范特西", "2026-09-19 11:05", "优质面经", "CVTE一面：微服务拆分");

        assertEquals(List.of(a, c), PostIdentity.dedupe(List.of(a, b, c)));
    }

    @Test
    void duplicateWhereOnlyOneSideHasPostId_deduped() {
        // 同一篇帖：一栏补到了官方 topic_id，另一栏没补到。
        // 只按单一身份键比较的话，一个 id: 一个 fp: 永远对不上，必须两个维度都查
        CrawledPost a = post("333", "范特西", "2026-09-19 11:08", "面试相关", "百度一面：介绍平台");
        CrawledPost b = post(null, "范特西", "2026-09-19 11:08", "优质面经", "百度一面：介绍平台");

        assertEquals(1, PostIdentity.dedupe(List.of(a, b)).size());
    }

    @Test
    void identityPrefersPostId() {
        CrawledPost p = post("999", "某人", "2026-09-01 10:00", "栏目", "正文");
        assertEquals("id:999", PostIdentity.identityOf(p));
    }

    @Test
    void samePostWithTwoIdForms_unifiedToTopicId() {
        // 实测：同一篇帖在「精华」栏只能拿到文章页 id，在「优质面经」栏拿到官方 topic_id，
        // 不归一的话库里会存成两行、外键各指一边
        CrawledPost article = post("id_hrrlaj6wpecj", "Jäger", "2026-09-10 21:00", "精华", "小红书面经：一面手撕算法");
        CrawledPost topic = post("14425514485851482", "Jäger", "2026-09-10 21:00", "优质面经", "小红书面经：一面手撕算法");

        Map<String, String> remap = PostIdentity.unifyPostIds(List.of(article, topic));

        assertEquals(Map.of("id_hrrlaj6wpecj", "14425514485851482"), remap);
        assertEquals("14425514485851482", article.postId);
        assertEquals(1, PostIdentity.dedupe(List.of(article, topic)).size());
    }

    @Test
    void unifyPostIds_leavesSinglePostAlone() {
        CrawledPost only = post("id_abc", "某人", "2026-09-01 10:00", "栏目", "正文");

        assertTrue(PostIdentity.unifyPostIds(List.of(only)).isEmpty());
        assertEquals("id_abc", only.postId);
    }

    private static CrawledPost post(String postId, String author, String at, String column, String content) {
        CrawledPost p = new CrawledPost();
        p.postId = postId;
        p.author = author;
        p.publishedAt = at;
        p.column = column;
        p.content = content;
        return p;
    }
}
