package com.example.domain.zsxq.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 正文图片 alt 回填。重点在<b>别把 Markdown 结构改坏</b>：
 * alt 里混进 {@code ]} 或换行，整个图片语法就崩了，正文后面的内容会连带错位。
 */
class MarkdownImageAltBackfillerTest {

    @Test
    void filenameAlt_isReplacedByDescription() {
        String md = "看图：\n\n![图片.png](https://article-images.zsxq.com/abc)\n\n后面还有字。";

        String out = MarkdownImageAltBackfiller.backfill(md,
                Map.of("https://article-images.zsxq.com/abc", "一张分层架构图，含 Controller、Service、Mapper 三层。"));

        assertTrue(out.contains("![一张分层架构图，含 Controller、Service、Mapper 三层。](https://article-images.zsxq.com/abc)"));
        assertTrue(out.contains("后面还有字。"), "图片之外的内容必须原样保留");
    }

    @Test
    void missingDescription_keepsOriginalAlt() {
        String md = "![图片.png](https://article-images.zsxq.com/xyz)";

        String out = MarkdownImageAltBackfiller.backfill(md, Map.of("https://other.com/x", "别的图"));

        assertEquals(md, out);
    }

    @Test
    void blankDescription_keepsOriginalAlt() {
        String md = "![图片.png](https://a.com/1)";

        String out = MarkdownImageAltBackfiller.backfill(md, Map.of("https://a.com/1", "   "));

        assertEquals(md, out);
    }

    @Test
    void multipleImages_eachMatchedByOwnUrl() {
        String md = "![图片.png](https://a.com/1)\n\n中间文字\n\n![图片.png](https://a.com/2)";

        String out = MarkdownImageAltBackfiller.backfill(md, Map.of(
                "https://a.com/1", "第一张：流程图",
                "https://a.com/2", "第二张：时序图"));

        assertTrue(out.contains("![第一张：流程图](https://a.com/1)"));
        assertTrue(out.contains("![第二张：时序图](https://a.com/2)"));
        assertTrue(out.contains("中间文字"));
    }

    @Test
    void urlOrderDoesNotMatter_matchIsByUrl() {
        // 实测 seq 与正文顺序对不上，所以匹配只能靠 URL；这里专门钉住「顺序反了也不串」
        String md = "![图片.png](https://a.com/second)\n\n![图片.png](https://a.com/first)";

        String out = MarkdownImageAltBackfiller.backfill(md, Map.of(
                "https://a.com/first", "先发的图",
                "https://a.com/second", "后发的图"));

        assertTrue(out.contains("![后发的图](https://a.com/second)"));
        assertTrue(out.contains("![先发的图](https://a.com/first)"));
    }

    // ---------- sanitize：别把语法改坏 ----------

    @Test
    void newlinesInDescription_areCollapsed() {
        String md = "![图片.png](https://a.com/1)";

        String out = MarkdownImageAltBackfiller.backfill(md,
                Map.of("https://a.com/1", "第一行\n第二行\t带制表符"));

        assertTrue(out.contains("![第一行 第二行 带制表符](https://a.com/1)"), out);
        assertEquals(-1, out.indexOf('\n'), "换行必须被压掉，否则图片语法跨行");
    }

    @Test
    void bracketsInDescription_areReplaced() {
        // alt 里出现 ] 会提前闭合语法，必须换掉
        String md = "![图片.png](https://a.com/1)";

        String out = MarkdownImageAltBackfiller.backfill(md,
                Map.of("https://a.com/1", "数组 array[0] 的取值"));

        assertTrue(out.contains("![数组 array（0） 的取值](https://a.com/1)"), out);
    }

    @Test
    void longDescription_isTruncatedWithEllipsis() {
        String md = "![图片.png](https://a.com/1)";
        String longDesc = "很长的描述".repeat(50);

        String out = MarkdownImageAltBackfiller.backfill(md, Map.of("https://a.com/1", longDesc));

        int altStart = out.indexOf("![") + 2;
        int altEnd = out.indexOf("](");
        assertTrue(altEnd - altStart <= MarkdownImageAltBackfiller.MAX_ALT_CHARS + 1,
                "alt 必须被截断，实际长度 " + (altEnd - altStart));
        assertTrue(out.endsWith("](https://a.com/1)"), "URL 不能被截断掉");
    }

    @Test
    void alreadyBackfilled_isIdempotent() {
        // 回填过的 alt 不含扩展名，第二次跑不改变内容
        String md = "![一张分层架构图](https://a.com/1)";

        String out = MarkdownImageAltBackfiller.backfill(md, Map.of("https://a.com/1", "一张分层架构图"));

        assertEquals(md, out);
    }

    @Test
    void emptyAlt_isFilled() {
        String md = "![](https://a.com/1)";

        String out = MarkdownImageAltBackfiller.backfill(md, Map.of("https://a.com/1", "一张报错截图"));

        assertEquals("![一张报错截图](https://a.com/1)", out);
    }

    @Test
    void imagesWithoutUrlsAreLeftAlone() {
        // 引用式图片/非 http 的 src 不该被碰
        String md = "普通文字，没有图片。";

        assertEquals(md, MarkdownImageAltBackfiller.backfill(md, Map.of("https://a.com/1", "描述")));
    }

    @Test
    void emptyInputs_passThroughUnharmed() {
        assertEquals("", MarkdownImageAltBackfiller.backfill("", Map.of("a", "b")));
        assertEquals(null, MarkdownImageAltBackfiller.backfill(null, Map.of("a", "b")));
        assertEquals("![图片.png](https://a.com/1)",
                MarkdownImageAltBackfiller.backfill("![图片.png](https://a.com/1)", Map.of()));
    }
}
