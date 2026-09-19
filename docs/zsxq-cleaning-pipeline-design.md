# 帖子转题库 · 清洗管道设计（草稿 v1）

> 目标：把「拿个offer-开源&项目实战-马丁」知识星球里的帖子，清洗成面试 agent 可用的**题库 / 知识库**。
> 依据：已爬取的 6 栏目 × 5 篇真实帖子结构 + 老板提供的 `cankao.md` 典型内容标注。
> 状态：待老板确认分类边界后，再落地代码。

---

## 0. 已确认的事实（来自真实爬取）

圈子是 Angular SPA，**栏目 = 侧边栏 chip，客户端过滤，无独立 URL**。每篇帖子 DOM 结构（`<app-topic type="flow">`）可直接抽取：

| 字段 | 选择器 | 说明 |
|------|--------|------|
| 作者 | `app-topic-header .role` | 文本即昵称；class 含 `owner`=星主 / `member`=星友 |
| 身份 | `.role` 的 class | 马丁=唯一星主 |
| 发布时间 | `app-topic-header .date` | 例 `2026-09-17 16:25` |
| 正文 | `.talk-content-container .content` | 长帖有 `展开全部` 需点击 |
| 话题标签 | `.tag-container .tag` | 例 `💡RagentAI` / `🌈面试相关` |
| 点赞用户 | `.like-user .eachLike` | 粗略计数 |
| 回复 | `.comment-box app-comment-item` | 含 评论人 / 内容 / 时间 |
| 星主是否回复 | 回复里 commenter 含「马丁」 | = Rule 2 信号 |

已爬栏目（chip 文案）：💡RagentAI / 📃技术问答 / 🌈面试相关 / 💫优质面经 / ✨精华 / 只看星主。
每栏 5 篇，存于 `crawl-output/zsxq/<key>.json`。

---

## 1. 核心洞察：栏目 ≠ 价值分层

老板在 `cankao.md` 里把帖子分成 7 类并逐条标注了「要 / 不要」，这推翻了「按栏目直接入库」的朴素想法：

- **5（或 6）个栏目只是输入筛选项**，不是价值分层。
- 真正价值由三件事决定：**帖子类型 + 作者身份 + 星主是否给出权威回复**。

`cankao.md` 七类提炼：

| 参考 | 内容 | 老板标注 | 清洗判定 |
|------|------|----------|----------|
| 一 | 成员面经复盘 + 马丁长篇点评 + 追问深答 | 精华、高质量 | ✅ 面经问答（高价值，马丁答案=权威答案） |
| 二 | 成员分享 GitHub 项目，马丁「加精」 | 「技术分享还是无关？可单独合集」 | ⚠️ 资源分享（可聚合成一篇合集） |
| 三 | 成员实习被坑避雷 | 「一般没用，成本高就算了」 | ❌ 避雷（默认丢弃/低权） |
| 四 | 成员上岸吹水 | 「一般没用」 | ❌ 纯上岸（默认丢弃） |
| 五 | 马丁日常吐槽（电脑坏了等） | 「大多无用」 | ⚠️ 闲聊丢弃，但**可抽取架构方向金句**（如「Ragent 2.0 基于 AgentScope」） |
| 六 | **马丁技术长文**（含图/链接/表/代码） | 「图片可保留；只看星主相邻文章是系列」 | ✅✅ 技术教程（最高价值，保留图片，做系列串联） |
| 七 | 成员分享 IDEA 快捷键，马丁点赞 | 「没必要，跟面试项目无关」 | ❌ 无关技术分享（丢弃） |

**结论**：即使马丁「点赞/加精」，也不代表该帖该进题库（参考二、七）。进题库的硬标准是**对面试/项目有直接价值**。

---

## 2. 帖子类型分类（Topic Guard 的最终形态）

用关键词 + 作者身份 + 星主回复，把每篇归到一类：

