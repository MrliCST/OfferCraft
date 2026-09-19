# 收窄 browser 包职责边界：配置归位 + 存档路径去重

## Goal

让 `com.example.domain.browser` 只表达一件事——**"以什么身份打开网页"**，把寄宿在它里面的别人的职责还给原主，并消掉一处"同一策略写两遍"的静默失败隐患。目标是**降低后续改错的可能性**，不是减少文件数。

## Background

起因是"browser 包文件不少，要不要重构"的提问。结论是**不拆子包**：

- 8 个类 865 行，最长 206 行（`BrowserSessionProvider`），离 Java 包开始难找的规模（20+ 类）很远；
- `PageSession` 的构造器是刻意的 package-private（`PageSession.java:41`），保证只有 `BrowserSessionProvider` 能造出会话、绕不过 `open()` 里的模式判定。拆成 `browser/session` + `browser/login` 会强迫它变 public——用现有的封装不变量换一个目录层级，是净亏。

但排查中发现两处**与包大小无关**的职责错位（R1、R3），以及一处可预防但不紧急的契约外露（R2，已降级为文档，见 Decisions）。

### 已确认事实（全部来自代码/spec，带锚点）

| # | 事实 | 锚点 |
|---|---|---|
| F1 | `maxTextChars` 的**生产**消费者只有 `WebPageTextTool` | `WebPageTextTool.java:63` |
| F2 | 项目已有正确范式：`ScreenshotProperties` 属 `domain.tool`，管 `screenshot.*` | `ScreenshotProperties.java`（`@ConfigurationProperties(prefix = "screenshot")`） |
| F3 | **更正**：`maxTextChars` 还被**测试按位置传参**消费，共 **10 处调用点分布在 5 个测试文件**。按字段名 grep 扫不到它们。明细：`of(cdp, maxTextChars)` 5 处 + 三参构造 5 处 | `of(...)`：`BrowserSessionProviderTest.java:49/65/119`、`LoggedInAccessVerificationTest.java:104`、`WebPageTextToolTest.java:58`；三参构造：`BrowserSessionProviderTest.java:78/93/110`、`LoggedInAccessVerificationTest.java:84`、`SiteLoginRegistryTest.java:96` |
| F4 | 因 F3，静态工厂 `of(...)` **有调用方，不能删，只能改签名** | `BrowserSessionProperties.java:39-41` |
| F5 | `WebPageTextToolTest.java:58` 靠 `maxTextChars=50` 逼出截断分支，并断言"已截断到 50 字" | `WebPageTextToolTest.java:58-66` |
| F6 | 三个测试类用 `@EnableConfigurationProperties({ScreenshotProperties.class, BrowserSessionProperties.class})` 注册 properties | `WebPageTextToolTest.java:24`、`WebScreenshotToolTest.java:39`、`BrowserSessionProviderTest.java:29`、`LoggedInAccessVerificationTest.java:27` |
| F7 | `application-test.yml` 有一条**死键** `browser.session.profile-headless: true`：`BrowserSessionProperties` 无此字段，且 `PROFILE` 模式已移除，Spring 静默忽略 | `application-test.yml` vs `BrowserSessionProperties.java:25-28`、spec:26/86 |
| F8 | `ZsxqCrawler` / `ZsxqExplore` 硬编码 `~/.config/JLRADemo/state/wx.zsxq.com.json`，该值正是 `SiteLoginRegistry.defaultFileFor("wx.zsxq.com")` 的返回值 | `ZsxqCrawler.java:76`、`ZsxqExplore.java:23` vs `SiteLoginRegistry.java:58-60` |
| F9 | spec 明文规定"唯一入口 `BrowserSessionProvider`，**不要自己 launch**"，而这两个 CLI 正是自己 launch 的 | `browser-automation.md:9-10` vs `ZsxqCrawler.java:84` |
| F10 | spec 已把 `browser.session.max-text-chars` 写进文档，改配置键必须同步改 spec | `browser-automation.md:136` |
| F11 | `PageSession` 只暴露 `mode()`/`page()`/`source()`/`close()`，**不暴露 `BrowserContext`** | `PageSession.java:48-62` |
| F12 | `ZsxqCrawler` 需要 `BrowserContext` 才能开详情页（每帖一个新页），不是单页工具 | `ZsxqCrawler.java:280`（`openDetail` 内 `context.newPage()`）、`:146`、`:277` |
| F13 | `CrawlThrottle` 的 javadoc 明确"刻意保持最小实现、不引入配置项" | `CrawlThrottle.java:18-19` |
| F14 | 限流许可泄漏的后果：单域名信号量上限 2，漏 2 次后同域名后续抓取**无超时永久阻塞且不报错** | `CrawlThrottle.java:25/41-61` |
| F15 | **（2026-09-19 更正，原表述有误）** 限流配对共 **4 处：3 处有 `try/finally`，1 处没有**。没护栏的是 `ZsxqCrawler` 外层那对——中间隔着 `clickChip` / `page.evaluate` / `om.writeValue`，任一抛异常就漏许可。它今天没暴露成故障，只是因为异常会一路抛出 `main`、进程结束、许可随 JVM 消失：是**被进程死亡掩盖**，不是"配对写对了"。这一点成立的前提（"异常必然终止进程"）一旦不成立（挪进长驻进程或循环），就会立刻变成 F14 的真死锁 | 有护栏：`WebPageTextTool.java:57/80`、`WebScreenshotTool.java:126/153`、`ZsxqCrawler.java:285/315`；**无护栏**：`ZsxqCrawler.java:102/141` |
| F16 | `guides/index.md` 立有 Pre-Modification Rule：改任何值前先 `grep -r` | `.trellis/spec/guides/index.md` |
| F17 | `directory-structure.md` 是未填写的模板，对"类该放哪个包"无约束 | `.trellis/spec/backend/directory-structure.md`（含 "To be filled by the team"） |

