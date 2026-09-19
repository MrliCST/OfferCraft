package com.example.domain.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 抓网页正文的参数，从 yml 的 {@code web-text.*} 绑过来，注册靠 {@link WebPageTextTool} 上的
 * {@code @EnableConfigurationProperties}，不用额外加 @Component。
 *
 * <p>为什么单独一个类、而不是寄在 {@code browser.session} 下：那个前缀管的是"以什么身份打开网页"
 * （接管端点、站点名单），而"正文留多少字"是抓回来之后怎么处置结果——两件事的变更理由完全不同。
 * 本项目的惯例是每个工具管自己的键（对照 {@code ScreenshotProperties} 管 {@code screenshot.*}），
 * 这样改截断策略不必动浏览器会话的配置类。
 *
 * @param maxChars 正文截断字数，超过就截断并注明原文长度
 */
@ConfigurationProperties(prefix = "web-text")
public record WebPageTextProperties(int maxChars) {

    public static final int DEFAULT_MAX_CHARS = 8000;

    /**
     * 每一项都在紧凑构造器里给兜底值：yml 里不写也能跑，写了就覆盖。
     * 这样做是因为 record 没法在字段上写默认值，而"少配一个就变成 0"比"配错一个"更隐蔽
     * ——截断字数 0 会把正文截成空串且不报错，报错信息还跟真正的原因对不上。
     */
    public WebPageTextProperties {
        maxChars = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS;
    }
}
