package com.example.domain.zsxq.crawl.api;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.CrawledReply;
import com.example.domain.zsxq.source.CrawlScope;
import com.example.domain.zsxq.source.CrawlSource;
import com.example.domain.zsxq.source.CrawlTask;
import com.example.domain.zsxq.source.TaskPage;

/**
 * 知识星球的<b>接口版</b>数据源：全程纯 HTTP，不启动浏览器。
 *
 * <p>与浏览器版的分工：本实现负责"快而全"的常规路径（列表按标签、详情、评论、长文全文），
 * 浏览器版降级为兜底，只在接口拿不到某些东西时使用。同一个站点两个实现，
 * 正是 {@link CrawlSource} 这个抽象存在的理由。
 *
 * <p>产出刻意沿用 {@link CrawledPost}：下游 S1–S7 清洗管道读的就是这个契约，
 * 换数据源不该让它们跟着改。字段名、时间格式、星主判定口径都与浏览器版对齐。
 */
public class ZsxqApiSource implements CrawlSource {

    /** 星主昵称。接口侧没有直接的 role 字段，用昵称判定，与浏览器版的兜底口径一致。 */
    private static final String STAR_MASTER = "马丁";

    private static final String BASE_URL = "https://wx.zsxq.com";
    private static final Pattern HASHTAG_TITLE = Pattern.compile("title=\"([^\"]*)\"");

    private final ZsxqApiClient client;
    private final ZsxqHashtagReader hashtagReader;
    private final ZsxqTopicReader topicReader;
    private final ZsxqArticleFetcher articleFetcher;

    public ZsxqApiSource(ZsxqApiClient client, String groupId) {
        this.client = client;
        this.hashtagReader = new ZsxqHashtagReader(client, groupId);
        this.topicReader = new ZsxqTopicReader(client);
        this.articleFetcher = new ZsxqArticleFetcher(client);
    }

    @Override
    public String sourceId() {
        return "zsxq";
    }

    /** 暴露标签清单，供 CLI 把"栏目名"翻译成 hashtag id。 */
    public List<ZsxqHashtag> hashtags() {
        return hashtagReader.listHashtags();
    }

    @Override
    public TaskPage plan(CrawlScope scope, String cursor) {
        return hashtagReader.pageByScope(scope, cursor);
    }

    @Override
    public CrawledPost fetch(CrawlTask task) {
        JsonNode topic = topicReader.detail(task.taskId);
        if (topic == null) {      // 连续重试仍拿不到：记为可重跑的失败，别让整批中断
            return null;
        }

        CrawledPost p = new CrawledPost();
        p.postId = task.taskId;
        p.column = "";      // 栏目由编排层回填：同一帖可属多个栏目，数据源不该替它决定归属
        p.author = topic.path("talk").path("owner").path("name").asText("");
        p.authorRole = STAR_MASTER.equals(p.author) ? "星主" : "星友";
        p.publishedAt = ZsxqHashtagReader.norm(topic.path("create_time").asText(""));

        // 话题标签：annotation 里的 hashtag，title 是 URL 编码过的
        p.topicTags.addAll(parseTags(topic.path("annotation").asText("")));

        // 点赞用户
        for (JsonNode like : topic.path("latest_likes")) {
            String n = like.path("owner").path("name").asText("");
            if (!n.isEmpty()) {
                p.likeUsers.add(n);
            }
        }

        // 正文：长文走文章页全文，短帖用接口正文
        String articleUrl = topic.path("talk").path("article").path("article_url").asText(null);
        boolean longForm = articleUrl != null && !articleUrl.isBlank();
        if (longForm) {
            ZsxqArticle art = articleFetcher.fetch(articleUrl);
            if (art != null && art.markdown != null && !art.markdown.isBlank()) {
                p.content = (art.title == null || art.title.isBlank())
                        ? art.markdown
                        : "# " + art.title + "\n\n" + art.markdown;
                p.imageUrls.addAll(art.imageUrls);
                p.sourceUrl = articleUrl;
            }
        }
        if (p.content == null || p.content.isBlank()) {
            p.content = ZsxqTopicReader.stripRichMarks(topic.path("talk").path("text").asText(""));
        }
        if (p.sourceUrl == null || p.sourceUrl.isBlank()) {
            p.sourceUrl = BASE_URL + "/topic/" + task.taskId;
        }

        // 评论：接口给得比 DOM 还全（含楼中楼字段），且直接带评论人
        List<CrawledReply> replies = topicReader.comments(task.taskId);
        for (CrawledReply r : replies) {
            if (r.commenter != null && r.commenter.contains(STAR_MASTER)) {
                p.starMasterReplied = true;
            }
            p.replies.add(r);
        }
        return p;
    }

    /**
     * 从 annotation 富文本里解析话题标签。
     * 形如 {@code <e type="hashtag" hid="88284825581252" title="%23%F0%9F%92%A1RagentAI%23" />}，
     * title 是 URL 编码的 {@code #💡RagentAI#}，需解码并去掉两侧井号。
     */
    static List<String> parseTags(String annotation) {
        Set<String> out = new LinkedHashSet<>();
        if (annotation == null || annotation.isBlank()) {
            return new ArrayList<>(out);
        }
        Matcher m = HASHTAG_TITLE.matcher(annotation);
        while (m.find()) {
            String raw = m.group(1);
            try {
                raw = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // 解码失败就按原样用，宁可标签难看也不能丢整篇
            }
            String tag = raw.replace("#", "").trim();
            if (!tag.isEmpty()) {
                out.add(tag);
            }
        }
        return new ArrayList<>(out);
    }
}
