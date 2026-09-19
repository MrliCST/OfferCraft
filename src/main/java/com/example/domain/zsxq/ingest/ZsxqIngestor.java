package com.example.domain.zsxq.ingest;

import java.util.List;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.example.domain.zsxq.io.ZsxqSampleIo;
import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.ZsxqCleanedDoc;

/**
 * S3 落库的 CLI 入口：只做 IO 编排——读样本目录、调 {@link ZsxqIngestService}、打印行数。
 * 跟 {@link com.example.domain.zsxq.clean.ZsxqCleaner} 一样只起轻量上下文，不 boot WebFlux。
 *
 * <p>用法: java ...ZsxqIngestor [样本目录] [圈子id]
 * 例：java ...ZsxqIngestor crawl-output/zsxq-eval-b2 51121244585524
 *
 * <p>前置：库表已建好（src/main/resources/db/schema.sql，应用启动时会自动执行；
 * 单独跑这条命令前可以用 mvn spring-boot:run 或手动执行那个脚本）。
 */
public final class ZsxqIngestor {

    /** 圈子 id，跟 ZsxqCrawler 里的 GROUP_URL 对应；命令行的第二个参数可以覆盖。 */
    private static final String DEFAULT_GROUP_ID = "51121244585524";

    public static void main(String[] args) throws Exception {
        String inDir = args.length > 0 ? args[0]
                : System.getProperty("user.home") + "/code/demo/JLRADemo/crawl-output/zsxq-eval-b2";
        String groupId = args.length > 1 ? args[1] : DEFAULT_GROUP_ID;

        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(ZsxqIngestConfig.class, ZsxqIngestService.class)) {
            ZsxqIngestService svc = ctx.getBean(ZsxqIngestService.class);

            List<CrawledPost> raw = ZsxqSampleIo.readRawPosts(inDir, ZsxqSampleIo.prettyMapper());
            List<ZsxqCleanedDoc> docs = ZsxqSampleIo.readDocs(inDir, ZsxqSampleIo.prettyMapper());
            System.out.println("读入: 原始帖 " + raw.size() + " 条 + 清洗文档 " + docs.size() + " 篇");

            ZsxqIngestService.IngestStats st = svc.ingest(raw, docs, groupId);

            System.out.println("落库完成:");
            System.out.println("  zsxq_raw_post : " + st.posts() + " 行（读入 " + st.readPosts()
                    + " 条，跨栏目重复 " + (st.readPosts() - st.posts()) + " 条）");
            System.out.println("  zsxq_reply    : " + st.replies() + " 行");
            System.out.println("  cleaned_doc   : " + st.docs() + " 行");
            System.out.println("（重跑是幂等的：raw_post / cleaned_doc 走 upsert，reply 先删后插，行数不该变多）");
        }
    }
}
