package com.example.domain.zsxq.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.ZsxqCleanedDoc;
import com.example.domain.zsxq.model.ZsxqDropRecord;

/**
 * 样本目录的读写工具：一个目录 = 一次爬取的完整产物。
 *
 * <p>目录约定：
 * <ul>
 *   <li>{@code <栏目key>.json} —— 各栏目的原始帖；</li>
 *   <li>{@code question-bank.json} —— 清洗后入库的文档（S7 产物）；</li>
 *   <li>{@code drops.json} —— 清洗丢弃记录。</li>
 * </ul>
 *
 * <p>抽出来的原因：清洗、评估、落库三个 CLI 都要读同一份目录，各自抄一遍会散落三份
 * 「哪些文件该跳过、column 怎么回填」的规则，改一处漏两处。
 */
public final class ZsxqSampleIo {

    /** 清洗产物，不属于原始栏目文件，读原始帖时要跳过。 */
    private static final String QUESTION_BANK = "question-bank.json";
    private static final String DROPS = "drops.json";

    private ZsxqSampleIo() {
    }

    /** 读各栏目原始帖（跳过清洗产物），并回填 column。 */
    public static List<CrawledPost> readRawPosts(String dir, ObjectMapper om) throws IOException {
        List<CrawledPost> all = new ArrayList<>();
        try (var stream = Files.list(Path.of(dir))) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !QUESTION_BANK.equals(p.getFileName().toString())
                            && !DROPS.equals(p.getFileName().toString()))
                    .sorted()
                    .toList();
            for (Path f : files) {
                CrawledPost[] arr = om.readValue(f.toFile(), CrawledPost[].class);
                String fallbackColumn = f.getFileName().toString().replace(".json", "");
                for (CrawledPost post : arr) {
                    if (post.column == null) {
                        post.column = fallbackColumn;
                    }
                    all.add(post);
                }
            }
        }
        return all;
    }

    /** 读清洗后入库的文档；文件不存在或格式不对时按空处理（评估/落库都能容忍空）。 */
    public static List<ZsxqCleanedDoc> readDocs(String dir, ObjectMapper om) {
        return readList(Path.of(dir, QUESTION_BANK), ZsxqCleanedDoc[].class, om);
    }

    /** 读清洗丢弃记录。 */
    public static List<ZsxqDropRecord> readDrops(String dir, ObjectMapper om) {
        return readList(Path.of(dir, DROPS), ZsxqDropRecord[].class, om);
    }

    /** 写 JSON（带缩进，方便人工查看与 diff）。 */
    public static void writeJson(String file, Object value, ObjectMapper om) throws IOException {
        Path p = Path.of(file);
        if (p.getParent() != null) {
            Files.createDirectories(p.getParent());
        }
        om.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), value);
    }

    /** 按缩进输出配置的 ObjectMapper，各 CLI 共用一份写法。 */
    public static ObjectMapper prettyMapper() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    private static <T> List<T> readList(Path file, Class<T[]> type, ObjectMapper om) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return List.of(om.readValue(file.toFile(), type));
        } catch (Exception e) {
            System.out.println("读取失败（按空处理）: " + file + " -> " + e.getMessage());
            return List.of();
        }
    }
}
