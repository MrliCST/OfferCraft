package com.example.domain.browser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.domain.tool.ScreenshotProperties;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * 登录态的手动验证与存档。
 *
 * <p>默认不参与全量测试（@Disabled），因为它依赖本机那个登录好的浏览器 —— 换台机器就没有意义。
 * 要验证时去掉 @Disabled，单独跑其中某个方法即可：
 * <pre>
 *   mvn test -Dtest='LoggedInAccessVerificationTest#saveState'
 *            -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition
 * </pre>
 */
@SpringBootTest(classes = BrowserConfig.class)
@EnableConfigurationProperties({ScreenshotProperties.class, BrowserSessionProperties.class})
@ActiveProfiles("test")
@org.junit.jupiter.api.Disabled("依赖本机登录好的浏览器，手动验证时才打开")
class LoggedInAccessVerificationTest {

    private static final String ZSXQ_HOME = "https://wx.zsxq.com/";
    private static final String ZSXQ_GROUP = "https://wx.zsxq.com/group/51121244585524";
    private static final String PROFILE = "~/.config/google-chrome-jlra";
    private static final String CDP = "http://127.0.0.1:9222";

    @Autowired
    private Playwright playwright;

    @Autowired
    private ScreenshotProperties screenshotProperties;

    @Autowired
    private BrowserSessionProperties configuredProperties;

    private BrowserSessionProvider provider(BrowserSessionProperties props) {
        return new BrowserSessionProvider(playwright, props, screenshotProperties, new LoginStateStore(props));
    }

    /**
     * 存档：把当前登录好的页面里的 cookie / localStorage / sessionStorage 抄到磁盘。
     * 跑之前必须先有一个**已登录**的浏览器（用 CDP 接管的那个），存到的是什么状态，以后就是什么状态。
     */
    @Test
    void saveState() {
        try (PageSession session = provider(propsCdp()).open(ZSXQ_HOME)) {
            Page page = session.page();
            page.waitForTimeout(3000);

            System.out.println("===== 存档前先看一眼 =====");
            System.out.println("sessionStorage: " + page.evaluate("JSON.stringify(Object.keys(sessionStorage))"));
            String body = page.innerText("body").replaceAll("\\n{2,}", "\n");
            System.out.println("正文 " + body.length() + " 字，前 150 字：\n"
                    + body.substring(0, Math.min(150, body.length())));

            new LoginStateStore(configuredProperties).save(page);
            System.out.println("✓ 存档完成：" + new LoginStateStore(configuredProperties).stateFile());
        }
    }

    /** 用存档恢复（无头，不用养浏览器）—— 存档之后用这个验证是否真的带登录态 */
    @Test
    void fetchWithStoredState() {
        BrowserSessionProperties props = new BrowserSessionProperties(null, null, null, false, 0);
        try (PageSession session = provider(props).open(ZSXQ_GROUP)) {
            Page page = session.page();
            page.waitForTimeout(3000);
            printResult("存档恢复", session, page);
        }
    }

    /** 接管常开浏览器抓一次（对照组） */
    @Test
    void checkCdp() {
        try (PageSession session = provider(propsCdp()).open(ZSXQ_HOME)) {
            Page page = session.page();
            page.waitForTimeout(3000);
            System.out.println("sessionStorage: " + page.evaluate("JSON.stringify(Object.keys(sessionStorage))"));
            printResult("CDP 接管", session, page);
        }
    }

    /** 用 profile 目录抓一次（对照组：登录态在 sessionStorage 的站点，这条路抓不到） */
    @Test
    void fetchWithProfile() {
        try (PageSession session =
                     provider(new BrowserSessionProperties(null, PROFILE, null, false, 0)).open(ZSXQ_GROUP)) {
            Page page = session.page();
            page.waitForTimeout(3000);
            printResult("profile 目录", session, page);
        }
    }

    private static BrowserSessionProperties propsCdp() {
        return new BrowserSessionProperties(CDP, null, null, false, 0);
    }

    private static void printResult(String label, PageSession session, Page page) {
        String body = page.innerText("body").replaceAll("\\n{2,}", "\n");
        System.out.println("===== " + label + " =====");
        System.out.println("来源：" + session.source());
        System.out.println("标题：" + page.title());
        System.out.println("正文 " + body.length() + " 字，前 400 字：");
        System.out.println(body.substring(0, Math.min(400, body.length())));
    }
}
