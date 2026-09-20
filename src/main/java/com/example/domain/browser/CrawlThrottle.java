package com.example.domain.browser;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;

/**
 * 给"爬网站"做的简单限流，目标是别被目标站识别成脚本 / 封号。只做最小必要三件事：
 * <ul>
 *   <li><b>限频</b>：每次导航前做高斯随机延迟，避免固定间隔（固定节奏最容易被风控盯上）；</li>
 *   <li><b>限流</b>：每个域名用信号量限制并行数（默认 2），同一站不会同时开一堆请求；</li>
 *   <li><b>限网</b>：拦掉图片 / 字体 / 样式表 / 媒体这类"抓正文、截图用不上的重资源"，提速且更省目标站带宽。</li>
 * </ul>
 * 不做成 Spring Bean，纯静态工具：调用方在开页面前调 {@link #beforeCrawl}，关页面后调 {@link #afterCrawl} 即可。
 * 这里不是项目重点，刻意保持最小实现、不引入配置项。
 */
@Slf4j
public final class CrawlThrottle {

    /** 单域名最大并行请求数（建议 1~2）。信号量控制，超过就排队等 */
    private static final int MAX_CONCURRENT_PER_DOMAIN = 2;

    /**
     * 拿许可的最长等待时间（秒）。超过就抛异常而不是无限等。
     *
     * <p>为什么必须有这个上限：许可只在 {@link #afterCrawl} 里归还，而调用方是在
     * {@code finally} 里调的 —— 只要抓取那段代码真能走到 finally 就没问题。但实际踩过坑：
     * Playwright 的 {@code navigate} 在页面永久白屏 / 连接被中间设备黑洞时会一直不返回，
     * 于是 finally 也到不了，许可永远拿不回来。两个许可被两篇卡死的帖子占满之后，
     * 整个爬虫就在 {@code acquire} 上静默睡死 —— 表现是 CPU 近 0、网络连接为 0、进程却活着。
     * 有超时至少会炸出异常，让人看见「卡住了」，而不是无声无息地停在那儿。
     */
    private static final int ACQUIRE_TIMEOUT_SECONDS = 120;

    /** 高斯随机延迟的均值与标准差（毫秒）。围绕均值抖动，下限钳到 0 */
    private static final double DELAY_MEAN_MS = 1500.0;
    private static final double DELAY_STDDEV_MS = 700.0;

    /** 每个域名一个信号量，懒创建 */
    private static final Map<String, Semaphore> DOMAIN_SEMAPHORES = new ConcurrentHashMap<>();

    private CrawlThrottle() {
    }

    /**
     * 进入一次抓取前调用：拿该域名的许可（超了就阻塞等待，最长 {@value #ACQUIRE_TIMEOUT_SECONDS} 秒），
     * 并做一次随机延迟。必须和 {@link #afterCrawl} 成对：后者在抓取结束后归还许可。
     *
     * <p>拿不到许可时<b>抛异常而不是无限等</b>。调用方本来就在 try/catch 里抓单篇帖子的失败，
     * 一篇卡死不该拖垮整个爬取 —— 抛出来正好被那层 catch 收走，记一条失败继续爬下一篇。
     */
    public static void beforeCrawl(String url) {
        String host = SiteLoginRegistry.hostOf(url);
        if (host.isEmpty()) {
            return;
        }
        try {
            boolean acquired = DOMAIN_SEMAPHORES
                    .computeIfAbsent(host, h -> new Semaphore(MAX_CONCURRENT_PER_DOMAIN))
                    .tryAcquire(ACQUIRE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException(
                        "等待 " + host + " 抓取许可超时（" + ACQUIRE_TIMEOUT_SECONDS
                                + "s），可能有页面卡死未归还许可");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 " + host + " 抓取许可时被中断", e);
        }
        randomDelay();
    }

    /** 抓取结束后调用：归还该域名的许可 */
    public static void afterCrawl(String url) {
        String host = SiteLoginRegistry.hostOf(url);
        if (host.isEmpty()) {
            return;
        }
        Semaphore sem = DOMAIN_SEMAPHORES.get(host);
        if (sem != null) {
            sem.release();
        }
    }

    /** 高斯随机延迟：均值附近抖动，避免固定间隔被风控识别 */
    private static void randomDelay() {
        double sample = DELAY_MEAN_MS + DELAY_STDDEV_MS * ThreadLocalRandom.current().nextGaussian();
        long ms = (long) Math.max(0, sample);
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 拦掉"抓正文 / 截图不需要"的重资源：图片、字体、样式表、媒体。
     * 必须在导航前调用（page.route 只对之后发出的请求生效）。只拦这几类，其余正常加载。
     *
     * <p>注意：截图场景拦掉样式表会让长图变成无样式版（只留文字排布），换取 3~5 倍速度；
     * 若更看重截图美观，把 "stylesheet" 从拦截名单拿掉即可。
     */
    public static void blockUselessResources(Page page) {
        page.route("**/*", route -> {
            String type = route.request().resourceType();
            boolean blocked = type != null && ("image".equals(type) || "font".equals(type)
                    || "stylesheet".equals(type) || "media".equals(type));
            if (blocked) {
                route.abort();
            } else {
                route.resume();
            }
        });
    }
}
