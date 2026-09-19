package com.example.domain.zsxq.normalize;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * S1 的正文图判定：哪些 &lt;img&gt; 属于「帖子正文内容」，哪些是站点的装饰资源。
 *
 * <p><b>为什么这道过滤必须住在 S1</b>：原来它写在 S4 的 {@code ZsxqImageService.registerImages} 里，
 * 也就是「图片 URL 已经进了 {@code CrawledPost.imageUrls}、数据库也落了原始帖」之后才筛。
 * 这等于让下游去擦上游的屁股 —— 污染源在采集层，就该在采集层堵住：
 * <ul>
 *   <li>表情/图标本来就不该进 {@code imageUrls} 这个「正文图」列表；</li>
 *   <li>落库和向量化都在下游，越往下游筛，走过的无用路径越长。</li>
 * </ul>
 *
 * <p><b>判据用域名白名单，不用黑名单</b>。黑名单（排掉 {@code wx.zsxq.com/assets*}）的问题是
 * 站点换个路径前缀就漏了；白名单只需认准「哪个域放正文图」这一个事实：
 * <ul>
 *   <li>{@code images.zsxq.com} —— 帖子正文图（CDN，URL 带签名参数）；</li>
 *   <li>{@code article-images.zsxq.com} —— 文章页（星主长文）正文图。</li>
 * </ul>
 * 实测样本里 {@code wx.zsxq.com/assets_dweb/images/emoji/抱拳.png} 这类表情、
 * 以及头像/水印/二维码，全都不在上面这两个域下。
 *
 * <p>纯函数、不依赖 Spring 与浏览器 —— 采集层（{@code ZsxqCrawler}）和测试都能直接用。
 */
public final class ContentImages {

    /** 承载帖子正文图的域名。 */
    private static final List<String> CONTENT_HOSTS = List.of(
            "images.zsxq.com",
            "article-images.zsxq.com");

    /** 内联 data URI：不是可追溯的图床地址，直接排除。 */
    private static final String DATA_URI_PREFIX = "data:";

    private ContentImages() {
    }

    /**
     * 是不是正文内容图。
     *
     * <p>认域名而非「是否以 http 开头」：采集时相对路径已被 {@code toAbsolute} 归一，
     * 能走到这里的都是绝对 URL；归一失败的（无 schema）一律当非正文图，宁可少收不可错收
     * —— 错收一张表情的代价是白花一次视觉模型的调用费。
     */
    public static boolean isContentImage(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        if (url.startsWith(DATA_URI_PREFIX)) {
            return false;
        }
        String host = hostOf(url);
        return host != null && CONTENT_HOSTS.contains(host);
    }

    /**
     * 从 URL 里取主机名。只用字符串处理不引 URI 解析：实测的图床 URL 带签名查询串
     * （{@code ?imageMogr2/...}），而 {@code URI.getHost()} 遇到某些带括号/空格的签名参数会抛异常。
     * 失败时返回 null，交给调用方当「非正文图」处理。
     */
    static String hostOf(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return null;                       // 没有 schema，不是可判定的绝对 URL
        }
        int start = schemeEnd + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = url.substring(start, end);
        int at = authority.indexOf('@');
        String host = at >= 0 ? authority.substring(at + 1) : authority;   // 去掉 user:pass@
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);   // 去掉端口
        }
        return host.isEmpty() ? null : host.toLowerCase();
    }

    /**
     * 从一组 URL 里挑出正文图，按出现顺序去重。
     *
     * <p>去重在这里做而不是留给调用方：同一张图在正文和回复里可能各出现一次，
     * 或者在懒加载占位与真图属性里各出现一次，先去重能省掉后面一路的重复开销。
     */
    public static List<String> filterContentImages(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String url : urls) {
            if (isContentImage(url)) {
                out.add(url);
            }
        }
        return new ArrayList<>(out);
    }
}
