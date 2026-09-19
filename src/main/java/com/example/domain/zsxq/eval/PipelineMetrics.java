package com.example.domain.zsxq.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 清洗管道一次运行的整体指标（评估报告的输入模型）。
 * 归 eval 作用域：只被评估报告消费，不参与管道其他阶段，故不放 model 包。
 */
public class PipelineMetrics {

    public int totalPosts;          // 去重后的独立帖数（指标都按这个算）
    public int rawPosts;            // 爬取到的原始条数（含跨栏目重复）
    public int keptDocs;            // 清洗后入库篇数
    public int droppedDocs;         // 丢弃篇数
    public int seriesLinked;        // S5 串到系列里的篇数
    public int starMasterVerified;  // 星主权威校验命中篇数
    public int articlePagePosts;    // 走文章页取全文的篇数（星主长文那条分支）
    public int feedOnlyPosts;       // 只走 feed 预览的篇数（星友帖那条分支）

    /** 入库文档按 post_type 分布。 */
    public Map<String, Integer> byPostType = new LinkedHashMap<>();
    /** 入库文档按权威分分布（0.9 / 0.1 / 0.3）。 */
    public Map<String, Integer> byAuthority = new LinkedHashMap<>();
    /** 丢弃原因分布。 */
    public Map<String, Integer> dropReasons = new LinkedHashMap<>();
    /** 分栏目指标。 */
    public List<ColumnMetrics> columns = new ArrayList<>();
    /** 需要人工抽查的异常项。 */
    public List<MetricAnomaly> anomalies = new ArrayList<>();

    public double dropRate() {
        return totalPosts == 0 ? 0 : droppedDocs * 100.0 / totalPosts;
    }

    public void countType(String type) {
        byPostType.merge(type == null ? "null" : type, 1, Integer::sum);
    }

    public void countAuthority(double score) {
        byAuthority.merge(String.valueOf(score), 1, Integer::sum);
    }

    public void countDropReason(String reason) {
        dropReasons.merge(reason == null || reason.isEmpty() ? "(未说明)" : reason, 1, Integer::sum);
    }
}
