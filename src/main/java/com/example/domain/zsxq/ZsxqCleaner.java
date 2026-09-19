package com.example.domain.zsxq;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.example.domain.zsxq.ZsxqCrawler.CrawledPost;

/**
 * 帖子转题库清洗器（S1–S7），落地老板拍板的 Q1–Q6 决策。
 * 输入：ZsxqCrawler 产出的各栏目 JSON（CrawledPost 列表）。
 * 输出：question-bank.json（入库题库）+ drops.json（丢弃追踪）。
 *
 * <p>S2 分类（Topic Guard）已抽成 {@link TopicGuard} 抽象：
 * 默认走 {@link HeuristicTopicGuard}（离线、确定性）；若环境变量 DEEPSEEK_WIN_KEY 存在，
 * 则走 {@link LangchainTopicGuard}（langchain4j AiService，DeepSeek 低温度，few-shot 来自 cankao 七类），
 * 解析失败自动回退启发式。本类只消费 {@link Classification} 结果，不再内含正则。
 *
 * 用法: java ...ZsxqCleaner [输入目录] [输出question-bank.json]
 */
public final class ZsxqCleaner {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static void main(String[] args) throws Exception {
        String inDir = args.length > 0 ? args[0]
                : System.getProperty("user.home") + "/code/demo/JLRADemo/crawl-output/zsxq";
        String outFile = args.length > 1 ? args[1] : inDir + "/question-bank.json";
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        // S2 分类闸：有 DeepSeek key 就用 LLM 版，否则启发式兜底
        TopicGuard guard = LangchainTopicGuard.fromEnv();
        if (guard == null) {
            guard = new HeuristicTopicGuard();
            System.out.println("S2 分类闸: HeuristicTopicGuard（离线，未检测到 DEEPSEEK_WIN_KEY）");
        } else {
            System.out.println("S2 分类闸: LangchainTopicGuard（DeepSeek AiService）");
        }

