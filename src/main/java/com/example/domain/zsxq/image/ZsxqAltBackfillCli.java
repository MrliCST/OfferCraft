package com.example.domain.zsxq.image;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.example.domain.zsxq.ingest.ZsxqIngestConfig;
import com.example.domain.zsxq.io.ZsxqSampleIo;
import com.example.domain.zsxq.model.ZsxqCleanedDoc;

/**
 * 把某个样本目录的 {@code question-bank.json} 里正文图片的 alt 换成多模态概括。
 *
 * <p><b>为什么需要这个</b>：{@link ZsxqImageRunner} 的 {@code --backfill} 只改数据库里的
 * {@code cleaned_doc.content}，<b>不会动落盘的 JSON 产物</b>。而落盘产物是给人看的、
 * 也是评估器/其他工具的直接输入 —— 库和文件两边不同步，就会出现「库里回填了、
 * 文件里还是 {@code ![图片.png]}」的割裂。
 *
 * <p>用法: java ...ZsxqAltBackfillCli [样本目录]
 * 例：java ...ZsxqAltBackfillCli crawl-output/zsxq-eval-b2
 *
 * <p>幂等：alt 已经是描述（不带图片扩展名）的图不会再改，重跑输出不变。
 * 描述从库里 {@code zsxq_image} 读（按 URL 匹配），所以要先跑过 {@code --describe}。
 */
public final class ZsxqAltBackfillCli {

    public static void main(String[] args) throws Exception {
        String inDir = args.length > 0 ? args[0]
                : System.getProperty("user.home") + "/code/demo/JLRADemo/crawl-output/zsxq-eval-b2";

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(
                ZsxqIngestConfig.class, ZsxqImageConfig.class, ZsxqImageService.class)) {
            ZsxqImageService svc = ctx.getBean(ZsxqImageService.class);

            String questionBank = inDir + "/question-bank.json";
            List<ZsxqCleanedDoc> docs = ZsxqSampleIo.readDocs(inDir, ZsxqSampleIo.prettyMapper());
            if (docs.isEmpty()) {
                System.out.println("没读到文档：" + questionBank + "（目录不对或清洗还没跑）");
                return;
            }

            Map<String, String> all = svc.allDescriptions();
            System.out.println("库里有描述的图: " + all.size() + " 张");

            int docsChanged = 0;
            int imgsChanged = 0;
            for (ZsxqCleanedDoc d : docs) {
                if (d.content == null) {
                    continue;
                }
                String before = d.content;
                d.content = MarkdownImageAltBackfiller.backfill(d.content, all);
                if (!d.content.equals(before)) {
                    docsChanged++;
                    imgsChanged += countFilenameAlts(before) - countFilenameAlts(d.content);
                }
            }

            if (docsChanged == 0) {
                System.out.println("没有需要回填的文档——alt 已经都是描述了。");
                return;
            }
            ZsxqSampleIo.writeJson(questionBank, docs, ZsxqSampleIo.prettyMapper());
            System.out.println("回填完成: " + docsChanged + " 篇文档 / " + imgsChanged + " 张图的 alt");
            System.out.println("已写回: " + questionBank);
        }
    }

    /** 统计内容里「alt 是图片文件名」的图片数，用来算本次改了几张。 */
    private static int countFilenameAlts(String content) {
        int n = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("!\\[[^\\]]*\\.(png|jpg|jpeg|gif|webp|bmp)\\s*\\]\\(")
                .matcher(content);
        while (m.find()) {
            n++;
        }
        return n;
    }
}
