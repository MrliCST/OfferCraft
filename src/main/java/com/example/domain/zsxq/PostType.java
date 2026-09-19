package com.example.domain.zsxq;

/**
 * S2 分类闸的输出类型（对齐设计文档 §2）。
 *
 * <p>与 schema（§12）的对应：cleaned_doc.post_type 取值为
 * tech_article / interview_qa / architecture_note / resource_share / member_post，
 * 聚合后的资源合集单独记为 resource_share_aggregate（S7 生成，非分类输出）；
 * off_topic 表示与面试/项目无直接价值的无关或低价值内容，直接丢弃（不入库）。
 */
public enum PostType {

    /** 星主结构化技术长文：最高价值，保留图片，做系列串联。权威 0.9。 */
    TECH_ARTICLE,

    /** 星友发问 + 星主实质性权威回答：星友问题作上下文(0.1)，星主答案为权威(0.9)。 */
    INTERVIEW_QA,

    /** 星主含架构/选型/方向性信息（含吐槽外壳的金句）：权威 0.9。Q4 抽取金句。 */
    ARCHITECTURE_NOTE,

    /** 星友分享开源/外链资源：不单篇入库，聚合成一篇低权汇总(0.3)。 */
    RESOURCE_SHARE,

    /** 星友原创（非明显无关）：低权保留(0.1)，不丢弃（§3 决策）。 */
    MEMBER_POST,

    /** 无关/低价值（IDEA 快捷键、避雷、纯上岸吹水、星主无干货吐槽）：直接丢弃。 */
    OFF_TOPIC;

    /** 是否直接丢弃（不入库）。 */
    public boolean isDrop() {
        return this == OFF_TOPIC;
    }

    /** 默认权威分（设计文档 §3）：星主原创/权威答 0.9，星友问/原创 0.1，资源聚合 0.3。 */
    public double defaultAuthority() {
        return switch (this) {
            case TECH_ARTICLE, ARCHITECTURE_NOTE -> 0.9;
            case INTERVIEW_QA -> 0.1;       // 帖子本体 0.1；star_master_answer 才是 0.9
            case RESOURCE_SHARE -> 0.3;     // Q2 聚合低权
            case MEMBER_POST -> 0.1;        // §3 低权保留
            case OFF_TOPIC -> 0.0;
        };
    }

    /** 星主是否给出权威校验（用于 interview_qa 的 star_master_verified）。 */
    public boolean defaultVerified() {
        return this == TECH_ARTICLE || this == INTERVIEW_QA || this == ARCHITECTURE_NOTE;
    }

    /** 容错解析 LLM 返回的字符串（忽略大小写/空格/连字符）。非法值返回 null。 */
    public static PostType from(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.trim().toLowerCase().replace(' ', '_').replace('-', '_')) {
            case "tech_article", "techarticle" -> TECH_ARTICLE;
            case "interview_qa", "interviewqa" -> INTERVIEW_QA;
            case "architecture_note", "architecturenote" -> ARCHITECTURE_NOTE;
            case "resource_share", "resourceshare" -> RESOURCE_SHARE;
            case "member_post", "memberpost" -> MEMBER_POST;
            case "off_topic", "offtopic" -> OFF_TOPIC;
            default -> null;
        };
    }
}
