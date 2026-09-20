package com.example.domain.zsxq.source;

import com.example.domain.zsxq.model.CrawledPost;

/**
 * 数据源适配：一个"能按范围列清单、能按清单取内容"的站点实现。
 *
 * <p>这是「换一个网站也能用」的关键抽象。抽象点刻意不是"网页爬虫"——
 * 那会把实现绑死在浏览器上；而是<b>数据源</b>：只要你能回答「我有哪些内容」「给我第 N 条」，
 * 就能接进管道，管你背后是 HTTP 接口、数据库还是浏览器。
 *
 * <p>知识星球一个站就逼着我们写两个实现（接口版快但字段不全、浏览器版慢但什么都能拿），
 * 这件事本身就证明抽象是必要的，而不是过度设计。
 *
 * <p>返回值沿用 {@link CrawledPost}：这是全管道的数据契约，
 * 换数据源不该让下游清洗阶段跟着改。
 */
public interface CrawlSource {

    /** 站点标识，与 {@link CrawlScope#sourceId} 对上才能被选中。 */
    String sourceId();

    /**
     * 阶段 A：按范围列出一页任务。
     *
     * @param scope  爬取范围
     * @param cursor 上一页返回的游标；null 或空串表示从头开始
     */
    TaskPage plan(CrawlScope scope, String cursor);

    /**
     * 阶段 B：按任务取回一篇完整内容。
     *
     * @return 取到则非 null；取不到返回 null 由编排层记一条失败（不中断整批）
     */
    CrawledPost fetch(CrawlTask task);
}
