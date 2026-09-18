package com.example.domain.browser;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 浏览器会话的参数，从 yml 的 {@code browser.session.*} 绑过来，注册靠 {@link BrowserSessionProvider} 上的
 * {@code @EnableConfigurationProperties}，不用额外加 @Component。
 *
 * <p>这里管的是"以什么身份打开网页"，跟 {@code screenshot.*}（截图怎么截）是两件事，所以分开配置：
 * 一个决定能不能看到登录后的内容，一个决定看到的画面怎么存下来。
 *
 * <p>两项来源都按"填了才算"判定，空串视同没填 —— 没填就走无头模式，现有行为不变。
 *
 * @param cdpEndpoint    已打开浏览器的调试端口，如 http://127.0.0.1:9222；填了就接管它（优先级最高）
 * @param userDataDir    用户数据目录；填了就用这个 profile 启动（优先级次之），登录态存在这个目录里
 * @param profileHeadless profile 模式是否无头。默认 true：cookie 在目录里，无头同样带登录态，还不弹窗
 * @param storageStateFile 登录态存档文件。存过档之后，无头也能带登录态（见 LoginStateStore）
 * @param maxTextChars   抓正文的截断字数，超过就截断并注明原文长度
 */
@ConfigurationProperties(prefix = "browser.session")
public record BrowserSessionProperties(
        String cdpEndpoint,
        String userDataDir,
        String storageStateFile,
        Boolean profileHeadless,
        int maxTextChars) {

    public static final int DEFAULT_MAX_TEXT_CHARS = 8000;
    /** 无头与否的默认：true。想看着浏览器操作、或站点检测 headless 时改成 false */
    public static final boolean DEFAULT_PROFILE_HEADLESS = true;

    public BrowserSessionProperties {
        cdpEndpoint = (cdpEndpoint == null || cdpEndpoint.isBlank()) ? null : cdpEndpoint.trim();
        userDataDir = (userDataDir == null || userDataDir.isBlank()) ? null : userDataDir.trim();
        maxTextChars = maxTextChars > 0 ? maxTextChars : DEFAULT_MAX_TEXT_CHARS;
    }

    /**
     * profile 模式要不要无头。组件类型是 {@code Boolean} 而不是 boolean，就是为了区分
     * "yml 没写"（null，走默认）和"显式写了 false"。
     * 这里不能覆写成 {@code profileHeadless()} —— record 的存取方法必须返回组件本身的类型。
     */
    public boolean useHeadlessProfile() {
        return profileHeadless == null ? DEFAULT_PROFILE_HEADLESS : profileHeadless;
    }

    /** 有没有配接管端点 */
    public boolean hasCdp() {
        return cdpEndpoint != null;
    }

    /** 有没有配用户数据目录 */
    public boolean hasUserDataDir() {
        return userDataDir != null;
    }
}
