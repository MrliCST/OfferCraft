package com.example.domain.zsxq.crawl.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.example.domain.browser.CrawlThrottle;
import com.example.domain.browser.LoginStateStore;
import com.example.domain.browser.SiteLoginRegistry;

/**
 * 知识星球接口的 HTTP 客户端：只负责"带登录态把请求发出去、把体拿回来"。
 *
 * <p>为什么单独抽一层，而不是各处直接 {@code HttpClient.send}：
 * 有三件事必须在<b>每一处</b>都做对，散着写一定会被漏掉 ——
 * <ol>
 *   <li><b>带 cookie</b>：登录态是 {@code .zsxq.com} 域的 {@code zsxq_access_token}（HttpOnly），
 *       通配域对 api / articles / wx 三个子域都生效，一个头走天下；</li>
 *   <li><b>限流</b>：复用 {@link CrawlThrottle}，跟浏览器版共用同一套节奏，别让两套速率打架；</li>
 *   <li><b>网络重试</b>：抖动导致的失败在这一层就救回来，不让上层每个调用点各写一遍。</li>
 * </ol>
 *
 * <p>刻意<b>不做</b>「空页重试」——那是列表接口的业务语义（返回 200 但 topics 为空），
 * 放在 HTTP 层会把"到底了"和"偶发空"混为一谈。见 {@link ZsxqHashtagReader}。
 */
public class ZsxqApiClient {

    /** 登录态 cookie 名。存档里是 HttpOnly，只能从 Playwright 存档文件读出。 */
    public static final String TOKEN_COOKIE = "zsxq_access_token";

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

    /** 单次请求超时。接口都是小报文，15s 足够；超时宁可快速失败让上层重试。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** 网络层重试次数（不含首次）。 */
    private static final int RETRY = 2;

    private final HttpClient http;
    private final String token;
    private final ObjectMapper om = new ObjectMapper();

    public ZsxqApiClient(String token) {
        this.token = token;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * 从登录态存档构造。路径按 host 推导，跟站点名单走同一份规则，不手抄字符串。
     *
     * @throws IllegalStateException 存档不存在或里面没有 token —— 这时该去重新登录，
     *                               而不是带着空凭证发一堆注定 302 的请求
     */
    public static ZsxqApiClient fromStoredState(String siteUrl) {
        LoginStateStore store = new LoginStateStore(
                SiteLoginRegistry.defaultFileFor(SiteLoginRegistry.hostOf(siteUrl)));
        String token = store.cookieValue(TOKEN_COOKIE);
        if (token == null) {
            throw new IllegalStateException("登录态存档里没有 " + TOKEN_COOKIE + "（" + store.stateFile()
                    + "）。请先在带调试端口的 Chrome 里登录知识星球并存档。");
        }
        return new ZsxqApiClient(token);
    }

    /** 取 JSON 并解析成树。网络失败重试 {@value #RETRY} 次后仍失败则抛异常。 */
    public JsonNode getJson(String url) {
        String body = get(url);
        try {
            return om.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON（" + url + "）：" + head(body, 120));
        }
    }

    /** 取原始文本（文章页 HTML 用这个）。 */
    public String get(String url) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= RETRY; attempt++) {
            try {
                CrawlThrottle.beforeCrawl(url);
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("Cookie", TOKEN_COOKIE + "=" + token)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json, text/html, */*")
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + resp.statusCode());
                }
                return resp.body();
            } catch (Exception e) {
                last = new IllegalStateException("请求失败（" + url + "）：" + e.getMessage());
                sleep(1500L * (attempt + 1));
            } finally {
                CrawlThrottle.afterCrawl(url);
            }
        }
        throw last;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String head(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n));
    }
}
