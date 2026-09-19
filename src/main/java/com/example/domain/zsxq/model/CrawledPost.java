package com.example.domain.zsxq.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 爬虫产出的帖子（S0 采集结果），是 crawl → classify → clean 三个阶段之间的数据契约。
 * 字段与清洗管道设计文档对齐：topic_key / published_at / authority 预留给清洗阶段赋值。
 */
public class CrawledPost {

    public String column;
    public String postId;               // 帖子 ID（从 /topic/<id> 解析），对应 raw_post.post_id
    public String sourceUrl;            // 详情页 URL，对应 cleaned_doc.source_url
    public String author;
    public String authorRole;          // 星主 / 星友
    public String publishedAt;
    public String content;
    public List<String> topicTags = new ArrayList<>();
    public List<String> likeUsers = new ArrayList<>();
    public boolean starMasterReplied;   // Rule 2 信号：星主是否回复
    public List<String> imageUrls = new ArrayList<>();  // 正文 + 回复图片 src（Q1 在清洗阶段过滤）
    public List<CrawledReply> replies = new ArrayList<>();
    // 清洗阶段填充
    public String topicKey;             // e.g. RagentAI / 技术问答
    public double authorityScore;       // 0.9 星主 / 0.1 星友（待清洗管道赋值）
}
