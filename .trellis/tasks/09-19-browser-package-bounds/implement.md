# 执行计划

需求见 `prd.md`，技术设计见 `design.md`。三步按序执行，每步是一个可独立编译、独立回滚的边界。

**顺序理由**：R1 最大且改动面被编译器全拦（位置传参），先做能以最低风险把主干立住；R3 是两行、零依赖；R4+R2 是纯文档，放最后，这样写进 spec 的是最终代码的样子，不会描述一个中间态。

---

## 步骤 1 — R1 正文截断配置归位

### 1.1 改前先 grep（Pre-Modification Rule，F16）

```bash
grep -rn "maxTextChars\|max-text-chars" src/ .trellis/spec/
```
把命中记下来当清单，改完逐条核对。

### 1.2 新增 `domain/tool/WebPageTextProperties.java`

照 `design.md` §2.1。参照物：`ScreenshotProperties.java` 的形状与 javadoc 风格。

### 1.3 `BrowserSessionProperties` 瘦身

按 `design.md` §2.2：删字段与常量、`of(...)` 改签名为 `of(String)`、紧凑构造器去掉兜底行、javadoc 删 `@param maxTextChars` 与"抓正文"描述。

### 1.4 `WebPageTextTool` 换注入源

按 `design.md` §2.3。**截断逻辑与返回文案一行不动。**

### 1.5 yml

- `src/main/resources/application.yml`：删 `browser.session.max-text-chars: 8000`，新增 `web-text.max-chars: 8000`（注意缩进层级：`web-text` 与 `browser`、`screenshot` 同级）。
- `src/test/resources/application-test.yml`：删 `browser:` 整段（仅含 F7 的死键）。

### 1.6 测试调用点（5 个文件）

按 `design.md` §2.5 的清单逐个改。

### 1.7 闸门（必须全过才进步骤 2）

```bash
grep -rn "maxTextChars\|max-text-chars" src/          # 期望：无命中
grep -rn "browser.session.max-text-chars" .trellis/   # 期望：仅剩 spec:136（步骤 3 才改）
mvn -o clean compile                                  # 期望：BUILD SUCCESS
mvn -o test                                           # 期望：相对 HEAD 无新增失败（见下方更正）
```

**（2026-09-19 执行期更正）** 原定"`mvn -o test` 全绿"在 HEAD 上即不可达。实测两项失败：

| 现象 | 本次引入？ | 处置 |
|---|---|---|
| 3 个 `AIChatService*` 报 `NoUniqueBeanDefinitionException`，`DemoApplication` 上下文起不来 | 否（`549e142` 引入，F18） | **步骤 1.5 修掉** |
| `WebPageTextToolTest#fetchText_returnsTitleAndBody` 断言 `"Example Domain"` 但 URL 是 `xiaoyuan.zhaopin.com` | 否（HEAD 干净树同挂，F21） | 记录（D5），另开任务 |

判定依据是 HEAD 对照：`git worktree add --detach /tmp/jlra-head HEAD` 后在干净树跑同样 4 个类得
`Tests run: 6, Failures: 1, Errors: 3`，失败集合与改造后**逐一相同**。闸门据此改判为 AC4 的新口径。

**专项确认**（AC3/AC4）：`WebPageTextToolTest#fetchText_truncatesWhenTooLong` 仍断言"已截断到 50 字"通过；跑一次该用例看它的控制台输出，确认截断文案仍是"（原文 N 字，已截断到 50 字）"。

---

## 步骤 1.5 — R5 修 `topicGuard` 的 bean 歧义（执行期追加，用户批准）

**位置说明**：本步在步骤 1 的闸门处被发现，且不修则最终验收不可能通过，因此**实际在步骤 2 之前执行**。
它与 R1/R3 改的文件完全不重叠，可独立回滚。

### 1.5.1 改一处签名 + 一个 import（`design.md` §8.2）

`ZsxqTopicGuardConfig.java`：

```java
import org.springframework.beans.factory.annotation.Qualifier;
...
public TopicGuard topicGuard(
        @Qualifier("deepseekClassifyModel") ObjectProvider<OpenAiChatModel> classifyModelProvider) {
```

**方法体一行不动**（AC14）。另在类 javadoc 补一段说明 qualifier 为何不能省：
同类型 bean 有两个、故障发生在容器刷新期、报错信息不指向本处。

### 1.5.2 闸门

```bash
mvn -o test -Dtest='AIChatServiceChatTest,AIChatServiceScreenshotTest,AIChatServiceStreamTest,ZsxqCleaningServiceTest'
# 期望：前三个由 Errors: 3 变 0（AC12）；ZsxqCleaningServiceTest 保持 4/4 绿（AC13）
git diff src/main/java/com/example/domain/zsxq/classify/ZsxqTopicGuardConfig.java
# 期望：只有 import、方法签名、javadoc（AC14）
```

`ZsxqCleaningServiceTest` 这一条**不能省**：它是"没有 `OpenAiChatModel` bean 时照样回退"的既有护栏
（F20 / `design.md` §8.4）。若 `@Qualifier` 把可选语义改成了必需语义，只有它会红。

**回滚点**：本步只碰 1 个文件，`git checkout --` 即可。

> 注意 `AIChatServiceChatTest` 实测耗时约 122 秒（真调模型），不要误判为卡死。

**回滚点**：本步的全部改动（1 个新增 + 3 个生产文件 + 2 个 yml + 5 个测试）可整体 `git checkout --` 掉；R3/R4 尚未开始，无需考虑交互。

---

## 步骤 2 — R3 存档路径去重

