package com.example.domain.zsxq.classify;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * S2 分类闸的 LLM 接口（langchain4j AiService）。
 * 由 Spring 按 {@code deepseekClassifyModel}（低温度）自动生成实现并注册为 bean；
 * 单测里也用 {@code AiServices.create(...)} 程序化构造（同一个接口，两套装配方式）。
 *
 * <p>方法返回原始 JSON 字符串，由 {@link LangchainTopicGuard} 负责解析与兜底——
 * 不依赖框架的 POJO 自动解析，跨模型更稳，也方便用 fake 模型做单测。
 *
 * <p>few-shot 来自老板的 cankao.md 七类标注（参考一~七）。
 */
@AiService(
    wiringMode = AiServiceWiringMode.EXPLICIT,
    chatModel = "deepseekClassifyModel",
    streamingChatModel = "deepseekStreamModel"
)
public interface TopicGuardAi {

    @SystemMessage("""
        你是知识星球「拿个offer-开源&项目实战-马丁」帖子的分类闸（Topic Guard）。
        目标：把每篇帖子归到唯一类型，并抽取面试 agent 题库需要的字段。

        ## 类型定义（post_type 只能取以下之一）
        - tech_article：星主（马丁）发布的结构化技术长文（含代码块/标题/多段落/图片/链接）。最高价值，保留图片，做系列串联。
        - interview_qa：星友发问 + 马丁给出实质性权威回答（回答较长、有技术细节）。星友问题作上下文，星主答案为权威答案。
        - architecture_note：星主发布的、含架构/技术选型/项目方向性信息的内容（即使带吐槽外壳，如「2.0 基于 AgentScope」）。抽取金句。
        - resource_share：星友分享开源项目/外链/工具（马丁常「加精」）。不单篇入库，会聚合为一篇低权汇总。
        - member_post：星友原创分享（非上面几类、非明显无关）。低权保留。
        - off_topic：与面试/项目无直接价值的无关或低价值内容——IDEA 快捷键等无关技术分享、避雷贴、纯上岸吹水、星主无干货吐槽。直接丢弃。

        ## 关键校准
        - 马丁「点赞/加精」≠ 进题库；只有对面试/项目有直接或高参考价值的才进。
        - 星友帖默认低权保留（member_post），除非是明确无关（off_topic 才丢弃）。
        - 进题库硬标准：对面试/项目有直接价值。

        ## 抽取字段
        - star_master_answer：当 post_type=interview_qa 时，填马丁那段实质性权威回答的原文（尽量完整）；其它类型填空串 ""。
        - architecture_quote：当 post_type=architecture_note 时，提取含架构/选型/方向的关键句原文；其它类型填空串 ""。

        ## 输出格式（严格只输出一个 JSON 对象，不要任何解释、不要 Markdown 代码块）
        {
          "post_type": "tech_article|interview_qa|architecture_note|resource_share|member_post|off_topic",
          "reason": "一句话分类理由",
          "star_master_answer": "",
          "architecture_quote": ""
        }

        ## few-shot 示例
        例1（interview_qa）：星友发「企业级 RAG 项目怎么做的？意图识别树怎么构建？澄清触发条件是什么？」+ 马丁长篇点评给建议 → post_type=interview_qa，star_master_answer=马丁点评原文。
        例2（resource_share）：星友发「发现宝藏开源项目 GitHub - xxx/Understand-Anything」+ 马丁「加精啦」→ post_type=resource_share。
        例3（off_topic）：星友发实习被坑避雷经历、劝大家避坑 → post_type=off_topic（个人经历不同，成本高就算了）。
        例4（off_topic）：星友发「上岸了北京某大厂，分享面经」纯上岸吹水 → post_type=off_topic。
        例5（architecture_note）：马丁吐槽「Ragent 2.0 基于 AgentScope 重构」含方向金句 → post_type=architecture_note，architecture_quote=金句原文。
        例6（tech_article）：马丁发带代码/图/链接的结构化长文讲 RAG 落地 → post_type=tech_article。
        例7（off_topic）：星友分享 IDEA 快捷键、马丁点赞 → post_type=off_topic（跟面试项目无关）。
        """)
    @UserMessage("待分类帖子（JSON）：\n{{post}}")
    String classify(@V("post") String postJson);
}
