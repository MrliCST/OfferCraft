package com.example.domain.zsxq.image;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把正文 Markdown 里图片的 alt 换成多模态概括（S4 的「引用方式」）。
 *
 * <p>S1 用 flexmark 转出来的图片长这样：{@code ![图片.png](url)} —— alt 是<b>文件名</b>，
 * 对阅读和检索都没价值。S4 拿到 description 之后回填成
 * {@code ![一张 Agent 调用 RAG 的流程图](url)}，人读正文能知道图在讲什么，
 * 也给将来「正文里搜图」留了抓手。
 *
 * <p><b>纯函数，不碰数据库</b>：url → description 的映射由调用方查好传进来，
 * 这样这段逻辑可以脱离 PG 单测（见 {@code MarkdownImageAltBackfillerTest}）。
 *
 * <p>为什么按 <b>URL 精确匹配</b>而不是按出现顺序对 {@code seq}：实测过，两者<b>对不上</b>。
 * {@code zsxq_image.seq} 是「采集时 {@code imageUrls} 列表里的下标」，
 * 而正文里的图片顺序是 flexmark 转换后的顺序，中间还隔着跨栏目去重、正文重排，
 * 拿位置配对会张冠李戴。URL 是唯一的，按它配才可靠。
 * （当前样本 24 个正文图引用 100% 能按 URL 配上。）
 */
public final class MarkdownImageAltBackfiller {

    /** Markdown 图片语法：{@code ![alt](url)}，alt 可为空。url 到右括号或空白为止。 */
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\((\\S+?)\\)");

    /**
     * alt 的最大长度。图注本来就不该长——正文里挂一段 200 字的描述会把行内文字挤爆。
     * 取 80 字左右，一句话说清「这张图是什么」即可；完整描述仍在 {@code zsxq_image.description} 里。
     */
    static final int MAX_ALT_CHARS = 80;

    private MarkdownImageAltBackfiller() {
    }

    /**
     * 逐个把匹配到的图片 alt 换成描述。
     *
     * @param markdown       正文
     * @param urlToDescription 图片 URL → 描述；没有描述或不在映射里的图<b>原样保留</b>
     * @return 回填后的正文；markdown 为空时原样返回
     */
    public static String backfill(String markdown, Map<String, String> urlToDescription) {
        if (markdown == null || markdown.isBlank() || urlToDescription == null || urlToDescription.isEmpty()) {
            return markdown;
        }
        Matcher m = IMAGE.matcher(markdown);
        StringBuilder out = new StringBuilder(markdown.length() + 256);
        while (m.find()) {
            String url = m.group(2);
            String desc = urlToDescription.get(url);
            String alt = (desc == null || desc.isBlank()) ? m.group(1) : sanitize(desc);
            m.appendReplacement(out, Matcher.quoteReplacement("![" + alt + "](" + url + ")"));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * 把描述洗成能安全放进 {@code ![...]} 的 alt。
     *
     * <p>三件事，每件都有破坏 Markdown 结构的风险：
     * <ol>
     *   <li><b>压成单行</b>：alt 里带换行会让图片语法跨行，后面解析全乱；</li>
     *   <li><b>去方括号</b>：alt 里出现 {@code ]} 会提前闭合，语法直接崩；</li>
     *   <li><b>截断</b>：太长（见 {@link #MAX_ALT_CHARS}）就砍，加省略号。</li>
     * </ol>
     * 顺带去掉首尾空白。
     */
    static String sanitize(String desc) {
        String s = desc.replaceAll("\\s+", " ")      // 换行/制表统统压成单个空格
                .replace("[", "（").replace("]", "）")
                .trim();
        if (s.length() > MAX_ALT_CHARS) {
            s = s.substring(0, MAX_ALT_CHARS) + "…";
        }
        return s;
    }
}
