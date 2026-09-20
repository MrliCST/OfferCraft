# JLRADemo 长期项目笔记

## 代码风格 / 架构约定（用户明确要求）
- **必须贴合 Spring Boot 项目规范，不要把代码一窝蜂挤到一起。**
  - Bean 的创建/装配放在 `@Configuration` + `@Bean`（或 langchain4j 的 `@AiService` 自动装配），**不要在领域 `@Component` 里命令式地 `xxx.builder().build()` 再 `AiServices.create(...)` 自己 new 出来**。
  - 不要把大段流程塞进一个类：CLI/工具类只做薄入口编排，清洗/分类/聚合等步骤拆成独立的 `@Service` 或策略类；DTO（如 `CleanedDoc`/`DropRecord`）独立成文件，不内嵌为 `public static class`。
  - langchain4j 在 1.20.0 下：`ChatModel`（原 `ChatLanguageModel`）、`AiServices.create(Class<T>, ChatModel)` 或 `builder(cls).chatModel(m).build()`、`ChatModel.doChat(ChatRequest)`（原 `generate`）。已在本机源码 `/home/lyz/code/langchain4j` 核实。
- 用户偏好：动手前先确认实现计划（不急于直接改）；给简洁直接的推进；实验报告/代码注释用中文。

## 可控爬取重构（2026-09-20 落地，替代浏览器版主路径）

### 知识星球可 100% 去浏览器（已实测，全部带 cookie 直取）
登录态 = `.zsxq.com` 域的 HttpOnly cookie `zsxq_access_token`（53 字符），通配 api/articles/wx 三子域。
`LoginStateStore.cookieValue(name)` 可从 Playwright 存档取出。
- 标签清单：`GET api.zsxq.com/v2/groups/<gid>/hashtags` → `hashtags[]`，字段是 **title**（形如 `#🌈面试相关#`，注意不是 name）+ `topics_count`
- **按标签拉列表**：`GET api.zsxq.com/v2/hashtags/<hid>/topics?count=20[&end_time=<上页末条create_time>]`，倒序
- 话题详情：`GET api.zsxq.com/v2/topics/<id>` → `resp_data.topic`（owner/text/annotation/latest_likes/create_time/talk.article.article_url）
- **评论（含评论人）**：`GET api.zsxq.com/v2/topics/<id>/comments?count=30` → `comments[]`（owner/text/create_time/replied_comments 楼中楼）
- 长文全文：`GET articles.zsxq.com/id_xxx.html` 静态页，`h1.title` + `div.content.ql-editor`，纯正则/jsoup 取即可
- 列表带 hashtag_id / tag 参数**无效**（会被忽略返回全量），必须走 hashtags 子路径

### ⚠️ 「偶发返空」真相：官方反爬 code=1059（2026-09-20 查明，动手前先看这段）
列表/详情/标签清单都会出现的"HTTP 200 + 结构合法 + 数据节点缺席"，真实响应是：
`{"succeeded":false,"code":1059,"info":"不支持非官方工具访问，建议使用官方 Skill 获取内容，稳定可靠。👉 https://garden.zsxq.com/skill/"}`
实测：无间隔 20 次空 5 次(25%)；间隔 2.5s 12 次空 3 次(25%) → **降速完全没用**，不是限频，
是按比例抽样拒绝非官方客户端；单次被拒后下一次通常立刻成功，所以重试能补回来。
后果：不重试 = 随机丢约 20%。**每个 read 方法都必须自带空重试（3 次 + 递增退避）**。
更重要：这套爬取是在蹭一个明确不欢迎它的接口，能用≠合规，随时可能收紧 → 见下方"官方 Skill"。

### 知识星球有**官方 Skill**（2026-09-20 发现，应作为首选路径）
- 主页 https://garden.zsxq.com/skill/ ，开源 https://github.com/unnoo/zsxq-skill
- 官方出品，OAuth 设备码授权，**明确支持 WorkBuddy / Claude Code / Cursor / TRAE**
- 安装：`npm i -g zsxq-cli` → `npx skills add https://github.com/unnoo/zsxq-skill --yes --global`
  （GitHub 不通走 gitee.com/unnoo/zsxq-skill；离线包 garden.zsxq.com/skill/zsxq-skills.zip）
  → `zsxq-cli auth login` → **WorkBuddy 用户需把 skill 同步到 `~/.workbuddy/skills/`**
- 5 个技能：zsxq-group / zsxq-topic / zsxq-note / zsxq-user / zsxq-shared
- CLI 定位是"登录/发帖/评论/回答/笔记/Agent集成"，**未见批量导出**；批量建题库仍需本地方案，
  但接入前应先评估它能否覆盖。

