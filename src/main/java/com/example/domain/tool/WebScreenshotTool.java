package com.example.domain.tool;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.example.domain.browser.BrowserSessionProvider;
import com.example.domain.browser.PageSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 给 LLM 用的"网页长截图"工具：把一整个网页（含滚动才加载出来的部分）截成一张长图，存到本地，
 * 把路径回报给模型。
 *
 * <p>流程就是三步，顺序不能换：
 * <ol>
 *   <li>从 {@link BrowserSessionProvider} 拿一个页面 —— 用哪种浏览器（无头 / 接管已登录的 /
 *       指定 profile）由配置决定，本类不关心；</li>
 *   <li>执行 JS 平滑滚到底再回顶部 —— 这一步是长图能不能截全的关键，
 *       现在很多站的图片是懒加载的，不滚一遍，滚过的位置全是占位图或空白；</li>
 *   <li>{@code fullPage=true} 截图，Playwright 自己算总高并拼接；</li>
 *   <li>存到目录里，把绝对路径、图片尺寸、耗时拼成一句话返回给模型。</li>
 * </ol>
 *
 * <p>用系统装好的 Google Chrome（channel = chrome），不让 Playwright 去下载 Chromium：
 * 一是省一百多兆，二是系统 Chrome 的字体和渲染跟用户平时看到的完全一致。
 * 换 Chromium 只要把 yml 的 {@code screenshot.channel} 改成空串（或换成 ms-playwright 里的版本）。
 *
 * <p>失败时不往外抛：抛异常模型只能看到一串栈，不如把"为什么失败"说成人话让它转述给用户。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(ScreenshotProperties.class)
public class WebScreenshotTool {

    /** 文件时间戳：20260917-221530，排序和肉眼识别都方便 */
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 滚动脚本。返回页面最终总高度（px）。
     *
     * <p>几个刻意的写法：
     * <ul>
     *   <li>每滚一步等一小会儿，图片请求是滚动才发出的，滚太快请求都攒在最后，反而截不到；</li>
     *   <li>触底后再等一轮并比对高度，高度不变才算真的到底（有的站滚到底会追加内容）；</li>
     *   <li>maxRounds 兜底：无限流的页面永远滚不完，不能让循环挂死；</li>
     *   <li>最后滚回顶部 —— 部分站点有"滚动后固定的顶栏"，从底部直接截会把它重复渲染或错位。</li>
     * </ul>
     */
    private static final String SCROLL_SCRIPT = """
            async (cfg) => {
              const sleep = (ms) => new Promise(r => setTimeout(r, ms));
              const height = () => Math.max(
                document.body.scrollHeight || 0,
                document.documentElement.scrollHeight || 0,
                document.body.offsetHeight || 0,
                document.documentElement.offsetHeight || 0);

              let rounds = 0;
              while (rounds++ < cfg.maxRounds) {
                window.scrollBy(0, cfg.step);
                await sleep(cfg.delay);
                const h = height();
                if (window.scrollY + window.innerHeight >= h) {
                  await sleep(cfg.settle);
                  if (height() === h) {
                    window.scrollTo(0, 0);
                    await sleep(cfg.delay);
                    return h;
                  }
                }
              }
              window.scrollTo(0, 0);
              await sleep(cfg.delay);
              return height();
            }
            """;

    private final BrowserSessionProvider provider;
    private final ScreenshotProperties properties;

    /**
     * 运行期默认目录。初始来自配置，用户让模型换目录后由 {@link #setSaveDir} 改掉，
     * 之后的调用就不用每次都带目录参数了。写在这里而不是改 yml，是因为它只对当前进程有效。
     */
    private final AtomicReference<String> saveDir = new AtomicReference<>();

