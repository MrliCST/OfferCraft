package com.example.domain.browser;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.domain.browser.BrowserSessionProperties.SiteLogin;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站点名单路由的测试。纯逻辑、不起浏览器也不起 Spring，跑得快，所以把匹配规则的各种边界都摊在这里。
 *
 * <p>浏览器层面的"命中之后真的用了这份存档"由 BrowserSessionProviderTest 覆盖，两边不重复。
 */
class SiteLoginRegistryTest {

    private static final String ZSXQ = "https://wx.zsxq.com/group/51121244585524";

    @Test
    void exactMatch_hits() {
        SiteLoginRegistry registry = registry(new SiteLogin("wx.zsxq.com", "/tmp/zsxq.json"));

        assertThat(registry.stateFileFor(ZSXQ)).contains(Path.of("/tmp/zsxq.json"));
    }

    @Test
    void exactMatch_doesNotLeakToOtherHosts() {
        SiteLoginRegistry registry = registry(new SiteLogin("wx.zsxq.com", "/tmp/zsxq.json"));

        assertThat(registry.stateFileFor("https://example.com")).isEmpty();
        assertThat(registry.stateFileFor("https://evil-wx.zsxq.com")).isEmpty();
    }

    @Test
    void suffixMatch_coversSubdomainAndBareDomain() {
        SiteLoginRegistry registry = registry(new SiteLogin(".zsxq.com", "/tmp/zsxq.json"));

        assertThat(registry.stateFileFor(ZSXQ)).isPresent();
        assertThat(registry.stateFileFor("https://zsxq.com/x")).isPresent();
        assertThat(registry.stateFileFor("https://notzsxq.com/x")).isEmpty();
    }

    @Test
    void hostMatch_isCaseInsensitive() {
        SiteLoginRegistry registry = registry(new SiteLogin("WX.ZSXQ.com", "/tmp/zsxq.json"));

        assertThat(registry.stateFileFor(ZSXQ)).isPresent();
    }

    @Test
    void siteWithoutStateFile_usesDefaultPath() {
        SiteLoginRegistry registry = registry(SiteLogin.of("wx.zsxq.com"));

        assertThat(registry.stateFileFor(ZSXQ))
                .contains(SiteLoginRegistry.stateDir().resolve("wx.zsxq.com.json"));
    }

    @Test
    void stateFileExpandsTilde() {
        SiteLoginRegistry registry = registry(new SiteLogin("wx.zsxq.com", "~/state/zsxq.json"));

        assertThat(registry.stateFileFor(ZSXQ))
                .contains(Path.of(System.getProperty("user.home"), "state/zsxq.json"));
    }

    @Test
    void unlistedSite_staysAnonymous() {
        SiteLoginRegistry registry = registry(new SiteLogin("wx.zsxq.com", "/tmp/zsxq.json"));

        // 名单之外的站点一律不带登录态，返回空
        assertThat(registry.stateFileFor("https://example.com")).isEmpty();
    }

    @Test
    void urlWithoutHost_returnsEmptyInsteadOfThrowing() {
        SiteLoginRegistry registry = registry(new SiteLogin("wx.zsxq.com", "/tmp/zsxq.json"));

        assertThat(registry.stateFileFor("not a url")).isEmpty();
        assertThat(registry.stateFileFor("file:///etc/passwd")).isEmpty();
        assertThat(registry.stateFileFor("")).isEmpty();
    }

    @Test
    void emptySitesList_everythingAnonymous() {
        // 一条名单都没配 = 功能没启用：所有站点都不带登录态（匿名无头）
        SiteLoginRegistry registry = registry();

        assertThat(registry.stateFileFor(ZSXQ)).isEmpty();
        assertThat(registry.stateFileFor("https://example.com")).isEmpty();
    }

    private static SiteLoginRegistry registry(SiteLogin... sites) {
        return new SiteLoginRegistry(
                new BrowserSessionProperties(null, List.of(sites)));
    }
}
