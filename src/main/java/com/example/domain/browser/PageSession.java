package com.example.domain.browser;

import com.microsoft.playwright.Page;

/**
 * 一次"打开网页"的句柄：拿到手的永远是一个 {@link Page}，外加这次是用什么方式打开的。
 *
 * <p>为什么要区分模式：三种模式的**关闭语义不一样**，混在一起会出事 ——
 * <ul>
 *   <li>接管模式（CDP）：连的是用户正在用的浏览器，{@code Browser.close()} 只是断开连接，
 *       不会关掉用户的 Chrome，所以只关自己开的那个标签页；</li>
 *   <li>profile 模式：浏览器是我们自己拉起来的，{@code context.close()} 会真的把它关掉；</li>
 *   <li>无头模式：同上，关掉自己启动的那个。</li>
 * </ul>
 * 用 try-with-resources 兜住，调用方不用记这些区别。
 *
 * @param mode        这次用的是哪种方式
 * @param page        页面
 * @param closable    要关的东西：接管模式是 Browser（连上的），另两种是 BrowserContext（自己起的）
 */
public class PageSession implements AutoCloseable {

    /** 登录态的三种来源，按优先级从高到低排列 */
    public enum Mode {
        /** 接管用户已打开的浏览器，用的是用户当前的登录会话 */
        CDP,
        /** 用存档恢复登录态（无头，不用养着浏览器） */
        STORED,
        /** 用指定的用户数据目录启动，登录态存在那个目录里 */
        PROFILE,
        /** 无头启动，未登录 */
        HEADLESS;

        public boolean isLoggedIn() {
            return this != HEADLESS;
        }
    }

    private final Mode mode;
    private final Page page;
    private final AutoCloseable closable;
    private final String source;

    PageSession(Mode mode, Page page, AutoCloseable closable, String source) {
        this.mode = mode;
        this.page = page;
        this.closable = closable;
        this.source = source;
    }

    public Mode mode() {
        return mode;
    }

    public Page page() {
        return page;
    }

    /** 给模型和日志看的一句话，例如"已登录浏览器（接管 127.0.0.1:9222）" */
    public String source() {
        return source;
    }

    @Override
    public void close() {
        try {
            page.close();
        } catch (Exception e) {
            // 页面可能已经被用户手动关掉了，不影响收尾
        }
        try {
            closable.close();
        } catch (Exception ignored) {
            // 同上：收尾阶段的失败不该盖掉业务结果
        }
    }
}