- `tech_article`（技术教程/文档）：作者=星主 且是结构化长文（含代码块/标题/多段落/图片）。来源 = cankao 六。**权威 0.9，保留图片**。
- `interview_qa`（面经问答）：星友发问 + 马丁给出**实质性**回答（star_master_replied 且马丁回帖长度 > 阈值，如 80 字）。来源 = cankao 一。问题 0.1，星主答案 0.9（`star_master_verified=true`）。
- `architecture_note`（项目方向）：星主发的、含架构/方向性信息者（即便带吐槽外壳，如 cankao 五抽取出的「2.0 基于 AgentScope」）。权威 0.9。
- `resource_share`（资源分享）：星友分享外链/开源项目（cankao 二）。可聚合为一篇「资源合集」，或单独低权。**待讨论**。
- `off_topic`（无关/低价值）：IDEA 快捷键（七）、纯吐槽（五闲聊）、避雷（三）、纯上岸吹水（四）→ 默认丢弃或 0.1 低权。

> 注：cankao 七里马丁「很赞，学到了」≠ 进题库——点赞是社交，不是背书。这正好校准了「星主互动即权威」的错误假设。

---

## 3. 权威分 / 校验（修订你原来的 0.9 / 0.1）

- 星主原创技术内容、星主权威回答：**0.9**
- 星友提问 / 分享：**0.1**
- 星友帖 + 星主权威回答：**帖子本体 0.1**（作上下文问题），但**抽取出的 `star_master_answer` 标记 0.9 且 `star_master_verified=true`**——题库里问答对以「星主答案」为准。
- **`member_post`（星友帖）最终决策（2026-09-18 22:43）：低权保留**——权威分 0.1 照常入库，检索时 `WHERE authority_score >= :min`（`:min` 默认 0.1）即会命中；不丢弃，待全量样本验证后再决定是否进一步收紧或降权。

---

## 4. 图片策略（来自 cankao 六，Q1 已拍板，2026-09-18 22:55 改为 0 存储）

- **保留范围（Q1）**：星主技术长文（`tech_article`）图全留；星主问答回复（`interview_qa` 的星主回答）仅白名单留流程图/架构图，表情包/头像剥离；星友图（`member_post` 等）全剥离。
- **0 存储**：知识星球图床 URL 与项目高度相关、长期可用，**不下载、不落本地文件**，只保留 `original_url`。
- **多模态概括进向量库**：对按 Q1 保留的每张图，调视觉模型（百炼 qwen-vl，经 langchain4j）生成文字概括 `description`，把 `description` 文本送嵌入模型向量化，存入 `zsxq_image.embedding`。检索时**图片独立召回**——命中后返回 `description` + `original_url`，点 URL 即看原图。
- **图片分类（kind 判别）**：星主长文盲留；星主问答回复里的图对每张调 qwen-vl 判 `kind ∈ {flowchart, architecture, meme, photo, other}`，只留 flowchart/architecture（全量阶段可先 URL/尺寸/alt 启发式粗筛，再对疑似图做 LLM 抽检控成本）。
- **引用方式**：`cleaned_doc.content` 的 Markdown 里以 `![<description>](<original_url>)` 引用（alt=概括，供人阅读+回溯）；图片语义检索走 `zsxq_image` 表，不依赖正文 embedding。

---

## 5. 系列串联 + 版本抑制（来自 cankao 六）

`cankao` 六明确指出：**只看星主相邻文章是系列**——「前一篇结尾预告下一篇，后一篇提一下前一篇」。

- **串联**：`只看星主` 栏按 `published_at` 升序相邻的文章，用 `series_id` 串起来，记录 `series_prev` / `series_next`。
- **版本抑制**：同一 `topic_key` 内，若 newer 帖子与 older 帖子存在事实冲突（如某项配置改了），**newer 覆盖 older**；检索时优先最新最完整的一篇。
- 这同时满足你最初提的「published_at 做版本抑制」——但位置应在**检索层 / 入库去重层**，不在抽取层。

---

## 6. 管道阶段（Stage 0–7）

