package com.example.domain.zsxq.classify;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.example.domain.zsxq.model.Classification;
import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.CrawledReply;
import com.example.domain.zsxq.model.PostType;

/**
 * S2 分类闸（LLM 版，主交付）：基于 langchain4j AiService（{@link TopicGuardAi}，DeepSeek 低温度）做分类，
 * few-shot 来自老板 cankao.md 七类标注；合并 Q4 星主吐槽金句抽取（architecture_quote）。
 *
 * <p>健壮性：LLM 返回解析失败、或 post_type 越界（不在枚举内）时，回退到 {@link HeuristicTopicGuard}
 * （设计文档 §8 Q4 的兜底），保证管道不因一次 LLM 异常而中断。
 *
 * <p>喂给 LLM 的是精简视图（作者/身份/时间/标签/正文截断 4k/回复），避免整页超长正文撑爆上下文。
 */
public class LangchainTopicGuard implements TopicGuard {

    private final TopicGuardAi ai;
    private final ObjectMapper om = new ObjectMapper();
    private final HeuristicTopicGuard fallback;

    public LangchainTopicGuard(TopicGuardAi ai, HeuristicTopicGuard fallback) {
        this.ai = ai;
        this.fallback = fallback;
    }

    @Override
    public Classification classify(CrawledPost post) {
        try {
            String postJson = om.writeValueAsString(toView(post));
            Classification c = parse(ai.classify(postJson));
            if (c != null) {
                return c;
            }
        } catch (Exception ignored) {
            // 解析失败 → 回退启发式
        }
        return fallback.classify(post);
    }

    /** 解析 LLM 的 JSON 输出；容错代码块包裹、未知字段、越界 post_type → 返回 null 触发回退。 */
    private Classification parse(String resp) {
        if (resp == null) {
            return null;
        }
        String json = resp.trim();
        if (json.startsWith("```")) {
            int s = json.indexOf('{');
            int e = json.lastIndexOf('}');
            if (s >= 0 && e > s) {
                json = json.substring(s, e + 1);
            }
        }
        try {
            JsonNode n = om.readTree(json);
            PostType t = PostType.from(n.has("post_type") ? n.get("post_type").asText() : null);
            if (t == null) {
                return null; // 越界类型 → 回退
            }
            String reason = n.has("reason") ? n.get("reason").asText() : "";
            String sma = n.has("star_master_answer") ? n.get("star_master_answer").asText() : "";
            String aq = n.has("architecture_quote") ? n.get("architecture_quote").asText() : "";
            return new Classification(t, reason, sma, aq);
        } catch (Exception e) {
            return null;
        }
    }

    /** 喂给 LLM 的精简视图。 */
    private Map<String, Object> toView(CrawledPost p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("author", p.author);
        m.put("author_role", p.authorRole);
        m.put("published_at", p.publishedAt);
        m.put("topic_tags", p.topicTags);
        String c = p.content == null ? "" : p.content;
        m.put("content", c.length() <= 4000 ? c : c.substring(0, 4000));
        List<Map<String, String>> reps = new ArrayList<>();
        if (p.replies != null) {
            for (CrawledReply r : p.replies) {
                Map<String, String> rm = new LinkedHashMap<>();
                rm.put("commenter", r.commenter);
                rm.put("text", r.text);
                reps.add(rm);
            }
        }
        m.put("replies", reps);
        return m;
    }
}
