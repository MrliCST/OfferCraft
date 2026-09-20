package com.example.domain.zsxq.source;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 A 的一页结果：任务列表 + 下一页游标。
 *
 * <p>游标是<b>字符串且由数据源自己定义语义</b>，上层只负责原样存盘、原样传回。
 * 这样换站点时上层编排一行都不用改 —— 知识星球用 {@code end_time} 时间戳当游标，
 * 别的站点可以用 offset、页码或任何它能表达「从这里继续」的东西。
 *
 * <p>{@code nextCursor} 为 null 表示已经到底。
 */
public class TaskPage {

    public final List<CrawlTask> tasks;
    public final String nextCursor;

    public TaskPage(List<CrawlTask> tasks, String nextCursor) {
        this.tasks = tasks == null ? new ArrayList<>() : tasks;
        this.nextCursor = nextCursor;
    }

    /** 没有更多数据时的空页。注意与「本次请求偶发空」区分：后者要重试，不是到底。 */
    public static TaskPage end() {
        return new TaskPage(new ArrayList<>(), null);
    }
}
