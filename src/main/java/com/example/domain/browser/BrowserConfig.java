package com.example.domain.browser;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.microsoft.playwright.Playwright;

/**
 * 无头浏览器的进程级资源：{@link Playwright} 实例。
 *
 * <p>为什么做成单例 bean：Playwright.create() 会把自带的 driver 解压到临时目录、拉起一个 node 进程，
 * 这个开销跟"截几张图"不相干，一次启动只该付一次；而且它本身是线程安全的，多个请求共用一个没问题。
 * 真正按次创建的是 Browser / Page（见 WebScreenshotTool），用完即关，避免残留进程。
 *
 * <p>destroyMethod = "close"：容器关闭时把 driver 进程收掉，否则 JVM 退出了 node 还挂着。
 *
 * <p>关键点 —— 为什么非得传这个 env：Playwright 启动时会自作主张跑一遍"安装浏览器"，会因网络超时失败；
 * 这里用本地chrome浏览器，所以显式告诉它别下。
 *
 * <p>用 {@code setEnv} 而不是依赖外部环境变量，是有意的：环境变量这条链在 IDE 里根本靠不住
 * （VS Code 的测试面板是 extension host 拉起的 JVM，拿不到终端 export 的东西），
 * 写进代码才能在"终端跑、VS Code 点、java -jar"三种方式下都生效。
 * env 里先放一份当前进程的环境再追加，避免把 PATH/HOME 这些必要变量顶掉。
 */
@Configuration
@EnableConfigurationProperties(BrowserSessionProperties.class)
public class BrowserConfig {

    /** driver 认的开关：值不是 "0"/"false" 就跳过浏览器下载 */
    private static final String SKIP_BROWSER_DOWNLOAD = "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD";

    /** 登录态存档的读写器。跟 Playwright 放一起：测试里只加载本类也能拿到它 */
    @Bean
    public LoginStateStore loginStateStore(BrowserSessionProperties sessionProperties) {
        return new LoginStateStore(sessionProperties);
    }

    @Bean(destroyMethod = "close")
    public Playwright playwright() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put(SKIP_BROWSER_DOWNLOAD, "1");

        return Playwright.create(new Playwright.CreateOptions().setEnv(env));
    }
}
