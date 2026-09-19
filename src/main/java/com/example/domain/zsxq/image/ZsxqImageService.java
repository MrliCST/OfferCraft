package com.example.domain.zsxq.image;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.domain.zsxq.model.ZsxqCleanedDoc;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ImageContent;

/**
 * S4 图片概括：把 {@code zsxq_image} 里还没描述的图挑出来，逐张交给
 * {@link ZsxqImageDescriberAi} 生成中文描述，写回 {@code description}。
 *
 * <p>分两步、两步都幂等，可以分别重跑：
 * <ol>
 *   <li><b>登记</b>（{@link #registerImages}）：从清洗结果里把该留的图写进 {@code zsxq_image}，
 *       此时 {@code description} 为空；</li>
 *   <li><b>概括</b>（{@link #describePending}）：只挑 {@code description IS NULL} 的图跑模型，
 *       断了再跑会接着上次的继续，不会重复花钱。</li>
 * </ol>
 *
 * <p>为什么登记和概括不合成一步：概括要调多模态模型（慢、按量计费），
 * 而登记只是写库。分开后可以先入库看看「一共多少张图」再决定跑多少，
 * 也避免概括失败导致整条清洗链重来。
 *
 * <p>单张图失败（网络拉不到、模型拒答、返回空）<b>不中断整批</b>，只记日志并跳过——
 * 留到下次重跑捡起来。这与 S7 向量化「一篇失败不拖垮一批」的处理保持一致。
 */
@Service
public class ZsxqImageService {

    private static final Logger log = LoggerFactory.getLogger(ZsxqImageService.class);

    /**
     * 站点静态资源前缀 —— 表情、图标这类不算正文内容，登记进来只会白花钱概括。
     *
     * <p>实测样本里 39 张「图」有 5 张是 {@code wx.zsxq.com/assets_dweb/images/emoji/抱拳.png}
     * 这种表情图。正文图来自 {@code images.zsxq.com} / {@code article-images.zsxq.com}，
     * 静态资源则统一挂在 {@code wx.zsxq.com/assets*} 下。
     */
    private static final List<String> NON_CONTENT_PREFIXES = List.of(
            "https://wx.zsxq.com/assets",
            "http://wx.zsxq.com/assets",
            "data:");

