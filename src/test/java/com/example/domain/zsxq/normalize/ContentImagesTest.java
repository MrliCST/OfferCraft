package com.example.domain.zsxq.normalize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ContentImages} 的判定与抽取。
 *
 * <p>样本取自实测：正例是真实抓到的图床 URL（带签名参数），反例是样本里真实出现过的表情。
 */
class ContentImagesTest {

    @Test
    void cdnImage_url_isContent() {
        assertTrue(ContentImages.isContentImage(
                "https://images.zsxq.com/FvY3EXAMPLE?imageMogr2/auto-orient/thumbnail/750x"));
    }

    @Test
    void articleImage_url_isContent() {
        assertTrue(ContentImages.isContentImage(
                "https://article-images.zsxq.com/FoX0EXAMPLE"));
    }

    @Test
    void emojiAsset_isNotContent() {
        // 实测样本里真实出现过的表情图，39 个 URL 里有 5 个是这种
        assertFalse(ContentImages.isContentImage(
                "https://wx.zsxq.com/assets_dweb/images/emoji/抱拳.png"));
    }

    @Test
    void avatarAndWatermarkHosts_areNotContent() {
        assertFalse(ContentImages.isContentImage("https://wx.zsxq.com/assets_dweb/images/avatar/default.png"));
        assertFalse(ContentImages.isContentImage("https://static.zsxq.com/watermark.png"));
        assertFalse(ContentImages.isContentImage("https://images.example.com/photo.jpg"));
    }

    @Test
    void dataUri_isNotContent() {
        assertFalse(ContentImages.isContentImage("data:image/png;base64,iVBORw0KGgo="));
    }

    @Test
    void blankAndRelativeAndNull_areNotContent() {
        assertFalse(ContentImages.isContentImage(null));
        assertFalse(ContentImages.isContentImage(""));
        assertFalse(ContentImages.isContentImage("   "));
        assertFalse(ContentImages.isContentImage("/images/foo.png"));   // 归一失败的相对路径
    }

    @Test
    void hostExtraction_handlesPortUserInfoAndQuery() {
        assertEquals("images.zsxq.com", ContentImages.hostOf("https://images.zsxq.com/a.png"));
        assertEquals("images.zsxq.com", ContentImages.hostOf("https://images.zsxq.com:8443/a.png?x=1"));
        assertEquals("images.zsxq.com", ContentImages.hostOf("https://user:pass@images.zsxq.com/a.png"));
        assertEquals("images.zsxq.com", ContentImages.hostOf("HTTPS://IMAGES.ZSXQ.COM/a.png"));
        assertEquals("images.zsxq.com", ContentImages.hostOf("https://images.zsxq.com"));
        assertEquals(null, ContentImages.hostOf("images.zsxq.com/a.png"));
    }

    @Test
    void signatureWithParentheses_doesNotBreakParsing() {
        // 实测签名参数里出现过括号，用 URI.getHost() 会抛异常，所以走字符串切分
        assertTrue(ContentImages.isContentImage(
                "https://images.zsxq.com/Fabc?imageMogr2/thumbnail/!50p(1)/format/webp"));
    }

    @Test
    void filter_keepsOrderAndDedupes() {
        List<String> in = List.of(
                "https://wx.zsxq.com/assets_dweb/images/emoji/抱拳.png",
                "https://images.zsxq.com/a.png",
                "https://images.zsxq.com/b.png",
                "https://images.zsxq.com/a.png");      // 重复
        assertEquals(List.of("https://images.zsxq.com/a.png", "https://images.zsxq.com/b.png"),
                ContentImages.filterContentImages(in));
    }

    @Test
    void filter_onEmptyOrNull_returnsEmpty() {
        assertEquals(List.of(), ContentImages.filterContentImages(null));
        assertEquals(List.of(), ContentImages.filterContentImages(List.of()));
    }
}