### 路线定位（2026-09-20 修正）
1. 首选 官方 Skill（合规稳定）；2. 本地纯 HTTP 作离线批量通道（带 1059 重试，能用先用）；
3. 旧 Playwright DOM 版已退役，仅作失败方案记录在 2026-09-20.md（慢、吃内存 5.6GB、必然崩）。
注意：DOM 版退役的真实原因不是"被 API 完美替代"，而是三者里它同样不该当主力。

### ⚠️ 官方 Skill 已装且已登录，但对目标星球**不可用**（2026-09-20 实测）
- 已装：CLI 0.5.1 在 `~/.npm-global/bin`（npm prefix 改过，PATH 已进 ~/.bashrc）；
  Skill（v2.2.0，单一体）在 `~/.workbuddy/skills/zsxq`。登录账号 靖默YZD / 214824848821411。
- 走 MCP：OAuth `mcp.zsxq.com/oauth`，端点 `mcp.zsxq.com/topic/mcp`，配置 `~/.config/zsxq-cli/config.json`。
- 工具覆盖很全：`get_group_hashtags` / `get_hashtag_topics`(end_time 分页) / `get_topic_info` /
  `get_topic_comments`(index 分页) / `get_group_topics` / `search_topics`(RAG 检索) / `call_zsxq_api`(通用)。
- **但**：该账号只加入了 1 个星球「拿个offer-开源&项目实战」(group_id=51121244585524, 星主马丁, 9871人)，
  调用结果一律 `该星球暂未开通 Skill 权限，如需使用，可联系星主申请开通`。
  `api raw` 走通用接口也报"路径不存在"。→ **官方通道对本项目数据源不可用，需星主开通**。
- 结论：**本地纯 HTTP 仍是主力**（带 1059 重试）；官方通道待开通后再切，
  `CrawlSource` 接口已预留切换位。别忘了礼貌：真要爬就先去申请权限。

### 官方 Skill 的定位：数据入口，不是面试 Agent（2026-09-20 判断）
它所有工具都是"读写知识星球内容"：拉列表/详情/评论、发帖、评论、回答、打标签、笔记、成员管理。
`search_topics` 虽是 RAG 检索，但检的是星球自己的索引，**无法控制分块/权威分过滤/系列串联**，
而那些正是本项目 S1–S7 清洗管道存在的意义 → 最多当补充召回，不能替代自建向量库。
从 references/scenarios 看，它的场景全是**星主运营视角**（运营日报、评论区分诊、每日巡场、
批量打标签、日报海报、负面内容监控、收录到专栏），老板是**成员备考视角**，需求方向相反。
结论：面试助手的面试交互 / 出题评估两层官方完全不覆盖，必须自建；官方只配当管道起点。

### 调用入口
`./scripts/zsxq-crawl.sh --list | <栏目名,逗号分隔> [--from=日期] [--limit=N] [--refresh]`
包装了 compile+classpath（缓存 tmp/.api-crawl-cp.txt）；崩了重跑同一条命令自动续。

### 新结构（符合 Spring Boot 分层，勿挤成一坨）
`zsxq/source/` 契约层：`CrawlScope`(时间区间+标签+limit+作者，可控性载体) / `CrawlTask` / `TaskPage`(游标) / `CrawlSource`
`zsxq/crawl/api/`：`ZsxqApiClient`(cookie+限流+重试) / `ZsxqHashtagReader` / `ZsxqTopicReader` / `ZsxqArticleFetcher` / `ZsxqApiSource`
`zsxq/crawl/job/`：`JsonlStore`(jsonl 追加，崩了不损坏) / `ZsxqCrawlJob`(两阶段) / `CrawlJobStats`
CLI 薄入口：`ZsxqCrawlCli`（已重命名为 ZsxqApiCrawlCli）

### 两阶段 + 幂等（崩了不用从头再来的答案）
阶段A 清单落 `tasks.jsonl`，阶段B 逐条取落 `posts.jsonl`，最后导出 `<slug>.json` 数组给下游清洗管道。
幂等键 = postId（官方 topic_id）。**重跑同一条命令 = 只补失败的**，实测二次跑失败率归零。
`JsonlStore.append` 必须自己 createDirectories（第一批写入时父目录还不存在）。

### 已有资产
- 旧 DOM 版：`crawl-output/zsxq/starmaster.json` 260篇（技术长文，中位1.2万字）、`ragentai.json` 228篇（问答对，207篇有马丁回复）
- 新版：`crawl-output/zsxq-api/面试相关.json` 172篇、`优质面经.json` 67篇（**两栏重叠22篇**，下游必须去重）

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
