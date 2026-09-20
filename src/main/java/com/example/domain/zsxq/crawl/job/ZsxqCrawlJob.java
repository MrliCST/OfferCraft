package com.example.domain.zsxq.crawl.job;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.source.CrawlScope;
import com.example.domain.zsxq.source.CrawlSource;
import com.example.domain.zsxq.source.CrawlTask;
import com.example.domain.zsxq.source.TaskPage;

/**
 * 两阶段作业编排：先列清单落盘，再逐条取内容落盘。
 *
 * <p>为什么必须拆成两段（这是"崩了不用从头再来"的全部答案）：
 * 旧实现把"翻到哪些帖"和"取回每篇内容"混在同一个循环里，进度只存在于内存下标。
 * 进程一崩，连"已经知道有哪些帖子"这件事都一起丢了。
 * 拆开之后，清单是盘上的文件、每条内容是独立的一行 ——
 * 崩了重跑同一条命令，几秒就知道做到哪了，接着往下走。
 *
 * <p>幂等靠 {@code taskId}（官方 topic_id）：同一篇出现在多个栏目也是同一个 id，
 * 第二次遇到直接跳过，天然完成跨栏目去重。
 */
public class ZsxqCrawlJob {

    /** 每取回这么多条落一次盘。崩一次最多丢这么多，而不是丢整批。 */
    private static final int FLUSH_EVERY = 10;

    /** 阶段 A 翻页保护：接口偶发吐重复游标时避免死循环。 */
    private static final int MAX_PAGES = 500;

    private final CrawlSource source;

    public ZsxqCrawlJob(CrawlSource source) {
        this.source = source;
    }

    /**
     * @param scope       爬取范围
     * @param outDir      输出根目录
     * @param refreshTasks true = 忽略已有清单重新拉（清单过期时用）
     */
    public CrawlJobStats run(CrawlScope scope, Path outDir, boolean refreshTasks) {
        CrawlJobStats stats = new CrawlJobStats();
        Path dir = outDir.resolve(scope.slug());
        Path tasksFile = dir.resolve("tasks.jsonl");
        Path postsFile = dir.resolve("posts.jsonl");

        List<CrawlTask> tasks = refreshTasks ? new ArrayList<>() : JsonlStore.read(tasksFile, CrawlTask.class);
        if (tasks.isEmpty()) {
            tasks = planAll(scope, stats);
            JsonlStore.append(tasksFile, tasks);
            System.out.println("  阶段A：清单 " + tasks.size() + " 条 -> " + tasksFile);
        } else {
            System.out.println("  阶段A：复用已有清单 " + tasks.size() + " 条（要重拉加 --refresh）");
        }
        stats.totalTasks = tasks.size();

        // 已完成的集合：幂等的依据
        Set<String> done = new LinkedHashSet<>();
        List<CrawledPost> posts = JsonlStore.read(postsFile, CrawledPost.class);
        for (CrawledPost p : posts) {
            if (p.postId != null) {
                done.add(p.postId);
            }
        }

        List<CrawledPost> buffer = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            CrawlTask t = tasks.get(i);
            if (done.contains(t.taskId)) {
                stats.skipped++;
                continue;
            }
            CrawledPost p;
            try {
                p = source.fetch(t);
            } catch (Exception e) {
                stats.failed++;
                System.out.println("    [" + (i + 1) + "/" + tasks.size() + "] 失败: "
                        + oneLine(e.getMessage()));
                continue;
            }
            if (p == null) {
                stats.failed++;
                continue;
            }
            p.column = scope.tagName;      // 归属由作业决定：同一帖可属多栏目
            buffer.add(p);
            done.add(t.taskId);
            stats.fetched++;

            if (buffer.size() >= FLUSH_EVERY) {
                JsonlStore.append(postsFile, buffer);
                buffer.clear();
            }
            if ((i + 1) % 50 == 0) {
                System.out.println("    ...进度 " + (i + 1) + "/" + tasks.size()
                        + "（新取 " + stats.fetched + "，跳过 " + stats.skipped + "，失败 " + stats.failed + "）");
            }
        }
        if (!buffer.isEmpty()) {
            JsonlStore.append(postsFile, buffer);
        }

        // 导出成 JSON 数组：下游清洗管道读的是数组格式，jsonl 只是我们自己的断点账本
        List<CrawledPost> all = JsonlStore.read(postsFile, CrawledPost.class);
        Path bank = outDir.resolve(scope.slug() + ".json");
        JsonlStore.write(bank, all);
        System.out.println("  阶段B：" + stats
                + " -> " + postsFile + "\n  导出 " + all.size() + " 篇 -> " + bank);
        return stats;
    }

    /** 阶段 A：一路翻页直到到底 / 达到上限。 */
    private List<CrawlTask> planAll(CrawlScope scope, CrawlJobStats stats) {
        List<CrawlTask> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String cursor = null;
        for (int page = 1; page <= MAX_PAGES; page++) {
            TaskPage pg = source.plan(scope, cursor);
            int added = 0;
            for (CrawlTask t : pg.tasks) {
                if (seen.add(t.taskId)) {
                    out.add(t);
                    added++;
                    if (scope.limit > 0 && out.size() >= scope.limit) {
                        break;
                    }
                }
            }
            System.out.println("    [清单] 第" + page + "页 +" + added + " 条，累计 " + out.size());
            if (pg.nextCursor == null || pg.nextCursor.isEmpty()) {
                break;                       // 到底了
            }
            if (pg.nextCursor.equals(cursor)) {
                break;                       // 游标没动，防死循环
            }
            if (scope.limit > 0 && out.size() >= scope.limit) {
                break;
            }
            cursor = pg.nextCursor;
        }
        stats.totalTasks = out.size();
        return out;
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "(无消息)";
        }
        String first = s.split("\n")[0];
        return first.length() <= 100 ? first : first.substring(0, 100);
    }
}