- **S0 Crawl**：`ZsxqCrawler`，6 栏 × 5 篇（已跑通）。
- **S1 Normalize**：`CrawledPost` → 规范字段（作者/身份/时间/正文/标签/回复）。
- **S2 Classify（Topic Guard）**：按 §2 规则判 `post_type`。
- **S3 Authority & Verify**：填 `authority_score` / `star_master_verified` / 抽取 `star_master_answer`。
- **S4 Image Policy**：`keep_images = (post_type == tech_article)`，其余剥图。
- **S5 Series Link**：星主文章按 `published_at` + 只看星主 串联 `series_id` / `prev` / `next`。
- **S6 Version Suppress**：同 `topic_key` 内 newer 覆盖 older 事实冲突。
- **S7 Emit**：输出题库 JSON（见 §7）供 RAG 检索。

> **代码组织（2026-09-19）**：管道代码已独立成 domain 包 `com.example.domain.zsxq`
> （`ZsxqCrawler` / `ZsxqCleaner` / `ZsxqExplore` / `HtmlToMarkdown`），与浏览器基建
> `com.example.domain.browser`（`BrowserSessionProvider` / `CrawlThrottle` / `LoginStateStore`
> 等**通用浏览器能力**）解耦。`zsxq` 包单向依赖 `browser` 基建，`browser` 包不反向依赖管道；
> 后续 S2–S7 的分类 / 落库 / 向量化类也落在 `zsxq` 包内。

---

## 7. 题库输出 Schema（每篇一条）

```json
{
  "doc_id": "zsxq-<topicId>",
  "topic_key": "RagentAI | 技术问答 | 面试相关 | 优质面经 | 精华 | 星主文章",
  "post_type": "tech_article | interview_qa | architecture_note | resource_share | off_topic",
  "author": "马丁",
  "author_role": "星主 | 星友",
  "published_at": "2026-09-17 16:25",
  "content": "正文（长帖已展开）",
  "topic_tags": ["💡RagentAI"],
  "authority_score": 0.9,
  "star_master_verified": true,
  "star_master_answer": "马丁的权威回答文本（interview_qa 时抽取）",
  "series_id": "ragent-2.0-setup",
  "series_prev": "doc_id_of_prev",
  "series_next": "doc_id_of_next",
  "keep_images": true,
  "source_url": "https://wx.zsxq.com/group/51121244585524/...",
  "ingest_at": "2026-09-18T20:00:00"
}
```

---

## 8. 待老板拍板的问题（Q1–Q6）—— **已拍板（2026-09-18）**

- **Q1 图片**：星主技术长文图**全留**；星主问答回复**仅白名单保留流程图/架构图**（剥离表情包）；星友图**全剥离**。
- **Q2 资源分享**：**不单篇入库**，聚合成一篇「开源/工具资源推荐汇总」**低权入库**（authority ≈ 0.3）。
- **Q3 避雷/纯上岸**：**直接丢弃（Direct Drop）**，降低清洗与向量化成本。
  - ⚠️ **2026-09-19 修正**：原写法被 LLM 闸理解成「星友分享面经 = 纯上岸吹水」，30 篇样本里 11 篇真实面经
    （百度一二三面 / 汇川 / CVTE / 小红书，含手撕题与 SQL 题）被整批丢弃。面经真题是面试题库**最核心的题目侧语料**，
    它不需要星主权威解答。现拆成两条：
    - 含**具体公司/轮次/题目清单**（一面、手撕、笔试题…）→ 新增类型 `peer_interview`，**低权保留(0.1)**，图片剥离。
    - **只有结果陈述、没有任何题目**（「上岸了，感谢马丁」）→ 仍是 `off_topic` 直接丢弃。
  - 判定顺序上，`peer_interview` 必须排在 `off_topic` 之前：「已上岸 / 被横向」这类词常和真题写在同一篇里。
