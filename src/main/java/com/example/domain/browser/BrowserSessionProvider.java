package com.example.domain.browser;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.example.domain.tool.ScreenshotProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 负责"用什么浏览器、带不带登录态"打开一个页面。上层工具（截图、抓正文）只拿到 {@link PageSession}，
 * 不关心背后是接管来的还是自己起的。
 *
 * <p>三种模式，按优先级取第一个成立的：
 * <ol>
 *   <li><b>CDP</b>：配了 {@code browser.session.cdp-endpoint} —— 接管用户已经打开并登录好的 Chrome，
 *       用的是用户当前的会话，最灵活；代价是会在用户浏览器上开一个标签页。</li>
 *   <li><b>STORED</b>：URL 的 host 命中 {@code browser.session.sites} 名单，且该站的存档已存在 ——
 *       无头启动并把存档灌回去，不用养常开浏览器；代价是登录态会过期（过期了重新存一次档）。</li>
 *   <li><b>HEADLESS</b>：以上都不成立 —— 名单外的站点，或者压根没配名单，一律无头匿名。</li>
 * </ol>
 *
 * <p>用哪个存档由 {@link SiteLoginRegistry} 说了算，本类只管拿到路径后怎么起浏览器。
 * 名单没命中时返回空，本类就当这个站不需要登录态 —— 这就是"没配置就无头"的全部实现。
 * 刻意<b>没有</b>"名单外也给登录态"的开关：那等于把登录态发给任意站点，风险远大于方便。
 *
 * <p>接管模式刻意不调用 {@code setViewportSize}：连的是用户正在用的浏览器，视口就是窗口大小，
 * 强行改会真的改动用户窗口尺寸（走 CDP 的 Emulation），用户能看见窗口跳一下。
 * 另两种模式照常按 {@code screenshot.viewport-*} 设置。
 *
 * <p>失败一律转成"人话"异常：模型看到的是能直接转述给用户的一句话，不是异常栈。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(BrowserSessionProperties.class)
public class BrowserSessionProvider {

    private final Playwright playwright;
    private final BrowserSessionProperties sessionProperties;
    private final ScreenshotProperties screenshotProperties;
    private final SiteLoginRegistry siteLoginRegistry;

    /**
     * 打开指定页面。
     *
     * <p>URL 校验放在这里而不是各个工具里：只要是要打开网页就得校验，写在入口处一次就够，
     * 省得每个新工具都抄一遍。
     *
     * @param url 要访问的地址，必须是 http/https
     * @return 会话句柄，用完必须关（建议 try-with-resources）
     */
    public PageSession open(String url) {
        String safeUrl = requireHttpUrl(url);
        PageSession session = openByPolicy(safeUrl);
        log.info("打开页面 {}，来源：{}", safeUrl, session.source());
        return session;
    }

    /**
     * 按优先级选一种打开方式。判断顺序里，站点名单优先于匿名无头。
     *
     * <p>名单命中但存档文件不存在时，<b>不报错</b>，只打一条 warning 然后走匿名无头：
     * 配了名单说明你打算用登录态，但还没存档是很常见的中间状态，让抓取直接失败太粗暴。
     */
    private PageSession openByPolicy(String url) {
        if (sessionProperties.hasCdp()) {
            return openOverCdp(url);
        }

        Optional<Path> stateFile = siteLoginRegistry.stateFileFor(url);
        if (stateFile.isPresent()) {
            LoginStateStore store = new LoginStateStore(stateFile.get());
            if (store.exists()) {
                return openWithStoredState(url, store);
            }
            // 名单里有这个站，但还没存过档 —— 很常见的中间状态，不报错，直接按匿名无头处理。
            // 想用存档的话：在带调试端口的 Chrome 里登录好之后跑 mvn -o -q compile exec:java
            log.warn("{} 在名单里，但存档 {} 还没存过，改用匿名无头。"
                            + "想用存档的话：登录好之后跑 mvn -o -q compile exec:java",
                    SiteLoginRegistry.hostOf(url), store.stateFile());
        }

        // 名单外的站点，或者压根没配名单：一律匿名无头
        return openHeadless(url);
    }

