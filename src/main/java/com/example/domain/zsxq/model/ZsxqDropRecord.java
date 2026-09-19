package com.example.domain.zsxq.model;

/**
 * 丢弃追踪记录（off_topic 等被分类闸丢弃的帖子，不入库，仅留痕便于复盘）。
 */
public class ZsxqDropRecord {

    public String column;
    public String author;
    public String publishedAt;
    public String reason;

    public ZsxqDropRecord(String column, String author, String publishedAt, String reason) {
        this.column = column;
        this.author = author;
        this.publishedAt = publishedAt;
        this.reason = reason;
    }
}
