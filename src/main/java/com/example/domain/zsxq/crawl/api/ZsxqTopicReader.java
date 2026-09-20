package com.example.domain.zsxq.crawl.api;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.example.domain.zsxq.model.CrawledReply;

/**
 * 读单篇帖子的<b>详情</b>与<b>评论</b>——阶段 B 的核心网络操作。
 *
 * <p>这里纠正了旧方案的一个前提错误：以前认为「谁回复的」只有 DOM 才有、接口拿不到，
 * 于是非得开浏览器。实测 {@code /v2/topics/<id>/comments} 直接返回评论人、正文、时间，
 * 甚至有 {@code replied_comments}（楼中楼）——比 DOM 只爬一层评论还全。
 * 这个发现是整套"去浏览器化"方案的支点。
 *
 * <p>评论正文是富文本，会带 {@code <e type="hashtag" .../>} 这类标记，
 * 必须剥掉再入库，否则标签占位符会混进题库正文。
 */
public class ZsxqTopicReader {

    /** 单次取多少条评论。够覆盖绝大多数帖；超长帖暂取前 N 条。 */
    private static final int COMMENT_COUNT = 30;

    /** 空响应重试次数（不含首次）。取值理由同 {@link ZsxqHashtagReader}。 */
    private static final int EMPTY_RETRY = 3;

    private final ZsxqApiClient client;

    public ZsxqTopicReader(ZsxqApiClient client) {
        this.client = client;
    }

    /**
     * 话题详情（作者、正文、标签、点赞、长文链接都在里面）。
     *
     * <p><b>必须空重试</b>：实测首轮全量爬取 172 篇里有 35 篇取空，事后对这些 id 单独重放
     * 又全部正常返回 —— 说明不是帖子有问题，而是这个接口跟列表接口一样会偶发吐空数据
     * （HTTP 仍是 200、结构也合法，只是 topic 节点缺席）。
     * 不重试就等于每 5 篇随机丢 1 篇，且丢得毫无规律。
     *
     * @return 取到则非 null；连续重试仍空返回 null，由编排层记为"可重跑的失败"
     */
    public JsonNode detail(String topicId) {
        String url = "https://api.zsxq.com/v2/topics/" + topicId;
        for (int attempt = 0; attempt <= EMPTY_RETRY; attempt++) {
            JsonNode t = client.getJson(url).path("resp_data").path("topic");
            if (!t.isMissingNode() && !t.isNull()) {
                return t;
            }
            if (attempt < EMPTY_RETRY) {
                sleep(1200L * (attempt + 1));
            }
        }
        return null;
    }

    /** 该帖的评论列表。取不到返回空列表，不抛异常——评论缺失不该让整篇作废。 */
    public List<CrawledReply> comments(String topicId) {
        List<CrawledReply> out = new ArrayList<>();
        try {
            JsonNode arr = client.getJson("https://api.zsxq.com/v2/topics/" + topicId
                    + "/comments?count=" + COMMENT_COUNT).path("resp_data").path("comments");
            for (JsonNode n : arr) {
                CrawledReply r = new CrawledReply();
                r.commenter = n.path("owner").path("name").asText("");
                r.text = stripRichMarks(n.path("text").asText(""));
                r.time = ZsxqHashtagReader.norm(n.path("create_time").asText(""));
                if (!r.commenter.isEmpty() || !r.text.isEmpty()) {
                    out.add(r);
                }
            }
        } catch (Exception e) {
            System.out.println("    [评论] " + topicId + " 拉取失败: " + e.getMessage());
        }
        return out;
    }

    /**
     * 剥掉富文本标记 {@code <e ... />}，只留可读文本。
     * 标签在详情里另有 {@code annotation} 字段承载，正文里这些只是渲染占位符。
     */
    static String stripRichMarks(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("<e[^>]*/?>", "").trim();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
