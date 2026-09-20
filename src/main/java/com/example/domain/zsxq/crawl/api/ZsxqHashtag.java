package com.example.domain.zsxq.crawl.api;

/** 圈子里的一个话题标签（前端侧边栏的"栏目"在接口侧的对应物）。 */
public class ZsxqHashtag {

    /** hashtag id，按标签拉列表时的路径参数。 */
    public String hashtagId;

    /** 标签名，带 emoji（如 {@code 🌈面试相关}）。接口返回时两侧带 #，已去掉。 */
    public String name;

    /** 该标签下有多少篇。爬之前就能估出规模，决定要不要设上限。 */
    public int topicCount;

    public ZsxqHashtag() {
    }

    public ZsxqHashtag(String hashtagId, String name, int topicCount) {
        this.hashtagId = hashtagId;
        this.name = name;
        this.topicCount = topicCount;
    }
}
