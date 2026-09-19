package com.example.domain.zsxq.image;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.example.domain.zsxq.ingest.ZsxqIngestConfig;
import com.example.domain.zsxq.io.ZsxqSampleIo;
import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.ZsxqCleanedDoc;
import com.example.domain.zsxq.vector.ZsxqVectorConfig;

/**
 * S4 图片概括的 CLI 入口：只做 IO 编排 —— 读样本目录、登记图片、调概括、打印统计。
 *
 * <p>用法: java ...ZsxqImageRunner [样本目录] [本批处理多少，默认 20]
 * 例：java ...ZsxqImageRunner crawl-output/zsxq-eval-b2 5
 *
 * <p>动作分开跑，对应 {@link ZsxqImageService} 的各个动作：
 * <pre>
 *   --register   只登记（从原始帖的 imageUrls + 清洗结果的 keepImages 生成 zsxq_image 行）
 *   --describe   只概括（挑 description 为空的图跑视觉模型）
 *   --embed      只补向量（描述已有但 embedding 为空）
 *   --backfill   只回填 alt（把正文 ![](url) 的 alt 换成描述）
 *   （不给参数）  登记 + 概括 一起跑
 * </pre>
 *
 * <p>顺序有依赖：<b>describe 必须先跑完，backfill 才有描述可填</b>。
 * backfill 幂等（改过的不再命中），插在管道末尾即可。
 *
 * <p>前置：
 * <ul>
 *   <li>S3 已落库（cleaned_doc / zsxq_raw_post 有数据）；</li>
 *   <li>库表已建好（schema.sql，含 zsxq_image.doc_id 列）；</li>
 *   <li>{@code DEEPSEEK_WIN_KEY}（视觉模型）与 {@code DASHSCOPE_API_KEY / BAILIAN_API_KEY}
 *       （描述向量化）都在环境里，或写在 {@code ~/.config/JLRADemo/secret.yml}。</li>
 * </ul>
 *
 * <p>轻量上下文：不 boot WebFlux，注册落库配置（数据源）+ 向量配置（embedding 模型）
 * + 图片配置（视觉模型与概括器）+ 概括服务。
 */
public final class ZsxqImageRunner {

    private static final int DEFAULT_LIMIT = 20;

    public static void main(String[] args) throws Exception {
        String inDir = System.getProperty("user.home") + "/code/demo/JLRADemo/crawl-output/zsxq-eval-b2";
        int limit = DEFAULT_LIMIT;
        String action = "";
        // 参数顺序宽松：目录 / 数字 / --动作 三选，按形状识别
        for (String a : args) {
            if (a.startsWith("--")) {
                action = a;
            } else if (a.matches("\\d+")) {
                limit = Integer.parseInt(a);
            } else {
                inDir = a;
            }
        }

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(
                ZsxqIngestConfig.class, ZsxqVectorConfig.class, ZsxqImageConfig.class, ZsxqImageService.class)) {
            ZsxqImageService svc = ctx.getBean(ZsxqImageService.class);

            boolean doRegister = action.isEmpty() || "--register".equals(action);
            boolean doDescribe = action.isEmpty() || "--describe".equals(action);
            boolean doEmbed = "--embed".equals(action);
            boolean doBackfill = "--backfill".equals(action);

            if (doRegister) {
                List<CrawledPost> raw = ZsxqSampleIo.readRawPosts(inDir, ZsxqSampleIo.prettyMapper());
                List<ZsxqCleanedDoc> docs = ZsxqSampleIo.readDocs(inDir, ZsxqSampleIo.prettyMapper());
                Map<String, List<String>> byPost = collectImages(raw);

                int n = svc.registerImages(docs, byPost);
                System.out.println("登记图片: " + n + " 张（来自 " + byPost.size() + " 篇有图的帖）");
                System.out.println("（register 幂等：同一文档的图先删后插，重跑行数不变）");
            }

            if (doDescribe) {
                System.out.println("概括前: 已描述 " + svc.countDescribed() + " 张 / 共 " + svc.countImages() + " 张");
                ZsxqImageService.ImageStats st = svc.describePending(limit);
                System.out.println("本批待处理 " + st.pending() + " 张，成功 " + st.ok() + " 张，失败 " + st.failed() + " 张");
                System.out.println("概括后: 已描述 " + st.described() + " 张 / 共 " + st.total() + " 张");
                if (st.pending() == 0) {
                    System.out.println("没有待概括的图——要么都已描述过，要么还没 register。");
                }
            }

            if (doEmbed) {
                int n = svc.fillMissingEmbeddings(limit);
                System.out.println("补齐向量: " + n + " 张");
            }

            if (doBackfill) {
                int n = svc.backfillAlts(limit);
                System.out.println("回填正文图片 alt: " + n + " 篇文档");
                if (n == 0) {
                    System.out.println("没有需要回填的文档——要么都已经回填过，要么图还没 describe。");
                }
                System.out.println("（回填幂等：改过的正文不再命中「alt 是文件名」的待处理条件）");
            }
        }
    }

    /** 帖子血缘键 → 图片 URL 列表。同一帖的图按出现顺序去重保序。 */
    private static Map<String, List<String>> collectImages(List<CrawledPost> posts) {
        Map<String, List<String>> out = new HashMap<>();
        for (CrawledPost p : posts) {
            if (p.postId == null || p.postId.isBlank() || p.imageUrls == null || p.imageUrls.isEmpty()) {
                continue;
            }
            List<String> urls = out.computeIfAbsent(p.postId, k -> new ArrayList<>());
            for (String u : p.imageUrls) {
                if (u != null && !u.isBlank() && !urls.contains(u)) {
                    urls.add(u);
                }
            }
        }
        return out;
    }
}