- **Q4 星主吐槽**：加一层**极简 LLM 抽检**——有架构/选型金句则抽取落库（architecture_note），无干货则丢弃。（v1 先用关键词启发式 `HeuristicSpotChecker` 顶上，留 `LangchainSpotChecker` 接口待接 LLM。）
- **Q5 系列串联**：采纳**启发式**——`只看星主` 栏目 + `published_at` 时间相邻 + 标题/正文含「系列/上篇/下篇/(一)」等标识 → 自动关联 `series_id`（记录 prev/next）。
- **Q6 爬取时间窗口**：抓取区间锁定 **2026-02-04 至今**，**全量翻页爬取**该时间段内的增量内容，自然过滤过期无效技术。（当前 6×5=30 篇为样本；正式跑需升级 `ZsxqCrawler` 做全量翻页 + 日期过滤 + 图片 URL 采集，见 §10。）

> 决策落地状态：S1–S7 清洗器 `ZsxqCleaner` 已基于 30 篇样本跑通；Q6 全量爬取为下一阶段。

### 8.1 补充决策（2026-09-19，B2 验收时新增）

- **S0 跨栏目去重**：同一篇帖会同时出现在多个栏目（实测「面试相关」「优质面经」「精华」都爬得到范特西的百度二三面、
  Jäger 的小红书面经），30 条样本里独立帖只有 21 篇。清洗入口先按 `PostIdentity` 去重
  （优先官方 `postId`，退化到「作者+时间+正文开头」指纹），评估报告同样按独立帖统计，避免重复样本带偏丢弃率。
- **血缘键补齐全量走 API**：DOM 里星友帖拿不到官方 `topic_id`，改由 `ZsxqApiTopics` 拉
  `api.zsxq.com/v2/groups/<gid>/topics` 索引（362 篇），`TopicMatcher` 按「作者+分钟+正文开头」对齐。
  该接口会**偶发返回空 topics（HTTP 仍 200）**，必须空页重试一次，否则索引只剩第一页 20 篇。
  跨栏目重复帖要求**幂等匹配**（返回同一个 topic_id），所以「正文开头对得上」这一档不受「已认领」限制。

---

## 9. 下一步

1. 老板确认 Q1–Q6 边界。
2. 落地 S1–S7 清洗器（复用 `CrawlThrottle` 限流，复用 `LoginStateStore` 登录态）。
3. 先拿已爬的 6×5=30 篇跑通清洗，再扩到全量。

## 10. 全量爬取阶段（Q6）待办

- `ZsxqCrawler` 升级：去掉「每栏 5 篇」上限，改为**全量翻页**（无限滚动一直加载到 `published_at` < 2026-02-04 停止），并按栏目分别落文件。
- **采集图片 URL**：`extractPost` 补充抓取 `.talk-content-container img` / 回复里 `img` 的 `src`，供 Q1 图片策略真实生效（样本目前是纯文本，Q1 仅以 `keep_images` 标记体现）。
- 采集帖子**详情 URL**（`查看详情` 跳转或 `app-topic` 上的链接），补 `source_url` 字段。
- 全量跑完后用 `ZsxqCleaner` 重新清洗；Q4 切到 `LangchainSpotChecker`（需配置 DeepSeek Key）。

## 11. 技术选型（已定，2026-09-18）

- **存储**：PostgreSQL + **pgvector**（与现有 Ragent 栈同为 PG，零新基础设施；向量主、ES 仅作可选 BM25 辅助）。MySQL 9.0+ 也可行但 pgvector 更成熟、且已在栈中。
- **原帖→Markdown**：**flexmark-html2md-converter**（`com.vladsch.flexmark:flexmark-html2md-converter:0.64.8`，含 `FlexmarkHtmlConverter`；HTML→MD 专用、进程内、确定性、无损）。注意 Maven artifactId 是 `flexmark-html2md-converter` 而非 `flexmark-html2md`。MinerU 是 PDF/论文抽取器，形状不对，不用于网页帖。
- **编排**：**Java 顺序管道**（S1–S7 线性执行），**不引入 LangGraph**（LangGraph 不提速、只增复杂度；价值在可恢复/分支/练手，本次不需要）。LLM 分类/金句闸用 **langchain4j `AiService`**（单点 LLM 调用，非自主 Agent 循环）。
- 嵌入模型复用百炼/Qwen embedding（langchain4j `EmbeddingModel`，确定性调用，非 agentic）。

