package com.example.domain.zsxq.classify;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.example.domain.zsxq.model.Classification;
import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.CrawledReply;
import com.example.domain.zsxq.model.PostType;

/**
 * S2 分类闸（启发式 / 正则版）—— 离线可用、确定性。
 * 是 {@link LangchainTopicGuard} 的兜底，也是 CLI 默认闸（设计文档 §8 Q4：v1 先用关键词启发式顶上）。
 * 规则对齐 §2：星主技术长文=tech_article；星友问+星主权威答=interview_qa；星主含架构金句=architecture_note；
 * 星友分享外链资源=resource_share；星友原创=member_post（低权保留）；避雷/上岸/无关技术分享=off_topic（丢弃）。
 */
public class HeuristicTopicGuard implements TopicGuard {

    private static final String STAR_MASTER = "马丁";

    // Q3 直接丢弃关键词（避雷/纯上岸/无关技术分享）
    private static final Pattern OFF_TOPIC = Pattern.compile("避雷|被坑|上岸|晒offer|快捷键|IDEA");
    /**
     * 面经真题特征：具体轮次 / 手撕 / 笔试题。命中即低权保留（2026-09-19 策略修正：
     * 这些是题库最核心的题目侧语料，不能因为「无星主回答」就丢）。
     */
    private static final Pattern REAL_INTERVIEW = Pattern.compile(
            "面经|一面|二面|三面|四面|手撕|笔试|面试题|反问|八股|offer 面|hr面|交叉面");
    // Q4 星主吐槽金句关键词（架构/选型）
    private static final Pattern NUGGET = Pattern.compile(
            "架构|选型|框架|Agent|RAG|向量|模型|2\\.0|检索|图谱|MCP|评测|设计|编排|记忆|上下文|ReAct");
    private static final Pattern EXTERNAL_LINK = Pattern.compile("https?://[^\\s)\\]]+");
    // 代码块标识（判断星主技术长文）
    private static final Pattern CODE = Pattern.compile(
            "(?i)(```|yaml|bash|docker|psql|mvn |public static|application\\.|pom\\.xml|\\.java|gradle)");

    @Override
    public Classification classify(CrawledPost p) {
        boolean star = "星主".equals(p.authorRole) || STAR_MASTER.equals(p.author);
        String martinAnswer = longestMartinReply(p);
        boolean substantive = martinAnswer.length() >= 60;

        if (star) {
            if (isTechArticle(p)) {
                return new Classification(PostType.TECH_ARTICLE, "星主结构化技术长文（含代码/长文）", "", "");
            }
            // 星主吐槽 → Q4 极简抽检
            if (NUGGET.matcher(p.content == null ? "" : p.content).find()) {
                return new Classification(PostType.ARCHITECTURE_NOTE, "星主含架构/选型金句", "", extractNugget(p.content));
            }
            return new Classification(PostType.OFF_TOPIC, "星主吐槽无干货", "", "");
        }

        if (isResourceShare(p)) {
            return new Classification(PostType.RESOURCE_SHARE, "星友分享开源/外链资源", "", "");
        }
        // 面经真题：没有星主回答也要低权保留（题目侧语料），且必须排在 off_topic 之前
        // ——「已上岸/被横向」这类词常和真题写在同一篇里，先判 off_topic 会把真题一起丢掉
        if (isRealInterview(p)) {
            return new Classification(PostType.PEER_INTERVIEW, "星友面经真题（含具体轮次/题目）", "", "");
        }
        if (p.content != null && OFF_TOPIC.matcher(p.content).find()) {
            return new Classification(PostType.OFF_TOPIC, "避雷/上岸/无关技术分享", "", "");
        }
        if (!martinAnswer.isEmpty() && substantive) {
            return new Classification(PostType.INTERVIEW_QA, "星友问 + 星主权威答", martinAnswer, "");
        }
        return new Classification(PostType.MEMBER_POST, "星友原创（低权保留）", "", "");
    }

    boolean isTechArticle(CrawledPost p) {
        String c = p.content == null ? "" : p.content;
        return c.length() > 600 || CODE.matcher(c).find();
    }

    /**
     * 资源分享 = 正文里有<b>站外</b>链接。不能只看 "http"：星球的正文末尾会挂话题标签链接
     * （https://wx.zsxq.com/tags/...），按旧写法面经帖会被误判成资源分享、塞进资源汇总里。
     */
    boolean isResourceShare(CrawledPost p) {
        String c = p.content == null ? "" : p.content;
        for (String link : EXTERNAL_LINK.matcher(c).results().map(java.util.regex.MatchResult::group).toList()) {
            if (!link.contains("zsxq.com")) {
                return true;
            }
        }
        return c.contains("github") || (c.contains("开源") && c.contains("项目"));
    }

    /** 面经真题：命中轮次/手撕/笔试一类特征词。 */
    boolean isRealInterview(CrawledPost p) {
        String c = p.content == null ? "" : p.content;
        return REAL_INTERVIEW.matcher(c).find();
    }

    /** Q4 抽取星主吐槽里的金句（v1 启发式；接 LLM 时由 LangchainTopicGuard 的 architecture_quote 替代）。 */
    String extractNugget(String content) {
        if (content == null) {
            return "";
        }
        List<String> sentences = new ArrayList<>();
        for (String s : content.split("[。！\n]")) {
            s = s.trim();
            if (!s.isEmpty() && NUGGET.matcher(s).find()) {
                sentences.add(s);
            }
        }
        return sentences.isEmpty() ? content : String.join("。", sentences);
    }

    String longestMartinReply(CrawledPost p) {
        String best = "";
        if (p.replies == null) {
            return best;
        }
        for (CrawledReply r : p.replies) {
            if (r.commenter != null && r.commenter.contains(STAR_MASTER)
                    && r.text != null && r.text.length() > best.length()) {
                best = r.text;
            }
        }
        return best;
    }
}
