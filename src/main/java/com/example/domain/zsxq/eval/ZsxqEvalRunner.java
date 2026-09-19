package com.example.domain.zsxq.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.example.domain.zsxq.io.ZsxqSampleIo;
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

            List<CrawledPost> raw = ZsxqSampleIo.readRawPosts(inDir, om);
            List<ZsxqCleanedDoc> kept = ZsxqSampleIo.readDocs(inDir, om);
            List<ZsxqDropRecord> dropped = ZsxqSampleIo.readDrops(inDir, om);

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

}
