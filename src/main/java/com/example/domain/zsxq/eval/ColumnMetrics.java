package com.example.domain.zsxq.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个栏目的采集 / 清洗指标（评估报告的分栏表一行）。
 * 归 eval 作用域：只被评估报告消费，不参与管道其他阶段，故不放 model 包。
 */
public class ColumnMetrics {

    public String column;
    public int total;
    public int withPostId;          // 血缘键解析成功的篇数
    public int withSourceUrl;       // 溯源链接解析成功的篇数
    public int articlePagePosts;    // 走文章页（articles.zsxq.com）取全文的篇数
    public int contentLenMin;
    public int contentLenMedian;
    public int contentLenMax;
    public int truncatedSuspects;   // 正文短于阈值，疑似 feed 预览截断
    public int withImages;
    public int withReplies;
    /** 该栏目清洗后的帖子类型分布。 */
    public Map<String, Integer> postTypes = new LinkedHashMap<>();

    public double postIdRate() {
        return total == 0 ? 0 : withPostId * 100.0 / total;
    }

    public double sourceUrlRate() {
        return total == 0 ? 0 : withSourceUrl * 100.0 / total;
    }
}
