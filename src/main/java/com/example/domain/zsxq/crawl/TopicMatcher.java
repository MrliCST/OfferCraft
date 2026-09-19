package com.example.domain.zsxq.crawl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 API 帖子和 DOM 爬到的帖子对上号（纯逻辑，不碰网络，可单测）。
 *
 * <p>匹配键为什么是「作者 + 分钟 + 正文开头」：
 * DOM 只显示到分钟，同一分钟同一个人可能发两篇（实测范特西 11:09 连发两条），
 * 光靠作者+时间会撞车；而正文两边格式不同（DOM 是 Markdown，API 是 &lt;e&gt; 富文本），
 * 所以比对前先规整成纯字符序列。都对不上时（比如星主帖标题被塞进 title 属性、
 * 剥离后开头对不齐）退化为同组内取第一条未用过的。
 */
public final class TopicMatcher {

    private final Map<String, List<ApiTopic>> byAuthorMinute = new LinkedHashMap<>();
    private final Set<String> used = new HashSet<>();

    public TopicMatcher(List<ApiTopic> topics) {
        for (ApiTopic t : topics) {
            byAuthorMinute.computeIfAbsent(key(t.author, t.createTimeMinute), k -> new ArrayList<>()).add(t);
        }
    }

    /**
     * @return 匹配上的 API 帖子。
     *
     * <p>两档策略，区别在「同一条能不能被返回两次」：
     * <ol>
     *   <li><b>正文开头对上</b> → 就是同一篇，直接返回，且必须是幂等的：同一篇帖子会出现在多个栏目
     *       （实测「面试相关」和「优质面经」都爬到范特西的百度二三面），各栏目都得拿到同一个 topic_id，
     *       否则清洗阶段没法按 postId 去重。所以这一档不受「已用过」限制。</li>
     *   <li><b>开头对不上</b>（星主帖标题被塞进 &lt;e title&gt; 属性，剥离后对不齐）→ 退化为取组内第一条
     *       还没被认领过的，这时才需要「不重复消费」，否则同一分钟连发的两篇会抢到同一条。</li>
     * </ol>
     */
    public ApiTopic match(String author, String publishedAt, String content) {
        List<ApiTopic> candidates = byAuthorMinute.get(key(author, publishedAt));
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String domHead = head(normalize(content), 24);
        // 一档：正文开头对得上，认定同一篇（幂等，可重复返回）
        for (ApiTopic t : candidates) {
            String apiHead = head(normalize(t.text), 24);
            if (!domHead.isEmpty() && !apiHead.isEmpty() && oneStartsWithOther(domHead, apiHead)) {
                return markUsed(t);
            }
        }
        // 二档：对不上就退化认领，同一条只认领一次
        for (ApiTopic t : candidates) {
            if (!used.contains(t.topicId)) {
                return markUsed(t);
            }
        }
        return null;
    }

    private ApiTopic markUsed(ApiTopic t) {
        used.add(t.topicId);
        return t;
    }

    /** 去掉富文本标记和标点，只留字母数字（含中文），让两边可比。 */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("<e[^>]*/?>", "")
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static boolean oneStartsWithOther(String a, String b) {
        String pa = head(a, 12);
        String pb = head(b, 12);
        return a.startsWith(pb) || b.startsWith(pa);
    }

    private static String head(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String key(String author, String minute) {
        return (author == null ? "" : author) + "|" + (minute == null ? "" : minute);
    }
}
