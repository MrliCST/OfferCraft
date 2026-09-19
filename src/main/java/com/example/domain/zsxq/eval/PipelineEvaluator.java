package com.example.domain.zsxq.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.ZsxqCleanedDoc;
import com.example.domain.zsxq.model.ZsxqDropRecord;

/**
 * 清洗管道指标计算（纯逻辑，不碰 IO）：把一次运行的原始帖 + 入库文档 + 丢弃记录
 * 折算成 {@link PipelineMetrics}，交给 {@link EvalReportRenderer} 渲染报告。
 *
 * <p>设计意图：管道最容易退化的是「采集分支」和「分类边界」——星主长文走文章页、
 * 星友帖走 feed 预览，两条路径的正文长度差两个数量级。所以这里把「正文疑似截断」
 * 和「血缘键缺失」做成一等指标，任何一次爬取都能立刻看出哪条分支坏了。
 */
@Service
public class PipelineEvaluator {

    /** 正文短于这个长度才进入截断排查（星主长文实测 1.4 万字，feed 预览约 270 字）。 */
    public static final int TRUNCATED_THRESHOLD = 400;

    private static final String ARTICLE_HOST = "articles.zsxq.com";

    public PipelineMetrics evaluate(List<CrawledPost> raw,
                                    List<ZsxqCleanedDoc> kept,
                                    List<ZsxqDropRecord> dropped) {
        PipelineMetrics m = new PipelineMetrics();
        m.totalPosts = raw.size();
        m.keptDocs = kept.size();
        m.droppedDocs = dropped.size();
        collectCleaned(m, kept);
        for (ZsxqDropRecord r : dropped) {
            m.countDropReason(r.reason);
        }

        Map<String, ZsxqCleanedDoc> byRawId = new LinkedHashMap<>();
        Map<String, ZsxqCleanedDoc> byFingerprint = new LinkedHashMap<>();
        for (ZsxqCleanedDoc d : kept) {
            if (d.rawPostId != null) {
                byRawId.put(d.rawPostId, d);
            }
            // 血缘键缺失时（星友帖目前拿不到官方 id）用内容指纹兜底关联，
            // 否则报告里那一栏的类型分布会整列空白
            byFingerprint.putIfAbsent(fingerprint(d.author, d.publishedAt, d.content), d);
            // architecture_note 的正文会被 Q4 换成金句，指纹对不上，只能靠「作者+时间」兜底
            byFingerprint.putIfAbsent(looseKey(d.author, d.publishedAt), d);
        }

        Map<String, ColumnMetrics> byCol = new LinkedHashMap<>();
        Map<String, List<Integer>> lensByCol = new LinkedHashMap<>();
        for (CrawledPost p : raw) {
            String col = p.column == null || p.column.isEmpty() ? "(未标栏目)" : p.column;
            ColumnMetrics cm = byCol.computeIfAbsent(col, k -> new ColumnMetrics());
            cm.column = col;
            cm.total++;

            int len = p.content == null ? 0 : p.content.length();
            lensByCol.computeIfAbsent(col, k -> new ArrayList<>()).add(len);

            if (isArticlePage(p.sourceUrl)) {
                cm.articlePagePosts++;
                m.articlePagePosts++;
            } else {
                m.feedOnlyPosts++;
            }

            if (p.postId != null && !p.postId.isEmpty()) {
                cm.withPostId++;
            } else {
                m.anomalies.add(new MetricAnomaly("POST_ID_MISSING", col, p.author, p.publishedAt,
                        "血缘键为空，无法幂等入库 / 无法溯源"));
            }
            if (p.sourceUrl != null && !p.sourceUrl.isEmpty()) {
                cm.withSourceUrl++;
            } else {
                m.anomalies.add(new MetricAnomaly("SOURCE_URL_MISSING", col, p.author, p.publishedAt,
                        "溯源链接为空，检索结果回不到原帖"));
            }
            if (len == 0) {
                m.anomalies.add(new MetricAnomaly("CONTENT_EMPTY", col, p.author, p.publishedAt,
                        "正文为空"));
            } else if (isTruncated(p.content, p.sourceUrl)) {
                cm.truncatedSuspects++;
                m.anomalies.add(new MetricAnomaly("CONTENT_TRUNCATED", col, p.author, p.publishedAt,
                        "正文仅 " + len + " 字且带预览特征（省略号收尾 / 走了文章页却没拿到长正文），疑似 feed 预览截断"));
            }
            if (p.imageUrls != null && !p.imageUrls.isEmpty()) {
                cm.withImages++;
            }
            if (p.replies != null && !p.replies.isEmpty()) {
                cm.withReplies++;
            }

            ZsxqCleanedDoc d = p.postId == null ? null : byRawId.get(p.postId);
            if (d == null) {
                d = byFingerprint.get(fingerprint(p.author, p.publishedAt, p.content));
            }
            if (d == null) {
                d = byFingerprint.get(looseKey(p.author, p.publishedAt));
            }
            checkClassification(m, cm, p, d);
        }

        for (Map.Entry<String, ColumnMetrics> e : byCol.entrySet()) {
            List<Integer> lens = lensByCol.getOrDefault(e.getKey(), List.of());
            ColumnMetrics cm = e.getValue();
            cm.contentLenMin = lens.stream().mapToInt(Integer::intValue).min().orElse(0);
            cm.contentLenMax = lens.stream().mapToInt(Integer::intValue).max().orElse(0);
            cm.contentLenMedian = median(lens);
        }
        m.columns.addAll(byCol.values());
        return m;
    }

