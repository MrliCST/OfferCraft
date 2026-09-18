package com.example.domain.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.domain.browser.BrowserConfig;
import com.example.domain.browser.BrowserSessionProperties;
import com.example.domain.browser.BrowserSessionProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抓正文工具的测试：取标题 + 正文，并如实标出这次是什么来源（登录态还是无头）。
 *
 * <p>用的是公开页面，不需要登录态，因此跑的是默认（无头）模式 —— 这正是要验的一点：
 * 没配登录态时工具照样能用，只是抓到的是未登录内容。
 *
 * <p>不连数据库也不调模型。跑在 test profile 下：控制台只留测试自己的打印。
 */
@SpringBootTest(classes = {WebPageTextTool.class, BrowserSessionProvider.class, BrowserConfig.class})
@EnableConfigurationProperties({ScreenshotProperties.class, BrowserSessionProperties.class})
@ActiveProfiles("test")
class WebPageTextToolTest {

    /** 用公开页面：这个用例验的是"能不能抓"，不该依赖登录态（登录态由 LoggedInAccessVerificationTest 单独看） */
    private static final String URL = "https://www.example.com";

    @Autowired
    private WebPageTextTool tool;

    @Autowired
    private BrowserSessionProperties properties;

    @Test
    void fetchText_returnsTitleAndBody() {
        long start = System.currentTimeMillis();
        String result = tool.fetchText(URL);
        System.out.println("===== 抓取结果（耗时 " + (System.currentTimeMillis() - start) + "ms）=====");
        System.out.println(result);

        assertThat(result).contains("标题：");
        // 只断言"如实标出了来源"，不断言具体是哪种 —— 那取决于 yml 配没配登录态，
        // 写死某一种会让这个用例在别人配了登录态时莫名其妙挂掉
        assertThat(result).contains("来源：").contains("浏览器");
        assertThat(result).as("正文不该是空的").contains("Example Domain");
    }

    @Autowired
    private BrowserSessionProvider provider;

    @Test
    void fetchText_truncatesWhenTooLong() {
        // 阈值压到 50 字逼出截断分支；直接 new 一个工具实例，不去改容器里的配置
        WebPageTextTool smallLimitTool =
                new WebPageTextTool(provider, new BrowserSessionProperties(null, null, null, true, 50));

        String result = smallLimitTool.fetchText(URL);
        System.out.println("===== 截断到 50 字 =====");
        System.out.println(result);

        assertThat(result).contains("已截断到 50 字");
    }

    @Test
    void fetchText_rejectsNonHttpUrl() {
        String result = tool.fetchText("file:///etc/passwd");

        System.out.println("===== 非法协议 =====");
        System.out.println(result);
        assertThat(result).contains("读取网页失败").contains("http");
    }
}
