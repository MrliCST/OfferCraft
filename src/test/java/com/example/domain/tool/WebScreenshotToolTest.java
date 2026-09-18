package com.example.domain.tool;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.domain.browser.BrowserConfig;
import com.example.domain.browser.BrowserSessionProperties;
import com.example.domain.browser.BrowserSessionProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长截图工具的直连测试：绕过模型，直接调工具方法，验证"浏览器真的起来了、图真的落盘了、图是真的长"。
 *
 * <p>会真的开一次无头 Chrome 并访问外网，所以放在需要环境的那一类测试里；不调模型，不花钱。
 *
 * <p>挑的页面是有一定长度的教程页：太短的页面（一屏就完）看不出"长图"和"截图"的差别，
 * 高度必须超过一屏这条断言才有意义。
 *
 * <p>跑在 test profile 下：application-test.yml 把日志压到 WARN，控制台只留测试自己的打印。
 */
// 只加载截图这条链上真正用到的东西：工具 + 会话层 + 浏览器 + 配置。
// 注意两个 @ConfigurationProperties（ScreenshotProperties / BrowserSessionProperties）都不能写进 classes ——
// 它们是 record，写进去 Spring 会当普通 bean 去找构造器参数，必然装配失败；
// 它们由各自的 @EnableConfigurationProperties 注册。
// 不加载整个应用是有意的：这里不碰模型和数据库，没必要为截个图去连库。
@SpringBootTest(classes = {WebScreenshotTool.class, BrowserSessionProvider.class, BrowserConfig.class})
@EnableConfigurationProperties({ScreenshotProperties.class, BrowserSessionProperties.class})
@ActiveProfiles("test")
class WebScreenshotToolTest {

    /** 内容够长、国内能直连 */
    /** 内容够长、国内能直连。用公开页面是刻意的：这个用例验的是"截图对不对"，不该依赖登录态 */
    private static final String URL = "https://xiaoyuan.zhaopin.com/";
    /**
     * 从回报文本里抠出文件路径：取"已保存到 "和紧跟着的中文左括号之间的内容。
     * 不能按空白截断 —— 默认目录叫 "Long photos"，路径里本来就有空格。
     */
    private static final Pattern PATH_IN_REPLY = Pattern.compile("已保存到\\s*(.+?)\\s*（");

    @Autowired
    private WebScreenshotTool tool;

    @Autowired
    private ScreenshotProperties properties;

    @Test
    void captureFullPage_savesPngTallerThanViewport() throws IOException {
        long start = System.currentTimeMillis();
        String reply = tool.captureWebpage(URL, null);
        System.out.println("===== 工具回报 =====");
        System.out.println(reply);
        System.out.println("===== 耗时 " + (System.currentTimeMillis() - start) + "ms =====");

        assertThat(reply).as("应报告成功").contains("截图完成");

        Path file = Path.of(extractPath(reply));
        assertThat(Files.exists(file)).as("图片应真的落盘").isTrue();
        assertThat(Files.size(file)).as("不该是空文件").isGreaterThan(10_000L);

        int[] size = pngSize(file);
        System.out.println("图片尺寸：" + size[0] + "x" + size[1]);
        assertThat(size[0]).as("宽度应等于配置的视口宽").isEqualTo(properties.viewportWidth());
        assertThat(size[1]).as("长图高度必须超过一屏，否则说明没截全")
                .isGreaterThan(properties.viewportHeight());
    }

    @Test
    void captureFullPage_honoursCustomDir(@TempDir Path tmp) throws IOException {
        String dir = tmp.resolve("nested").resolve("shots").toString();

        String reply = tool.captureWebpage(URL, dir);
        System.out.println("===== 自定义目录 =====");
        System.out.println(reply);

        Path file = Path.of(extractPath(reply));
        assertThat(file.getParent()).as("应存到用户给的目录（含自动创建的多级目录）")
                .isEqualTo(Path.of(dir));
        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    void setSaveDir_changesDefaultForLaterCalls(@TempDir Path tmp) throws IOException {
        Path custom = tmp.resolve("default-dir");
        System.out.println(tool.setSaveDir(custom.toString()));

        String reply = tool.captureWebpage(URL, null);
        System.out.println("===== 改过默认目录后的截图 =====");
        System.out.println(reply);

        assertThat(Path.of(extractPath(reply)).getParent()).isEqualTo(custom);
    }

    @Test
    void captureFullPage_rejectsNonHttpUrl() {
        String reply = tool.captureWebpage("file:///etc/passwd", null);

        System.out.println("===== 非法协议 =====");
        System.out.println(reply);
        assertThat(reply).contains("截图失败").contains("http");
    }

    private static String extractPath(String reply) {
        Matcher m = PATH_IN_REPLY.matcher(reply);
        assertThat(m.find()).as("回报里应该带一个绝对路径，实际是：" + reply).isTrue();
        return m.group(1);
    }

    /** 直接读 PNG 头的 IHDR 拿宽高 —— 长图可能几万像素高，用 ImageIO 会整张读进内存，没必要 */
    private static int[] pngSize(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            in.readLong();  // 8 字节文件签名
            in.readInt();   // chunk 长度
            in.readInt();   // "IHDR"
            int width = in.readInt();
            int height = in.readInt();
            return new int[] {width, height};
        }
    }
}
