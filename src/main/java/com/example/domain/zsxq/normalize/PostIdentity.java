package com.example.domain.zsxq.normalize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }
}