| F18 | `ZsxqTopicGuardConfig.topicGuard` 的参数写成 `ObjectProvider<OpenAiChatModel>`（**按类型**取），而 `DeepSeekModelConfig` 注册了**两个**同类型 bean（`deepseekChatModel` 温度 1.3、`deepseekClassifyModel` 温度 0.1）。`getIfAvailable()` 因此抛 `NoUniqueBeanDefinitionException`，在容器刷新期炸掉 `topicGuard` → `zsxqCleaningService` → **整个 `DemoApplication` 上下文起不来** | `ZsxqTopicGuardConfig.java:41`、`DeepSeekModelConfig.java:43/74` |
| F19 | 该 javadoc 写的是"接 `deepseekClassifyModel`"，即**按名**可选获取，与代码的按类型取不符；`@Qualifier` 修复后的语义才与 javadoc 一致 | `ZsxqTopicGuardConfig.java:24-26` |
| F20 | 轻量离线上下文（没有 `OpenAiChatModel` bean）已有现成回归护栏：`ZsxqCleaningServiceTest` 构造 `AnnotationConfigApplicationContext(ZsxqTopicGuardConfig.class, ZsxqCleaningService.class)`，若 `@Qualifier` 破坏"取不到就回退"，它会立刻变红 | `ZsxqCleaningServiceTest.java:91-92` |
| F21 | 另有一处**存量**测试坏，与本次改动无关：`WebPageTextToolTest` 的 URL 已是 `xiaoyuan.zhaopin.com`，断言却仍要求正文含 `"Example Domain"`。HEAD 干净树上同样失败 | `WebPageTextToolTest.java:30` vs `:46`；HEAD 复跑证据见下 |

F11 + F12 合起来是一个关键约束：**把 `ZsxqCrawler` 迁到 `BrowserSessionProvider` 不是平移改造**，必须给 `PageSession` 开 `BrowserContext` 访问器，而那正是本包值得保持完整的依据。

> **锚点口径**：上表行号为**规划时**的取值。R1/R3 落地后 `WebPageTextTool`、`BrowserSessionProperties`、`ZsxqCrawler`、`ZsxqExplore` 与 `browser-automation.md` 均有移位（如 F1 的 `WebPageTextTool.java:63` 现为 `:64`，F10 的 `spec:136` 现为 `:164`）。F15/F18–F21 为落地期间新增或更正，锚点是**当时实测**的。
>
> **F21 的对照证据**：`git worktree add --detach /tmp/jlra-head HEAD` 后在该干净树跑 `mvn -o test -Dtest='WebPageTextToolTest,AIChatServiceChatTest,AIChatServiceScreenshotTest,AIChatServiceStreamTest'`，得 `Tests run: 6, Failures: 1, Errors: 3`，与改造后工作区的失败集合**逐一相同**；`fetchText_truncatesWhenTooLong` 在两边都通过。

## Requirements

### R1 — 正文截断配置归位（生产 3 文件 + 测试 5 文件 + yml + spec）

`browser.session.max-text-chars` 表达的是"正文留多少字"，与"以什么身份开网页"无关。照 F2 的既有范式为 `WebPageTextTool` 建自己的 properties record。

