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