    /** 只放行 http/https：别让模型拿着 file:// 或 javascript: 去开页面 */
    private static String requireHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 是空的");
        }
        String trimmed = url.trim();
        String scheme = URI.create(trimmed).getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("只支持 http/https 地址，收到的是：" + trimmed);
        }
        return trimmed;
    }

    /** 接管用户已经打开的 Chrome。注意：这里的 close() 只断开连接，不会关掉用户的浏览器 */
    private PageSession openOverCdp(String url) {
        String endpoint = sessionProperties.cdpEndpoint();
        Browser browser;
        try {
            browser = playwright.chromium().connectOverCDP(endpoint);
        } catch (PlaywrightException e) {
            throw new IllegalStateException("连不上 Chrome 的调试端口（" + endpoint + "）："
                    + "请先用 --remote-debugging-port=9222 启动 Chrome 并登录好，再让程序接管。"
                    + "原始错误：" + e.getMessage());
        }

        try {
            // 接管模式下只有一个现成的 context，直接用它开标签页
            BrowserContext context = browser.contexts().isEmpty()
                    ? browser.newContext()
                    : browser.contexts().get(0);
            Page page = context.newPage();
            beginCrawl(page, url, false);
            return new PageSession(PageSession.Mode.CDP, page, browser, "已登录浏览器（接管 " + endpoint + "）");
        } catch (RuntimeException e) {
            // 开页面失败时连接也得断开，否则会残留一个连着的 driver
            browser.close();
            throw e;
        }
    }

    /**
     * 用存档恢复登录态：无头启动，但把 cookie / localStorage / sessionStorage 灌回去。
     * 不用养一个常开的浏览器，也不用每次扫码 —— 代价是登录态会过期，过期了重新存一次档。
     */
    private PageSession openWithStoredState(String url, LoginStateStore store) {
        Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setChannel(screenshotProperties.channel())
                .setHeadless(true));

        try {
            Browser.NewContextOptions options = new Browser.NewContextOptions()
                    .setViewportSize(screenshotProperties.viewportWidth(), screenshotProperties.viewportHeight());
            store.applyTo(options);   // cookie（含 HttpOnly）+ localStorage

            BrowserContext context = browser.newContext(options);
            String initScript = store.sessionStorageInitScript();
            if (!initScript.isBlank()) {
                context.addInitScript(initScript);  // sessionStorage：页面加载前就填好
            }

            Page page = context.newPage();
            beginCrawl(page, url, true);
            return new PageSession(PageSession.Mode.STORED, page, browser,
                    "已登录浏览器（存档 " + store.stateFile() + "）");
        } catch (RuntimeException e) {
            browser.close();
            throw e;
        }
    }

    /** 没配登录态时的老路子：无头启动，未登录 */
    private PageSession openHeadless(String url) {
        Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setChannel(screenshotProperties.channel())
                .setHeadless(true));

        try {
            Page page = browser.newPage(new Browser.NewPageOptions()
                    .setViewportSize(screenshotProperties.viewportWidth(), screenshotProperties.viewportHeight()));
            beginCrawl(page, url, true);
            return new PageSession(PageSession.Mode.HEADLESS, page, browser, "无头浏览器（未登录）");
        } catch (RuntimeException e) {
            browser.close();
            throw e;
        }
    }

    private void navigate(Page page, String url) {
        try {
            page.navigate(url, new Page.NavigateOptions().setTimeout(screenshotProperties.navigationTimeoutMs()));
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);
        } catch (PlaywrightException e) {
            page.close();
            throw new IllegalStateException("打不开页面 " + url + "：" + e.getMessage());
        }
    }

    /**
     * 开爬前的统一准备：自动化模式（存档 / 无头）拦掉无用资源以提速；接管模式（CDP）不拦，
     * 避免干扰用户正在用的浏览器。随机延迟放在 {@link CrawlThrottle#beforeCrawl} 里，由调用方在开页面前触发。
     */
    private void beginCrawl(Page page, String url, boolean automated) {
        if (automated) {
            CrawlThrottle.blockUselessResources(page);
        }
        navigate(page, url);
    }
}
