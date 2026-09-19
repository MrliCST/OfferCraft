package com.example.domain.zsxq.clean;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.example.domain.zsxq.classify.ZsxqTopicGuardConfig;
import com.example.domain.zsxq.io.ZsxqSampleIo;
import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.ZsxqCleanedDoc;
import com.example.domain.zsxq.model.ZsxqCleaningResult;

/**
 * 帖子转题库清洗器（S1–S7）的 CLI 入口。
 *
 * <p>只做 IO 编排：读 S1 产出的各栏目 JSON → 委托 {@link ZsxqCleaningService} 清洗 → 写 question-bank.json / drops.json。
 * 不 boot 整个 Spring Boot（WebFlux 那套对一次性批量清洗毫无意义），只起一个轻量
 * {@link AnnotationConfigApplicationContext} 取清洗服务，跑完即关——启动快、方便 AI 辅助下的高频测试。
 *
 * <p>分类闸（LLM / 启发式）的装配见 {@link ZsxqTopicGuardConfig}：有 deepseekClassifyModel bean 或 DEEPSEEK_WIN_KEY
 * 环境变量走 LLM 闸，否则纯启发式兜底。
 *
 * <p>用法: java ...ZsxqCleaner [输入目录] [输出question-bank.json]
 */
public final class ZsxqCleaner {

    public static void main(String[] args) throws Exception {
        String inDir = args.length > 0 ? args[0]
                : System.getProperty("user.home") + "/code/demo/JLRADemo/crawl-output/zsxq";
        String outFile = args.length > 1 ? args[1] : inDir + "/question-bank.json";
        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(ZsxqTopicGuardConfig.class, ZsxqCleaningService.class)) {
            ZsxqCleaningService svc = ctx.getBean(ZsxqCleaningService.class);

            List<CrawledPost> all = ZsxqSampleIo.readRawPosts(inDir, om);
            System.out.println("S1 载入原始帖子: " + all.size() + " 篇（来自 " + inDir + "）");

            ZsxqCleaningResult result = svc.run(all);

            ZsxqSampleIo.writeJson(outFile, result.kept(), om);
            ZsxqSampleIo.writeJson(inDir + "/drops.json", result.dropped(), om);
            System.out.println("S7 入库题库: " + result.kept().size() + " 篇 -> " + outFile);
            System.out.println("   丢弃: " + result.dropped().size() + " 篇 -> " + inDir + "/drops.json");

            printStats(result);
        }
    }

    private static void printStats(ZsxqCleaningResult result) {
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (ZsxqCleanedDoc d : result.kept()) {
            byType.merge(d.postType, 1, Integer::sum);
        }
        System.out.println("=== 入库按类型 ===");
        byType.forEach((k, v) -> System.out.println("   " + k + ": " + v));
        int series = (int) result.kept().stream().filter(d -> d.seriesId != null).count();
        int verified = (int) result.kept().stream().filter(d -> d.starMasterVerified).count();
        System.out.println("   系列串联: " + series + " 篇 | 星主权威校验: " + verified + " 篇");
    }
}
