package com.example.domain.zsxq.image;

/**
 * 一条待概括的图片记录（{@code zsxq_image} 的一行）。
 *
 * <p>只带概括所需的字段：主键 + 图片地址 + 归属信息。
 * 归属信息（post_type / author_role / authority_score）不参与概括，但要跟着图一起
 * 落到描述外侧，供检索时加权——沿用 chunk 那套「命中后回关联取权威分」的思路。
 *
 * @param id           zsxq_image 主键
 * @param postId       所属帖子的血缘键
 * @param originalUrl  图片原始地址
 * @param kind         图片类型（由清洗阶段 S4 白名单判定，可空）
 * @param postType     所属文档的 post_type
 * @param authorRole   所属文档的作者角色（星主/星友）
 */
public record ZsxqImageRow(
        long id,
        String postId,
        String originalUrl,
        String kind,
        String postType,
        String authorRole) {
}
