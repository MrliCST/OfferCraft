package com.example.domain.zsxq.normalize;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

/**
 * S1 归一化：帖子 / 回复的 HTML 片段转 Markdown。
 * 用 flexmark-html2md（进程内、确定性、无损），对齐技术选型。
 *
 * 处理顺序：
 *  1) 先用 jsoup 归一化 &lt;img&gt; —— 知识星球懒加载图往往同时带 src(占位 gif) 与
 *     data-src(真图 URL)，flexmark 遇到双属性会把图渲染成 {@code :文件名:} 占位符并丢
 *     失 URL；这里把 data-src 提为唯一 src，保证 Markdown 输出干净的 {@code ![](url)}。
 *  2) 再交给 FlexmarkHtmlConverter 转 Markdown。
 *
 * 说明：图片以 {@code ![](url)} 形式留在 Markdown 里（0 存储，URL 即溯源），
 * 具体哪些图保留由清洗管道 S4 图片策略决定；{@code ZsxqCrawler} 仍单独收集 imageUrls
 * 供 S4 过滤与 zsxq_image 表入库（多模态描述走 S5）。
 */
public final class HtmlToMarkdown {

    private static final FlexmarkHtmlConverter CONVERTER = FlexmarkHtmlConverter.builder().build();

    private HtmlToMarkdown() {
    }

    public static String toMarkdown(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String normalized = normalizeImages(html);
        return CONVERTER.convert(normalized).trim();
    }

    /**
     * 把懒加载 &lt;img&gt; 归一成单一、干净的 src：
     *  - 优先取 data-src（真图），否则用 src；
     *  - 丢弃 data:/占位 src（如 loading gif）；
     *  - 移除 data-src / data-original / onerror / onload 等冗余属性。
     * 归一化失败则原样返回，交给 flexmark 尽力转换。
     */
    private static String normalizeImages(String html) {
        try {
            Document doc = Jsoup.parseBodyFragment(html);
            Elements imgs = doc.select("img");
            for (Element img : imgs) {
                String dataSrc = img.attr("data-src");
                String src = img.attr("src");
                String real = (!dataSrc.isEmpty() && !dataSrc.startsWith("data:")) ? dataSrc : src;
                if (real != null && !real.isEmpty() && !real.startsWith("data:")) {
                    img.attr("src", real);
                }
                img.removeAttr("data-src");
                img.removeAttr("data-original");
                img.removeAttr("onerror");
                img.removeAttr("onload");
            }
            return doc.body().html();
        } catch (Exception e) {
            return html;
        }
    }
}
