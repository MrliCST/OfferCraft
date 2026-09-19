package com.example.domain.zsxq.crawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TopicMatcherTest {

    @Test
    void matchesByAuthorMinuteAndContentHead() {
        ApiTopic a = topic("111", "阳zero", "2026-09-17 16:25", "马哥，可以先把2.0简历模板出了吗，想先投着再说");
        TopicMatcher m = new TopicMatcher(List.of(a));

        ApiTopic hit = m.match("阳zero", "2026-09-17 16:25", "马哥，可以先把**2.0简历模板**出了吗，想先投着再说");

        assertEquals("111", hit.topicId);
    }

    @Test
    void sameMinuteTwoPosts_distinguishedByContent() {
        ApiTopic first = topic("201", "范特西", "2026-09-19 11:09", "百度三面：讲讲你这个xxx的整体流程");
        ApiTopic second = topic("202", "范特西", "2026-09-19 11:09", "百度二面 介绍一下秒杀领券流程");
        TopicMatcher m = new TopicMatcher(List.of(first, second));

        ApiTopic hit1 = m.match("范特西", "2026-09-19 11:09", "百度二面 介绍一下秒杀领券流程？有没有做异常处理");
        ApiTopic hit2 = m.match("范特西", "2026-09-19 11:09", "百度三面：讲讲你这个xxx的整体流程");

        assertEquals("202", hit1.topicId);
        assertEquals("201", hit2.topicId);
        assertNotEquals(hit1.topicId, hit2.topicId);
    }

    @Test
    void starMasterPost_fallsBackToUnusedWhenHeadDiffers() {
        // 星主帖：API 的标题被塞进 <e title="URL编码"> 里，剥离后正文开头对不上 DOM 的 Markdown 标题
        ApiTopic t = topic("301", "马丁", "2026-09-18 22:22",
                "<e type=\"text_bold\" title=\"%E3%80%8AAI%E5%A4%A7\" />\n\n上一篇，我们把一段人设送进模型");
        TopicMatcher m = new TopicMatcher(List.of(t));

        ApiTopic hit = m.match("马丁", "2026-09-18 22:22", "# 《AI大模型Ragent项目》——RAG如何成为Agent的工具\n\n上一篇，我们把...");

        assertEquals("301", hit.topicId);
    }

    @Test
    void crossColumnDuplicate_returnsSameTopicId() {
        // 同一篇帖子会同时出现在「面试相关」「优质面经」等栏目，两栏都得拿到同一个 topic_id，
        // 否则清洗阶段没法按 postId 去重
        ApiTopic a = topic("401", "某人", "2026-09-01 10:00", "同一段正文");
        TopicMatcher m = new TopicMatcher(List.of(a));

        assertEquals("401", m.match("某人", "2026-09-01 10:00", "同一段正文").topicId);
        assertEquals("401", m.match("某人", "2026-09-01 10:00", "同一段**正文**（换个栏目）").topicId);
    }

    @Test
    void fallbackClaim_notConsumedTwice() {
        // 开头对不上时的退化认领：同一条只能被认领一次，否则同一分钟连发两篇会抢到同一条
        ApiTopic a = topic("402", "马丁", "2026-09-18 22:22", "<e type=\"text_bold\" title=\"%E6%A0%87%E9%A2%98\" />正文A");
        ApiTopic b = topic("403", "马丁", "2026-09-18 22:22", "<e type=\"text_bold\" title=\"%E6%A0%87%E9%A2%98\" />正文B");
        TopicMatcher m = new TopicMatcher(List.of(a, b));

        ApiTopic hit1 = m.match("马丁", "2026-09-18 22:22", "# 标题\n\n完全对不上的正文一");
        ApiTopic hit2 = m.match("马丁", "2026-09-18 22:22", "# 标题\n\n完全对不上的正文二");

        assertEquals("402", hit1.topicId);
        assertEquals("403", hit2.topicId);
    }

    @Test
    void noCandidate_returnsNull() {
        TopicMatcher m = new TopicMatcher(List.of(topic("501", "甲", "2026-09-01 10:00", "正文")));

        assertNull(m.match("乙", "2026-09-01 10:00", "正文"));
        assertNull(m.match("甲", "2026-09-02 10:00", "正文"));
    }

    @Test
    void normalize_stripsRichTextAndPunctuation() {
        assertEquals("百度三面讲讲你这个xxx的整体流程",
                TopicMatcher.normalize("<e type=\"text_bold\" />百度三面：讲讲你这个 xxx 的整体流程！"));
    }

    @Test
    void minuteOf_alignsWithDomFormat() {
        assertEquals("2026-09-18 22:22", ZsxqApiTopics.minuteOf("2026-09-18T22:22:41.020+0800"));
        assertEquals("", ZsxqApiTopics.minuteOf(null));
    }

    @Test
    void sourceUrl_prefersArticlePage() {
        ApiTopic withArticle = topic("601", "马丁", "2026-09-18 22:22", "正文");
        withArticle.articleUrl = "https://articles.zsxq.com/id_abc.html";
        ApiTopic plain = topic("602", "星友", "2026-09-18 22:22", "正文");

        assertEquals("https://articles.zsxq.com/id_abc.html", withArticle.sourceUrl("https://wx.zsxq.com"));
        assertEquals("https://wx.zsxq.com/topic/602", plain.sourceUrl("https://wx.zsxq.com"));
    }

    private static ApiTopic topic(String id, String author, String minute, String text) {
        ApiTopic t = new ApiTopic();
        t.topicId = id;
        t.author = author;
        t.createTimeMinute = minute;
        t.text = text;
        return t;
    }
}
