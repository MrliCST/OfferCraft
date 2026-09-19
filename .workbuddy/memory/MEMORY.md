# JLRADemo 长期项目笔记

## 代码风格 / 架构约定（用户明确要求）
- **必须贴合 Spring Boot 项目规范，不要把代码一窝蜂挤到一起。**
  - Bean 的创建/装配放在 `@Configuration` + `@Bean`（或 langchain4j 的 `@AiService` 自动装配），**不要在领域 `@Component` 里命令式地 `xxx.builder().build()` 再 `AiServices.create(...)` 自己 new 出来**。
  - 不要把大段流程塞进一个类：CLI/工具类只做薄入口编排，清洗/分类/聚合等步骤拆成独立的 `@Service` 或策略类；DTO（如 `CleanedDoc`/`DropRecord`）独立成文件，不内嵌为 `public static class`。
  - langchain4j 在 1.20.0 下：`ChatModel`（原 `ChatLanguageModel`）、`AiServices.create(Class<T>, ChatModel)` 或 `builder(cls).chatModel(m).build()`、`ChatModel.doChat(ChatRequest)`（原 `generate`）。已在本机源码 `/home/lyz/code/langchain4j` 核实。
- 用户偏好：动手前先确认实现计划（不急于直接改）；给简洁直接的推进；实验报告/代码注释用中文。

## 项目结构约定
- 帖子转题库管道在 `com.example.domain.zsxq`；`browser` 包只放通用浏览器基建（Playwright/登录态/限流），zsxq 单向依赖它。
- 知识星球清洗管道设计文档：`docs/zsxq-cleaning-pipeline-design.md`（S1–S7 + Q1–Q6 决策）。
- zsxq 分包：`crawl`（爬取+API）、`clean`（清洗编排）、`classify`（分类闸）、`normalize`（正文/身份归一化）、`model`（DTO）、`eval`（评估报告，只被报告消费）。

## 知识星球清洗策略（老板拍板，2026-09-19）
- **面经真题 = 低权保留，不丢弃**。星友分享的真实面经（具体公司+轮次+题目清单/手撕题）是面试题库
  最核心的**题目侧**语料，不需要星主权威解答。类型 `peer_interview`，权威分 0.1。
  只有「无题目、仅结果陈述」的纯上岸/情绪帖才 `off_topic` 丢弃。判定顺序上必须**先判面经再判丢弃词**，
  因为「已上岸/被横向」常和真题写在同一篇里。
- **权威分 0.9 的正当来源是马丁的实质作答**，不是帖子作者身份。星友提问 + 马丁大段回复
  → `interview_qa` + `star_master_answer`（≥60 字即 0.9）；`architecture_note` 只给马丁本人发的帖子，
  星友帖判成它会丢掉马丁的完整回复和星友的提问原文。
- **血缘键走官方 API**：DOM 里星友帖没有 topic_id，用 `api.zsxq.com/v2/groups/<gid>/topics` 索引 +
  `TopicMatcher` 对齐。该接口偶发返回空 topics（HTTP 200），必须空页重试。
- **跨栏目去重是必须的**：同一帖会同时出现在多个栏目（30 条采集里只有 25 篇独立帖），
  清洗和评估都要先按 `PostIdentity`（postId + 内容指纹两个维度）去重。
