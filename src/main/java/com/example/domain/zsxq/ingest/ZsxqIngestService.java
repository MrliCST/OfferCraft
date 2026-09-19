package com.example.domain.zsxq.ingest;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.example.domain.zsxq.model.CrawledPost;
import com.example.domain.zsxq.model.CrawledReply;
import com.example.domain.zsxq.model.ZsxqCleanedDoc;
import com.example.domain.zsxq.normalize.PostIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * S3 落库：把爬取结果和清洗结果写进 PG（zsxq_raw_post / zsxq_reply / cleaned_doc）。
 *
 * <p>纯逻辑 + 注入，不碰 IO（读文件、建上下文都留给 CLI 入口 {@link ZsxqIngestor}）。
 *
 * <p><b>幂等是硬要求</b>：管道会反复重跑（重爬、改分类规则后重洗），不能越跑越多。
 * <ul>
 *   <li>{@code zsxq_raw_post}：{@code post_id} 上有 UNIQUE，走 ON CONFLICT DO UPDATE；</li>
 *   <li>{@code zsxq_reply}：没有唯一键，按 {@code post_id} 先删后插；</li>
 *   <li>{@code cleaned_doc}：{@code doc_id} 主键，走 ON CONFLICT DO UPDATE。</li>
 * </ul>
 *
 * <p>写入顺序不能反：reply 和 cleaned_doc 的 {@code post_id / raw_post_id} 都是指向
 * {@code zsxq_raw_post(post_id)} 的外键，所以必须先落原始帖。
 */
@Service
public class ZsxqIngestService {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 星主名字，用来判断回复者身份（CrawledReply 里只带昵称，不带角色）。 */
    private static final String STAR_MASTER = "马丁";

    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();

    public ZsxqIngestService(JdbcTemplate zsxqJdbcTemplate) {
        this.jdbc = zsxqJdbcTemplate;
    }

    /** 一次跑完：原始帖 → 回复 → 清洗文档。返回各表写入行数，便于 CLI 打印核对。 */
    public IngestStats ingest(List<CrawledPost> rawPosts, List<ZsxqCleanedDoc> docs, String groupId) {
        // 先把血缘键统一成官方 topic_id：同一篇帖在有的栏目拿到文章页 id、有的栏目拿到 topic_id，
        // 不归一的话库里会存成两行，外键也各指一边
        Map<String, String> remap = PostIdentity.unifyPostIds(rawPosts);
        for (ZsxqCleanedDoc d : docs) {
            if (d.rawPostId != null && remap.containsKey(d.rawPostId)) {
                d.rawPostId = remap.get(d.rawPostId);
            }
        }
        // 再跨栏目去重：同一篇帖在多个栏目各爬到一次，post_id 上 UNIQUE 只留一条
        List<CrawledPost> unique = PostIdentity.dedupe(rawPosts);
        int raw = ingestRawPosts(unique, groupId);
        int replies = ingestReplies(unique);
        int kept = ingestDocs(docs);
        return new IngestStats(rawPosts.size(), unique.size(), replies, kept);
    }

    /** 落原始帖（血缘/审计，不进 RAG）。 */
    int ingestRawPosts(List<CrawledPost> posts, String groupId) {
        String sql = """
                INSERT INTO zsxq_raw_post
                  (group_id, post_id, src_column, author, author_role, published_at, raw_markdown, topic_tags, source_url)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?)
                ON CONFLICT (post_id) DO UPDATE SET
                  group_id = EXCLUDED.group_id,
                  src_column = EXCLUDED.src_column,
                  author = EXCLUDED.author,
                  author_role = EXCLUDED.author_role,
                  published_at = EXCLUDED.published_at,
                  raw_markdown = EXCLUDED.raw_markdown,
                  topic_tags = EXCLUDED.topic_tags,
                  source_url = EXCLUDED.source_url
                """;
        int n = 0;
        for (CrawledPost p : posts) {
            if (p.postId == null || p.postId.isBlank()) {
                continue;   // 没有血缘键的帖不落：后续 doc 外键指不过来，且无法去重
            }
            jdbc.update(sql, groupId, p.postId, p.column, p.author, p.authorRole,
                    toTimestamp(p.publishedAt), p.content, toJson(p.topicTags), p.sourceUrl);
            n++;
        }
        return n;
    }

