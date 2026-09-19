package com.example.domain.zsxq.model;

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

    /**
     * interview_qa 的权威来源不在帖子本体（那是星友的提问），而在马丁的回答里。
     * 马丁给了实质回答，这一篇就按 0.9 计——检索命中它时，用户真正拿到的是马丁的权威解答。
     * 「实质」沿用分类闸的口径：回答 >= 60 字，短到「可以的，等几天」那种不算。
     */
    private static final int SUBSTANTIVE_ANSWER_LEN = 60;

    public Classification(PostType postType, String reason, String starMasterAnswer, String architectureQuote) {
        this.postType = postType;
        this.drop = postType != null && postType.isDrop();
        this.reason = reason == null ? "" : reason;
        this.starMasterAnswer = starMasterAnswer == null ? "" : starMasterAnswer;
        this.architectureQuote = architectureQuote == null ? "" : architectureQuote;
        boolean hasAuthoritativeAnswer = postType == PostType.INTERVIEW_QA
                && this.starMasterAnswer.length() >= SUBSTANTIVE_ANSWER_LEN;
        this.authorityScore = hasAuthoritativeAnswer ? 0.9
                : (postType == null ? 0.0 : postType.defaultAuthority());
        this.starMasterVerified = postType != null && postType.defaultVerified();
    }

    @Override
    public String toString() {
        return "Classification{" + postType + ", drop=" + drop + ", authority=" + authorityScore
                + ", verified=" + starMasterVerified + ", reason='" + reason + "'}";
    }
}
