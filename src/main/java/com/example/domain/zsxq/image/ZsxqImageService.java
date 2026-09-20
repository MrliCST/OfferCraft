package com.example.domain.zsxq.image;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.domain.zsxq.model.ZsxqCleanedDoc;
import com.example.domain.zsxq.normalize.ContentImages;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ImageContent;

/**
 * S4 图片概括：把 {@code zsxq_image} 里还没描述的图挑出来，逐张交给
 * {@link ZsxqImageDescriberAi} 生成中文描述，写回 {@code description}。
 *
 * <p><b>这是一条独立于正文的异步支线</b>。正文主干道是
 * 「S1 抽图 → S3 落库 → S7 文本向量化」，图片走的是
 * 「register 登记 → describe 概括 → embed 向量化」，两条路互不阻塞：
 * 正文里的图片只留标识 {@code ![图片.png](url)}，<b>不回填描述</b>、不因此重算 chunk。
 *
 * <p>为什么描述不回填正文（2026-09-19 拍的）：
 * <ul>
 *   <li>回填要改正文 ⇒ 已算好的 chunk 作废 ⇒ 必须重跑向量化，是纯浪费；</li>
 *   <li>一张图讲 A、周围文字讲 B，描述混进块里反而把块的语义中心搅浑，
 *       不如让图片描述作为<b>独立向量</b>参与召回，向量中心就是这张图本身；</li>
 *   <li>{@code ![图片.png](url)} 这串字符在 900 字的块里噪音占比很低，
 *       对文本召回的影响可以忽略。</li>
 * </ul>
 * 所以图片的召回通路是 {@code zsxq_image.embedding}（描述向量）+ 独立的检索适配层，
 * 不是靠正文顺带召回。
 *
 * <p>三步都幂等、都能单独重跑：
 * <ol>
 *   <li><b>登记</b>（{@link #registerImages}）：把该留的图写进 {@code zsxq_image}，
 *       此时 {@code description} 为空；</li>
 *   <li><b>概括</b>（{@link #describePending}）：只挑 {@code description IS NULL} 的图跑模型，
 *       断了再跑会接着上次的继续，不会重复花钱；</li>
 *   <li><b>补向量</b>（{@link #fillMissingEmbeddings}）：挑 {@code embedded_at IS NULL}
 *       但有描述的图，补齐向量 —— 概括时 embedding 接口抖了就靠这步兜。</li>
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

    /**
     * {@code embeddingModel} 标 {@link Lazy}：它只在「落描述时顺手做向量」和
     * {@link #fillMissingEmbeddings} 这两条路上用得到，而 {@link #registerImages} /
     * {@code countImages} 这类纯库操作根本不需要它。
     * 不懒加载的话，跑一次纯登记也得先有个能用的百炼密钥 —— 明明不调 embedding 接口，
     * 却因为建不出 bean 而失败，是很别扭的耦合。
     */
    public ZsxqImageService(JdbcTemplate zsxqJdbcTemplate,
                            ZsxqImageDescriberAi zsxqImageDescriber,
                            ZsxqImageContentFactory zsxqImageContentFactory,
                            @Lazy EmbeddingModel zsxqEmbeddingModel) {
        this.jdbc = zsxqJdbcTemplate;
        this.describer = zsxqImageDescriber;
        this.imageContentFactory = zsxqImageContentFactory;
        this.embeddingModel = zsxqEmbeddingModel;
    }

    /**
     * 把清洗结果里该保留的图片登记进 {@code zsxq_image}。
     *
     * <p>{@code doc.keepImages} 为 false 的文档一张都不登记（Q1 图片策略：星友图全剥离）。
     *
     * <p><b>重跑要保住已花的钱</b>：图可能被作者删掉、或新加进来，所以同一文档的图
     * 还是要「先删后插」来反映最新状态；但描述和向量是调视觉模型换来的（慢、按量计费），
     * <b>同一 URL 的既有描述不能因为一次 register 就丢</b>。
     * 做法是先按 URL 快照旧的 {@code description / embedding / embedded_at}，
     * 重新插入时把能对上的填回去 —— 只有真正新增的图（或换了 URL 的图）才需要重新概括。
     *
     * <p>踩过的坑：最初是纯「先删后插」，跑一次 register 就把 25 张图的描述全清了，
     * 等于白花一遍模型钱。上面的快照回填就是为此加的。
     *
     * @param docs 清洗后的文档（已带 docId / rawPostId / keepImages）
     * @param imagesByPostId 帖子血缘键 → 图片 URL 列表
     * @return 登记的图片行数
     */
    public int registerImages(List<ZsxqCleanedDoc> docs, Map<String, List<String>> imagesByPostId) {
        String insert = """
                INSERT INTO zsxq_image (post_id, doc_id, seq, original_url, kind, kept, description, embedding, embedded_at)
                VALUES (?,?,?,?,?,?,?,?::vector,?)
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
            // 先快照：url → 已算好的描述/向量，重插时按 URL 填回，避免重复调模型
            Map<String, ExistingImage> existing = existingByUrl(d.docId);
            jdbc.update("DELETE FROM zsxq_image WHERE doc_id = ?", d.docId);
            int seq = 0;
            for (String url : urls) {
                if (url == null || isNonContent(url)) {
                    continue;
                }
                ExistingImage old = existing.get(url);
                jdbc.update(insert, d.rawPostId, d.docId, seq++, url, null, true,
                        old == null ? null : old.description(),
                        old == null || old.embeddingLiteral() == null ? null : old.embeddingLiteral(),
                        old == null ? null : old.embeddedAt());
                n++;
            }
        }
        return n;
    }

    /** 该文档现有图片的 url → 描述/向量快照，供 register 重插时回填。 */
    private Map<String, ExistingImage> existingByUrl(String docId) {
        return jdbc.query(
                "SELECT original_url, description, embedding::text AS embedding_text, embedded_at "
                        + "FROM zsxq_image WHERE doc_id = ? AND original_url IS NOT NULL",
                rs -> {
                    Map<String, ExistingImage> m = new java.util.HashMap<>();
                    while (rs.next()) {
                        m.put(rs.getString("original_url"), new ExistingImage(
                                rs.getString("description"),
                                rs.getString("embedding_text"),
                                rs.getTimestamp("embedded_at")));
                    }
                    return m;
                },
                docId);
    }

    /** register 重插时要保留的既有状态（同一 URL 的老行）。 */
    private record ExistingImage(String description, String embeddingLiteral, java.sql.Timestamp embeddedAt) {
    }

    /**
     * 表情/图标/内联 data URI 等非正文图，不登记。
     *
     * <p><b>已降级为兜底断言</b>：主要防线在 S1 —— {@code ZsxqArticleFetcher} 抽长文图、
     * {@link com.example.domain.zsxq.normalize.HtmlToMarkdown} 已经在抽图/转 Markdown 时就
     * 剔除了非正文图，正常数据流到这里不会有表情。留这一道是为了防「别处喂进来的脏列表」
     * （比如历史落盘的 JSON、或将来新增的采集入口），出现即说明上游有漏。
     *
     * <p>判定逻辑统一住在 {@link ContentImages}，此处只是取反 —— 避免同一个事实记两份、
     * 改一处忘一处。
     */
    public static boolean isNonContent(String url) {
        return !ContentImages.isContentImage(url);
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
            // 描述落库但向量留空 —— embedded_at 保持 NULL，等 --embed 补
            jdbc.update("UPDATE zsxq_image SET description = ? WHERE id = ?", desc, id);
            return;
        }
        // embedding 列是 vector(1024)，必须显式 ::vector 转换，否则驱动按 text 发过去类型对不上
        jdbc.update("UPDATE zsxq_image SET description = ?, embedding = ?::vector, embedded_at = now() WHERE id = ?",
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

    /**
     * 补齐「有描述、还没向量」的图 —— 异步支线的断点续跑入口。
     *
     * <p>判据用 {@code embedded_at IS NULL} 而不是 {@code embedding IS NULL}：
     * 后者无法区分「已 describe 但向量没做」和「压根还没 describe」两种情况，
     * 会把还没概括的图也捞进来（那时没有描述可 embed）。{@code embedded_at} 与
     * {@code zsxq_chunk.embedded_at} 语义一致，全管道就一个「待办」口径。
     *
     * @param limit 本批最多补多少张
     * @return 实际补上的张数
     */
    public int fillMissingEmbeddings(int limit) {
        String sql = """
                SELECT id, description FROM zsxq_image
                WHERE description IS NOT NULL AND embedded_at IS NULL
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
                jdbc.update("UPDATE zsxq_image SET embedding = ?::vector, embedded_at = now() WHERE id = ?",
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

    /** 已向量化的图片张数（{@code embedded_at} 非空）。 */
    public int countEmbedded() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM zsxq_image WHERE embedded_at IS NOT NULL", Integer.class);
    }

    /** 待补向量的图片张数（有描述、还没向量）—— 断点续跑时看这个决定还剩多少活。 */
    public int countPendingEmbedding() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM zsxq_image WHERE description IS NOT NULL AND embedded_at IS NULL",
                Integer.class);
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
