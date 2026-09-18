package com.example.domain.browser;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

/**
 * 命令行存档工具：接管一个<b>已经登录好</b>的 Chrome，把里面每个站点各存一份登录态。
 *
 * <p>为什么写成独立的 main 而不是 Spring 组件：存档常常是在"数据库还没起、应用没跑"的时候做的，
 * 拉起整个应用上下文只会平添失败点（踩过：docker 没开 → 上下文起不来 → 连存档都存不了）。
 * 这里只用到 Playwright 和存档读写两样东西，直接 main 跑最省事。
 *
 * <p>用法（一条命令）：
 * <pre>
 *   mvn -o -q compile exec:java -Dexec.mainClass=com.example.domain.browser.LoginStateCapture
 * </pre>
 * 可选参数依次是：调试端口（默认 http://127.0.0.1:9222）、输出目录（默认 ~/.config/JLRADemo/state）、
 * 只存 host 里包含这个串的站点（默认全存）。
 */
public final class LoginStateCapture {

    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:9222";
    private static final String SKIP_BROWSER_DOWNLOAD = "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD";

    private LoginStateCapture() {
    }

    public static void main(String[] args) {
        String endpoint = arg(args, 0, DEFAULT_ENDPOINT);
        // 支持 ~ 开头的家目录写法；没给目录时用默认 state 目录（本身已经是展开后的绝对路径）
        Path dir = LoginStateStore.expand(arg(args, 1, SiteLoginRegistry.stateDir().toString()));
        String onlyHost = args.length > 2 ? args[2].trim().toLowerCase(Locale.ROOT) : "";

        System.out.println("正在接管 " + endpoint + " ...");

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put(SKIP_BROWSER_DOWNLOAD, "1");
        Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
        try {
            Browser browser = connect(playwright, endpoint);
            Map<String, Page> pagesByHost = collectLoggedInPages(browser, onlyHost);

            if (pagesByHost.isEmpty()) {
                System.err.println("没找到可存档的 http 页面。请先在那个浏览器里登录好，再跑这条命令。");
                System.exit(1);
            }

            // 带了 host 过滤却一个都没命中：几乎可以肯定"登录在另一个 Chrome 了"。
            // 两个 Chrome 实例的 cookie / 登录态完全隔离，代码只连得到启动调试端口那一个。
            if (!onlyHost.isEmpty()
                    && pagesByHost.keySet().stream().noneMatch(h -> h.contains(onlyHost))) {
                System.err.println("连到的浏览器里没有任何包含 '" + onlyHost + "' 的页面。");
                System.err.println("常见原因：你登录在另一个 Chrome 窗口里了，而这条命令连的是另一个实例");
                System.err.println("（两个 Chrome 的页面 / 登录态互不打通）。请确认启动 --remote-debugging-port");
                System.err.println("的那个 Chrome，就是你已经登录好的那个；或者在这个浏览器里重新登录一次再跑。");
                System.err.println("当前这个浏览器里只有这些站点：" + String.join(", ", pagesByHost.keySet()));
                System.exit(1);
            }

            System.out.println("发现 " + pagesByHost.size() + " 个站点，开始存档：");
            for (Map.Entry<String, Page> entry : pagesByHost.entrySet()) {
                Path file = dir.resolve(entry.getKey() + ".json");
                new LoginStateStore(file).save(entry.getValue());
                System.out.println("  " + entry.getKey() + "  ->  " + file);
            }
            System.out.println("存档完成。之后抓取这些站点会自动带上登录态。");
        } finally {
            playwright.close();
        }
    }

    private static Browser connect(Playwright playwright, String endpoint) {
        try {
            return playwright.chromium().connectOverCDP(endpoint);
        } catch (PlaywrightException e) {
            System.err.println("连不上 " + endpoint + "。请先这样启动一个专门的 Chrome：");
            System.err.println("  /usr/bin/google-chrome --remote-debugging-port=9222 \\");
            System.err.println("      --user-data-dir=$HOME/.config/google-chrome-jlra &");
            System.err.println("注意：必须用非默认的 --user-data-dir，否则 Chrome 会直接忽略调试端口（不监听）。");
            System.err.println("原始错误：" + e.getMessage());
            System.exit(1);
            return null;   // 走不到这里，exit 已经退出了
        }
    }

    /**
     * 收集浏览器里所有 http 页面，按 host 去重（同一站点开多个标签只存一次）。
     * 跳过 chrome://、about:blank 这类没有 host 的页面。
     */
    private static Map<String, Page> collectLoggedInPages(Browser browser, String onlyHost) {
        Map<String, Page> pagesByHost = new LinkedHashMap<>();
        for (BrowserContext context : browser.contexts()) {
            for (Page page : context.pages()) {
                String host = SiteLoginRegistry.hostOf(page.url());
                if (host.isEmpty() || pagesByHost.containsKey(host)) {
                    continue;
                }
                if (!onlyHost.isEmpty() && !host.contains(onlyHost)) {
                    continue;
                }
                pagesByHost.put(host, page);
            }
        }
        return pagesByHost;
    }

    private static String arg(String[] args, int index, String fallback) {
        return args.length > index && !args[index].isBlank() ? args[index].trim() : fallback;
    }
}
