package com.example.domain.browser;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
 * <p>三种模式，按优先级取第一个配了的：
 * <ol>
 *   <li><b>CDP</b>：配了 {@code browser.session.cdp-endpoint} —— 接管用户已经打开并登录好的 Chrome，
 *       用的是用户当前的会话，最灵活；代价是会在用户浏览器上开一个标签页。</li>
 *   <li><b>PROFILE</b>：配了 {@code browser.session.user-data-dir} —— 用这个用户数据目录启动，
 *       登录态存在目录里，适合无人值守；代价是该目录不能被另一个 Chrome 同时占用。</li>
 *   <li><b>HEADLESS</b>：都没配 —— 跟以前一样无头启动，未登录。</li>
 * </ol>
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
    private final LoginStateStore loginStateStore;

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
        PageSession session = switch (pickMode()) {
            case CDP -> openOverCdp(safeUrl);
            case STORED -> openWithStoredState(safeUrl);
            case PROFILE -> openWithProfile(safeUrl);
            case HEADLESS -> openHeadless(safeUrl);
        };
        log.info("打开页面 {}，来源：{}", safeUrl, session.source());
        return session;
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

    /**
     * 按配置挑模式。顺序是有讲究的：
     * CDP（实时接管，最准）→ 存档（无头恢复，最省事）→ profile 目录 → 无头。
     *
     * <p>存档排在 profile 前面，是因为存档是"登录成功那一刻抄下来的快照"，
     * 而 profile 目录里的 cookie 会过期、也可能被 Chrome 判坏丢掉（踩过）。
     */
    private PageSession.Mode pickMode() {
        if (sessionProperties.hasCdp()) {
            return PageSession.Mode.CDP;
        }
        if (loginStateStore.exists()) {
            return PageSession.Mode.STORED;
        }
        if (sessionProperties.hasUserDataDir()) {
            return PageSession.Mode.PROFILE;
        }
        return PageSession.Mode.HEADLESS;
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
            navigate(page, url);
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
    private PageSession openWithStoredState(String url) {
        Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setChannel(screenshotProperties.channel())
                .setHeadless(true));

        try {
            Browser.NewContextOptions options = new Browser.NewContextOptions()
                    .setViewportSize(screenshotProperties.viewportWidth(), screenshotProperties.viewportHeight());
            loginStateStore.applyTo(options);   // cookie（含 HttpOnly）+ localStorage

            BrowserContext context = browser.newContext(options);
            String initScript = loginStateStore.sessionStorageInitScript();
            if (!initScript.isBlank()) {
                context.addInitScript(initScript);  // sessionStorage：页面加载前就填好
            }

            Page page = context.newPage();
            navigate(page, url);
            return new PageSession(PageSession.Mode.STORED, page, browser,
                    "已登录浏览器（存档 " + loginStateStore.stateFile() + "）");
        } catch (RuntimeException e) {
            browser.close();
            throw e;
        }
    }

    /** 用指定的用户数据目录启动，登录态就在这个目录里 */
    private PageSession openWithProfile(String url) {
        Path dir = expand(sessionProperties.userDataDir());
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException("用户数据目录没法创建：" + dir + "，原因：" + e.getMessage());
        }

        BrowserContext context;
        try {
            context = playwright.chromium().launchPersistentContext(dir, new BrowserType.LaunchPersistentContextOptions()
                    .setChannel(screenshotProperties.channel())
                    .setHeadless(sessionProperties.useHeadlessProfile())
                    .setViewportSize(screenshotProperties.viewportWidth(), screenshotProperties.viewportHeight()));
        } catch (PlaywrightException e) {
            throw new IllegalStateException("用用户数据目录 " + dir + " 启动 Chrome 失败，"
                    + "多半是这个目录正被另一个 Chrome 占用（同一份 profile 不能同时开两次）。"
                    + "原始错误：" + e.getMessage());
        }

        try {
            Page page = context.newPage();
            navigate(page, url);
            return new PageSession(PageSession.Mode.PROFILE, page, context, "已登录浏览器（profile " + dir + "）");
        } catch (RuntimeException e) {
            context.close();
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
            navigate(page, url);
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

    /** 支持 ~ 开头的家目录写法 */
    private static Path expand(String dir) {
        String path = dir.trim();
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return Paths.get(path).toAbsolutePath().normalize();
    }
}