    /** 落回复。同一帖的回复先删后插，避免重跑时越堆越多。 */
    int ingestReplies(List<CrawledPost> posts) {
        String insert = """
                INSERT INTO zsxq_reply (post_id, commenter, commenter_role, reply_text, reply_time, is_star_master, reply_idx)
                VALUES (?,?,?,?,?,?,?)
                """;
        int n = 0;
        for (CrawledPost p : posts) {
            if (p.postId == null || p.postId.isBlank() || p.replies == null || p.replies.isEmpty()) {
                continue;
            }
            jdbc.update("DELETE FROM zsxq_reply WHERE post_id = ?", p.postId);
            int idx = 0;
            for (CrawledReply r : p.replies) {
                boolean star = r.commenter != null && r.commenter.contains(STAR_MASTER);
                jdbc.update(insert, p.postId, r.commenter,
                        star ? "星主" : "星友", r.text, toTimestamp(r.time), star, idx++);
                n++;
            }
        }
        return n;
    }

    /** 落清洗后的题库文档。 */
    int ingestDocs(List<ZsxqCleanedDoc> docs) {
        String sql = """
                INSERT INTO cleaned_doc
                  (doc_id, topic_key, post_type, author, author_role, published_at, content, question,
                   authority_score, star_master_verified, star_master_answer,
                   series_id, series_prev, series_next, keep_images, raw_post_id, source_url, superseded)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (doc_id) DO UPDATE SET
                  topic_key = EXCLUDED.topic_key,
                  post_type = EXCLUDED.post_type,
                  author = EXCLUDED.author,
                  author_role = EXCLUDED.author_role,
                  published_at = EXCLUDED.published_at,
                  content = EXCLUDED.content,
                  question = EXCLUDED.question,
                  authority_score = EXCLUDED.authority_score,
                  star_master_verified = EXCLUDED.star_master_verified,
                  star_master_answer = EXCLUDED.star_master_answer,
                  series_id = EXCLUDED.series_id,
                  series_prev = EXCLUDED.series_prev,
                  series_next = EXCLUDED.series_next,
                  keep_images = EXCLUDED.keep_images,
                  raw_post_id = EXCLUDED.raw_post_id,
                  source_url = EXCLUDED.source_url,
                  superseded = EXCLUDED.superseded
                """;
        int n = 0;
        for (ZsxqCleanedDoc d : docs) {
            jdbc.update(sql, d.docId, d.topicKey, d.postType, d.author, d.authorRole,
                    toTimestamp(d.publishedAt), d.content, null,   // question 待后续抽取，先置空
                    d.authorityScore, d.starMasterVerified, nullIfBlank(d.starMasterAnswer),
                    d.seriesId, d.seriesPrev, d.seriesNext, d.keepImages,
                    nullIfBlank(d.rawPostId), d.sourceUrl, d.superseded);
            n++;
        }
        return n;
    }

    /** 日期字符串 → Timestamp。解析不了就给 NULL，不让一条脏时间卡住整批。 */
    private static Timestamp toTimestamp(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Timestamp.valueOf(LocalDateTime.parse(s.trim(), TS_FMT));
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return om.writeValueAsString(o == null ? List.of() : o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 本次落库的条目数，给 CLI 打印用。
     *
     * @param readPosts 读入的原始条数（含跨栏目重复）
     * @param posts     去重后的独立帖数（= zsxq_raw_post 行数）
     * @param replies   回复行数
     * @param docs      入库文档数
     */
    public record IngestStats(int readPosts, int posts, int replies, int docs) {
    }
}
