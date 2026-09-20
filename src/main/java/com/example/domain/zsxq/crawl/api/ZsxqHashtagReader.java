package com.example.domain.zsxq.crawl.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import com.example.domain.zsxq.source.CrawlScope;
import com.example.domain.zsxq.source.CrawlTask;
import com.example.domain.zsxq.source.TaskPage;

/**
 * 读「圈子标签清单」与「按标签翻页拉帖子列表」——阶段 A 的全部网络操作都在这里。
 *
 * <p><b>为什么按标签拉，而不是拉全量再筛</b>：实测 {@code /v2/groups/<gid>/topics} 是全量流，
 * 只能一路翻到底才知道有哪些帖，而 {@code /v2/hashtags/<hid>/topics} 是<b>按标签的独立时间流</b>，
 * 直接就只给这个栏目的帖子。后者让「只爬面试相关」从一个前端点击动作，
 * 变成了一个可传参、可断点、可复现的接口调用 —— 这才是可控爬取该有的样子。
 *
 * <p><b>空页必须重试</b>：这个接口会偶发返回 200 + 空 topics（实测首次请求就有概率命中）。
 * 旧代码只重试 1 次就判定"到底了"，结果索引停在 248 篇，后面几百篇全部匹配不到 topic_id。
 * 这里改成重试 {@value #EMPTY_RETRY} 次并递增退避，仍空才认为到底。
 */
public class ZsxqHashtagReader {

    /** 单页条数。接口上限就是 20，调大无效。 */
    private static final int PAGE_SIZE = 20;

    /** 空页重试次数（不含首次）。实测偶发空，重试几乎必成功。 */
    private static final int EMPTY_RETRY = 3;

    private final ZsxqApiClient client;
    private final String groupId;

    public ZsxqHashtagReader(ZsxqApiClient client, String groupId) {
        this.client = client;
        this.groupId = groupId;
    }

    /**
     * 圈子全部话题标签。用于把"栏目名"翻译成接口要的 hashtag id。
     *
     * <p>同样会偶发返回空数组，所以跟列表接口一样要重试 —— 拿不到标签清单的话，
     * 后面"按栏目爬"就无从谈起，这一步空了必须自己救回来。
     */
    public List<ZsxqHashtag> listHashtags() {
        String url = "https://api.zsxq.com/v2/groups/" + groupId + "/hashtags";
        JsonNode arr = null;
        for (int attempt = 0; attempt <= EMPTY_RETRY; attempt++) {
            arr = client.getJson(url).path("resp_data").path("hashtags");
            if (arr.isArray() && arr.size() > 0) {
                break;
            }
            if (attempt < EMPTY_RETRY) {
                sleep(1200L * (attempt + 1));
            }
        }
        List<ZsxqHashtag> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String id = n.path("hashtag_id").asText(null);
            if (id == null || id.isEmpty()) {
                continue;
            }
            // 接口给的是 title 且两侧带 #（"#🌈面试相关#"），没有 name 字段
            String title = n.path("title").asText("");
            out.add(new ZsxqHashtag(id, title.replace("#", "").trim(), n.path("topics_count").asInt()));
        }
        return out;
    }

    /**
     * 按标签翻一页。
     *
     * @param cursor 上一页末条的 create_time；null/空表示从最新开始
     */
    public TaskPage page(String hashtagId, String cursor) {
        String url = "https://api.zsxq.com/v2/hashtags/" + hashtagId + "/topics?count=" + PAGE_SIZE
                + (cursor == null || cursor.isEmpty() ? "" : "&end_time=" + encode(cursor));
        JsonNode arr = null;
        for (int attempt = 0; attempt <= EMPTY_RETRY; attempt++) {
            arr = client.getJson(url).path("resp_data").path("topics");
            if (arr.isArray() && arr.size() > 0) {
                break;
            }
            if (attempt < EMPTY_RETRY) {
                sleep(1200L * (attempt + 1));
            }
        }
        if (arr == null || !arr.isArray() || arr.size() == 0) {
            return TaskPage.end();     // 连续重试仍空：真的到底了
        }

        List<CrawlTask> tasks = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode n : arr) {
            CrawlTask t = toTask(n);
            if (t != null && seen.add(t.taskId)) {
                tasks.add(t);
            }
        }
        // 下一页的游标就是本页末条的 create_time（接口会把它重复返回一次，上面靠 seen 去重）
        JsonNode last = arr.get(arr.size() - 1);
        String next = last.path("create_time").asText(null);
        return new TaskPage(tasks, next);
    }

    /**
     * 按完整范围翻一页，附带时间区间与作者过滤。
     *
     * <p>列表是<b>从新到旧</b>的，所以一旦某条早于 {@code fromTime}，后面只会更旧，
     * 直接判定到底，不用把整条流翻完 —— 全量几千篇时这一句能省掉大量无用请求。
     */
    public TaskPage pageByScope(CrawlScope scope, String cursor) {
        TaskPage raw = page(scope.tagId, cursor);
        if (raw.nextCursor == null && raw.tasks.isEmpty()) {
            return raw;
        }
        String from = norm(scope.fromTime);
        String to = norm(scope.toTime);
        List<CrawlTask> kept = new ArrayList<>();
        boolean reachedFloor = false;
        for (CrawlTask t : raw.tasks) {
            String ct = norm(t.publishedAt);
            if (!from.isEmpty() && ct.compareTo(from) < 0) {
                reachedFloor = true;          // 已经早于起始时间，后面更旧
                break;
            }
            boolean inWindow = to.isEmpty() || ct.compareTo(to) <= 0;
            boolean byAuthor = scope.author == null || scope.author.isEmpty()
                    || scope.author.equals(t.author);
            if (inWindow && byAuthor) {
                kept.add(t);
            }
        }
        if (reachedFloor) {
            return TaskPage.end();
        }
        return new TaskPage(kept, raw.nextCursor);
    }

    private static CrawlTask toTask(JsonNode n) {
        String id = n.path("topic_id").asText(null);
        if (id == null || id.isEmpty()) {
            return null;
        }
        JsonNode talk = n.path("talk");
        String author = talk.path("owner").path("name").asText("");
        String time = n.path("create_time").asText("");
        String text = talk.path("text").asText("");
        String digest = text.length() <= 60 ? text : text.substring(0, 60);
        return new CrawlTask(id, time, author, digest.replace("\n", " "));
    }

    /** "2026-09-18T22:22:41.020+0800" → "2026-09-18 22:22"，与 scope 里的时间口径对齐后可字典序比较。 */
    static String norm(String t) {
        if (t == null || t.length() < 16) {
            return "";
        }
        return (t.substring(0, 10) + " " + t.substring(11, 16)).replace('T', ' ').trim();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
