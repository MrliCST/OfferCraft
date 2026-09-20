package com.example.domain.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import lombok.extern.slf4j.Slf4j;

/**
 * 登录态存档的读写器：绑定到<b>某一个</b>存档文件，负责把它存下来、以及在无头启动时灌回新页面。
 *
 * <p>一个实例对应一份存档（通常就是一个站点）。要用哪一份，由 {@link SiteLoginRegistry} 按 URL 决定，
 * 本类不掺和路由 —— 这样"存到哪"和"怎么存"两件事分开，各自都好测。
 *
 * <p>为什么需要它：无头浏览器是全新的、没有登录态的。把登录成功那一刻的
 * cookie / localStorage / sessionStorage 抄到磁盘，之后每次抓取前再写回新页面，
 * 于是无头也能带登录态，不用再养一个常开的 Chrome。
 *
 * <p>每个存档存<b>两份</b>文件，是刻意的：
 * <ul>
 *   <li>{@code xxx.json} —— Playwright 原生格式，存 cookie（含 HttpOnly）和 localStorage。
 *       HttpOnly 的 cookie 用 JS 是写不进去的，只能走它；</li>
 *   <li>{@code xxx-session.json} —— 自定义格式，存 sessionStorage。
 *       Playwright 的 storageState 不含这部分，只能自己提取、自己注入。</li>
 * </ul>
 */
@Slf4j
public class LoginStateStore {

    /** 没给站点单独配置、也没配 {@code storage-state-file} 时用的全局存档 */
    public static final Path DEFAULT_STATE_FILE =
            Path.of(System.getProperty("user.home"), ".config/JLRADemo/storage-state.json");

    /** 站点存档的默认目录：{@code sites} 里没写 state-file 时落在 <host>.json */
    public static final Path DEFAULT_STATE_DIR =
            Path.of(System.getProperty("user.home"), ".config/JLRADemo/state");

    private final Path stateFile;

    public LoginStateStore(Path stateFile) {
        this.stateFile = stateFile.toAbsolutePath().normalize();
    }

    public LoginStateStore(String stateFile) {
        this(expand(stateFile));
    }

    /** 这份存档存过没有。没存过就说明该站点还没登录态，抓取时按普通未登录处理 */
    public boolean exists() {
        return Files.exists(stateFile) && Files.exists(sessionFile());
    }

    public Path stateFile() {
        return stateFile;
    }

    /**
     * 把当前页面的登录态存下来。调用时页面必须已经是登录状态，存到的是什么状态，
     * 以后恢复的就是什么状态 —— 没登录就存档，等于存了个"游客"。
     *
     * <p>注意：存档拿到的是<b>整个浏览器上下文</b>的状态，所以同一浏览器里登录了 A、B 两个站，
     * 存出来的两份文件里都同时含有 A 和 B 的 cookie。这不影响正确性（cookie 带 domain，
     * 注入后只会发给匹配的域），只是文件里有冗余。
     */
    public void save(Page page) {
        try {
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            page.context().storageState(new BrowserContext.StorageStateOptions().setPath(stateFile));
            Files.writeString(sessionFile(), readSessionStorage(page));
            log.info("登录态已存档到 {}", stateFile);
        } catch (Exception e) {
            throw new IllegalStateException("保存登录态失败：" + e.getMessage());
        }
    }

    /** 给新 context 灌入 cookie + localStorage（HttpOnly 的 cookie 只能这么进） */
    public void applyTo(Browser.NewContextOptions options) {
        options.setStorageStatePath(stateFile);
    }

    /**
     * 生成注入 sessionStorage 的脚本，交给 {@code context.addInitScript}，
     * 这样每个新页面在加载前就先把 sessionStorage 填好，页面 JS 一读就有值。
     */
    public String sessionStorageInitScript() {
        try {
            String json = Files.readString(sessionFile());
            return "(() => { try { const saved = " + json + ";"
                    + " for (const [k, v] of Object.entries(saved)) { sessionStorage.setItem(k, v); }"
                    + " } catch (e) { console.error('restore sessionStorage failed', e); } })()";
        } catch (Exception e) {
            return "";
        }
    }

    /** {@code wx.zsxq.com.json} → {@code wx.zsxq.com-session.json} */
    private Path sessionFile() {
        String name = stateFile.getFileName().toString();
        String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        return stateFile.resolveSibling(base + "-session.json");
    }

    /**
     * 不能用 {@code Object.fromEntries(sessionStorage.entries())} —— Storage 是老式接口，
     * Chrome 上压根没有 entries() 方法（实测报 "sessionStorage.entries is not a function"）。
     * 只能按下标 + key(i) 老老实实遍历。
     */
    private static String readSessionStorage(Page page) {
        Object raw = page.evaluate("(() => { const o = {};"
                + " for (let i = 0; i < sessionStorage.length; i++) {"
                + "   const k = sessionStorage.key(i); o[k] = sessionStorage.getItem(k); }"
                + " return JSON.stringify(o); })()");
        return raw instanceof String s ? s : "{}";
    }

    /**
     * 从存档里按名字取一个 cookie 的值，取不到返回 null。
     *
     * <p>为什么需要它：存档本来只为"灌回无头浏览器"服务，但纯 HTTP 抓取（不用浏览器、
     * 直接带 cookie 调接口）同样需要登录态，而且开销比开浏览器低一个数量级。
     * 这类抓取走 {@code java.net.http} 或 OkHttp，没有 Playwright 的 context，
     * 只能自己把 cookie 从存档里读出来塞进请求头。
     *
     * <p>刻意只做"按名字取值"这一件小事，不引入 HTTP 客户端依赖 ——
     * 怎么发请求是调用方的事，本类只负责把凭证交出去。
     */
    public String cookieValue(String cookieName) {
        if (!Files.exists(stateFile) || cookieName == null || cookieName.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(stateFile.toFile());
            for (com.fasterxml.jackson.databind.JsonNode c : root.path("cookies")) {
                if (cookieName.equals(c.path("name").asText(null))) {
                    String v = c.path("value").asText(null);
                    return (v == null || v.isEmpty()) ? null : v;
                }
            }
        } catch (Exception e) {
            log.warn("从存档 {} 读取 cookie {} 失败：{}", stateFile, cookieName, e.getMessage());
        }
        return null;
    }

    /** 支持 ~ 开头的家目录写法；没给路径时用全局存档 */
    public static Path expand(String file) {
        String path = (file == null || file.isBlank()) ? DEFAULT_STATE_FILE.toString() : file.trim();
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return Paths.get(path).toAbsolutePath().normalize();
    }
}