    /** 入库文档侧的整体统计：类型 / 权威分 / 系列 / 校验。 */
    private void collectCleaned(PipelineMetrics m, List<ZsxqCleanedDoc> kept) {
        for (ZsxqCleanedDoc d : kept) {
            m.countType(d.postType);
            m.countAuthority(d.authorityScore);
            if (d.seriesId != null) {
                m.seriesLinked++;
            }
            if (d.starMasterVerified) {
                m.starMasterVerified++;
            }
        }
    }

    /** 分类边界抽查：身份与判定结果明显矛盾时才报，避免噪音。 */
    private void checkClassification(PipelineMetrics m, ColumnMetrics cm, CrawledPost p, ZsxqCleanedDoc d) {
        if (d == null) {
            return;
        }
        cm.postTypes.merge(d.postType == null ? "null" : d.postType, 1, Integer::sum);
        if ("星主".equals(p.authorRole) && "member_post".equals(d.postType)) {
            m.anomalies.add(new MetricAnomaly("CLASSIFY_SUSPECT", cm.column, p.author, p.publishedAt,
                    "星主帖被判为 member_post（应为 tech_article / architecture_note）"));
        } else if ("星友".equals(p.authorRole) && d.authorityScore >= 0.9) {
            m.anomalies.add(new MetricAnomaly("CLASSIFY_SUSPECT", cm.column, p.author, p.publishedAt,
                    "星友帖拿到 " + d.authorityScore + " 权威分（应为 0.1）"));
        }
    }

    /**
     * 疑似截断 = 正文短 + 带预览特征。
     *
     * <p>不能只看长度：星友提问帖本来就可能只有二三十字（实测「马哥，可以先把2.0简历模板出了吗」24 字），
     * 那不是截断。真正的 feed 预览有两个可识别特征——以省略号收尾，或明明有文章页却没取到长正文。
     */
    private static boolean isTruncated(String content, String sourceUrl) {
        if (content == null || content.length() >= TRUNCATED_THRESHOLD) {
            return false;
        }
        String tail = content.stripTrailing();
        boolean ellipsis = tail.endsWith("...") || tail.endsWith("…");
        return ellipsis || isArticlePage(sourceUrl);
    }

    /** 血缘键缺失时的兜底关联键：作者 + 时间 + 正文开头。 */
    private static String fingerprint(String author, String publishedAt, String content) {
        String head = content == null ? "" : content.substring(0, Math.min(30, content.length()));
        return author + "|" + publishedAt + "|" + head;
    }

    private static String looseKey(String author, String publishedAt) {
        return author + "|" + publishedAt;
    }

    private static boolean isArticlePage(String url) {
        return url != null && url.contains(ARTICLE_HOST);
    }

    private static int median(List<Integer> lens) {
        if (lens.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(lens);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }
}