## 12. 数据库表设计（PostgreSQL + pgvector，4 张表）

```sql
CREATE EXTENSION IF NOT EXISTS vector;

-- 1) 原始爬取：血缘/审计，不进 RAG
CREATE TABLE zsxq_raw_post (
  id            BIGSERIAL PRIMARY KEY,
  group_id      TEXT,
  post_id       TEXT UNIQUE,                 -- 血缘键（UNIQUE 以承载 reply / cleaned_doc 外键）
  src_column    TEXT,                       -- 来源栏目（原字段名 column 为 SQL 保留字，改名）
  author        TEXT,
  author_role   TEXT,                       -- 星主 / 星友
  published_at  TIMESTAMPTZ,
  raw_markdown  TEXT,                        -- 原帖全量 Markdown（含星主长文）
  topic_tags    JSONB,
  source_url    TEXT,
  crawled_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX ON zsxq_raw_post (post_id);
CREATE INDEX ON zsxq_raw_post (src_column);

-- 2) 回复：星主问答追溯；单独存可重跑清洗而不重爬
CREATE TABLE zsxq_reply (
  id              BIGSERIAL PRIMARY KEY,
  post_id         TEXT REFERENCES zsxq_raw_post(post_id),
  commenter       TEXT,
  commenter_role  TEXT,
  reply_text      TEXT,
  reply_time      TIMESTAMPTZ,
  is_star_master  BOOLEAN,
  reply_idx       INT
);
CREATE INDEX ON zsxq_reply (post_id);
CREATE INDEX ON zsxq_reply (is_star_master);

-- 3) 清洗后题库：可查询 / RAG 检索
CREATE TABLE cleaned_doc (
  doc_id               TEXT PRIMARY KEY,
  topic_key            TEXT,
  post_type            TEXT,                -- tech_article/interview_qa/architecture_note/resource_share_aggregate/member_post
  author               TEXT,
  author_role          TEXT,
  published_at         TIMESTAMPTZ,
  content              TEXT,                -- Markdown
  question             TEXT,                -- interview_qa 星友提问原文（非该类型 NULL），支撑 question / question+answer 向量化
  authority_score      REAL,                -- 0.9 / 0.1 / 0.3
  star_master_verified BOOLEAN,
  star_master_answer   TEXT,                -- 星主权威回答（interview_qa 抽取）
  series_id            TEXT,                -- 只看星主系列串联
  series_prev          TEXT,
  series_next          TEXT,
  keep_images          BOOLEAN,             -- Q1 图片策略
  raw_post_id          TEXT REFERENCES zsxq_raw_post(post_id),  -- 显式血缘外键，直接 JOIN 溯源
  source_url           TEXT,                        -- 人可点击的原帖链接
  embedding            vector(1536),        -- 维度随嵌入模型（768/1024/1536）
  superseded           BOOLEAN DEFAULT FALSE,
  ingest_at            TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX ON cleaned_doc USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON cleaned_doc (topic_key, post_type, published_at);
CREATE INDEX ON cleaned_doc (superseded) WHERE superseded = FALSE;
CREATE INDEX ON cleaned_doc (raw_post_id);

-- 4) 图片血缘：0 存储，仅保留 original_url + 多模态概括，独立进向量库可召回查看
CREATE TABLE zsxq_image (
  id           BIGSERIAL PRIMARY KEY,
  post_id      TEXT REFERENCES zsxq_raw_post(post_id),
  seq          INT,
  original_url TEXT,                        -- 知识星球图床 URL，长期可用，不落地文件
  kind         TEXT,                        -- flowchart / architecture / meme / photo / other（Q1 判别）
  description  TEXT,                        -- 多模态大模型（qwen-vl）生成的图片内容概括
  kept         BOOLEAN,                     -- 是否按 Q1 策略保留（保留的才进向量库）
  embedding    vector(1536),                -- 图片概括向量，独立召回图片
  ingest_at    TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX ON zsxq_image USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON zsxq_image (post_id);
```

