package com.example.domain.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.domain.tool.ScreenshotProperties;
import com.microsoft.playwright.Playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 会话层的测试：重点验证"配了什么就走哪条路"，以及没配时跟以前一样。
 *
 * <p>不连数据库也不调模型。模式靠手工 new 一个 properties 传进去，
 * 不去改 yml —— 改 yml 会影响别的测试，而这里要的就是逐个模式单独验证。
 *
 * <p>跑在 test profile 下：application-test.yml 把日志压到 WARN，控制台只留测试自己的打印。
 */
@SpringBootTest(classes = BrowserConfig.class)
@EnableConfigurationProperties({ScreenshotProperties.class, BrowserSessionProperties.class})
@ActiveProfiles("test")
class BrowserSessionProviderTest {

    private static final String URL = "https://www.example.com";

    /**
     * 指向一个不存在的存档文件：本机真的存过档（~/.config/JLRADemo/state/*.json），
     * 而存档的优先级高于无头，不隔离的话这几个用例会全部跑到存档模式上去。
     */
    private static final String NO_STATE = "/tmp/no-such-storage-state.json";

    @Autowired
    private Playwright playwright;

    @Autowired
    private ScreenshotProperties screenshotProperties;

    @Test
    void noLoginConfigured_fallsBackToHeadless() {
        BrowserSessionProvider provider = provider(BrowserSessionProperties.of(null, 0));

        try (PageSession session = provider.open(URL)) {
            System.out.println("===== 未配登录态 =====");
            System.out.println("模式=" + session.mode() + "，来源=" + session.source());

            assertThat(session.mode()).isEqualTo(PageSession.Mode.HEADLESS);
            assertThat(session.mode().isLoggedIn()).isFalse();
            assertThat(session.source()).contains("未登录");
            assertThat(session.page().title()).isNotBlank();
        }
    }

    @Test
    void cdpConfiguredButChromeNotListening_reportsHumanReadableError() {
        BrowserSessionProvider provider =
                provider(BrowserSessionProperties.of("http://127.0.0.1:9222", 0));

        System.out.println("===== 配了端口但 Chrome 没开调试端口 =====");
        assertThatThrownBy(() -> provider.open(URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("9222")
                .hasMessageContaining("调试端口");
    }

    @Test
    void siteNotInList_staysAnonymous(@TempDir Path tmp) throws Exception {
        // 名单里配的是别的站，本站该老老实实走匿名 —— 白名单的核心语义
        Path state = fakeStateFile(tmp, "other.com");
        BrowserSessionProvider provider = provider(new BrowserSessionProperties(null, 0, List.of(new BrowserSessionProperties.SiteLogin("other.com", state.toString()))));

        try (PageSession session = provider.open(URL)) {
            System.out.println("===== 名单外的站点 =====");
            System.out.println("模式=" + session.mode() + "，来源=" + session.source());

            assertThat(session.mode()).isEqualTo(PageSession.Mode.HEADLESS);
            assertThat(session.mode().isLoggedIn()).isFalse();
            assertThat(session.source()).contains("未登录");
        }
    }

    @Test
    void siteInList_usesItsOwnStateFile(@TempDir Path tmp) throws Exception {
        Path state = fakeStateFile(tmp, "www.example.com");
        BrowserSessionProvider provider = provider(new BrowserSessionProperties(null, 0, List.of(new BrowserSessionProperties.SiteLogin("www.example.com", state.toString()))));

        try (PageSession session = provider.open(URL)) {
            System.out.println("===== 命中站点名单 =====");
            System.out.println("模式=" + session.mode() + "，来源=" + session.source());

            assertThat(session.mode()).isEqualTo(PageSession.Mode.STORED);
            assertThat(session.mode().isLoggedIn()).isTrue();
            // 用的是这一站自己的存档文件，不是全局那份
            assertThat(session.source()).contains("www.example.com.json");
        }
    }

    @Test
    void siteInListButStateMissing_fallsBackToAnonymous(@TempDir Path tmp) {
        // 配了名单但还没存档是很常见的中间状态，不该让抓取直接失败
        Path notSaved = tmp.resolve("www.example.com.json");
        BrowserSessionProvider provider = provider(new BrowserSessionProperties(null, 0, List.of(new BrowserSessionProperties.SiteLogin("www.example.com", notSaved.toString()))));

        try (PageSession session = provider.open(URL)) {
            assertThat(session.mode()).isEqualTo(PageSession.Mode.HEADLESS);
        }
    }

    @Test
    void rejectsNonHttpUrl() {
        BrowserSessionProvider provider = provider(BrowserSessionProperties.of(null, 0));

        assertThatThrownBy(() -> provider.open("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
    }

    /** 造一份内容是空的合法存档，够 Playwright 加载，用来验证"用了哪份文件"而不是真登录 */
    private static Path fakeStateFile(Path dir, String host) throws Exception {
        Files.createDirectories(dir);
        Path main = dir.resolve(host + ".json");
        Files.writeString(main, "{\"cookies\":[],\"origins\":[]}");
        Files.writeString(dir.resolve(host + "-session.json"), "{}");
        return main;
    }

    private BrowserSessionProvider provider(BrowserSessionProperties sessionProperties) {
        return new BrowserSessionProvider(playwright, sessionProperties, screenshotProperties,
                new SiteLoginRegistry(sessionProperties));
    }
}
