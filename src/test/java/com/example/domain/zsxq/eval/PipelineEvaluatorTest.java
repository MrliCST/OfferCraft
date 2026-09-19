package com.example.domain.zsxq.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.ZsxqCleanedDoc;
import com.example.domain.zsxq.model.ZsxqDropRecord;

class PipelineEvaluatorTest {

    private final PipelineEvaluator evaluator = new PipelineEvaluator();

    @Test
    void healthyArticle_noAnomaly() {
        CrawledPost p = post("starmaster", "id_a", "https://articles.zsxq.com/id_a.html", "星主", 5000);
        ZsxqCleanedDoc d = doc("id_a", "tech_article", 0.9);

        PipelineMetrics m = evaluator.evaluate(List.of(p), List.of(d), List.of());

        assertEquals(1, m.totalPosts);
        assertEquals(1, m.keptDocs);
        assertEquals(1, m.articlePagePosts);
        assertEquals(0, m.feedOnlyPosts);
        assertTrue(m.anomalies.isEmpty(), "健康样本不该有异常: " + m.anomalies.size());
        assertEquals(100.0, m.columns.get(0).postIdRate());
        assertEquals(5000, m.columns.get(0).contentLenMax);
    }

    @Test
    void missingPostIdAndTruncated_reported() {
        CrawledPost p = post("tech_qa", null, null, "星友", 120);
        p.content = "x".repeat(117) + "...";   // feed 预览的省略号收尾特征

        PipelineMetrics m = evaluator.evaluate(List.of(p), List.of(), List.of());

        assertEquals(1, m.feedOnlyPosts);
        assertTrue(m.anomalies.stream().anyMatch(a -> "POST_ID_MISSING".equals(a.kind)));
        assertTrue(m.anomalies.stream().anyMatch(a -> "SOURCE_URL_MISSING".equals(a.kind)));
        assertTrue(m.anomalies.stream().anyMatch(a -> "CONTENT_TRUNCATED".equals(a.kind)));
        assertEquals(1, m.columns.get(0).truncatedSuspects);
    }

    @Test
    void shortButCompletePost_notFlagged() {
        CrawledPost p = post("ragentai", null, null, "星友", 24);   // 星友提问帖本来就短
        p.content = "马哥，可以先把2.0简历模板出了吗，想先投着再说";

        PipelineMetrics m = evaluator.evaluate(List.of(p), List.of(), List.of());

        assertTrue(m.anomalies.stream().noneMatch(a -> "CONTENT_TRUNCATED".equals(a.kind)),
                "完整短帖不该被判成截断");
        assertEquals(0, m.columns.get(0).truncatedSuspects);
    }

    @Test
    void classifySuspect_reported() {
        CrawledPost p = post("starmaster", "id_b", "https://articles.zsxq.com/id_b.html", "星主", 8000);
        ZsxqCleanedDoc d = doc("id_b", "member_post", 0.1);   // 星主帖被判成星友帖

        PipelineMetrics m = evaluator.evaluate(List.of(p), List.of(d), List.of());

        assertTrue(m.anomalies.stream().anyMatch(a -> "CLASSIFY_SUSPECT".equals(a.kind)));
    }

    @Test
    void columnsAggregatedByColumn() {
        CrawledPost a = post("tech_qa", "id_1", "https://wx.zsxq.com/topic/1", "星友", 600);
        CrawledPost b = post("interview", "id_2", "https://wx.zsxq.com/topic/2", "星友", 900);
        CrawledPost c = post("interview", "id_3", "https://wx.zsxq.com/topic/3", "星友", 1500);

        PipelineMetrics m = evaluator.evaluate(List.of(a, b, c), List.of(), List.of());

        assertEquals(2, m.columns.size());
        ColumnMetrics interview = m.columns.stream()
                .filter(x -> "interview".equals(x.column)).findFirst().orElseThrow();
        assertEquals(2, interview.total);
        assertEquals(1200, interview.contentLenMedian);   // 900 与 1500 的中位数
        assertEquals(0, interview.articlePagePosts);
        assertEquals(3, m.feedOnlyPosts);
    }

    @Test
    void droppedCountedByReason() {
        CrawledPost p = post("mianjing", "id_4", "https://wx.zsxq.com/topic/4", "星友", 700);
        List<ZsxqDropRecord> drops = List.of(new ZsxqDropRecord("mianjing", "某人", "2026-09-01 10:00", "off_topic"));

        PipelineMetrics m = evaluator.evaluate(List.of(p), List.of(), drops);

        assertEquals(1, m.droppedDocs);
        assertEquals(1, m.dropReasons.get("off_topic"));
        assertEquals(100.0, m.dropRate());
    }

    private static CrawledPost post(String column, String postId, String url, String role, int contentLen) {
        CrawledPost p = new CrawledPost();
        p.column = column;
        p.postId = postId;
        p.sourceUrl = url;
        p.author = "马丁";
        p.authorRole = role;
        p.publishedAt = "2026-09-18 22:22";
        p.content = "x".repeat(contentLen);
        return p;
    }

    private static ZsxqCleanedDoc doc(String rawPostId, String postType, double authority) {
        ZsxqCleanedDoc d = new ZsxqCleanedDoc();
        d.docId = "zsxq-" + rawPostId;
        d.rawPostId = rawPostId;
        d.postType = postType;
        d.authorityScore = authority;
        d.content = "正文";
        return d;
    }
}