- **关系**：`cleaned_doc.raw_post_id` → `zsxq_raw_post.post_id`（显式 FK，直接 JOIN 溯源）；`zsxq_reply.post_id` / `zsxq_image.post_id` → `zsxq_raw_post`。`post_id` 在 raw_post 上设为 UNIQUE 以承载外键。`zsxq_image` 是图片血缘层：0 存储、只留 `original_url` + 多模态 `description`，其 `embedding` 独立可召回。
- **检索**：文本/问答走 `cleaned_doc`（`ORDER BY embedding <=> :q` + `WHERE post_type IN (...) AND superseded = FALSE AND authority_score >= :min`）；**图片独立走 `zsxq_image`**（`ORDER BY embedding <=> :q` + `WHERE kept = TRUE`），召回返回 `description` + `original_url`，点 URL 即看原图。两者均可按 `post_id` 回 `zsxq_raw_post` 溯源。
- `raw_post` / `reply` 是血缘层：调 LLM 闸或改规则时，可**直接重跑清洗**，不必重新爬。
- `embedding` 维度必须和所选嵌入模型一致（百炼 text-embedding-v2=1536 / v3=1024，按需改 `vector(N)`）。
- **向量化弹性**：`question` 字段落地后，后续可按场景选「对 question 单独向量化」「question+answer 联合向量化」或「保留单 embedding 列」；如需双路召回可再增 `question_embedding vector(N)` 列，无需回表改结构。

---

## 9. S6 版本抑制修正（2026-09-19，跑真实样本后推翻原设计）

**原设计**（§5）：「同一 `topic_key` 内，newer 覆盖 older 事实冲突」。`topic_key` 取第一个话题标签。

**跑真实样本后发现前提不成立**：话题标签是**分类**不是**主题**。实测标签分布：

| 标签 | 篇数 | 实际情况 |
|---|---|---|
| `💡RagentAI` | 6 篇 | 6 个不同的人提的 6 个不同问题 |
| `🌈面试相关` | 5 篇 | 5 场不同的面试（百度一面/二面/三面、CVTE、汇川） |
| `📃技术问答` | 5 篇 | 各自的独立提问 |

按标签抑制的后果是**灾难性的**：「面试相关」栏 6 篇面经被标掉 5 篇，而那正是最该留存的面经真题。
24 篇入库文档里有 16 篇被误标 `superseded = TRUE`。

**修正后的判据**（`ZsxqCleaningService.suppressVersions`）换成**内容维度**：

- 前置条件：**同作者**（不同的面经常常由同一个星友反复发，只比正文会误判）；
- 判据：正文开头 30 字**重合一半以上**（正文先去掉图片和空白——改版常常只是换了个封面图）；
- 排除：同一 `seriesId` 的相邻篇目**不算**版本（`系列串联` 串的是「同系列上下文相邻」，不是「同一篇的新旧版本」）；
- `deriveTopicKey` 不再拿栏目名兜底，没有标签就返回 `null`。标签只作展示 / 拉取用，**不再参与抑制**。

**修复后**：24 篇全部 `superseded = FALSE`，全部进向量库。

**教训**：`published_at` 做版本抑制这个需求本身是对的，但**判据必须落在内容上，不能落在分类字段上**。
下一步做全量爬取时，同一篇帖的修订版会更常见，届时这个判据要拿真实数据复核一遍。

---

## 10. S4 图片概括落地（2026-09-19，已跑通 25/25）

### 10.1 模型选择：实测定案

原设计文档 §4 写的是「百炼 qwen-vl」。实测后改用 **DeepSeek 的 `deepseek-flash`**：

