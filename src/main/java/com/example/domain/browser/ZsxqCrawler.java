package com.example.domain.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * 知识星球圈子爬虫：按栏目（话题 chip）各爬 5 篇帖，保存结构化 JSON。
 * 用法: java ...ZsxqCrawler [输出目录]
 * 输出: <输出目录>/<columnKey>.json，每文件含 5 篇 CrawledPost。
 */
public final class ZsxqCrawler {

    private static final String GROUP_URL = "https://wx.zsxq.com/group/51121244585524";

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

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0]
                : System.getProperty("user.home") + "/code/demo/JLRADemo/crawl-output/zsxq";
        Files.createDirectories(Path.of(outDir));

        LoginStateStore store = new LoginStateStore("~/.config/JLRADemo/state/wx.zsxq.com.json");
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        Map<String, String> env = Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
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

            CrawlThrottle.beforeCrawl(GROUP_URL);
            page.navigate(GROUP_URL);
            Thread.sleep(5000);

            for (Map.Entry<String, String> col : COLUMNS.entrySet()) {
                String key = col.getKey();
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
                    if (visible >= 5) {
                        break;
                    }
                }

                List<ElementHandle> topics = page.querySelectorAll("app-topic[type='flow']");
                System.out.println("  该栏目可见帖子数: " + topics.size() + (visible < 5 ? "  (不足5，可能栏目加载失败)" : ""));
                List<CrawledPost> posts = new ArrayList<>();
                int n = Math.min(5, topics.size());
                for (int i = 0; i < n; i++) {
                    try {
                        posts.add(extractPost(topics.get(i), chipText));
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

    private static CrawledPost extractPost(ElementHandle topic, String column) throws Exception {
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
            if (STAR_MASTER.equals(p.author)) {
                p.authorRole = "星主";
            }
        }

        // 时间
        ElementHandle date = topic.querySelector("app-topic-header .date");
        if (date != null) {
            p.publishedAt = date.innerText().trim();
        }

        // 正文（长帖展开）
        ElementHandle showAll = topic.querySelector(".showAll");
        if (showAll != null) {
            try {
                showAll.click();
                Thread.sleep(400);
            } catch (Exception ignored) {
            }
        }
        ElementHandle content = topic.querySelector(".talk-content-container .content");
        if (content != null) {
            p.content = content.innerText().trim();
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
                r.text = rt.innerText().trim();
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

    /** 爬取到的帖子（与清洗管道设计对齐：topic_key / published_at / authority 预留）。 */
    public static class CrawledPost {
        public String column;
        public String author;
        public String authorRole;          // 星主 / 星友
        public String publishedAt;
        public String content;
        public List<String> topicTags = new ArrayList<>();
        public List<String> likeUsers = new ArrayList<>();
        public boolean starMasterReplied;   // Rule 2 信号：星主是否回复
        public List<CrawledReply> replies = new ArrayList<>();
        // 清洗阶段填充
        public String topicKey;             // e.g. RagentAI / 技术问答
        public double authorityScore;       // 0.9 星主 / 0.1 星友（待清洗管道赋值）
    }

    public static class CrawledReply {
        public String commenter;
        public String text;
        public String time;
    }
}
