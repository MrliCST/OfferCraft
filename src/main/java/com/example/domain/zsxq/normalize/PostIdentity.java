package com.example.domain.zsxq.normalize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.domain.zsxq.model.CrawledPost;

/**
 * 给一篇帖子算「稳定身份」，用于跨栏目去重。
 *
 * <p>为什么需要：同一篇帖会同时出现在多个栏目（实测「面试相关」「优质面经」「精华」里
 * 都能爬到范特西的百度二三面、Jäger 的小红书面经）。爬出来是 30 条，独立帖其实只有 21 篇。
 * 不去重的话：题库里同一份面经存 3 份，评估报告的丢弃率/覆盖率也都被重复样本带偏。
 *
 * <p>身份优先级：官方 postId（B2 从 API 补的 topic_id）→ 内容指纹（作者+时间+正文开头）。
 * postId 拿不到时指纹也能兜住，因为同一篇帖在各栏目的作者/时间/正文完全一致。
 */
public final class PostIdentity {

    private PostIdentity() {
    }

    /** 帖子的稳定身份：优先官方血缘键，否则退化为内容指纹。 */
    public static String identityOf(CrawledPost p) {
        if (p.postId != null && !p.postId.isBlank()) {
            return "id:" + p.postId;
        }
        return "fp:" + fingerprint(p.author, p.publishedAt, p.content);
    }

    /** 内容指纹：作者 + 时间 + 正文开头 30 字（去空白，避免各栏目渲染差异）。 */
    public static String fingerprint(String author, String publishedAt, String content) {
        String head = content == null ? "" : content.substring(0, Math.min(30, content.length()));
        return normalize(author) + "|" + normalize(publishedAt) + "|" + normalize(head);
    }

    /**
     * 按身份去重，保留首次出现的那条（栏目顺序即优先级）。
     *
     * <p>postId 和指纹<b>两个维度各记一份</b>，命中任意一个就算重复：
     * 重复帖可能出现「一栏补到了 postId、另一栏没补到」的情况（旧样本里就有），
     * 只按 identityOf 的单键比较，一个有 id 一个没 id 就永远对不上。
     */
    public static List<CrawledPost> dedupe(List<CrawledPost> posts) {
        Set<String> seenIds = new LinkedHashSet<>();
        Set<String> seenFingerprints = new LinkedHashSet<>();
        List<CrawledPost> out = new ArrayList<>();
        for (CrawledPost p : posts) {
            String fp = fingerprint(p.author, p.publishedAt, p.content);
            String id = (p.postId == null || p.postId.isBlank()) ? null : p.postId;
            if ((id != null && seenIds.contains(id)) || seenFingerprints.contains(fp)) {
                continue;
            }
            if (id != null) {
                seenIds.add(id);
            }
            seenFingerprints.add(fp);
            out.add(p);
        }
        return out;
    }

    /**
     * 把同一篇帖的两种 id 统一成官方 topic_id，返回「旧 id → 规范 id」的映射。
     *
     * <p>为什么会有两种 id：星主长文/部分帖在 DOM 里只能拿到文章页链接
     * （{@code articles.zsxq.com/id_xxx.html}，postId 就是 {@code id_xxx}），
     * 而 B2 的 API 索引给的是数字型 {@code topic_id}。同一篇帖在不同栏目可能拿到不同形态的 id，
     * 结果就是库里同一篇存成两行、外键各指一边。
     *
     * <p>规范：一律以数字型 topic_id 为准（它可溯源 {@code wx.zsxq.com/topic/<id>}，
     * 文章页 URL 本来就已经记在 source_url 里，不靠 postId 承载）。
     *
     * @return 被改动的 id 的映射（old → new），调用方拿它去修 cleaned_doc.raw_post_id 这类外键
     */
    public static Map<String, String> unifyPostIds(List<CrawledPost> posts) {
        Map<String, List<CrawledPost>> groups = new LinkedHashMap<>();
        for (CrawledPost p : posts) {
            groups.computeIfAbsent(fingerprint(p.author, p.publishedAt, p.content), k -> new ArrayList<>()).add(p);
        }
        Map<String, String> remap = new LinkedHashMap<>();
        for (List<CrawledPost> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            String canonical = null;
            for (CrawledPost p : group) {
                if (isTopicId(p.postId)) {
                    canonical = p.postId;
                    break;
                }
            }
            if (canonical == null) {
                continue;
            }
            for (CrawledPost p : group) {
                if (p.postId != null && !p.postId.equals(canonical)) {
                    remap.put(p.postId, canonical);
                    p.postId = canonical;
                }
            }
        }
        return remap;
    }

    /** 官方 topic_id 是纯数字；文章页 id 形如 id_xxx。 */
    public static boolean isTopicId(String postId) {
        return postId != null && !postId.isBlank() && postId.chars().allMatch(Character::isDigit);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }
}
