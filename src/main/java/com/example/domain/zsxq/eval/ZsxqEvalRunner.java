package com.example.domain.zsxq.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.ZsxqCleanedDoc;
import com.example.domain.zsxq.model.ZsxqDropRecord;

/**
 * 管道评估的 CLI 入口：只做 IO 编排——读原始帖 / 入库文档 / 丢弃记录，
 * 委托 {@link PipelineEvaluator} 算指标、{@link EvalReportRenderer} 渲染，最后写报告文件。
 * 跟 {@link com.example.domain.zsxq.clean.ZsxqCleaner} 一样只起轻量上下文，不 boot WebFlux。
 *
 * <p>用法: java ...ZsxqEvalRunner [样本目录] [输出报告.md]
 */
public final class ZsxqEvalRunner {

    public static void main(String[] args) throws Exception {
        String inDir = args.length > 0 ? args[0]
                : System.getProperty("user.home") + "/code/demo/JLRADemo/crawl-output/zsxq-eval";
        String outFile = args.length > 1 ? args[1] : "docs/zsxq-pipeline-eval-report.md";
        ObjectMapper om = new ObjectMapper();

        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(PipelineEvaluator.class, EvalReportRenderer.class)) {
            PipelineEvaluator evaluator = ctx.getBean(PipelineEvaluator.class);
            EvalReportRenderer renderer = ctx.getBean(EvalReportRenderer.class);

            List<CrawledPost> raw = loadRaw(inDir, om);
            List<ZsxqCleanedDoc> kept = loadList(Path.of(inDir, "question-bank.json"), ZsxqCleanedDoc[].class, om);
            List<ZsxqDropRecord> dropped = loadList(Path.of(inDir, "drops.json"), ZsxqDropRecord[].class, om);

            PipelineMetrics m = evaluator.evaluate(raw, kept, dropped);
            String md = renderer.render(m, inDir);

            Path out = Path.of(outFile);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, md);

            System.out.println("样本: " + raw.size() + " 篇原始帖 + " + kept.size() + " 篇入库 + " + dropped.size() + " 篇丢弃");
            System.out.println("异常项: " + m.anomalies.size() + " 条");
            System.out.println("报告 -> " + out.toAbsolutePath());
        }
    }

    /** 读各栏目原始 JSON（跳过清洗产物 question-bank.json / drops.json），并回填 column。 */
    private static List<CrawledPost> loadRaw(String inDir, ObjectMapper om) throws Exception {
        List<CrawledPost> all = new ArrayList<>();
        try (var stream = Files.list(Path.of(inDir))) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().equals("question-bank.json")
                            && !p.getFileName().toString().equals("drops.json"))
                    .forEach(p -> {
                        try {
                            CrawledPost[] arr = om.readValue(p.toFile(), CrawledPost[].class);
                            for (CrawledPost post : arr) {
                                post.column = post.column == null
                                        ? p.getFileName().toString().replace(".json", "") : post.column;
                                all.add(post);
                            }
                        } catch (Exception e) {
                            throw new RuntimeException("读栏目文件失败: " + p, e);
                        }
                    });
        }
        return all;
    }

    private static <T> List<T> loadList(Path file, Class<T[]> type, ObjectMapper om) {
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