        // S1 归一化：载入全部栏目 JSON
        List<CrawledPost> all = new ArrayList<>();
        List<String> files = new ArrayList<>();
        try (var stream = Files.list(Path.of(inDir))) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals("question-bank.json")
                            && !p.getFileName().toString().equals("drops.json"))
                    .forEach(p -> files.add(p.toString()));
        }
        for (String f : files) {
            CrawledPost[] arr = om.readValue(Path.of(f).toFile(), CrawledPost[].class);
            for (var p : arr) {
                p.column = p.column == null ? Path.of(f).getFileName().toString().replace(".json", "") : p.column;
                all.add(p);
            }
        }
        System.out.println("S1 载入原始帖子: " + all.size() + " 篇（来自 " + files.size() + " 个栏目文件）");

        // S2–S6 清洗
        List<CleanedDoc> kept = new ArrayList<>();
        List<DropRecord> dropped = new ArrayList<>();
        List<CleanedDoc> resourceShares = new ArrayList<>();
        int idx = 0;
        for (CrawledPost p : all) {
            Classification c = guard.classify(p);
            if (c.drop) {
                dropped.add(new DropRecord(p.column, p.author, p.publishedAt, c.reason));
                continue;
            }
            CleanedDoc d = toDoc(p, c, idx++);
            if (c.postType == PostType.RESOURCE_SHARE) {
                resourceShares.add(d); // Q2 不单篇入库，先收集
            } else {
                kept.add(d);
            }
        }

        // Q2 资源分享聚合为一篇低权汇总
        if (!resourceShares.isEmpty()) {
            kept.add(aggregateResources(resourceShares));
            System.out.println("Q2 资源分享聚合: " + resourceShares.size() + " 篇 -> 1 篇低权汇总");
        }

        // S5 系列串联（只看星主 + 时间相邻 + 系列标识）
        linkSeries(kept);
        // S6 版本抑制（同 topic_key 内新覆盖旧）
        suppressVersions(kept);

        // S7 输出
        om.writeValue(Path.of(outFile).toFile(), kept);
        om.writeValue(Path.of(inDir, "drops.json").toFile(), dropped);
        System.out.println("S7 入库题库: " + kept.size() + " 篇 -> " + outFile);
        System.out.println("   丢弃: " + dropped.size() + " 篇 -> " + inDir + "/drops.json");

        // 统计
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (CleanedDoc d : kept) {
            byType.merge(d.postType, 1, Integer::sum);
        }
        System.out.println("=== 入库按类型 ===");
        byType.forEach((k, v) -> System.out.println("   " + k + ": " + v));
        int series = (int) kept.stream().filter(d -> d.seriesId != null).count();
        int verified = (int) kept.stream().filter(d -> d.starMasterVerified).count();
        System.out.println("   系列串联: " + series + " 篇 | 星主权威校验: " + verified + " 篇");
    }

    /** S3 规范字段 + 权威分 + 校验 + Q1 图片策略；消费 S2 的 Classification。 */
    private static CleanedDoc toDoc(CrawledPost p, Classification c, int idx) {
        CleanedDoc d = new CleanedDoc();
        d.docId = "zsxq-" + (p.column == null ? "x" : p.column) + "-" + idx;
        d.topicKey = deriveTopicKey(p);
        d.postType = c.postType.name().toLowerCase();
        d.author = p.author;
        d.authorRole = p.authorRole;
        d.publishedAt = p.publishedAt;
        d.topicTags = p.topicTags;
        d.sourceUrl = p.sourceUrl;
        d.ingestAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
        d.superseded = false;
        d.authorityScore = c.authorityScore;
        d.starMasterVerified = c.starMasterVerified;

        switch (c.postType) {
            case TECH_ARTICLE -> {
                d.content = p.content;
                d.keepImages = true;            // Q1 星主技术长文：图全留
            }
            case INTERVIEW_QA -> {
                d.content = p.content;          // 星友问题（0.1）作上下文
                d.starMasterAnswer = c.starMasterAnswer;
                d.keepImages = true;            // Q1 星主问答：白名单图（URL 采集后按流程图/架构图过滤）
            }
            case ARCHITECTURE_NOTE -> {
                d.content = c.architectureQuote.isEmpty() ? p.content : c.architectureQuote; // Q4 只留金句
                d.keepImages = false;           // 吐槽无图，剥离
            }
            case MEMBER_POST -> {
                d.content = p.content;
                d.keepImages = false;           // Q1 星友图全剥离
            }
            default -> { // RESOURCE_SHARE 等：图剥离
                d.content = p.content;
                d.keepImages = false;
            }
        }
        return d;
    }

    /** Q2 资源分享聚合。 */
    private static CleanedDoc aggregateResources(List<CleanedDoc> shares) {
        CleanedDoc agg = new CleanedDoc();
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
        for (CleanedDoc s : shares) {
            sb.append("· 来源：").append(s.author).append("（").append(s.publishedAt).append("）\n")
              .append(s.content).append("\n\n");
        }
        agg.content = sb.toString().trim();
        return agg;
    }

    /** S5 系列串联：只看星主 + 时间相邻(<=30d) + 系列标识。 */
    private static void linkSeries(List<CleanedDoc> docs) {
        List<CleanedDoc> sm = docs.stream()
                .filter(d -> "星主".equals(d.authorRole) || "只看星主".equals(d.topicKey)
                        || PostType.TECH_ARTICLE.name().equalsIgnoreCase(d.postType)
                        || PostType.ARCHITECTURE_NOTE.name().equalsIgnoreCase(d.postType))
                .sorted(Comparator.comparing(d -> parseDate(d.publishedAt)))
                .toList();
        CleanedDoc prev = null;
        String currentSeries = null;
        for (CleanedDoc d : sm) {
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

    /** S6 版本抑制：同 topic_key 内，较新覆盖较旧（标记 superseded）。 */
    private static void suppressVersions(List<CleanedDoc> docs) {
        Map<String, List<CleanedDoc>> byKey = new LinkedHashMap<>();
        for (CleanedDoc d : docs) {
            byKey.computeIfAbsent(d.topicKey, k -> new ArrayList<>()).add(d);
        }
        for (List<CleanedDoc> group : byKey.values()) {
            if (group.size() <= 1) {
                continue;
            }
            group.sort(Comparator.comparing(d -> parseDate(d.publishedAt)));
            for (int i = 0; i < group.size() - 1; i++) {
                group.get(i).superseded = true; // 旧的标为被覆盖
            }
        }
    }

    private static String deriveTopicKey(CrawledPost p) {
        if (p.topicTags != null && !p.topicTags.isEmpty()) {
            return p.topicTags.get(0).replaceAll("[^一-龥A-Za-z0-9]", "");
        }
        return p.column == null ? "未分类" : p.column;
    }

    private static LocalDateTime parseDate(String s) {
        try {
            return LocalDateTime.parse(s.trim(), FMT);
        } catch (Exception e) {
            return LocalDateTime.MIN;
        }
    }

    /** 入库题库条目（对应设计文档 §7 输出 schema）。 */
    public static class CleanedDoc {
        public String docId;
        public String topicKey;
        public String postType;
        public String author;
        public String authorRole;
        public String publishedAt;
        public String content;
        public List<String> topicTags;
        public double authorityScore;
        public boolean starMasterVerified;
        public String starMasterAnswer;
        public String seriesId;
        public String seriesPrev;
        public String seriesNext;
        public boolean keepImages;
        public String sourceUrl;
        public String ingestAt;
        public boolean superseded;
    }

    public static class DropRecord {
        public String column;
        public String author;
        public String publishedAt;
        public String reason;
        public DropRecord(String c, String a, String t, String r) {
            this.column = c; this.author = a; this.publishedAt = t; this.reason = r;
        }
    }

    // S5 系列标识（正则留在清洗器，分类逻辑已迁出）
    private static final java.util.regex.Pattern SERIES_MARKER = java.util.regex.Pattern.compile(
            "系列|上篇|下篇|（一）|（二）|（三）|续|预告|下一篇|前一篇");
}
