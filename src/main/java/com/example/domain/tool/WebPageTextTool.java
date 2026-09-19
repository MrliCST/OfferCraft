package com.example.domain.tool;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.example.domain.browser.BrowserSessionProvider;
import com.example.domain.browser.CrawlThrottle;
import com.example.domain.browser.PageSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 给 LLM 用的"抓网页正文"工具：打开页面，把标题和可见正文取回来。
 *
 * <p>跟长截图工具是同一条上网链路（同一个 {@link BrowserSessionProvider}），
 * 所以配置过登录态时，抓到的是**登录后**看到的内容 —— 这才是这个工具的意义：
 * 未登录就能看的页面，模型自己用训练知识或普通抓取就够了；需要登录的才非它不可。
 *
 * <p>取 {@code innerText("body")} 而不是 {@code content()}：后者连 script、style 一起给，
 * 几千行标签塞回模型纯属浪费上下文；innerText 得到的是渲染后用户真正看得见的文字。
 *
 * <p>正文会按 {@code web-text.max-chars} 截断 —— 长文章动辄几万字，
 * 不截断会直接把模型上下文撑爆。截断时如实告知原文长度，让模型知道这是节选。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(WebPageTextProperties.class)
public class WebPageTextTool {

    private final BrowserSessionProvider provider;
    private final WebPageTextProperties properties;

    /**
     * 抓取网页标题与正文。
     *
     * @param url 网页地址，必须 http/https
     * @return 标题 + 正文（可能截断），带登录态来源；失败则是一句人话原因
     */
    @Tool(name = "fetch_page_text", value = {
            "打开指定网页并把它的标题和正文文字取回来。",
            "如果配置了登录态，取到的是登录后才能看到的内容。",
            "当用户要求读取、总结、提取某个网页的内容时调用它；只想要图片时用截图工具。"
    })
    public String fetchText(
            @P(value = "要读取的网页完整 URL，必须以 http:// 或 https:// 开头", required = true) String url) {

        log.info("抓取正文：url={}", url);
        long start = System.currentTimeMillis();

        // 限流 + 限频：导航前占该域名一个许可并做高斯随机延迟（抓取结束后在 finally 归还）
        CrawlThrottle.beforeCrawl(url);
        try (PageSession session = provider.open(url)) {
            Page page = session.page();

            String title = safeTitle(page);
            String text = safeBodyText(page);

            int max = properties.maxChars();
            boolean truncated = text.length() > max;
            String body = truncated ? text.substring(0, max) : text;

            long cost = System.currentTimeMillis() - start;
            return "标题：" + title
                    + "\n来源：" + session.source()
                    + "\n正文" + (truncated
                            ? "（原文 " + text.length() + " 字，已截断到 " + max + " 字）"
                            : "（共 " + text.length() + " 字）")
                    + "，耗时 " + String.format(java.util.Locale.ROOT, "%.1f", cost / 1000.0) + "s：\n"
                    + body;
        } catch (Exception e) {
            log.warn("抓取正文失败：{}", url, e);
            return "读取网页失败（" + url + "）：" + e.getMessage();
        } finally {
            CrawlThrottle.afterCrawl(url);
        }
    }

    private static String safeTitle(Page page) {
        try {
            return page.title();
        } catch (PlaywrightException e) {
            return "(取不到标题)";
        }
    }

    /**
     * 取可见正文。{@code innerText} 拿不到时退回 {@code textContent}：
     * 少数页面 body 上有 display:none 之类的样式，innerText 会直接抛错，
     * textContent 至少还能把文字拿出来。两个都不行就返回空串，由调用方如实说明。
     */
    private static String safeBodyText(Page page) {
        String text;
        try {
            text = page.innerText("body");
        } catch (PlaywrightException e) {
            try {
                text = page.textContent("body");
            } catch (PlaywrightException ignored) {
                return "";
            }
        }
        if (text == null) {
            return "";
        }
        // innerText 里常有一长串空行，压一下再交给模型，省上下文
        return text.replaceAll("[ \t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
