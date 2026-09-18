package com.example.domain.browser;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * 按 URL 决定"该用哪份登录态存档"。名单路由的全部逻辑都在这里，别处不再判断站点。
 *
 * <p>判定顺序：
 * <ol>
 *   <li>从 URL 里取出 host，去 {@code browser.session.sites} 里找第一条命中的；</li>
 *   <li>命中了 —— 写了 {@code state-file} 就用它，没写就用 {@code state/}<i>host</i>{@code .json}；</li>
 *   <li>没命中 —— 返回空，这个站按匿名访问。</li>
 * </ol>
 * 就这三种情况，没有别的策略分支。
 *
 * <p>返回空不代表出错，只代表"这个站不该带登录态"。要不要真的匿名，由
 * {@link BrowserSessionProvider} 结合其它来源（接管端点）一起决定。
 */
@Component
public class SiteLoginRegistry {

    private final BrowserSessionProperties properties;

    public SiteLoginRegistry(BrowserSessionProperties properties) {
        this.properties = properties;
    }

    /**
     * 这个 URL 该用哪份存档。
     *
     * @param url 要访问的地址
     * @return 存档路径；空表示该站点不带登录态（名单之外，或压根没配名单）
     */
    public Optional<Path> stateFileFor(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) {
            return Optional.empty();
        }
        return properties.sites().stream()
                .filter(site -> site.matches(host))
                .findFirst()
                .map(site -> site.hasStateFile()
                        ? LoginStateStore.expand(site.stateFile())
                        : defaultFileFor(host));
    }

    /** 站点存档的默认目录。sites 里没写 state-file 时落在这里 */
    public static Path stateDir() {
        return LoginStateStore.DEFAULT_STATE_DIR;
    }

    /** 站点存档的默认文件名：<host>.json */
    public static Path defaultFileFor(String host) {
        return stateDir().resolve(host + ".json");
    }

    /**
     * 从 URL 里取主机名，取不到就返回空串。
     * 空串是合法的返回（表示"没法判断，按匿名处理"），不抛异常 —— 路由不该因为一个奇怪的 URL 就中断抓取。
     */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
