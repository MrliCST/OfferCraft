package com.example.domain.zsxq.source;

/**
 * 一次爬取要覆盖的<b>范围</b>——「可控」这件事的载体。
 *
 * <p>旧实现为什么不可控：它把范围隐含在两层硬编码循环里（{@code for 栏目 → for 帖子}），
 * 栏目只能靠点前端 chip 得到、进度只存在于内存下标 {@code i}。于是既不能从中间开始，
 * 也不能按时间筛，崩了内存一清就只能从头再来。
 *
 * <p>把范围显式化成一个对象之后，「从哪个栏目爬」「从哪天爬到哪天」「最多爬多少篇」
 * 全都成了可传入、可持久化、可复现的参数。这是可控爬取的最小充分条件。
 *
 * <p>字段一律用站点无关的语义命名：{@code tagId} 在知识星球是话题 hashtag id，
 * 换个站点可以是专栏 id、作者 id 或版块 id —— 上层编排不关心它到底指什么。
 */
public class CrawlScope {

    /** 站点标识，决定用哪个 {@link CrawlSource} 实现（如 {@code zsxq}）。 */
    public String sourceId = "zsxq";

    /** 范围内的分类 id（站点内口径）。空串 = 不限分类。 */
    public String tagId = "";

    /** 分类显示名，只用于日志与落盘目录命名，不参与筛选。 */
    public String tagName = "";

    /**
     * 起始时间（含）。格式 {@code yyyy-MM-dd} 或 {@code yyyy-MM-dd HH:mm}，空串 = 不限。
     *
     * <p>为什么必须支持时间：面经这类内容有明显的时效（秋招/春招/暑期实习），
     * 「只要 2026 年秋招的面经」是最自然的需求，而靠滚动条永远表达不了这个条件。
     */
    public String fromTime = "";

    /** 结束时间（含）。空串 = 不限。 */
    public String toTime = "";

    /** 本次最多取多少篇。0 = 不限（受数据源自身上限约束）。 */
    public int limit = 0;

    /** 只取该作者发的帖（昵称精确匹配）。空串 = 不限。用于「只看星主」这类场景。 */
    public String author = "";

    public CrawlScope() {
    }

    public CrawlScope(String tagId, String tagName) {
        this.tagId = tagId;
        this.tagName = tagName;
    }

    /** 生成用于落盘/日志的稳定标识，去掉 emoji 与空白，避免文件名踩坑。 */
    public String slug() {
        String base = (tagName == null || tagName.isBlank()) ? tagId : tagName;
        if (base == null || base.isBlank()) {
            return "all";
        }
        return base.replaceAll("[^\\p{L}\\p{N}_-]", "");
    }
}