    /**
     * 截取整个网页的长图并保存到本地。
     *
     * @param url     网页地址，必须 http/https
     * @param dir     保存目录，可空；空就用当前默认目录
     * @return 给模型看的一句话结果：成功带绝对路径和尺寸，失败带原因
     */
    @Tool(name = "capture_webpage_screenshot", value = {
            "把指定网页完整截成一张长图保存到本地，返回图片的保存路径。",
            "会自动向下滚动一遍再截，滚动才加载的图片和内容也能截进去。",
            "当用户要求截图、保存网页、把网页存成图片、做长图时调用它。"
    })
    public String captureWebpage(
            @P(value = "要截取的网页完整 URL，必须以 http:// 或 https:// 开头", required = true) String url,
            @P(value = "图片保存目录，绝对路径；不填就用当前默认目录。目录不存在会自动创建", required = false) String dir) {

        log.info("长截图：url={}，dir={}", url, dir);
        long start = System.currentTimeMillis();

        try {
            // URL 校验在 provider 里做过了，这里直接用
            Path targetDir = resolveDir(dir);

            // URL 校验统一在 provider.open 里做，这里直接用原值
            try (PageSession session = provider.open(url)) {
                Page page = session.page();

                int pageHeight = scrollThrough(page);
                waitIfIdleNeeded(page);

                Path file = targetDir.resolve(fileName(url));
                page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(true)
                        .setPath(file)
                        .setTimeout(properties.navigationTimeoutMs()));

                long cost = System.currentTimeMillis() - start;
                // 宽度报实际值而不是配置值：接管模式下视口就是用户窗口的大小，不一定是配置的那个
                return describe(file, viewportWidth(page), pageHeight, cost, session.source());
            }
        } catch (Exception e) {
            log.warn("长截图失败：{}", url, e);
            return "截图失败（" + url + "）：" + e.getMessage()
                    + "。可以让用户换个能正常访问的 URL，或换一个你有写权限的保存目录再试。";
        }
    }

    /**
     * 改默认保存目录。用户说"以后都存到 XXX"时，模型调这个，比每次截图都带一遍目录省事。
     *
     * @param dir 新的默认目录，绝对路径，不存在会直接创建
     * @return 确认信息，顺便告诉模型当前默认目录是什么
     */
    @Tool(name = "set_screenshot_save_dir", value = {
            "修改网页截图的默认保存目录，之后的截图都会存到这里。",
            "当用户要求「把图片存到某处」「以后都存到某处」这类换目录的要求时调用它。"
    })
    public String setSaveDir(
            @P(value = "新的默认保存目录，绝对路径，例如 /home/lyz/图片/Long photos", required = true) String dir) {
        Path resolved = expand(dir);
        try {
            Files.createDirectories(resolved);
            if (!Files.isWritable(resolved)) {
                return "目录没有写权限，默认目录没变，仍然是 " + currentDir();
            }
        } catch (Exception e) {
            return "创建目录失败：" + e.getMessage() + "，默认目录仍然是 " + currentDir();
        }
        saveDir.set(resolved.toString());
        return "默认保存目录已改为 " + resolved + "，之后的截图都会存到这里。";
    }

    /** 没指定目录就用默认目录（运行期改过的优先），并保证目录存在、可写 */
    private Path resolveDir(String dir) throws Exception {
        Path target = (dir == null || dir.isBlank()) ? Paths.get(currentDir()) : expand(dir);
        Files.createDirectories(target);
        if (!Files.isWritable(target)) {
            throw new IllegalArgumentException("目录不可写：" + target);
        }
        return target;
    }

    private String currentDir() {
        String runtime = saveDir.get();
        return (runtime != null && !runtime.isBlank()) ? runtime : properties.defaultDir();
    }

    /** 支持 ~ 开头的家目录写法，省得模型去猜用户名 */
    private static Path expand(String dir) {
        String path = dir.trim();
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        return Paths.get(path).toAbsolutePath().normalize();
    }

    /** 页面实际宽度。接管模式下视口就是用户窗口的大小，不一定是配置的那个，所以现问现用 */
    private static int viewportWidth(Page page) {
        Object width = page.evaluate("window.innerWidth");
        return width instanceof Number number ? number.intValue() : 0;
    }

    /** 滚到底再回顶，返回页面总高度 */
    private int scrollThrough(Page page) {
        Object height = page.evaluate(SCROLL_SCRIPT, Map.of(
                "step", properties.scrollStepPx(),
                "delay", properties.scrollDelayMs(),
                "settle", properties.settleTimeoutMs(),
                "maxRounds", properties.maxScrollRounds()));

        if (height instanceof Number number) {
            return number.intValue();
        }
        return properties.viewportHeight();
    }

    /**
     * 滚动结束后可选地再等一次网络空闲，兜住"滚完了最后一批图还在路上"的情况。
     * 默认关：多数站滚一遍就够，等这个要花好几秒，还经常永远等不到（有轮询、有长连接的站），
     * 所以等不到也不算失败，超时就走。
     */
    private void waitIfIdleNeeded(Page page) {
        if (!properties.waitNetworkIdle()) {
            return;
        }
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(properties.settleTimeoutMs()));
        } catch (PlaywrightException e) {
            log.debug("等 networkidle 超时，直接截图：{}", e.getMessage());
        }
    }

    /** 文件名：域名_时间戳.png，直接用 host，一眼能看出截的是哪个站 */
    private static String fileName(String url) {
        String host = "webpage";
        try {
            String h = URI.create(url).getHost();
            if (h != null && !h.isBlank()) {
                host = h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "")
                        .replaceAll("[^a-z0-9._-]", "_");
            }
        } catch (Exception ignored) {
            // 取不到 host 就用默认名，不值得为文件名中断截图
        }
        return host + "_" + FILE_STAMP.format(LocalDateTime.now()) + ".png";
    }

    /** 拼给模型看的回报：路径、尺寸、耗时，外加这次是用什么浏览器打开的（登录态说明） */
    private static String describe(Path file, int width, int height, long costMs, String source) {
        String size = "";
        try {
            size = String.format(Locale.ROOT, "%.1f KB", Files.size(file) / 1024.0);
        } catch (Exception ignored) {
            // 量不到大小也要把路径给出去了，别因为这个让整次调用失败
        }
        return "截图完成，已保存到 " + file.toAbsolutePath()
                + "（" + width + "×" + height + " px" + (size.isEmpty() ? "" : "，" + size)
                + "，耗时 " + String.format(Locale.ROOT, "%.1f", costMs / 1000.0) + "s"
                + "，来源：" + source + "）。"
                + "把这个完整路径原样告诉用户。";
    }
}
