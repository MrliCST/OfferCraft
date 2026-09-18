package com.example.domain.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网页长截图的参数，从 yml 的 {@code screenshot.*} 绑过来，注册靠 {@link WebScreenshotTool} 上的
 * {@code @EnableConfigurationProperties}，不用额外加 @Component。
 *
 * <p>每一项都在紧凑构造器里给了兜底值：yml 里不写也能跑，写了就覆盖。
 * 这样做是因为 record 没法在字段上写默认值，而"少配一个就变成 0/null"比"配错一个"更隐蔽
 * —— 视口宽 0、超时 0 都会让截图直接失败，且报错信息跟真正的原因对不上。
 *
 * @param defaultDir         默认保存目录，LLM 没指定目录时用它，不存在就创建
 * @param viewportWidth      视口宽，决定截图的宽度（懒加载通常是按视口宽度挑图源的）
 * @param viewportHeight     视口高，只影响首屏和滚动步长，不影响长图总高
 * @param navigationTimeoutMs 打开页面的超时
 * @param scrollStepPx       每次向下滚动的像素
 * @param scrollDelayMs      每滚一步等多久，给图片发出请求的时间
 * @param maxScrollRounds    最多滚多少步，兜底防死循环（页面高度一直涨的那种）
 * @param settleTimeoutMs    滚到底后再等一会儿，让最后一批图片加载完
 * @param waitNetworkIdle    滚完是否再等一次网络空闲；失败不中断，只记一笔
 * @param channel            浏览器通道，chrome = 用系统装好的 Google Chrome
 */
@ConfigurationProperties(prefix = "screenshot")
public record ScreenshotProperties(
        String defaultDir,
        int viewportWidth,
        int viewportHeight,
        int navigationTimeoutMs,
        int scrollStepPx,
        int scrollDelayMs,
        int maxScrollRounds,
        int settleTimeoutMs,
        boolean waitNetworkIdle,
        String channel) {

    /**
     * 兜底目录。注意这里不能写 {@code ${user.home}}：那是 yml 里的占位符写法，只有经过 Spring 绑定才会被解析，
     * 放在 Java 常量里就是一个字面量，最后会得到名为 "${user.home}" 的文件夹。所以直接取系统属性。
     */
    public static final String DEFAULT_DIR = System.getProperty("user.home") + "/图片/Long photos";
    public static final int DEFAULT_VIEWPORT_WIDTH = 1280;
    public static final int DEFAULT_VIEWPORT_HEIGHT = 800;
    public static final int DEFAULT_NAVIGATION_TIMEOUT_MS = 60_000;
    public static final int DEFAULT_SCROLL_STEP_PX = 600;
    public static final int DEFAULT_SCROLL_DELAY_MS = 120;
    public static final int DEFAULT_MAX_SCROLL_ROUNDS = 400;
    public static final int DEFAULT_SETTLE_TIMEOUT_MS = 1_500;
    public static final String DEFAULT_CHANNEL = "chrome";

    public ScreenshotProperties {
        defaultDir = (defaultDir == null || defaultDir.isBlank()) ? DEFAULT_DIR : defaultDir.trim();
        viewportWidth = viewportWidth > 0 ? viewportWidth : DEFAULT_VIEWPORT_WIDTH;
        viewportHeight = viewportHeight > 0 ? viewportHeight : DEFAULT_VIEWPORT_HEIGHT;
        navigationTimeoutMs = navigationTimeoutMs > 0 ? navigationTimeoutMs : DEFAULT_NAVIGATION_TIMEOUT_MS;
        scrollStepPx = scrollStepPx > 0 ? scrollStepPx : DEFAULT_SCROLL_STEP_PX;
        scrollDelayMs = scrollDelayMs >= 0 ? scrollDelayMs : DEFAULT_SCROLL_DELAY_MS;
        maxScrollRounds = maxScrollRounds > 0 ? maxScrollRounds : DEFAULT_MAX_SCROLL_ROUNDS;
        settleTimeoutMs = settleTimeoutMs >= 0 ? settleTimeoutMs : DEFAULT_SETTLE_TIMEOUT_MS;
        channel = (channel == null || channel.isBlank()) ? DEFAULT_CHANNEL : channel.trim();
    }
}
