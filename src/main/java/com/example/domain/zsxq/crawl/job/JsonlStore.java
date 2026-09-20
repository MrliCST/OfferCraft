package com.example.domain.zsxq.crawl.job;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON Lines 读写：每行一条记录，追加写、逐行读。
 *
 * <p>为什么用 jsonl 而不是一个大 JSON 数组：
 * <ul>
 *   <li><b>能追加</b>——爬到第 173 条崩了，前 172 条已经在盘上，不用重写整个文件；</li>
 *   <li><b>能续读</b>——下次启动逐行读回来就知道哪些做过了；</li>
 *   <li><b>崩了不损坏</b>——最多最后一行写了一半，丢一条而已，不像数组文件会整个变成非法 JSON。</li>
 * </ul>
 * 最终再导出成 JSON 数组交给下游清洗管道（那层要的是数组格式）。
 */
public final class JsonlStore {

    private static final ObjectMapper OM = new ObjectMapper();

    private JsonlStore() {
    }

    /** 逐行读。文件不存在返回空列表——第一次跑就是这个情况，不算错误。 */
    public static <T> List<T> read(Path file, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (!Files.exists(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                out.add(OM.readValue(line, type));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + file + " 失败：" + e.getMessage(), e);
        }
        return out;
    }

    /**
     * 追加写。注意<b>不做去重</b>——去重是调用方的事（它才知道按哪个字段算重复），
     * 这里只保证每次写入都是完整合法的一行。
     */
    public static <T> void append(Path file, List<T> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("创建目录 " + file.getParent() + " 失败：" + e.getMessage(), e);
        }
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (T item : items) {
                w.write(OM.writeValueAsString(item));
                w.write('\n');
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入 " + file + " 失败：" + e.getMessage(), e);
        }
    }

    /** 覆盖写（导出给下游时用）。 */
    public static <T> void write(Path file, List<T> items) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            OM.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), items);
        } catch (IOException e) {
            throw new IllegalStateException("写出 " + file + " 失败：" + e.getMessage(), e);
        }
    }
}
