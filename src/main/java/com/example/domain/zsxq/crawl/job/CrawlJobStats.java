package com.example.domain.zsxq.crawl.job;

/** 一次爬取作业的计数结果，供 CLI 打印与调用方判断是否需要重跑。 */
public class CrawlJobStats {

    /** 阶段 A 拿到的任务总数（含本次之前已落盘的）。 */
    public int totalTasks;

    /** 本次新取回并落盘的篇数。 */
    public int fetched;

    /** 本次跳过（之前已取过）的篇数。 */
    public int skipped;

    /** 本次失败的篇数。重跑同一条命令会只重试这些。 */
    public int failed;

    public CrawlJobStats() {
    }

    @Override
    public String toString() {
        return "任务 " + totalTasks + " 篇，本次新取 " + fetched
                + "，跳过 " + skipped + "，失败 " + failed;
    }
}
