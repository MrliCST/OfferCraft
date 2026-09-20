package com.example.domain.zsxq.source;

/**
 * 一个待爬任务（阶段 A 的产物）。
 *
 * <p>它存在的意义是把「列清单」和「取详情」拆成两个阶段：
 * <ul>
 *   <li>清单很轻（只有 id 和时间），拉得快、能整份落盘、能断点、能人工筛选；</li>
 *   <li>详情很重（正文 + 评论 + 长文全文），但每条独立，取失败只影响这一条。</li>
 * </ul>
 * 旧实现把两步混在一个循环里，于是取详情时崩一次，连"已经知道有哪些帖子"这件事都一起丢了。
 */
public class CrawlTask {

    /** 站点内唯一 id。知识星球就是官方 topic_id —— 天然幂等的键。 */
    public String taskId;

    /** 发布时间（原始字符串，站点口径）。用于时间区间过滤与断点排序。 */
    public String publishedAt;

    /** 作者昵称，用于按作者过滤。 */
    public String author;

    /** 正文摘要（前若干字），只供日志与人工核对，不参与业务逻辑。 */
    public String digest;

    /** 是否命中长文全文链接；true 时阶段 B 需要额外取一次文章页。 */
    public boolean hasArticle;

    public CrawlTask() {
    }

    public CrawlTask(String taskId, String publishedAt, String author, String digest) {
        this.taskId = taskId;
        this.publishedAt = publishedAt;
        this.author = author;
        this.digest = digest;
    }
}
