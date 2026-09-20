package com.example.domain.zsxq.crawl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.example.domain.zsxq.crawl.api.ZsxqApiClient;
import com.example.domain.zsxq.crawl.api.ZsxqApiSource;
import com.example.domain.zsxq.crawl.api.ZsxqHashtag;
import com.example.domain.zsxq.crawl.job.ZsxqCrawlJob;
import com.example.domain.zsxq.source.CrawlScope;

/**
 * 可控爬取的命令行入口：<b>只解析参数、调编排，不写业务逻辑</b>。
 *
 * <p>用法：
 * <pre>
 *   # 先看这个圈子有哪些栏目（标签）
 *   ZsxqApiCrawlCli --list
 *
 *   # 爬「面试相关」「优质面经」两个栏目
 *   ZsxqApiCrawlCli crawl-output/zsxq-api 面试相关,优质面经
 *
 *   # 只要 2026 年秋招之后的、最多 200 篇
 *   ZsxqApiCrawlCli crawl-output/zsxq-api 面试相关 --from=2026-07-01 --limit=200
 *
 *   # 崩了直接重跑同一条命令即可续上；清单过期才需要 --refresh
 * </pre>
 *
 * <p>取代了早期基于 Playwright 的 DOM 爬取方案：那一版要启动浏览器、点前端 chip 切栏目，
 * 全量跑一次会吃掉数 GB 内存并必然崩溃，已于 2026-09-20 删除。
 * 现在栏目、时间区间、上限全是命令行参数，可复现、可写进脚本、可定时跑。
 */
public final class ZsxqApiCrawlCli {

    private static final String GROUP_URL = "https://wx.zsxq.com/group/51121244585524";
    private static final String GROUP_ID = GROUP_URL.substring(GROUP_URL.lastIndexOf('/') + 1);

    public static void main(String[] args) throws Exception {
        List<String> positional = new ArrayList<>();
        boolean list = false;
        boolean refresh = false;
        String from = "";
        String to = "";
        int limit = 0;
        String author = "";

        for (String a : args) {
            if (a.equals("--list")) {
                list = true;
            } else if (a.equals("--refresh")) {
                refresh = true;
            } else if (a.startsWith("--from=")) {
                from = a.substring(7);
            } else if (a.startsWith("--to=")) {
                to = a.substring(5);
            } else if (a.startsWith("--limit=")) {
                limit = Integer.parseInt(a.substring(8));
            } else if (a.startsWith("--author=")) {
                author = a.substring(9);
            } else if (!a.startsWith("--")) {
                positional.add(a);
            }
        }

        ZsxqApiClient client = ZsxqApiClient.fromStoredState(GROUP_URL);
        ZsxqApiSource source = new ZsxqApiSource(client, GROUP_ID);

        if (list || positional.isEmpty()) {
            printHashtags(source);
            return;
        }

        String outDir = positional.get(0);
        String[] wanted = positional.size() > 1 ? positional.get(1).split(",") : new String[]{"all"};

        List<ZsxqHashtag> all = source.hashtags();
        for (String w : wanted) {
            String key = w.trim();
            if (key.isEmpty()) {
                continue;
            }
            ZsxqHashtag hit = match(all, key);
            if (hit == null) {
                System.out.println("!! 没找到栏目 [" + key + "]，用 --list 看看有哪些");
                continue;
            }
            CrawlScope scope = new CrawlScope(hit.hashtagId, hit.name);
            scope.fromTime = from;
            scope.toTime = to;
            scope.limit = limit;
            scope.author = author;
            System.out.println("\n##### 栏目: " + hit.name + " (" + hit.hashtagId + ") "
                    + (from.isEmpty() ? "" : "从 " + from + " ")
                    + (to.isEmpty() ? "" : "到 " + to + " ")
                    + (limit > 0 ? "上限 " + limit + " 篇" : "不限量"));
            new ZsxqCrawlJob(source).run(scope, Path.of(outDir), refresh);
        }
    }

    private static void printHashtags(ZsxqApiSource source) {
        System.out.println("=== 圈子栏目（话题标签）===");
        for (ZsxqHashtag h : source.hashtags()) {
            System.out.printf("  %-22s 约%5d篇  id=%s%n", h.name, h.topicCount, h.hashtagId);
        }
        System.out.println("\n用法: ZsxqApiCrawlCli <输出目录> <栏目名，逗号分隔> [--from=日期] [--limit=N]");
    }

    /** 按名字或 id 匹配。名字用 contains 而不是 equals——前端栏目名带 emoji，手打容易不全。 */
    private static ZsxqHashtag match(List<ZsxqHashtag> all, String key) {
        for (ZsxqHashtag h : all) {
            if (h.hashtagId.equals(key)) {
                return h;
            }
        }
        for (ZsxqHashtag h : all) {
            if (h.name != null && (h.name.contains(key) || key.contains(h.name))) {
                return h;
            }
        }
        return null;
    }

    private ZsxqApiCrawlCli() {
    }
}
