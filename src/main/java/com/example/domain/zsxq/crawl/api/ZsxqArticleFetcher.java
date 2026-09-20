package com.example.domain.zsxq.crawl.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.example.domain.zsxq.normalize.ContentImages;
import com.example.domain.zsxq.normalize.HtmlToMarkdown;

/**
 * 取星主长文的全文：<b>纯 HTTP 直取文章页，不开浏览器</b>。
 *
 * <p>这是整套方案里收益最大的一处替换。旧实现每篇长文都要 {@code context.newPage()}
 * 打开文章页，浏览器老老实实把几十张图、字体、样式表全下载下来，一篇文章就是几百 MB 的
 * 渲染进程开销，跑几百篇必然 OOM。而实测这个页面是<b>服务端渲染的静态 HTML</b>，
 * 带 cookie 直接 GET 就能拿到完整正文（实测单篇 3.5 万字 + 13 个图片 URL）。
 *
 * <p>关键点：我们只要 {@code <img>} 的 src / data-src 这两个 HTML 属性做溯源，
 * <b>图根本不需要真的下载</b>。属性在 DOM 里就是字符串，跟网络请求无关。
 *
 * <p>页面结构（实测）：标题 {@code h1.title}，正文 {@code div.content.ql-editor}。
 * 外层 {@code div.post} 上带 {@code js_watermark}，说明站点本身在水印上做了处理，
 * 因此只取正文容器内部、并复用 {@link ContentImages} 过滤，避免水印/头像混入。
 */
public class ZsxqArticleFetcher {

    private final ZsxqApiClient client;

    public ZsxqArticleFetcher(ZsxqApiClient client) {
        this.client = client;
    }

    /** 取不到返回 null（链接失效、无权限等），由编排层记一条失败而不是中断整批。 */
    public ZsxqArticle fetch(String articleUrl) {
        if (articleUrl == null || articleUrl.isBlank()) {
            return null;
        }
        String html;
        try {
            html = client.get(articleUrl);
        } catch (Exception e) {
            System.out.println("    [长文] 取全文失败 " + articleUrl + " : " + e.getMessage());
            return null;
        }
        if (html == null || html.isBlank()) {
            return null;
        }

        Document doc = Jsoup.parse(html);
        String title = "";
        Element h1 = doc.selectFirst("h1.title");
        if (h1 == null) {
            h1 = doc.selectFirst("h1");
        }
        if (h1 != null) {
            title = h1.text().trim();
        }

        Element body = doc.selectFirst("div.content.ql-editor");
        if (body == null) {
            body = doc.selectFirst(".content");
        }
        if (body == null) {
            return null;
        }

        List<String> images = collectImages(body);
        String md = HtmlToMarkdown.toMarkdown(body.html());
        return new ZsxqArticle(title, md, images);
    }

    /**
     * 只收正文容器内的图，且必须过 {@link ContentImages} 这道闸——
     * 表情、头像、水印、二维码混进来的话，后面登记、概括、向量化全是在给噪音花钱。
     */
    private static List<String> collectImages(Element body) {
        Set<String> out = new LinkedHashSet<>();
        Elements imgs = body.select("img");
        for (Element img : imgs) {
            String dataSrc = img.attr("data-src");
            String src = img.attr("src");
            String url = (!dataSrc.isBlank() && !dataSrc.startsWith("data:")) ? dataSrc : src;
            if (url.isBlank() || url.startsWith("data:")) {
                continue;
            }
            if (!ContentImages.isContentImage(url)) {
                continue;
            }
            out.add(url);
        }
        return new ArrayList<>(out);
    }
}
