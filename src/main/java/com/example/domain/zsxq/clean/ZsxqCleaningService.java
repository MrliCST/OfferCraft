package com.example.domain.zsxq.clean;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.example.domain.zsxq.classify.TopicGuard;
import com.example.domain.zsxq.model.Classification;
import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.PostType;
import com.example.domain.zsxq.model.ZsxqCleanedDoc;
import com.example.domain.zsxq.model.ZsxqCleaningResult;
import com.example.domain.zsxq.model.ZsxqDropRecord;
import com.example.domain.zsxq.normalize.PostIdentity;

/**
 * 帖子转题库清洗服务（S2–S6，Spring Boot 规范：纯逻辑、只收注入、不碰 IO/建模型）。
 *
 * <p>输入：S1 归一化后的各栏目 {@link CrawledPost} 列表；输出 {@link ZsxqCleaningResult}（保留 + 丢弃）。
 * 分类闸 {@link TopicGuard} 由构造注入——具体是 LLM 版还是启发式兜底，由 {@link ZsxqTopicGuardConfig} 决定，
 * 本类不关心。写盘（question-bank.json / drops.json）留给 CLI 入口 {@link ZsxqCleaner}。
 */
@Service
public class ZsxqCleaningService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** S5 系列标识（正则留在本服务，分类逻辑已迁出到 TopicGuard）。 */
    private static final Pattern SERIES_MARKER = Pattern.compile(
            "系列|上篇|下篇|（一）|（二）|（三）|续|预告|下一篇|前一篇");

    private final TopicGuard guard;

    public ZsxqCleaningService(TopicGuard guard) {
        this.guard = guard;
    }

    /** 编排 S0 跨栏去重 → S2 分类 → S3 字段 → Q2 聚资 → S5 串联 → S6 抑版。 */
    public ZsxqCleaningResult run(List<CrawledPost> all) {
        List<CrawledPost> posts = PostIdentity.dedupe(all);   // S0：同一帖在多栏目重复只留一份
        List<ZsxqCleanedDoc> kept = new ArrayList<>();
        List<ZsxqDropRecord> dropped = new ArrayList<>();
        List<ZsxqCleanedDoc> resourceShares = new ArrayList<>();
        int idx = 0;
        for (CrawledPost p : posts) {
            Classification c = guard.classify(p);
            if (c.drop) {
                dropped.add(new ZsxqDropRecord(p.column, p.author, p.publishedAt, c.reason));
                continue;
            }
            ZsxqCleanedDoc d = toDoc(p, c, idx++);
            if (c.postType == PostType.RESOURCE_SHARE) {
                resourceShares.add(d);            // Q2 不单篇入库，先收集
            } else {
                kept.add(d);
            }
        }

        if (!resourceShares.isEmpty()) {
            kept.add(aggregateResources(resourceShares));   // Q2 资源分享聚合为一篇低权汇总
        }
        linkSeries(kept);          // S5 系列串联
        suppressVersions(kept);    // S6 版本抑制
        return new ZsxqCleaningResult(kept, dropped);
    }

    /** S3 规范字段 + 权威分 + 校验 + Q1 图片策略；消费 S2 的 Classification。 */
    private ZsxqCleanedDoc toDoc(CrawledPost p, Classification c, int idx) {
        ZsxqCleanedDoc d = new ZsxqCleanedDoc();
        // doc_id 必须稳定：优先用帖子血缘键 postId（重跑不变、可幂等入库），
        // 拿不到 postId 才退化为「栏目+序号」——那种 id 重跑就会飘，只是保底
        d.docId = "zsxq-" + (p.postId == null || p.postId.isEmpty()
                ? (p.column == null ? "x" : p.column) + "-" + idx
                : p.postId);
        d.rawPostId = p.postId;
        d.topicKey = deriveTopicKey(p);
        d.postType = c.postType.name().toLowerCase();
        d.author = p.author;
        d.authorRole = p.authorRole;
        d.publishedAt = p.publishedAt;
        d.topicTags = p.topicTags;
        d.sourceUrl = p.sourceUrl;
        d.ingestAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        d.superseded = false;
        d.authorityScore = authorityWithGuard(c.authorityScore, p.authorRole, c.starMasterAnswer);
        d.starMasterVerified = c.starMasterVerified;

        switch (c.postType) {
            case TECH_ARTICLE -> {
                d.content = p.content;
                d.keepImages = true;             // Q1 星主技术长文：图全留
            }
            case INTERVIEW_QA -> {
                d.content = p.content;           // 星友问题（0.1）作上下文
                d.starMasterAnswer = c.starMasterAnswer;
                d.keepImages = true;             // Q1 星主问答：白名单图（URL 采集后按流程图/架构图过滤）
            }
            case ARCHITECTURE_NOTE -> {
                d.content = c.architectureQuote.isEmpty() ? p.content : c.architectureQuote; // Q4 只留金句
                d.keepImages = false;            // 吐槽无图，剥离
            }
            case MEMBER_POST -> {
                d.content = p.content;
                d.keepImages = false;            // Q1 星友图全剥离
            }
            case PEER_INTERVIEW -> {
                d.content = p.content;           // 面经真题原文全留（题目侧语料）
                d.keepImages = false;            // Q1 星友图全剥离
            }
            default -> {                          // RESOURCE_SHARE 等：图剥离
                d.content = p.content;
                d.keepImages = false;
            }
        }
        return d;
    }

    /**
     * 权威分护栏：没有马丁实质作答的星友内容，一律封顶 0.1。
     *
     * <p>为什么需要：权威分是按 post_type 推的（{@link PostType#defaultAuthority()}），
     * 而类型闸只看内容不看身份——星友的提问帖里提到「架构 / RAG / 2.0」，就可能被判成
     * architecture_note 白拿 0.9。
     *
     * <p>为什么有例外：马丁在回复里给了实质解答（{@link Classification} 已据此给到 0.9）是
     * 高分的正当来源，那条回复才是这篇的核心价值，不能再压回 0.1。所以这里只拦「无权威作答却拿高分」。
     */
    private static double authorityWithGuard(double score, String authorRole, String starMasterAnswer) {
        if (starMasterAnswer != null && starMasterAnswer.length() >= 60) {
            return score;                       // 马丁实质作答：权威来源正当
        }
        if ("星主".equals(authorRole)) {
            return score;
        }
        return Math.min(score, 0.1);
    }

    /** Q2 资源分享聚合。 */
    private ZsxqCleanedDoc aggregateResources(List<ZsxqCleanedDoc> shares) {
        ZsxqCleanedDoc agg = new ZsxqCleanedDoc();
        agg.docId = "zsxq-resource-aggregate";
        agg.topicKey = "资源推荐";
        agg.postType = "resource_share_aggregate";
        agg.author = "（聚合）";
        agg.authorRole = "星友";
        agg.authorityScore = 0.3;
        agg.starMasterVerified = false;
        agg.keepImages = false;
        agg.ingestAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        StringBuilder sb = new StringBuilder("【开源/工具资源推荐汇总】\n\n");
        for (ZsxqCleanedDoc s : shares) {
            sb.append("· 来源：").append(s.author).append("（").append(s.publishedAt).append("）\n")
              .append(s.content).append("\n\n");
        }
        agg.content = sb.toString().trim();
        return agg;
    }

    /** S5 系列串联：只看星主 + 时间相邻(<=30d) + 系列标识。 */
    private void linkSeries(List<ZsxqCleanedDoc> docs) {
        List<ZsxqCleanedDoc> sm = docs.stream()
                .filter(d -> "星主".equals(d.authorRole)
                        || PostType.TECH_ARTICLE.name().equalsIgnoreCase(d.postType)
                        || PostType.ARCHITECTURE_NOTE.name().equalsIgnoreCase(d.postType))
                .sorted(Comparator.comparing(d -> parseDate(d.publishedAt)))
                .toList();
        ZsxqCleanedDoc prev = null;
        String currentSeries = null;
        for (ZsxqCleanedDoc d : sm) {
            boolean marker = d.content != null && SERIES_MARKER.matcher(d.content).find();
            boolean adjacent = prev != null && java.time.Duration.between(
                    parseDate(prev.publishedAt), parseDate(d.publishedAt)).toDays() <= 30;
            if (prev != null && adjacent && (marker || SERIES_MARKER.matcher(prev.content == null ? "" : prev.content).find())) {
                d.seriesId = currentSeries;
                d.seriesPrev = prev.docId;
                prev.seriesNext = d.docId;
            } else if (marker) {
                currentSeries = "sm-series-" + d.publishedAt.replace(" ", "T").replace(":", "");
                d.seriesId = currentSeries;
            }
            prev = d;
        }
    }

    /**
     * S6 版本抑制：判析「同一批帖里，后一篇是否是对前一篇的修订」。
     *
     * <p><b>2026-09-19 重写。</b>原实现是「同 topic_key 内 newer 覆盖 older」，跑真实样本后
     * 发现这个前提根本不成立 —— 话题标签是<b>分类</b>不是<b>主题</b>：
     * 「💡RagentAI」下有 6 篇不同人提的不同问题，「🌈面试相关」下有 5 场不同的面试。
     * 按标签抑制的后果是「面试相关」栏 6 篇面经被标掉 5 篇，而那正是最该留存的面经真题。
     *
     * <p>所以判据换成<b>内容维度</b>：正文开头的重合度。同一篇长文被修订后再发一次，
     * 开头几十字基本不变；不同主题的帖子开头就分岔了。同作者是前置条件
     * （不同的面经往往由同一个 star 反复发，只比正文会误判）。
     *
     * <p>系列篇不算版本：{@code 系列串联} 已经把相邻篇目的 series_prev/next 串起来了，
     * 那是「同一系列的上下文相邻」，不是「同一篇的新旧版本」，不能互相抑制。
     */
    private void suppressVersions(List<ZsxqCleanedDoc> docs) {
        List<ZsxqCleanedDoc> sorted = new ArrayList<>(docs);
        sorted.sort(Comparator.comparing(d -> parseDate(d.publishedAt)));
        for (int i = 0; i < sorted.size(); i++) {
            ZsxqCleanedDoc older = sorted.get(i);
            if (older.superseded) {
                continue;
            }
            for (int j = i + 1; j < sorted.size(); j++) {
                ZsxqCleanedDoc newer = sorted.get(j);
                if (!newer.superseded && isRevision(older, newer)) {
                    older.superseded = true;   // 旧的标为被覆盖，保留新的
                    break;
                }
            }
        }
    }

    /** 新版正文开头要跟旧版重合到这么多字，才认作同一篇的修订。 */
    private static final int REVISION_HEAD_CHARS = 30;
    /** 开头重合度的下限（比例）：30 字里至少一半对得上。 */
    private static final double REVISION_HEAD_RATIO = 0.5;

    private static boolean isRevision(ZsxqCleanedDoc older, ZsxqCleanedDoc newer) {
        if (!Objects.equals(authorOf(older), authorOf(newer))) {
            return false;
        }
        if (Objects.equals(older.seriesId, newer.seriesId) && older.seriesId != null) {
            return false;   // 同一系列的相邻篇，不是版本
        }
        String a = head(older.content);
        String b = head(newer.content);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        int n = Math.min(a.length(), b.length());
        if (n < REVISION_HEAD_CHARS) {
            // 太短的帖子没法靠开头判断，只能要求高度相似
            return n > 0 && a.equals(b);
        }
        int same = 0;
        for (int i = 0; i < REVISION_HEAD_CHARS; i++) {
            if (a.charAt(i) == b.charAt(i)) {
                same++;
            }
        }
        return same >= REVISION_HEAD_CHARS * REVISION_HEAD_RATIO;
    }

    /** 正文开头，去掉图片和空白（改版时往往只是换了个封面图）。 */
    private static String head(String content) {
        if (content == null) {
            return "";
        }
        return content.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
                .replaceAll("\\s+", "")
                .substring(0, Math.min(160, content.replaceAll("\\s+", "").length()));
    }

    private static String authorOf(ZsxqCleanedDoc d) {
        return d.author == null ? "" : d.author;
    }

    /**
     * 主题键：取第一个话题标签，仅作展示 / 拉取用。
     *
     * <p>没有话题标签时返回 {@code null}。<b>不要拿栏目名兜底</b>——标签是分类不是主题，
     * 拿它做分组会把整个栏目当成一个主题（这条踩过坑，见 {@link #suppressVersions}）。
     */
    private String deriveTopicKey(CrawledPost p) {
        if (p.topicTags != null && !p.topicTags.isEmpty()) {
            return p.topicTags.get(0).replaceAll("[^一-龥A-Za-z0-9]", "");
        }
        return null;
    }

    private LocalDateTime parseDate(String s) {
        try {
            return LocalDateTime.parse(s.trim(), FMT);
        } catch (Exception e) {
            return LocalDateTime.MIN;
        }
    }
}