    /** 待概括的图：已有地址、还没描述。 */
    private static final String PENDING_IMAGES = """
            SELECT i.id, i.post_id, i.original_url, i.kind,
                   d.post_type, d.author_role
            FROM zsxq_image i
            LEFT JOIN cleaned_doc d ON d.doc_id = i.doc_id
            WHERE i.original_url IS NOT NULL
              AND i.description IS NULL
            ORDER BY i.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final ZsxqImageDescriberAi describer;
    private final ZsxqImageContentFactory imageContentFactory;
    private final EmbeddingModel embeddingModel;

    public ZsxqImageService(JdbcTemplate zsxqJdbcTemplate,
                            ZsxqImageDescriberAi zsxqImageDescriber,
                            ZsxqImageContentFactory zsxqImageContentFactory,
                            EmbeddingModel zsxqEmbeddingModel) {
        this.jdbc = zsxqJdbcTemplate;
        this.describer = zsxqImageDescriber;
        this.imageContentFactory = zsxqImageContentFactory;
        this.embeddingModel = zsxqEmbeddingModel;
    }

    /**
     * 把清洗结果里该保留的图片登记进 {@code zsxq_image}。
     *
     * <p>{@code doc.keepImages} 为 false 的文档一张都不登记（Q1 图片策略：星友图全剥离）。
     * 同一帖的图先删后插，保证重跑不堆积——与 {@code ZsxqIngestService.ingestReplies} 同一套路。
     *
     * @param docs 清洗后的文档（已带 docId / rawPostId / keepImages）
     * @param imagesByPostId 帖子血缘键 → 图片 URL 列表
     * @return 登记的图片行数
     */
    public int registerImages(List<ZsxqCleanedDoc> docs, Map<String, List<String>> imagesByPostId) {
        String insert = """
                INSERT INTO zsxq_image (post_id, doc_id, seq, original_url, kind, kept)
                VALUES (?,?,?,?,?,?)
                """;
        int n = 0;
        for (ZsxqCleanedDoc d : docs) {
            if (!d.keepImages || d.rawPostId == null || d.rawPostId.isBlank()) {
                continue;
            }
            List<String> urls = imagesByPostId.get(d.rawPostId);
            if (urls == null || urls.isEmpty()) {
                continue;
            }
            // 先删：内容不变时 URL 不变，但图可能被作者删掉；重跑要能反映最新状态
            jdbc.update("DELETE FROM zsxq_image WHERE doc_id = ?", d.docId);
            int seq = 0;
            for (String url : urls) {
                if (url == null || isNonContent(url)) {
                    continue;
                }
                jdbc.update(insert, d.rawPostId, d.docId, seq++, url, null, true);
                n++;
            }
        }
        return n;
    }

    /** 表情/图标/内联 data URI 等非正文图，不登记。 */
    public static boolean isNonContent(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        for (String prefix : NON_CONTENT_PREFIXES) {
            if (url.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 概括最多 {@code limit} 张待处理图片，并把描述向量化后一起写入。
     *
     * <p>描述文本会同时作为下一轮检索的依据，所以写完立刻做 embedding，
     * 不留「有描述没向量」的中间态。
     */
    public ImageStats describePending(int limit) {
        List<ZsxqImageRow> rows = jdbc.query(PENDING_IMAGES,
                (rs, i) -> new ZsxqImageRow(
                        rs.getLong("id"),
                        rs.getString("post_id"),
                        rs.getString("original_url"),
                        rs.getString("kind"),
                        rs.getString("post_type"),
                        rs.getString("author_role")),
                limit);

        int ok = 0;
        int failed = 0;
        for (ZsxqImageRow row : rows) {
            try {
                String desc = describeOne(row);
                if (desc == null || desc.isBlank()) {
                    // finish_reason=length 时模型只输出了思考过程、正文为空。记下来，下次重跑再试
                    log.warn("图片概括返回空（id={}，可能是 reasoning 吃光 token）：{}", row.id(), row.originalUrl());
                    failed++;
                    continue;
                }
                writeDescription(row.id(), desc, embedOrNull(desc));
                ok++;
            } catch (Exception e) {
                // 单张失败不拖垮整批：可能是模型拉不到这张图、或网络抖动
                log.warn("图片概括失败（id={}）：{}", row.id(), e.getMessage());
                failed++;
            }
        }
        return new ImageStats(rows.size(), ok, failed, countDescribed(), countImages());
    }

    /** 调模型概括一张图，附上所属帖子的上下文，帮助模型判断图的用途。 */
    private String describeOne(ZsxqImageRow row) {
        ImageContent content = imageContentFactory.from(row.originalUrl());
        String desc = describer.describe(content);
        return desc == null ? null : desc.trim();
    }

    /**
     * 描述向量化。失败返回 null（描述照样落库）——向量可以事后单独补，
     * 不能因为 embedding 接口抖一下就把已经花钱拿到的描述丢了。
     */
    private Embedding embedOrNull(String desc) {
        try {
            return embeddingModel.embed(desc).content();
        } catch (Exception e) {
            log.warn("图片描述向量化失败，先落描述，向量留待重跑：{}", e.getMessage());
            return null;
        }
    }

    private void writeDescription(long id, String desc, Embedding embedding) {
        if (embedding == null) {
            jdbc.update("UPDATE zsxq_image SET description = ? WHERE id = ?", desc, id);
            return;
        }
        // embedding 列是 vector(1024)，必须显式 ::vector 转换，否则驱动按 text 发过去类型对不上
        jdbc.update("UPDATE zsxq_image SET description = ?, embedding = ?::vector WHERE id = ?",
                desc, toVectorLiteral(embedding), id);
    }

    /** {@code float[]} → pgvector 字面量 {@code [v1,v2,...]}。 */
    static String toVectorLiteral(Embedding embedding) {
        StringBuilder sb = new StringBuilder("[");
        float[] v = embedding.vector();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    /** 已概括、但向量还空着的图（供单独补向量）。 */
    public int fillMissingEmbeddings(int limit) {
        String sql = """
                SELECT id, description FROM zsxq_image
                WHERE description IS NOT NULL AND embedding IS NULL
                ORDER BY id LIMIT ?
                """;
        List<Object[]> rows = jdbc.query(sql,
                (rs, i) -> new Object[]{rs.getLong("id"), rs.getString("description")}, limit);
        int n = 0;
        for (Object[] r : rows) {
            String desc = Objects.toString(r[1], "");
            if (desc.isBlank()) {
                continue;
            }
            Embedding e = embedOrNull(desc);
            if (e != null) {
                jdbc.update("UPDATE zsxq_image SET embedding = ?::vector WHERE id = ?",
                        toVectorLiteral(e), r[0]);
                n++;
            }
        }
        return n;
    }

    public int countImages() {
        return jdbc.queryForObject("SELECT count(*) FROM zsxq_image", Integer.class);
    }

    public int countDescribed() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM zsxq_image WHERE description IS NOT NULL", Integer.class);
    }

    /**
     * 本次图片概括的结果。
     *
     * @param pending 本批取出的待处理张数
     * @param ok      成功概括并落库的张数
     * @param failed  失败的张数（留待重跑）
     * @param described 累计已概括张数
     * @param total     库内图片总张数
     */
    public record ImageStats(int pending, int ok, int failed, int described, int total) {
    }
}
