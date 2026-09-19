package com.example.domain.zsxq.model;

import java.util.List;

/**
 * 入库题库条目（对应设计文档 §7 输出 schema）。
 * 由 {@link ZsxqCleaningService} 产出，最终落 PG（S3）+ 百炼 Embedding（S7）。
 */
public class ZsxqCleanedDoc {

    public String docId;
    public String rawPostId;           // 血缘外键 → zsxq_raw_post.post_id（帖子稳定 id，可溯源重跑）
    public String topicKey;
    public String postType;            // tech_article / interview_qa / architecture_note / resource_share / member_post
    public String author;
    public String authorRole;          // 星主 / 星友
    public String publishedAt;
    public String content;
    public List<String> topicTags;
    public double authorityScore;      // 0.9 星主 / 0.1 星友 / 0.3 资源聚合
    public boolean starMasterVerified; // 星主是否给出权威校验
    public String starMasterAnswer;    // interview_qa 时抽取（Q4 待补）
    public String seriesId;            // S5 系列串联
    public String seriesPrev;
    public String seriesNext;
    public boolean keepImages;         // Q1 图片策略：true=保留图片 URL，false=剥离
    public String sourceUrl;
    public String ingestAt;
    public boolean superseded;         // S6 版本抑制：被同 topic_key 新帖覆盖
}
