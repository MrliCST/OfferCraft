package com.example.domain.zsxq.eval;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * 把 {@link PipelineMetrics} 渲染成 Markdown 报告（纯字符串拼装，不碰文件 IO）。
 */
@Service
public class EvalReportRenderer {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String render(PipelineMetrics m, String sampleDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 清洗管道评估报告\n\n");
        sb.append("> 生成时间：").append(LocalDateTime.now().format(FMT))
          .append("　样本目录：`").append(sampleDir).append("`\n");
        sb.append("> 覆盖链路：S0 采集 → S1 归一化 → S2 分类闸 → S3 权威分 → S5 系列串联 → S6 版本抑制 → S7 输出\n\n");

        appendOverview(sb, m);
        appendDistribution(sb, m);
        appendColumns(sb, m);
        appendAnomalies(sb, m);
        appendVerdict(sb, m);
        return sb.toString();
    }

    private void appendOverview(StringBuilder sb, PipelineMetrics m) {
        sb.append("## 一、总览\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append("| 采集帖子 | ").append(m.totalPosts).append(" 篇 |\n");
        sb.append("| 入库文档 | ").append(m.keptDocs).append(" 篇 |\n");
        sb.append("| 丢弃 | ").append(m.droppedDocs).append(" 篇（丢弃率 ")
          .append(String.format("%.1f%%", m.dropRate())).append("）|\n");
        sb.append("| 系列串联 | ").append(m.seriesLinked).append(" 篇 |\n");
        sb.append("| 星主权威校验 | ").append(m.starMasterVerified).append(" 篇 |\n");
        sb.append("| **文章页路径**（星主长文） | ").append(m.articlePagePosts).append(" 篇 |\n");
        sb.append("| **feed 预览路径**（星友帖等） | ").append(m.feedOnlyPosts).append(" 篇 |\n");
        sb.append("| 异常项 | ").append(m.anomalies.size()).append(" 条 |\n\n");
    }

    private void appendDistribution(StringBuilder sb, PipelineMetrics m) {
        sb.append("## 二、分类与权威分分布\n\n");
        sb.append("### 帖子类型\n\n| post_type | 篇数 |\n|---|---|\n");
        m.byPostType.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        sb.append("\n### 权威分\n\n| authority_score | 篇数 |\n|---|---|\n");
        m.byAuthority.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        if (!m.dropReasons.isEmpty()) {
            sb.append("\n### 丢弃原因\n\n| reason | 篇数 |\n|---|---|\n");
            m.dropReasons.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        }
        sb.append('\n');
    }

    private void appendColumns(StringBuilder sb, PipelineMetrics m) {
        sb.append("## 三、逐栏目指标\n\n");
        sb.append("| 栏目 | 篇数 | postId 覆盖 | 源链接覆盖 | 正文 最小/中位/最大 | 疑似截断 | 有图 | 有回复 | 走文章页 | 类型分布 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (ColumnMetrics c : m.columns) {
            sb.append('|').append(c.column)
              .append("|").append(c.total)
              .append("|").append(String.format("%.0f%%", c.postIdRate()))
              .append("|").append(String.format("%.0f%%", c.sourceUrlRate()))
              .append("|").append(c.contentLenMin).append(" / ").append(c.contentLenMedian)
              .append(" / ").append(c.contentLenMax)
              .append("|").append(c.truncatedSuspects)
              .append("|").append(c.withImages)
              .append("|").append(c.withReplies)
              .append("|").append(c.articlePagePosts)
              .append("|").append(formatTypes(c.postTypes))
              .append("|\n");
        }
        sb.append('\n');
    }

    private void appendAnomalies(StringBuilder sb, PipelineMetrics m) {
        sb.append("## 四、异常清单（需人工抽查）\n\n");
        if (m.anomalies.isEmpty()) {
            sb.append("无。\n\n");
            return;
        }
        Map<String, List<MetricAnomaly>> grouped = new LinkedHashMap<>();
        for (MetricAnomaly a : m.anomalies) {
            grouped.computeIfAbsent(a.kind, k -> new ArrayList<>()).add(a);
        }
        for (Map.Entry<String, List<MetricAnomaly>> e : grouped.entrySet()) {
            sb.append("### ").append(e.getKey()).append("（").append(e.getValue().size()).append(" 条）\n\n");
            sb.append("| 栏目 | 作者 | 发布时间 | 说明 |\n|---|---|---|---|\n");
            for (MetricAnomaly a : e.getValue()) {
                sb.append('|').append(a.column).append('|').append(nvl(a.author))
                  .append('|').append(nvl(a.publishedAt)).append('|').append(a.detail).append("|\n");
            }
            sb.append('\n');
        }
    }

    /** 自动结论：把「能不能落库」直接判出来，省得每次人肉看表。 */
    private void appendVerdict(StringBuilder sb, PipelineMetrics m) {
        List<String> blockers = new ArrayList<>();
        long noPostId = m.anomalies.stream().filter(a -> "POST_ID_MISSING".equals(a.kind)).count();
        long truncated = m.anomalies.stream().filter(a -> "CONTENT_TRUNCATED".equals(a.kind)).count();
        long empty = m.anomalies.stream().filter(a -> "CONTENT_EMPTY".equals(a.kind)).count();
        long suspect = m.anomalies.stream().filter(a -> "CLASSIFY_SUSPECT".equals(a.kind)).count();

        if (noPostId > 0) {
            blockers.add("血缘键缺失 " + noPostId + " 篇——落库后无法幂等重跑，必须先修采集");
        }
        if (empty > 0) {
            blockers.add("正文为空 " + empty + " 篇——选择器失效，必须先修采集");
        }
        if (truncated > 0) {
            blockers.add("正文疑似截断 " + truncated + " 篇——大概率没取到全文，入库会污染题库");
        }
        if (suspect > 0) {
            blockers.add("分类可疑 " + suspect + " 篇——身份与判定矛盾，需回看原文校准规则");
        }

        sb.append("## 五、结论\n\n");
        if (blockers.isEmpty()) {
            sb.append("**通过**：无血缘键缺失、无正文截断、无分类矛盾，可以进入落库阶段。\n\n");
        } else {
            sb.append("**未通过，落库前需处理：**\n\n");
            for (String b : blockers) {
                sb.append("- ").append(b).append('\n');
            }
            sb.append('\n');
        }
        sb.append("> 判定口径：血缘键 / 源链接必须 100% 覆盖；正文低于 ")
          .append(PipelineEvaluator.TRUNCATED_THRESHOLD).append(" 字视为疑似截断；")
          .append("身份与分类结果矛盾（星主判 member_post、星友拿 0.9 分）视为分类可疑。\n");
    }

    private static String formatTypes(Map<String, Integer> types) {
        if (types.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        types.forEach((k, v) -> sb.append(k).append("×").append(v).append(" "));
        return sb.toString().trim();
    }

    private static String nvl(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }
}
