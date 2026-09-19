package com.example.domain.zsxq.eval;

/**
 * 评估报告里的异常项：点名具体的帖子，便于人工回原文抽查。
 */
public class MetricAnomaly {

    /** 异常类型：POST_ID_MISSING / SOURCE_URL_MISSING / CONTENT_EMPTY / CONTENT_TRUNCATED / CLASSIFY_SUSPECT。 */
    public String kind;
    public String column;
    public String author;
    public String publishedAt;
    /** 具体数值或正文摘要，说明为什么被判为异常。 */
    public String detail;

    public MetricAnomaly(String kind, String column, String author, String publishedAt, String detail) {
        this.kind = kind;
        this.column = column;
        this.author = author;
        this.publishedAt = publishedAt;
        this.detail = detail;
    }
}