- R1.1 `BrowserSessionProperties` 不再含正文截断字段，其 javadoc 只描述"以什么身份开网页"。
- R1.2 新建 `domain.tool/WebPageTextProperties`（`@ConfigurationProperties(prefix = "web-text")`，字段 `int maxChars`，紧凑构造器兜底 8000）；注册照 F6 的范式在 `WebPageTextTool` 上打 `@EnableConfigurationProperties`。
- R1.3 `WebPageTextTool` 改为注入 `WebPageTextProperties`；**截断逻辑本身一行不动**（`text.substring(0, max)` 留在原处）。
- R1.4 新键为 `web-text.max-chars`（D2）；yml 与 spec（F10）同步更新，旧键删除且不留悬空引用。
- R1.5 静态工厂 `of(...)` 因 F4 **保留但改签名**为 `of(String cdpEndpoint)`。
- R1.6 按 F3/F5/F6 更新 5 个测试文件的调用点与 properties 注册；`WebPageTextToolTest` 的截断用例改为传新的配置类，断言不变。
- R1.7 顺手清掉 F7 的死键 `browser.session.profile-headless`（它引用的模式已不存在，留着会让人以为测试在配无头）。

### R2 — 限流配对契约：**只留文档，不改代码**（见 Decisions D3）

不改任何 Java。在 `browser-automation.md` 的"硬性规则"一节加一条：`CrawlThrottle.beforeCrawl` 必须与 `afterCrawl` 成对放在 `finally` 里，并写明漏掉的后果（F14）与今日现状（F15）。

不改代码的理由：F15 表明今天**零个活故障**；F13 表明作者已明示要这个简单形态；改造的代价是实的（见 design.md §3）。文档警告落在"将来写新工具的人"眼前，正是该风险的目标人群。

### R3 — 存档路径不再有第二份实现

- R3.1 `ZsxqCrawler.java:76`、`ZsxqExplore.java:23` 改用 `SiteLoginRegistry.defaultFileFor(SiteLoginRegistry.hostOf(<正在爬的 URL>))`，不再出现字面量 `~/.config/JLRADemo/state/...`。
- R3.2 `LoginStateStore.DEFAULT_STATE_DIR` 变更时两处自动跟随。
- R3.3 改造后 CLI 解析出的存档路径与改造前**逐字节一致**。

### R4 — spec 与代码的真实状态对齐

- R4.1 `browser-automation.md:136` 的配置键更新为 `web-text.max-chars`。
- R4.2 把 `ZsxqCrawler` / `ZsxqExplore` 记为 spec 第 9–10 行"唯一入口"规则的**显式例外**，写明理由（F11/F12：它们是需要 `BrowserContext` 开多页的独立 CLI），消除 spec 与代码的既存矛盾。
- R4.3 加入 R2 的那条配对规则。
- R4.4 R2 的规则里写明 F15 更正后的现状：`ZsxqCrawler` 外层那对**没有** `try/finally`，今天是靠"异常终止进程"掩盖的。

### R5 — 修 `topicGuard` 的 bean 歧义（**用户批准的范围扩张**，2026-09-19）

**与包边界目标无关**，是执行期间发现的阻断性缺陷（F18）。它让本任务的最终验收 `mvn -o test` **永远不可能通过**，故经用户明确批准并入本任务。

- R5.1 `ZsxqTopicGuardConfig.topicGuard` 的参数加 `@Qualifier("deepseekClassifyModel")`，恢复 javadoc 已声明的"按名可选获取"语义（F19）。
- R5.2 **不改变回退链**：没有 `deepseekClassifyModel` 时仍按 `DEEPSEEK_WIN_KEY` → 启发式回退，由 F20 的既有护栏保证。
- R5.3 补 javadoc 说明 qualifier 为何不能省（同类型 bean 有两个、故障发生在容器刷新期、报错信息不指向本处）。

## Acceptance Criteria

- **AC1**（R1）`grep -rn "maxTextChars\|max-text-chars"` 在 `src/main/java/com/example/domain/browser/` 下无命中。
- **AC2**（R1）`BrowserSessionProperties` 的字段全部与"以什么身份开网页"相关（人工判定：`cdpEndpoint`、`sites` 符合，无例外字段）。
- **AC3**（R1）`WebPageTextTool` 返回的截断文案不变：仍为"（原文 N 字，已截断到 M 字）"；默认阈值仍 8000。
- **AC4**（R1）**（2026-09-19 改口径）** 原定"`mvn -o test` 全绿"在 HEAD 上即不可达（F21 的存量测试坏 + F18 的容器起不来）。改判为：`mvn -o test` 相对 HEAD **无新增失败**，且 `WebPageTextToolTest#fetchText_truncatesWhenTooLong` 仍断言"已截断到 50 字"并通过（F5）。实测：`Tests run: 41, Failures: 1, Errors: 0, Skipped: 3`，唯一失败即 F21。
- **AC5**（R1.7）`grep -rn "profile-headless" src/` 无命中。
- **AC6**（R2）`browser-automation.md` 中存在配对规则条目；且 `git diff` 不包含 `CrawlThrottle.java`、`PageSession.java` 与 `BrowserSessionProvider.java`。
- **AC7**（R3）`grep -rn "JLRADemo/state" src/main/java/com/example/domain/zsxq/` 无命中。
- **AC8**（R3）CLI 解析出的存档路径与改造前逐字节一致（改造前后各打印一次比对，不保留为测试）。
- **AC9**（R4）`browser-automation.md` 不再引用旧配置键；"唯一入口"规则的例外已显式写明，读起来是决定而非遗漏。
- **AC10**（全局）`mvn -o clean compile` BUILD SUCCESS。
- **AC11**（全局）本次除配置键改名外无行为变化：`fetchText` 的返回结构、"来源"字段、耗时统计口径均与改造前相同。
- **AC12**（R5）`mvn -o test` 中三个 `AIChatService*` 的 `Errors` 由 3 变为 0。
- **AC13**（R5）`ZsxqCleaningServiceTest` 保持 4/4 绿（F20：`@Qualifier` 未破坏"取不到就回退"）。
- **AC14**（R5）`git diff` 中 `ZsxqTopicGuardConfig.java` 的改动仅限 import、方法签名与 javadoc，**方法体一行未动**。

