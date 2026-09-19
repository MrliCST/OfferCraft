package com.example.domain.zsxq;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.example.domain.zsxq.ZsxqCrawler.CrawledPost;
import com.example.domain.zsxq.ZsxqCrawler.CrawledReply;

/**
 * S2 分类闸（启发式 / 正则版）—— 离线可用、确定性。
 * 是 {@link LangchainTopicGuard} 的兜底，也是 CLI 默认闸（设计文档 §8 Q4：v1 先用关键词启发式顶上）。
 * 规则对齐 §2：星主技术长文=tech_article；星友问+星主权威答=interview_qa；星主含架构金句=architecture_note；
 * 星友分享外链资源=resource_share；星友原创=member_post（低权保留）；避雷/上岸/无关技术分享=off_topic（丢弃）。
 */
@Component
public class HeuristicTopicGuard implements TopicGuard {

    private static final String STAR_MASTER = "马丁";

    // Q3 直接丢弃关键词（避雷/上岸/无关技术分享）
    private static final Pattern OFF_TOPIC = Pattern.compile("避雷|被坑|上岸|晒offer|快捷键|IDEA");
    // Q4 星主吐槽金句关键词（架构/选型）
    private static final Pattern NUGGET = Pattern.compile(
            "架构|选型|框架|Agent|RAG|向量|模型|2\\.0|检索|图谱|MCP|评测|设计|编排|记忆|上下文|ReAct");
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

    boolean isResourceShare(CrawledPost p) {
        String c = p.content == null ? "" : p.content;
        return c.contains("http") || c.contains("github") || (c.contains("开源") && c.contains("项目"));
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
