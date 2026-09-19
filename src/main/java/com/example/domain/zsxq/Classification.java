package com.example.domain.zsxq;

/**
 * S2 分类闸的输出：一篇帖子归到的类型 + 抽取字段 + 权威分/校验。
 *
 * <p>权威分与 star_master_verified 由 post_type 确定性推导（见 {@link PostType}），
 * 不信任 LLM 自由输出，保证可复现；LLM 只负责判类型与抽取文本字段。
 *
 * <p>drop：是否直接丢弃（不入库）。off_topic 即丢弃，其余保留。
 */
public class Classification {

    public final PostType postType;
    public final boolean drop;
    public final String reason;
    /** interview_qa 时抽取的星主权威回答原文；其它类型为空串。 */
    public final String starMasterAnswer;
    /** architecture_note 时抽取的架构/选型金句原文（Q4）；其它类型为空串。 */
    public final String architectureQuote;
    public final double authorityScore;
    public final boolean starMasterVerified;

    public Classification(PostType postType, String reason, String starMasterAnswer, String architectureQuote) {
        this.postType = postType;
        this.drop = postType != null && postType.isDrop();
        this.reason = reason == null ? "" : reason;
        this.starMasterAnswer = starMasterAnswer == null ? "" : starMasterAnswer;
        this.architectureQuote = architectureQuote == null ? "" : architectureQuote;
        this.authorityScore = postType == null ? 0.0 : postType.defaultAuthority();
        this.starMasterVerified = postType != null && postType.defaultVerified();
    }

    @Override
    public String toString() {
        return "Classification{" + postType + ", drop=" + drop + ", authority=" + authorityScore
                + ", verified=" + starMasterVerified + ", reason='" + reason + "'}";
    }
}