| 型号 | 视觉能力 | 结论 |
|---|---|---|
| `deepseek-flash` | ✅ 原生多模态，左绿右蓝纯色块、黑底白字母 T 都能准确识别 | **采用** |
| `deepseek-v4-pro` | ❌ 自称 "unsupported image" | 该型号不支持视觉 |

注意：`/models` 接口里**没有 `DeepSeek-V4.1-Flash` 这个型号名**，实际可用的是 `deepseek-flash`。

### 10.2 两个必须处理的坑

1. **推理模型会「过度思考」吃光 token。** `deepseek-flash` 是推理型，看图时会先想一大段
   ——实测一张 64×64 纯色图，输出 76 token 里 **74 个是 reasoning**，真正内容只占 2 个。
   若 `max_tokens` 不放大，reasoning 会把额度吃光，结果 `finish_reason=length` 且 `content` 为空。
   **对策**：`maxTokens(8000)`，并在 system prompt 里明确「直接输出描述，不要分析过程」。
2. **AiService 的图片参数必须带注解。** 裸 `ImageContent` 参数会在**调用时**抛
   `IllegalConfigurationException: The parameter 'arg1' ... must be annotated with either
   UserMessage, V, MemoryId, or UserName`（`AiServiceValidation.validateParameters` 拦的）。
   编译期完全看不出来。**正确形态**是给图片参数挂 `@UserMessage`：

   ```java
   @SystemMessage("...（概括规范）")
   @UserMessage("请概括这张图片。")
   String describe(@UserMessage ImageContent image);
   ```

   框架的 `DefaultAiServices.addContentsToUserMessage` 专门处理「`@UserMessage` 标注的
   `Content` 型参数」，会把它收进消息 contents。也**不能**写成
   `describe(@UserMessage String prompt, ImageContent image)` —— 第二个参数照样没注解。

### 10.3 装配：为什么模型 bean 建了两份

| 场景 | 装配方式 | 原因 |
|---|---|---|
| 应用内（Web 容器） | `DeepSeekModelConfig#deepseekVisionModel` + `@AiService` 自动装配 | 有 Boot 属性绑定，`@ConfigurationProperties` 能绑 yml |
| CLI（批处理） | `ZsxqImageConfig#zsxqVisionModel` + `AiServices.builder(...)` 手工装配 | 轻量 `AnnotationConfigApplicationContext` **没有** Boot 属性绑定，也**没有** langchain4j starter 的 AiService 自动配置 |

接口上仍保留 `@AiService` 注解，两条路共用同一个接口定义。这与 §11「S7 向量化」的双路装配是同一套路。

### 10.4 非正文图过滤（实测踩出来的）

样本里 39 张「图」有 **5 张是表情**（`wx.zsxq.com/assets_dweb/images/emoji/抱拳.png`）。
这类站点静态资源不是帖子正文图，登记进来只会白花钱概括。过滤规则：

- `wx.zsxq.com/assets*` → 站点静态资源（表情/图标），排除；
- `data:` → 内联 data URI，排除。

正文图来自 `images.zsxq.com`（CDN，带签名参数）和 `article-images.zsxq.com`（文章页正文图）。

### 10.5 数据流与幂等

```
原始帖 imageUrls ──┐
                   ├─→ registerImages() ─→ zsxq_image（description 为空）
cleaned_doc        │                           │
（keepImages 判定）─┘                           ↓
                                    describePending() ─→ 视觉模型 ─→ description
                                                          └─→ 嵌入模型 ─→ embedding
```

- **register 幂等**：同一 `docId` 的图**先删后插**，重跑行数不变（同 `ingestReplies` 套路）。
- **describe 幂等**：只挑 `description IS NULL` 的图，断了重跑接着上次来，不重复花钱。
- **单张失败不中断整批**：网络拉不到图 / 模型返回空 / 接口抖动，都只记日志跳过，下次重跑捡起来。
- **描述与向量解耦**：embedding 失败不影响 description 落库（描述是花了钱换来的，不能丢），
  向量留待 `fillMissingEmbeddings()` 单独补。

