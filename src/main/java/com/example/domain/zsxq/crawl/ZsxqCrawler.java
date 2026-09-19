package com.example.domain.zsxq.crawl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import com.example.domain.browser.CrawlThrottle;
import com.example.domain.browser.LoginStateStore;
import com.example.domain.browser.SiteLoginRegistry;
import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.CrawledReply;
import com.example.domain.zsxq.normalize.HtmlToMarkdown;

/**
 * 知识星球圈子爬虫：按栏目（话题 chip）各爬 N 篇帖，保存结构化 JSON。
 * 用法: java ...ZsxqCrawler [输出目录] [每栏篇数] [栏目key，逗号分隔，留空=全部]
 * 输出: <输出目录>/<columnKey>.json，每文件含 N 篇 CrawledPost。
 * 例：只爬「只看星主」栏最近 3 篇
 *      java ...ZsxqCrawler crawl-output/zsxq-smoke 3 starmaster
 *
 * 本次修复（2026-09-18）：
 *  - 星主长文截断：feed 预览约 260 字，遇「查看详情」则开新页取完整正文。
 *  - 图片 src 采集：帖子正文 + 回复里的 &lt;img&gt; 全部收集（清洗阶段按 Q1 过滤）。
 *  - 补齐 post_id（从 /topic/&lt;id&gt; 解析）与 source_url（详情页 URL）。
 *
 * 本次修复（2026-09-19，小样本验收暴露）：
 *  - 星主长文的全文不在 /topic/ 详情页，而在 feed 里的文章页链接
 *    articles.zsxq.com/id_xxx.html（页面 h1=标题、.content=正文、正文内 img 才是真图，
 *    页面其余 img 是水印/头像/二维码）。旧代码只认 /topic/，导致 postId/sourceUrl 全 null、
 *    正文永远停在 270 字预览。现在文章页链接优先：取全文 + 正文图 + h1 标题。
 *  - 文章页必须带登录态打开，否则跳 wx.zsxq.com/login，所以复用同一个 BrowserContext。
 */
public final class ZsxqCrawler {

    private static final String GROUP_URL = "https://wx.zsxq.com/group/51121244585524";
    private static final String BASE_URL = "https://wx.zsxq.com";
    /** 星主长文的文章页域名：全文、标题、正文图都在这里，且 URL 里的 id 是稳定血缘键。 */
    private static final String ARTICLE_HOST = "articles.zsxq.com";

    /** 栏目 = 侧边栏 chip 文案（contains 匹配）。 */
    private static final Map<String, String> COLUMNS = new LinkedHashMap<>();
    static {
        COLUMNS.put("ragentai", "RagentAI");     // 💡RagentAI 核心文档
        COLUMNS.put("tech_qa", "技术问答");        // 📃技术问答
        COLUMNS.put("interview", "面试相关");       // 🌈面试相关
        COLUMNS.put("mianjing", "优质面经");        // 💫优质面经
        COLUMNS.put("jinghua", "精华");            // ✨精华（精选高质量帖）
        COLUMNS.put("starmaster", "只看星主");      // 只看星主（马丁）
    }

    private static final String STAR_MASTER = "马丁";
    private static final int DEFAULT_PER_COLUMN = 5;

    /** 详情页提取结果（标题 + 完整正文 + 图片）。 */
    private static class DetailResult {
        final String title;
        final String content;
        final List<String> images;

        DetailResult(String title, String content, List<String> images) {
            this.title = title;
            this.content = content;
            this.images = images;
        }
    }
    