### 2.1 改前先确认路径等价（AC8 的前置）

```bash
# 改造前先记一次实际路径
grep -n "LoginStateStore" src/main/java/com/example/domain/zsxq/crawl/ZsxqCrawler.java
```
临时在该行后加一句 `System.out.println(store.stateFile());`，跑一次 CLI 记下输出，然后删掉这行临时打印。

### 2.2 改两行

按 `design.md` §4：`ZsxqCrawler.java:76` 与 `ZsxqExplore.java:23` 换成
`new LoginStateStore(SiteLoginRegistry.defaultFileFor(SiteLoginRegistry.hostOf(<URL>)))`。
`ZsxqCrawler` 用常量 `GROUP_URL`，`ZsxqExplore` 用局部变量 `url`。记得补 `SiteLoginRegistry` 的 import、删可能变成未使用的旧 import。

### 2.3 闸门

```bash
grep -rn "JLRADemo/state" src/main/java/com/example/domain/zsxq/   # 期望：无命中
mvn -o clean compile                                                # 期望：BUILD SUCCESS
```
再确认路径与之前**逐字节一致**。

> **（2026-09-19 执行期改用更强证据）** 未按 2.1 的办法"跑一次 CLI 看打印"：那要起 Chrome、要联网，
> 且 CLI 中途还会抓取，输出长、噪声大，作为证据反而弱。改为写一个**一次性证据程序**（不进仓库，
> 落在 `/tmp/pathequiv/PathEquiv.java`），直接对编译产物调用两个表达式并 `equals` 比较：
>
> ```
> host         = wx.zsxq.com
> old (expand) = /home/lyz/.config/JLRADemo/state/wx.zsxq.com.json
> new (derive) = /home/lyz/.config/JLRADemo/state/wx.zsxq.com.json
> equals       = true
> ```
>
> 不变式更强：不依赖本机有没有存档、不依赖网络、不依赖 CLI 是否跑到底。
> **代价（须如实记录）**：因此**没有**验证"日志里仍有『已登录浏览器（存档 …）』字样"这一条。
> 该条的实质是"没退化成匿名"，而它由路径相等 + `LoginStateStore.exists()` 的语义共同保证；
> 但就本次执行的证据而言，这一条是**推论**，不是实测。

**回滚点**：本步只碰 2 个文件，独立回滚不影响步骤 1。

---

## 步骤 3 — R4 + R2 文档收口

### 3.1 改 spec 三处

`.trellis/spec/backend/browser-automation.md`，按 `design.md` §5：
1. `:136` 配置键改名；
2. `:7-10` 加 CLI 例外说明；
3. "硬性规则"节加 `beforeCrawl`/`afterCrawl` 配对规则（R2 的唯一交付物）。

### 3.2 闸门

```bash
grep -rn "max-text-chars" .trellis/spec/              # 期望：无命中（旧键已清）
grep -rn "web-text.max-chars" .trellis/spec/          # 期望：命中 :136 处
git diff --stat src/main/java/com/example/domain/browser/
# 期望：只有 BrowserSessionProperties.java（AC6：CrawlThrottle / PageSession /
#       BrowserSessionProvider 三个文件必须不在改动集内）
```

人工通读改后的 spec 三处，按 AC9 确认例外段"读起来是决定，不是遗漏"。

**回滚点**：纯文档，`git checkout --` 该文件即可。

---

## 最终验收（三步全做完后）

```bash
mvn -o clean compile && mvn -o test          # AC10 + AC4 + AC12
git diff --stat                              # 对照 design.md §0 的改动清单，多一个文件都要有解释
grep -rn "maxTextChars\|max-text-chars" src/ # AC1
grep -rn "profile-headless" src/             # AC5
grep -rn "JLRADemo/state" \
  src/main/java/com/example/domain/zsxq/     # AC7
grep -rn "max-text-chars\|web-text.max-chars" .trellis/spec/   # AC9
git diff --stat src/main/java/com/example/domain/browser/      # AC6
```

逐条核对 `prd.md` 的 **AC1–AC14**。特别确认：

- **AC11**（无行为变化）：`WebPageTextToolTest` 那次真实抓取的返回文本，结构与"来源：…浏览器…"字样与改造前一致；
- **AC13**（R5 的真实风险点）：`ZsxqCleaningServiceTest` 仍绿 —— 它是"`@Qualifier` 没把可选语义改成必需语义"的唯一护栏；
- **AC14**（R5 边界）：`ZsxqTopicGuardConfig.java` 的方法体一行未动。

### 执行期对 `prd.md` 的两处更正（须在收尾时一并交代）

1. **F15 原表述不实**：曾写"4 个调用点全部配对正确"，实为 **3 处有 `try/finally`、1 处没有**
   （`ZsxqCrawler.java:102/141`）。结论不变（仍不紧急），但依据从"写对了"改为"被进程死亡掩盖"。
2. **AC4 原口径不可达**：改为"相对 HEAD 无新增失败"。判定靠 HEAD 干净 worktree 的对照复跑。

## task.py start 之前的检查

- [ ] `prd.md` / `design.md` / `implement.md` 三者一致，无相互矛盾
- [ ] `prd.md` 的 Open Questions 为空（本轮已清空）
- [ ] `implement.jsonl` 与 `check.jsonl` 各有真实条目（本平台按 sub-agent-dispatch 流程走，空清单或只留 `_example` 占位不算数）
- [ ] 用户已就最终规划摘要给出**新一轮的明确批准**（不是"建任务"那句，也不是本轮提问的回答）
- [ ] 未开始任何代码改动
