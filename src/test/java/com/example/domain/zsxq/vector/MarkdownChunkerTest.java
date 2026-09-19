package com.example.domain.zsxq.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

/** 切块：标题分节是我们自己的一层，节内再切交给 langchain4j 的段落切块器。 */
class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker();

    @Test
    void shortPostWithoutHeading_becomesOneChunk() {
        Document doc = doc("zsxq-1", "面试官问线程池参数怎么配，我答了核心线程数和队列，被追问拒绝策略就卡住了。");

        List<TextSegment> chunks = chunker.split(doc);

        assertEquals(1, chunks.size());
        assertNull(chunks.get(0).metadata().getString(MarkdownChunker.MD_HEADING));
        assertEquals(0, chunks.get(0).metadata().getInteger(MarkdownChunker.MD_INDEX));
    }

    @Test
    void atxHeadings_splitIntoSections() {
        Document doc = doc("zsxq-2", """
                # 一篇长文

                开篇第一段。

                ## 1. 第一小节

                """ + "第一小节讲了线程池的七个参数，以及队列和拒绝策略怎么配合。".repeat(5) + """

                ## 2. 第二小节

                """ + "第二小节讲线程池的监控和动态调参，以及线上怎么发现线程池打满。".repeat(5));

        List<TextSegment> chunks = chunker.split(doc);

        // 开篇那段太短（不足 MIN_SECTION），会并进下一节
        assertEquals(2, chunks.size());
        assertEquals("# 一篇长文", chunks.get(0).metadata().getString(MarkdownChunker.MD_HEADING));
        assertEquals("## 2. 第二小节", chunks.get(1).metadata().getString(MarkdownChunker.MD_HEADING));
        assertTrue(chunks.get(0).text().startsWith("# 一篇长文"), "标题要拼进正文开头，检索时自带语境");
    }

    @Test
    void setextHeading_recognized() {
        // 星主长文里真有这种写法：文字行 + 一串下划线
        Document doc = doc("zsxq-3", "技术背景：框架长什么样\n----------------------\n\n" + "正文".repeat(200));

        List<TextSegment> chunks = chunker.split(doc);

        assertEquals("技术背景：框架长什么样", chunks.get(0).metadata().getString(MarkdownChunker.MD_HEADING));
    }

    @Test
    void tinySections_mergedIntoOneChunk() {
        // 每节都只有一句话，不合并会切出一堆几十字的碎片块
        Document doc = doc("zsxq-tiny", """
                ## 1. 短节

                一句话。

                ## 2. 另一个短节

                另一句。
                """);

        assertEquals(1, chunker.split(doc).size());
    }

    @Test
    void longSection_splitIntoMultipleChunks() {
        String longBody = "这一节讲了很多东西。".repeat(120);   // 远超软上限 900
        Document doc = doc("zsxq-4", "### 大节\n\n" + longBody);

        List<TextSegment> chunks = chunker.split(doc);

        assertTrue(chunks.size() > 1, "超长节要切成多块，实际 " + chunks.size());
        for (TextSegment c : chunks) {
            assertEquals("### 大节", c.metadata().getString(MarkdownChunker.MD_HEADING));
            assertTrue(c.text().startsWith("### 大节"));
            assertTrue(c.text().length() <= 1200, "每块不能太长，实际 " + c.text().length());
        }
    }

    @Test
    void splitTwice_sameChunkIds() {
        // chunk_id 必须确定性，否则重跑会越跑越多
        Document doc = doc("zsxq-5", "### 节\n\n" + "内容。".repeat(600));

        List<String> first = chunker.split(doc).stream().map(ZsxqChunkEmbeddingStore::chunkIdOf).toList();
        List<String> second = chunker.split(doc).stream().map(ZsxqChunkEmbeddingStore::chunkIdOf).toList();

        assertTrue(first.size() > 1, "要切出多块才测得到序号，实际 " + first.size());
        assertEquals(first, second);
        assertEquals("zsxq-5#0", first.get(0));
    }

    @Test
    void documentMetadata_inheritedByEveryChunk() {
        Document doc = Document.from("### 节\n\n正文内容足够长一点的正文内容。",
                Metadata.from(Map.<String, Object>of(MarkdownChunker.MD_DOC_ID, "zsxq-6", "author", "马丁")));

        List<TextSegment> chunks = chunker.split(doc);

        assertEquals("zsxq-6", chunks.get(0).metadata().getString(MarkdownChunker.MD_DOC_ID));
        assertEquals("马丁", chunks.get(0).metadata().getString("author"));
    }

    private static Document doc(String docId, String content) {
        return Document.from(content, Metadata.from(Map.<String, Object>of(MarkdownChunker.MD_DOC_ID, docId)));
    }
}