    //入口方法：
    public static void main(String[] args) throws Exception {
        
        String outDir = args.length > 0 ? args[0]
                : System.getProperty("user.home") + "/code/demo/JLRADemo/crawl-output/zsxq";
        int perColumn = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PER_COLUMN;
        Set<String> onlyColumns = parseColumns(args.length > 2 ? args[2] : null);
        Files.createDirectories(Path.of(outDir));
        //登陆态：路径由 host 推导，不手抄字符串 —— 手抄的那份跟站点名单各记一份，
        //改名单时不会跟着变，而且错了不报错，只是静默退化成匿名抓取
        LoginStateStore store = new LoginStateStore(
                SiteLoginRegistry.defaultFileFor(SiteLoginRegistry.hostOf(GROUP_URL)));
        //格式化输出带缩进的 json
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        //跳过下载chrome内核，直接用本地chrome浏览器
        Map<String, String> env = Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        //
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(env))) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions()
                    .setChannel("chrome")
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

            Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions();
            store.applyTo(ctxOpts);
            BrowserContext context = browser.newContext(ctxOpts);
            String init = store.sessionStorageInitScript();
            if (!init.isEmpty()) {
                context.addInitScript(init);
            }

            Page page = context.newPage();
            //爬虫限流
            CrawlThrottle.beforeCrawl(GROUP_URL);
            page.navigate(GROUP_URL);
            Thread.sleep(5000);
            //遍历帖子
            for (Map.Entry<String, String> col : COLUMNS.entrySet()) {
                String key = col.getKey();
                if (!onlyColumns.isEmpty() && !onlyColumns.contains(key)) {
                    continue;
                }
                String chipText = col.getValue();
                System.out.println("\n##### 栏目: " + chipText + " #####");
                clickChip(page, chipText);
                page.evaluate("() => window.scrollTo(0, 0)");
                Thread.sleep(2000);
                // 等待该栏目帖子渲染（切换栏目有竞态：旧帖清空→新帖加载），滚动触发无限加载
                int visible = 0;
                for (int i = 0; i < 12; i++) {
                    page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                    Thread.sleep(1200);
                    visible = page.querySelectorAll("app-topic[type='flow']").size();
                    if (visible >= perColumn) {
                        break;
                    }
                }

                List<ElementHandle> topics = page.querySelectorAll("app-topic[type='flow']");
                System.out.println("  该栏目可见帖子数: " + topics.size() + (visible < perColumn ? "  (不足" + perColumn + ")" : ""));
                List<CrawledPost> posts = new ArrayList<>();
                int n = Math.min(perColumn, topics.size());
                for (int i = 0; i < n; i++) {
                    try {
                        posts.add(extractPost(topics.get(i), chipText, context));
                    } catch (Exception e) {
                        System.out.println("  帖子 " + (i + 1) + " 提取失败: " + e.getMessage());
                    }
                }

                Path out = Path.of(outDir, key + ".json");
                om.writeValue(out.toFile(), posts);
                System.out.println("  已保存 " + posts.size() + " 篇 -> " + out);
            }

            CrawlThrottle.afterCrawl(GROUP_URL);
            context.close();
            browser.close();
        }
        System.out.println("\n全部栏目爬取完成，输出目录: " + outDir);
    }

    /** 解析「只爬这些栏目」参数：逗号分隔的 column key，空串表示不过滤。 */
    private static Set<String> parseColumns(String spec) {
        Set<String> out = new LinkedHashSet<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String s : spec.split(",")) {
            String k = s.trim();
            if (!k.isEmpty()) {
                out.add(k);
            }
        }
        return out;
    }

    //提取帖子的结构画信息，封装为CrawledPost对象。
    private static CrawledPost extractPost(ElementHandle topic, String column, BrowserContext context) throws Exception {
        CrawledPost p = new CrawledPost();
        p.column = column;

        // 作者 + 角色（role 元素 class 含 member=星友 / owner=星主；注意 ng-star-inserted 含 "star" 不能用来判星主）
        ElementHandle role = topic.querySelector("app-topic-header .role");
        if (role != null) {
            p.author = role.innerText().trim();
            String cls = role.getAttribute("class");
            if (cls != null && cls.contains("owner")) {
                p.authorRole = "星主";
            } else {
                p.authorRole = "星友";
            }
        }

        // 时间
        ElementHandle date = topic.querySelector("app-topic-header .date");
        if (date != null) {
            p.publishedAt = date.innerText().trim();
        }

        // post_id + 详情 URL：优先 topic-id 属性，否则从 /topic/<id> 链接解析
        String topicIdAttr = topic.getAttribute("topic-id");
        if (topicIdAttr != null && !topicIdAttr.isEmpty()) {
            p.postId = topicIdAttr;
        }
        String detailUrl = null;
        String articleUrl = null;   // 文章页：星主长文的全文在这里
        boolean hasViewDetail = false;
        for (ElementHandle a : topic.querySelectorAll("a")) {
            String href = a.getAttribute("href");
            if (href == null) continue;
            String abs = toAbsolute(href);
            if (abs.contains(ARTICLE_HOST)) {
                if (articleUrl == null) {
                    articleUrl = abs;
                }
                if (p.postId == null) {
                    p.postId = parseArticleId(abs);
                }
            }
            if (p.postId == null && href.contains("/topic/")) {
                p.postId = parseTopicId(href);
            }
            if (containsText(a, "查看详情")) {
                hasViewDetail = true;
                if (detailUrl == null) detailUrl = abs;
            } else if (detailUrl == null && href.contains("/topic/")) {
                detailUrl = abs;
            }
        }
        // 文章页优先于 /topic/ 详情页：前者才有全文，后者只是 feed 同款预览
        if (articleUrl != null) {
            detailUrl = articleUrl;
        }
        if (detailUrl != null) {
            p.sourceUrl = detailUrl;
        }

        // 正文（先尝试 feed 内「展开全部」）
        ElementHandle showAll = topic.querySelector(".showAll");
        if (showAll != null) {
            try {
                showAll.click();
                Thread.sleep(400);
            } catch (Exception ignored) {
            }
        }
        ElementHandle contentEl = topic.querySelector(".talk-content-container .content");
        if (contentEl != null) {
            p.content = HtmlToMarkdown.toMarkdown(contentEl.innerHTML());
        }

        // 星主长文截断：feed 只有预览，全文在文章页（或 /topic/ 详情页）里
        boolean truncated = hasViewDetail || articleUrl != null;
        if (truncated && context != null) {
            DetailResult d = openDetail(context, detailUrl);
            if (d.content != null && !d.content.isEmpty()) {
                // 文章页的 .content 不含标题，标题在 h1，补回去保证入库后有标题
                p.content = (d.title != null && !d.title.isEmpty())
                        ? "# " + d.title + "\n\n" + d.content
                        : d.content;
            }
            p.imageUrls.addAll(d.images);
        } else {
            // 未走详情页：直接在 feed 采图片（正文 + 回复）
            p.imageUrls.addAll(collectImages(topic.querySelectorAll(".talk-content-container img, .comment-box img")));
        }

        // 话题标签
        for (ElementHandle tag : topic.querySelectorAll(".tag-container .tag")) {
            String t = tag.innerText().trim();
            if (!t.isEmpty()) {
                p.topicTags.add(t);
            }
        }

        // 点赞用户（粗略计数）
        for (ElementHandle like : topic.querySelectorAll(".like-user .eachLike span:not(.space)")) {
            String s = like.innerText().trim();
            if (!s.isEmpty() && !"、".equals(s)) {
                p.likeUsers.add(s.replace("、", ""));
            }
        }

        // 回复（评论）
        for (ElementHandle ci : topic.querySelectorAll(".comment-box app-comment-item")) {
            CrawledReply r = new CrawledReply();
            ElementHandle c = ci.querySelector(".comment");
            if (c != null) {
                r.commenter = c.innerText().trim();
            }
            ElementHandle rt = ci.querySelector("span[parsetype='pure'].text");
            if (rt == null) {
                rt = ci.querySelector(".text span.text");
            }
            if (rt != null) {
                r.text = HtmlToMarkdown.toMarkdown(rt.innerHTML());
            }
            ElementHandle tm = ci.querySelector(".operations .time");
            if (tm != null) {
                r.time = tm.innerText().trim();
            }
            if (r.commenter != null && r.commenter.contains(STAR_MASTER)) {
                p.starMasterReplied = true;
            }
            p.replies.add(r);
        }

        // 星主身份统一兜底
        if (p.author != null && p.author.contains(STAR_MASTER)) {
            p.authorRole = "星主";
        }
        return p;
    }

    private static boolean containsText(ElementHandle el, String needle) {
        try {
            String t = el.textContent();
            return t != null && t.contains(needle);
        } catch (Exception e) {
            return false;
        }
    }

    /** 开新页取详情页完整正文 + 图片（不干扰 feed 页面）。文章页必须带登录态，故复用同一 context。 */
    private static DetailResult openDetail(BrowserContext context, String detailUrl) {
        List<String> imgs = new ArrayList<>();
        String content = null;
        String title = null;
        Page dp = context.newPage();
        try {
            CrawlThrottle.beforeCrawl(detailUrl);
            dp.navigate(detailUrl);
            try {
                dp.waitForSelector(".talk-content-container .content, .content",
                        new Page.WaitForSelectorOptions().setTimeout(8000));
            } catch (Exception ignored) {
                // 详情页结构可能不同，继续尝试读取
            }
            Thread.sleep(1500); // 等懒加载图片渲染
            ElementHandle h1 = dp.querySelector("h1");
            if (h1 != null) {
                title = h1.innerText().trim();
            }
            // 文章页正文在 .content；老详情页在 .talk-content-container .content
            ElementHandle c = dp.querySelector(".talk-content-container .content");
            if (c == null) {
                c = dp.querySelector(".content");
            }
            if (c != null) {
                // 详情页若仍折叠，点展开
                ElementHandle sa = dp.querySelector(".showAll");
                if (sa != null) {
                    try {
                        sa.click();
                        Thread.sleep(400);
                    } catch (Exception ignored) {
                    }
                }
                content = HtmlToMarkdown.toMarkdown(c.innerHTML());
                // 只取正文容器内的图：页面其他 img 是水印 / 头像 / 底部二维码
                imgs.addAll(collectImages(c.querySelectorAll("img")));
            }
        } catch (Exception e) {
            System.out.println("    详情页提取失败: " + e.getMessage());
        } finally {
            try {
                CrawlThrottle.afterCrawl(detailUrl);
            } catch (Exception ignored) {
            }
            dp.close();
        }
        return new DetailResult(title, content, imgs);
    }

    /** 收集 &lt;img&gt; 的 src（优先 data-src，兼容懒加载），过滤 data: 与相对路径归一。 */
    private static List<String> collectImages(List<ElementHandle> imgs) {
        Set<String> out = new LinkedHashSet<>();
        for (ElementHandle img : imgs) {
            String dataSrc = img.getAttribute("data-src");
            String src = img.getAttribute("src");
            String url = (dataSrc != null && !dataSrc.isEmpty()) ? dataSrc : src;
            if (url == null || url.isEmpty()) {
                continue;
            }
            if (url.startsWith("data:")) {
                continue;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = toAbsolute(url);
            }
            out.add(url);
        }
        return new ArrayList<>(out);
    }
    /** 从文章页 URL 提取文章 id（.../id_u32qjplonsiw.html → id_u32qjplonsiw）：星主长文的稳定血缘键。 */
    private static String parseArticleId(String url) {
        int i = url.indexOf("/id_");
        if (i < 0) {
            return null;
        }
        String rest = url.substring(i + 1);
        int dot = rest.indexOf('.');
        if (dot > 0) {
            rest = rest.substring(0, dot);
        }
        int q = rest.indexOf('?');
        if (q > 0) {
            rest = rest.substring(0, q);
        }
        return rest.isEmpty() ? null : rest;
    }

    //解析帖子id
    private static String parseTopicId(String url) {
        String marker = "/topic/";
        int i = url.indexOf(marker);
        if (i < 0) {
            return null;
        }
        String rest = url.substring(i + marker.length());
        int end = rest.indexOf('/');
        if (end < 0) {
            end = rest.length();
        }
        int q = rest.indexOf('?');
        if (q >= 0 && q < end) {
            end = q;
        }
        return rest.substring(0, end);
    }
    //URL 归一化，把相对链接补成全链接
    private static String toAbsolute(String href) {
        if (href == null) {
            return null;
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        if (href.startsWith("//")) {
            return "https:" + href;
        }
        if (href.startsWith("/")) {
            return BASE_URL + href;
        }
        return BASE_URL + "/" + href;
    }
    //点击函数：
    private static void clickChip(Page page, String name) {
        for (int attempt = 0; attempt < 3; attempt++) {
            for (ElementHandle el : page.querySelectorAll("div.item, a.item, li.item, span.item")) {
                try {
                    String t = el.textContent();
                    if (t != null && t.trim().contains(name)) {
                        el.click();
                        System.out.println("  已点击 chip: " + t.trim());
                        return;
                    }
                } catch (Exception ignored) {
                }
            }
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
        }
        System.out.println("  未找到 chip: " + name);
    }

}
