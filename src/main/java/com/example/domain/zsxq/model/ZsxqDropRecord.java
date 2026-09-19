package com.example.domain.zsxq.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 丢弃追踪记录（off_topic 等被分类闸丢弃的帖子，不入库，仅留痕便于复盘）。
 *
 * <p>会被写进 drops.json 再读回来做评估复盘，所以需要能被 Jackson 反序列化
 * ——只有带参构造器时 Jackson 造不出对象，故显式标注 {@link JsonCreator}。
 */
public class ZsxqDropRecord {

    public String column;
    public String author;
    public String publishedAt;
    public String reason;

    @JsonCreator
    public ZsxqDropRecord(@JsonProperty("column") String column,
                          @JsonProperty("author") String author,
                          @JsonProperty("publishedAt") String publishedAt,
                          @JsonProperty("reason") String reason) {
        this.column = column;
        this.author = author;
        this.publishedAt = publishedAt;
        this.reason = reason;
    }
}
