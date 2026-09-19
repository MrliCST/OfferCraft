package com.example.domain.zsxq.crawl;

/**
 * 知识星球 API（v2/groups/&lt;gid&gt;/topics）返回的一条帖子。
 *
 * <p>DOM 爬取拿不到星友帖的官方 id（app-topic 上没有任何 id 属性、也没有链接），
 * 这类帖子只能靠 API 补齐血缘键与溯源链接，所以这里只存补字段需要的那些列。
 * 归 crawl 作用域：只被采集阶段消费，不进 model 包。
 */
public class ApiTopic {

    /** 官方帖子 id（数字字符串），落库后就是 zsxq_raw_post.post_id。 */
    public String topicId;
    public String author;
    /** API 原始时间，ISO 带毫秒：2026-09-18T22:22:41.020+0800。 */
    public String createTimeRaw;
    /** 规整到分钟的 "yyyy-MM-dd HH:mm"，跟 DOM 的 publishedAt 同格式，便于匹配。 */
    public String createTimeMinute;
    /** 正文原文（富文本 &lt;e&gt; 标记）。 */
    public String text;
    /** 星主长文的文章页 URL（普通帖为 null）。 */
    public String articleUrl;
    public String title;
    public int commentsCount;
    public boolean digested;
    public boolean sticky;

    /** 溯源链接：星主长文给文章页，普通帖给帖子详情页。 */
    public String sourceUrl(String groupUrlPrefix) {
        if (articleUrl != null && !articleUrl.isEmpty()) {
            return articleUrl;
        }
        return groupUrlPrefix + "/topic/" + topicId;
    }
}
