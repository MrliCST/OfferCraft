package com.example.domain.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;

import lombok.extern.slf4j.Slf4j;

/**
 * 登录态存档：把"已经登录好的页面"里的登录信息拷到磁盘，下次无头启动时再灌回去。
 *
 * <p>为什么需要它：有些站点（知识星球就是）把登录态放在 <b>sessionStorage</b> 里，
 * 那东西的生命周期是"窗口开着"，浏览器一关就没了 —— 持久化 profile、复制 cookie 全都救不了，
 * 只剩"接管一个常开的浏览器"这一条路，代价是每次开机都要重新扫码。
 * 存档把这一关跳过去：登录成功时把 sessionStorage 抄下来，之后每次抓取前再写回新页面，
 * 于是无头也能带登录态，不用再养一个常开的 Chrome。
 *
 * <p>存两份文件是刻意的：
 * <ul>
 *   <li>{@code storage-state.json} —— Playwright 原生格式，存 cookie（含 HttpOnly）和 localStorage。
 *       HttpOnly 的 cookie 用 JS 是写不进去的，只能走它；</li>
 *   <li>{@code storage-state-session.json} —— 自定义格式，存 sessionStorage。
 *       Playwright 的 storageState 不含这部分，只能自己提取、自己注入。</li>
 * </ul>
 *
 * <p>由 {@link BrowserConfig} 注册成 bean（不标 @Component 是刻意的：它和 Playwright 一样属于
 * 浏览器那一层的基础设施，放在一起好找，测试里只加载 BrowserConfig 也能拿到）。
 */
@Slf4j
public class LoginStateStore {

    private final Path stateFile;

    public LoginStateStore(BrowserSessionProperties properties) {
        this.stateFile = expand(properties.storageStateFile());
    }

    /** 存档在不在。没有存档就说明还没存过，抓取时按普通未登录处理 */
    public boolean exists() {
        return Files.exists(stateFile) && Files.exists(sessionFile());
    }

    public Path stateFile() {
        return stateFile;
    }

    /**
     * 把当前页面的登录态存下来。调用时页面必须已经是登录状态，存到的是什么状态，
     * 以后恢复的就是什么状态 —— 没登录就存档，等于存了个"游客"。
     */
    public void save(Page page) {
        try {
            Files.createDirectories(stateFile.getParent());
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

    private Path sessionFile() {
        return Paths.get(stateFile + "-session.json");
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

    /** 支持 ~ 开头的家目录写法 */
    private static Path expand(String file) {
        String path = (file == null || file.isBlank())
                ? System.getProperty("user.home") + "/.config/JLRADemo/storage-state.json"
                : file.trim();
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return Paths.get(path).toAbsolutePath().normalize();
    }
}