## Out of Scope

- **不拆 `browser` 子包**（依据见 Background）。
- **R2 不做代码改动**（D3）。
- 不把 `ZsxqCrawler` / `ZsxqExplore` 迁到 `BrowserSessionProvider`（D1；代价已由 R4.2 的例外说明兜住）。
- 不改 `CrawlThrottle` 的限流参数（均值 1500ms / 标准差 700ms / 单域名并发 2），也不把常量提成配置项（尊重 F13）。
- 不改 CDP / STORED / HEADLESS 三种模式的判定优先级与关闭语义。
- 不移除 `LoginStateCapture` 的 `main`，也不改它"不启 Spring"的设计（其 javadoc 有理由：存档常在数据库/应用未启动时做）。
- 不填 `directory-structure.md`（F17 的空白模板）——那是一件独立的事，本次不顺手扩边。
- 不新增测试框架依赖，不为本次改动新写测试（沿用现有测试作为回归网）。
- 不动 `.workbuddy/memory/` 下的历史记录（历史快照，不回填）。
- **F21 那处存量测试坏不在本任务内修**（D5），只记录不修。
- 不补 `spring-boot-configuration-processor`：IDE 对 `screenshot` / `browser` / `llm` 三个前缀**本来就**报 unknown（元数据从未生成），`web-text` 只是加入同一名单，不是本次引入的问题。

## Decisions

- **D1（2026-09-19）**：两个 CLI **只做存档路径去重（R3），不迁到 `BrowserSessionProvider`**。理由：迁移需给 `PageSession` 开 `BrowserContext` 访问器（F11/F12），会破坏本包值得保护的封装。代价（CLI 读不到 yml 的 `state-file`）由 R4.2 的 spec 例外说明兜住。
- **D2（2026-09-19）**：R1 的配置键取 `web-text.max-chars`，与既有的 `screenshot.*` 对称；**旧键 `browser.session.max-text-chars` 直接删除，不留兼容别名**。已知代价：若本地 yml 或环境变量（`BROWSER_SESSION_MAXTEXTCHARS`）覆盖过该值，改造后会静默回落到默认 8000。判定可接受：`browser.session` 前缀下原有配置中并无"活着的"正文键（F7 那条本身是死键）。
- **D3（2026-09-19，同日更正依据）**：R2 **降级为一行 spec 规则，代码零改动**。理由：F13 表明作者已明示要保持最小实现；改造代价是实的（延迟进 `open()` 会让两个测试文件多等 ~10.5s，或接受"acquire 自动 / pause 显式"的不对称）。用成本≈0 的文档警告覆盖"将来写新工具的人"这一目标人群即可。
  **更正**：原 D3 的依据写作"F15 表明今天 4 个调用点全部配对正确"。该表述不实——实为 3 处有护栏、1 处没有（见更正后的 F15）。**结论不变**（仍不紧急：唯一没护栏的那处在单次运行的 CLI 里，异常必然终止进程），但依据从"写对了"改为"被进程死亡掩盖"。因 R2 已定稿为文档，此处仅更正记录，不因此改判为代码改动。
- **D4（2026-09-19）**：**接受 R5 的范围扩张**（用户批准）。理由：F18 让整个应用起不来，不修则本任务的最终验收永远红；修法是把代码恢复成 javadoc 已经声明的语义（F19），不是新增设计。代价：本任务多带一处与包边界无关的改动，已在 R5 标题处显式标注。
- **D5（2026-09-19）**：F21 的测试断言与 URL 矛盾**另开任务**，本任务只记录不修。理由：修法取决于"`WebPageTextToolTest` 该验哪个站"这一**产品选择**（沿用 `xiaoyuan.zhaopin.com` 就把断言改成实际内容；改回 `example.com` 则与 `BrowserSessionProviderTest` 的覆盖重叠）；在本任务内顺手改，等于把一个待定的产品决定藏进一次重构里。
