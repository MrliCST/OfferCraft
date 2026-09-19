package com.example.domain.zsxq.crawl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;

import com.example.domain.browser.CrawlThrottle;

/**
 * 调知识星球 API 拉帖子列表，用于补 DOM 爬不到的血缘键 / 溯源链接。
 *
 * <p>接口：GET https://api.zsxq.com/v2/groups/&lt;gid&gt;/topics?scope=all&amp;count=20
 * 翻页：带上本页最后一条的 create_time 作 end_time 游标（下一页会重复上页最后一条，靠 topicId 去重）。
 * 登录态由 Playwright 的 {@link APIRequestContext} 自动带上 cookie，不用自己管鉴权。
 */
public final class ZsxqApiTopics {

    private static final String API_HOST = "https://api.zsxq.com";
    private static final int PAGE_SIZE = 20;

    private final APIRequestContext request;
    private final String groupId;
    private final ObjectMapper om = new ObjectMapper();

    public ZsxqApiTopics(APIRequestContext request, String groupId) {
        this.request = request;
        this.groupId = groupId;
    }

    /**
     * 翻页拉取，直到返回不足一页、达到 maxPages 或请求失败。
     *
     * <p>踩过的坑：接口偶发返回空 topics（HTTP 仍是 200），此时若直接 break，
     * 索引会只剩第一页 20 篇，导致大量帖子匹配不到 topic_id。所以空页先重试一次，
     * 仍为空才停。另外下一页首条 = 上页末条，靠 topicId 去重。
     */
    public List<ApiTopic> fetchAll(int maxPages) {
        List<ApiTopic> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = "";
        for (int page = 1; page <= maxPages; page++) {
            JsonNode arr = call(cursor).path("resp_data").path("topics");
            int n = arr.isArray() ? arr.size() : 0;
            if (n == 0) {
                // 偶发空页：换同一个游标再要一次，仍空才认为到底了
                arr = call(cursor).path("resp_data").path("topics");
                n = arr.isArray() ? arr.size() : 0;
                System.out.println("  [API] 第" + page + "页空，重试后 " + n + " 条");
                if (n == 0) {
                    break;
                }
            }
            for (JsonNode node : arr) {
                ApiTopic t = parse(node);
                if (t != null && seen.add(t.topicId)) {
                    out.add(t);
                }
            }
            System.out.println("  [API] 第" + page + "页 " + n + " 条，累计 " + out.size() + " 篇");
            JsonNode last = arr.get(n - 1);
            String ct = last.path("create_time").asText(null);
            if (ct == null) {
                break;
            }
            cursor = "&end_time=" + encode(ct);
            if (n < PAGE_SIZE) {
                break;
            }
        }
        return out;
    }

    private JsonNode call(String cursor) {
        String url = API_HOST + "/v2/groups/" + groupId + "/topics?scope=all&count=" + PAGE_SIZE + cursor;
        try {
            CrawlThrottle.beforeCrawl(url);
            APIResponse resp = request.get(url);
            if (resp.status() != 200) {
                System.out.println("  [API] HTTP " + resp.status());
            }
            return om.readTree(resp.text());
        } catch (Exception e) {
            System.out.println("  [API] 请求失败: " + e.getMessage());
            return om.createObjectNode();
        } finally {
            CrawlThrottle.afterCrawl(url);
        }
    }

    private static ApiTopic parse(JsonNode n) {
        String id = n.path("topic_id").asText(null);
        if (id == null || id.isEmpty()) {
            return null;
        }
        ApiTopic t = new ApiTopic();
        t.topicId = id;
        t.author = n.path("talk").path("owner").path("name").asText(null);
        t.createTimeRaw = n.path("create_time").asText(null);
        t.createTimeMinute = minuteOf(t.createTimeRaw);
        t.text = n.path("talk").path("text").asText("");
        t.articleUrl = n.path("talk").path("article").path("article_url").asText(null);
        t.title = n.path("title").asText(null);
        t.commentsCount = n.path("comments_count").asInt();
        t.digested = n.path("digested").asBoolean();
        t.sticky = n.path("sticky").asBoolean();
        return t;
    }

    /** "2026-09-18T22:22:41.020+0800" → "2026-09-18 22:22"，跟 DOM 的 publishedAt 对齐。 */
    static String minuteOf(String iso) {
        if (iso == null || iso.length() < 16) {
            return "";
        }
        return iso.substring(0, 10) + " " + iso.substring(11, 16);
    }

    private static String encode(String s) {
        return s.replace("+", "%2B").replace(":", "%3A");
    }
}