### 10.6 schema 变更

`zsxq_image` 补了 **`doc_id` 列**（`TEXT REFERENCES cleaned_doc(doc_id) ON DELETE CASCADE`）：
- 有它才能「按文档重登记」（重跑先删该文档的图）；
- 有它才能从图回查 `post_type` / `authority_score` 做加权（同 chunk 那套）。
老库用 DO block 判列存在再 ALTER，幂等可反复跑。

### 10.7 验收结果

**register**：9 篇有图的帖 → 39 个 URL（含跨栏目重复）→ 去重去表情后 **25 张**登记入库，重跑仍 25 行。

**describe**：25 张**全部成功，0 失败**，全部带上向量。概括质量抽样（远超预期，OCR 准确）：

- [26] 「对比『原始闭环』与『Agent 化后』RAG 调用链的示意图。左侧『用户 → RAG → 用户』，
  右侧『用户 → 主 Agent → RAG Tool → 主 Agent → 用户』，标注『主边界上移』『RAG 变为 Tool』
  『边界必须重划』。底部结论：调用链一旦改变，系统边界、职责归属、封装位置都要随之调整。」
- [44] 「一张『初始化流程（initializer）』的 12 步流程图……依次为：校验模板 checksum 与数量断言
  → 登录主服务检查 Admin、PostgreSQL、Redis 与空间状态 → 清理文档、平台数据、业务数据与缓存
  → 灌入 rag_ent_bit 演示数据 → …… → 输出 [initializer] SUCCESS。」
- [39] 「AI Agent 执行下单流程的对话日志截图……优惠券码 PHONE-8000-500；TOOL create_order
  耗时 16ms，订单号 88248，商品 iPhone 18 Pro 256GB 黑色 (MJT74CH/A) ×1，单价 9999.00。」

**图片向量召回**（自然语言查图，6 个查询全部命中正确目标）：

| 查询 | Top1 命中 | 分数 |
|---|---|---|
| Agent 调用 RAG 的链路是怎么走的 | RAG 调用链对比示意图 | 0.759 |
| 多用户并发下工具状态不一致的问题 | 多用户工具状态错位示意图 | 0.746 |
| 初始化流程有哪些步骤 | initializer 12 步流程图 | 0.793 |
| 代码里缓存实例怎么获取的 | 缓存实例获取逻辑流程图 | 0.744 |
| ReAct 循环的中间件拦截点在哪 | MiddlewareBase 五拦截点流程图 | 0.718 |
| 订单创建的工具调用长什么样 | 下单流程对话日志截图 | 0.611 |

分数梯度也健康（如「多用户并发」查询 Top1 0.746 vs Top2 0.567），说明描述文本的判别力够。

### 10.8 CLI 用法

```bash
java -cp "target/classes:$(cat tmp/cp.txt)" \
  com.example.domain.zsxq.image.ZsxqImageRunner [样本目录] [数量] [--register|--describe|--embed]
```

不给动作参数则 register + describe 一起跑。前置：S3 已落库、schema 已迁移、
`DEEPSEEK_WIN_KEY`（视觉模型）与 `DASHSCOPE_API_KEY / BAILIAN_API_KEY`（描述向量化）在环境里。

### 10.9 遗留

- **`kind` 字段仍为空**：设计文档 §4 说要判 `kind ∈ {flowchart, architecture, meme, photo, other}`，
  当前是「星主图全留」的粗策略，还没做 kind 判别。考虑到实测概括质量很高、非正文图已按 URL 前缀过滤掉，
  这个判别的收益不大，**建议降级为可选**（等全量爬取看到更多噪声图再定）。
- **正文 Markdown 里的图片没换成 `![description](url)`**：设计文档 §4 的「引用方式」这条未落地。
  当前正文里仍是 `![](url)`（alt 为空）。要做的话是在 S1/S3 之间插一步，用 description 回填 alt。
