package com.example.domain.browser;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 浏览器会话的参数，从 yml 的 {@code browser.session.*} 绑过来，注册靠 {@link BrowserConfig} 上的
 * {@code @EnableConfigurationProperties}，不用额外加 @Component。
 *
 * <p>这里管的是"以什么身份打开网页"，跟 {@code screenshot.*}（截图怎么截）是两件事，所以分开配置：
 * 一个决定能不能看到登录后的内容，一个决定看到的画面怎么存下来。
 *
 * <p><b>策略只有一条：配了才带登录态，没配就无头匿名。</b>
 * 站点名单（{@code sites}）是唯一的开关 —— 命中名单的站点用它的存档，名单外的一律无头。
 * 没有"名单外也给登录态"的开关：那等于把你唯一的登录态发给任意站点，风险远大于方便。
 *
 * @param cdpEndpoint   已打开浏览器的调试端口，如 http://127.0.0.1:9222；填了就接管它（优先级最高）
 * @param maxTextChars  抓正文的截断字数，超过就截断并注明原文长度
 * @param sites         需要登录的站点名单；为空表示不启用登录态
 */
@ConfigurationProperties(prefix = "browser.session")
public record BrowserSessionProperties(
        String cdpEndpoint,
        int maxTextChars,
        List<SiteLogin> sites) {

    public static final int DEFAULT_MAX_TEXT_CHARS = 8000;

    /**
     * 只关心来源、不关心名单时的简写，省得调用方补一个 null 的 sites。
     *
     * <p>做成静态工厂而不是第二个构造器，是有原因的：Spring Boot 的属性绑定在遇到<b>多个</b>构造器时
     * 会退化成"找无参构造器"，而 record 没有无参构造器 —— 结果就是启动报
     * "No default constructor found"。只留一个规范构造器就没这个歧义。
     */
    public static BrowserSessionProperties of(String cdpEndpoint, int maxTextChars) {
        return new BrowserSessionProperties(cdpEndpoint, maxTextChars, List.of());
    }

    public BrowserSessionProperties {
        cdpEndpoint = blankToNull(cdpEndpoint);
        maxTextChars = maxTextChars > 0 ? maxTextChars : DEFAULT_MAX_TEXT_CHARS;
        // 没写 host 的条目是配置写漏了，静默丢掉比让它在匹配时空转好
        sites = sites == null
                ? List.of()
                : sites.stream().filter(Objects::nonNull).filter(s -> s.host() != null).toList();
    }

    /** 有没有配接管端点 */
    public boolean hasCdp() {
        return cdpEndpoint != null;
    }

    /**
     * 名单里的一条：哪个站点、用哪份登录态存档。
     *
     * <p>host 的写法有两种，区分方式是<b>看开头有没有点</b>：
     * <ul>
     *   <li>{@code wx.zsxq.com} —— 精确匹配，只管这一个主机；</li>
     *   <li>{@code .zsxq.com} —— 后缀匹配，管 zsxq.com 及所有子域。</li>
     * </ul>
     * 刻意不自动补 {@code www.} / {@code m.} 之类：猜错比不猜更糟，你要哪个就明确写出来。
     *
     * @param host      主机名，忽略大小写；点号开头表示后缀匹配
     * @param stateFile 该站点的登录态存档文件；不写就用 {@code state/}<i>host</i>{@code .json}
     */
    public record SiteLogin(String host, String stateFile) {

        public SiteLogin {
            host = blankToLower(host);
            stateFile = blankToNull(stateFile);
        }

        /** 只指定 host、存档用默认路径。理由同 {@link BrowserSessionProperties#of}：不能出现第二个构造器 */
        public static SiteLogin of(String host) {
            return new SiteLogin(host, null);
        }

        /**
         * 这个主机名是否命中本条配置。
         *
         * @param host 待判定的主机名，比如从 URL 里解析出来的 {@code wx.zsxq.com}
         */
        public boolean matches(String host) {
            if (this.host == null || host == null || host.isBlank()) {
                return false;
            }
            String candidate = host.toLowerCase(Locale.ROOT);
            if (this.host.startsWith(".")) {
                // .zsxq.com 同时认 zsxq.com 和 wx.zsxq.com
                return candidate.endsWith(this.host) || candidate.equals(this.host.substring(1));
            }
            return this.host.equals(candidate);
        }

        /** 有没有单独指定存档文件 */
        public boolean hasStateFile() {
            return stateFile != null;
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String blankToLower(String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
