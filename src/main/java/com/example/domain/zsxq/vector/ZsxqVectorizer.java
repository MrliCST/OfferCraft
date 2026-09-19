package com.example.domain.zsxq.vector;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.example.domain.zsxq.ingest.ZsxqIngestConfig;

/**
 * S7 向量化的 CLI 入口：只做「起上下文 + 调一次 + 打印」，不掺业务逻辑。
 *
 * <p>用法: java ...ZsxqVectorizer [本批最多处理多少篇，默认 50]
 * 例：java ...ZsxqVectorizer 10
 *
 * <p>前置：
 * <ul>
 *   <li>S3 已落库（cleaned_doc 有数据），本命令只读它、只写 zsxq_chunk；</li>
 *   <li>库表已建好（src/main/resources/db/schema.sql）；</li>
 *   <li>PG_* 和 DASHSCOPE_API_KEY 都在环境里（或写在 ~/.config/JLRADemo/secret.yml）。</li>
 * </ul>
 *
 * <p>断点续跑：只挑「没有块」或「有块没嵌入」的文档，所以中断后再跑一次会接着来，
 * 已经向量化过的不会重算。
 */
public final class ZsxqVectorizer {

    private static final int DEFAULT_LIMIT = 50;

    public static void main(String[] args) {
        int limit = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_LIMIT;

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(
                ZsxqIngestConfig.class, ZsxqVectorConfig.class, ZsxqVectorService.class)) {
            ZsxqVectorService svc = ctx.getBean(ZsxqVectorService.class);

            int before = svc.countEmbedded();
            System.out.println("向量化前：已嵌入 " + before + " 块 / 共 " + svc.countChunks() + " 块");

            ZsxqVectorService.VectorizeStats st = svc.vectorize(limit);

            System.out.println("本批处理 " + st.docs() + " 篇");
            System.out.println("向量化后：共 " + st.chunks() + " 块，其中已嵌入 " + st.embedded() + " 块");
            if (st.docs() == 0) {
                System.out.println("没有待处理的文档——要么都已经向量化过了，要么 cleaned_doc 是空的。");
            }
        }
    }
}
