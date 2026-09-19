package com.example.domain.zsxq.classify;

import com.example.domain.zsxq.model.Classification;
import com.example.domain.zsxq.model.CrawledPost;

/**
 * S2 分类闸接口（Topic Guard）。输入一篇爬取的帖子，输出结构化 {@link Classification}。
 *
 * <p>两个实现：
 * <ul>
 *   <li>{@link HeuristicTopicGuard}：正则/关键词启发式，离线、确定性，作为兜底与 CLI 默认。</li>
 *   <li>{@link LangchainTopicGuard}：langchain4j AiService（DeepSeek），few-shot 来自 cankao 七类，
 *       合并 Q4 金句抽取；解析失败/越界时回退启发式（设计文档 §8 Q4 兜底）。</li>
 * </ul>
 */
public interface TopicGuard {
    Classification classify(CrawledPost post);
}
