# 技术设计：browser 包职责收窄

本文件只写技术设计。需求与验收见 `prd.md`，执行顺序见 `implement.md`。

## 0. 本次改动的形状

一次纯搬移 + 一次去重 + 一批文档。**没有新的抽象、没有新的依赖、没有行为变化**（唯一例外是配置键改名，D2 已接受）。

```
src/main/java/com/example/domain/browser/BrowserSessionProperties.java   改（瘦身）
src/main/java/com/example/domain/browser/…（其余 7 个类）                 零改动
src/main/java/com/example/domain/tool/WebPageTextProperties.java         新增
src/main/java/com/example/domain/tool/WebPageTextTool.java               改（注入源 + 注册）
src/main/java/com/example/domain/zsxq/crawl/ZsxqCrawler.java             改（1 行）
src/main/java/com/example/domain/zsxq/crawl/ZsxqExplore.java             改（1 行）
src/main/resources/application.yml                                       改（键迁移）
src/test/resources/application-test.yml                                  改（删死键）
src/test/java/…（5 个测试文件）                                          改（调用点签名）
.trellis/spec/backend/browser-automation.md                              改（3 处）
```

## 1. 边界：改造后 browser 包只回答一个问题

**"以什么身份打开网页，以及打开后怎么收摊。"**

| 类 | 改造后职责 | 本次改动 |
|---|---|---|
| `BrowserSessionProvider` | 模式判定（CDP→STORED→HEADLESS）+ 会话建立 | 不动 |
| `PageSession` | 会话句柄与关闭语义 | 不动 |
| `BrowserSessionProperties` | 只留"以什么身份开网页"的配置（`cdp-endpoint` / `sites`） | 改 |
| `SiteLoginRegistry` / `LoginStateStore` | 存档路由与读写 | 不动 |
| `LoginStateCapture` | 存档 CLI | 不动 |
| `CrawlThrottle` | 限频/限流/限网的纯静态实现 | 不动 |
| `BrowserConfig` | Spring 装配 | 不动 |

判据不是文件数，而是"删掉这个类，剩下的还讲不讲一个完整的故事"。

> 本设计**否决**了拆子包。依据：`PageSession` 的构造器是 package-private（`PageSession.java:41`），拆包会强迫它变 public，等于用已建好的封装不变量换一个目录层级。

## 2. R1：正文截断配置归位

### 2.1 新 record

`domain/tool/WebPageTextProperties.java`，照 `ScreenshotProperties` 的形状：

```java
@ConfigurationProperties(prefix = "web-text")
public record WebPageTextProperties(int maxChars) {
    public static final int DEFAULT_MAX_CHARS = 8000;
    public WebPageTextProperties { maxChars = maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS; }
}
```

紧凑构造器兜底不是抄形式：record 没法在字段上写默认值，而"少配一个就变成 0"比"配错一个"更隐蔽——`maxChars = 0` 会把正文截成空串且不报错。这条理由 `ScreenshotProperties` 的 javadoc 已写明，直接沿用同一套。

注册照 F6 的范式：在 `WebPageTextTool` 上打 `@EnableConfigurationProperties(WebPageTextProperties.class)`。

### 2.2 `BrowserSessionProperties` 瘦身

- 删字段 `maxTextChars`、常量 `DEFAULT_MAX_TEXT_CHARS`；
- `of(...)` 因有 5 处测试调用（F3）**保留但改签名为 `of(String cdpEndpoint)`**；
- 紧凑构造器里去掉 `maxTextChars` 的兜底行；
- javadoc 的 `@param maxTextChars` 与"抓正文的截断字数"描述删除。

`SiteLogin` 内部类的 javadoc 引用了 `{@link BrowserSessionProperties#of}`（`BrowserSessionProperties.java:77`），签名变了但链接仍成立，需确认 javadoc 语义没变味。

### 2.3 `WebPageTextTool`

- 注入 `WebPageTextProperties properties` 替换 `BrowserSessionProperties properties`（`WebPageTextTool.java:36`），import 相应更换；
- `WebPageTextTool.java:63` 的 `properties.maxTextChars()` 改为 `properties.maxChars()`；
- 类 javadoc 里 `{@code browser.session.max-text-chars}`（`:27`）改为新键；
- **截断逻辑本身（`text.substring(0, max)` 与那段"（原文 N 字，已截断到 M 字）"文案）一行不动**——这是 AC3/AC11 的落点。

### 2.4 yml

```yaml
# application.yml：删除 browser.session 下的 max-text-chars，新增同级段
web-text:
  # 抓正文的截断字数
  max-chars: 8000
```

`application-test.yml`：删除 `browser:` 整段（只含 `session.profile-headless`，F7 的死键）。该文件顶部"profile 文件是叠加的"那段注释与键无关，保留。

### 2.5 测试改造清单（F3/F5/F6）

| 文件 | 改什么 |
|---|---|
| `BrowserSessionProviderTest.java` | `:49/:65/:119` 的 `of(a, b)` → `of(a)`；`:78/:93/:110` 的三参构造 → 二参（去掉 `0`） |
| `LoggedInAccessVerificationTest.java` | `:104` 的 `of(CDP, 0)` → `of(CDP)`；`:84` 三参 → 二参 |
| `SiteLoginRegistryTest.java` | `:96` 三参 → 二参 |
| `WebPageTextToolTest.java` | `:24` 的 `@EnableConfigurationProperties` 加 `WebPageTextProperties.class`；`:35` 那个 `@Autowired BrowserSessionProperties properties` 改为 `WebPageTextProperties`（或删掉，若再无引用）；`:58` 的 `new WebPageTextTool(provider, BrowserSessionProperties.of(null, 50))` → `new WebPageTextTool(provider, new WebPageTextProperties(50))` |
| `WebScreenshotToolTest.java` | `:39` 的 `@EnableConfigurationProperties` 无需改（该测试不用正文配置）——实现时确认一次，不用就别动 |

**这一步是本次最容易出错的环节**：签名是位置传参，编译器能全部拦住，所以以"编译通过 + 测试全绿"为准，不靠人眼扫。

## 3. R2：为什么不做代码改动（保留论证，避免以后重复讨论）

### 3.1 曾考虑的两个方案

- **方案甲：`open()` 取许可、`close()` 归还；高斯延迟另拆 `CrawlThrottle.pause(url)` 由调用方显式调。** acquire 自动、pause 显式，两者分居两处。
- **方案乙：许可 + 延迟一起进 `open()`（即把现有的 `beforeCrawl`/`afterCrawl` 挪入）。** 心智模型最简单——"`open()` 就是一次抓取"。

### 3.2 否决理由（三条，按分量排序）

1. **零个活故障。** F15：4 个调用点今天全部配对正确。R2 防的是"将来有人写新工具时漏一行"，触发链条长（要新写工具 + 没照抄现有写法 + 同一 host 上又漏一次）。
2. **推翻一个已做过的取舍。** F13：`CrawlThrottle` 的 javadoc 明确"刻意保持最小实现、不引入配置项"。作者是有意选了这个简单形态，而 R2 换来的收益是零个今日故障。
3. **代价是实的。** 方案乙会让**直接调 `provider.open()`** 的两个测试文件（`BrowserSessionProviderTest` 4 次导航 + `LoggedInAccessVerificationTest` 3 次导航）各多付一次高斯延迟，**合计约 +10.5s**；方案甲则引入"acquire 自动 / pause 显式"的不对称，读代码要多想一层。

> 注意区分两类代价：走工具的测试（`WebPageTextToolTest` / `WebScreenshotToolTest`）今天**本来就付**这笔延迟，改造后不多不少。增量只落在直接调 `provider.open()` 的用例上。

### 3.3 最终处置：一行 spec 规则

在 `browser-automation.md` 的"硬性规则"节加入：`CrawlThrottle.beforeCrawl(url)` 必须与 `CrawlThrottle.afterCrawl(url)` 成对放在 `finally` 里；漏掉的后果是 F14（同域名漏 2 次 → 后续抓取无超时永久阻塞且不报错）；并附今日 4 个正确的调用点作为可抄的范例。

`CrawlThrottle.java`、`PageSession.java`、`BrowserSessionProvider.java` 三个文件**零改动**——这是 AC6 的直接判据。

## 4. R3：存档路径去重

```java
// ZsxqCrawler.java:76 与 ZsxqExplore.java:23 共同改成：
String stateHost = SiteLoginRegistry.hostOf(GROUP_URL);        // wx.zsxq.com
LoginStateStore store = new LoginStateStore(SiteLoginRegistry.defaultFileFor(stateHost));
```

（`ZsxqExplore` 用它的局部变量 `url`，`ZsxqCrawler` 用常量 `GROUP_URL`。）

选 `hostOf(<正在爬的 URL>)` 而不是字面量 `"wx.zsxq.com"`：站点应由"正在爬哪个 URL"决定，而不是由常量决定。

### 4.1 路径逐字节一致的论证（AC8 / R3.3）

| 来源 | 计算 |
|---|---|
| 改造前 | `expand("~/.config/JLRADemo/state/wx.zsxq.com.json")` → `user.home + "/.config/..."` → `Paths.get(..).toAbsolutePath().normalize()` |
| 改造后 | `DEFAULT_STATE_DIR.resolve("wx.zsxq.com.json")`，而 `DEFAULT_STATE_DIR = Path.of(user.home, ".config/JLRADemo/state")`（本身已绝对且规范） |

两者都得到 `<user.home>/.config/JLRADemo/state/wx.zsxq.com.json`。验证：改造前后各打印一次比对，不保留为测试。

另一条独立佐证：改造后走 `LoginStateStore(Path)` 构造器而不再是 `LoginStateStore(String)`，前者不做 `~` 展开（也不需要——`defaultFileFor` 返回的已经是绝对路径）。

### 4.2 明确不解决的（写清楚，避免误解）

CLI **读不到 yml 里的 `state-file`**（它不起 Spring），改造前后都是如此。今天没问题是因为 `application.yml` 给 `wx.zsxq.com` 显式写的 `state-file` 恰好等于默认路径。若日后给该站配**非默认** `state-file`，CLI 仍会去默认位置找——这是 D1 选择"不迁 provider"的已知代价，由 R4.2 的 spec 例外说明兜住。

## 5. R4：spec 同步（`browser-automation.md`）

1. **`:136`**：`browser.session.max-text-chars`（默认 8000）→ `web-text.max-chars`（默认 8000）。
2. **`:7-10`"唯一入口"节**：追加例外——`ZsxqCrawler` / `ZsxqExplore` 是**独立 CLI**，需要 `BrowserContext` 开多页（每帖一个详情页，`ZsxqCrawler.java:280`），而 `PageSession` 刻意只暴露 `page()`（`PageSession.java:48-62`）；强行收编会迫使 `PageSession` 放宽封装。写明触发条件与理由，让它读起来是决定，不是遗漏。
3. **"硬性规则"节**：加入 R2 的配对规则（§3.3）。

> **语言**：`backend/index.md` 写着 "All documentation should be written in English"，但该文件同时要求"Document your project's **actual** conventions"，而 `browser-automation.md` 全文是中文、项目代码注释也是中文。按"匹配周围文件"取中文。若英文约定是真要求，那是独立的一件事，另开任务，不塞进本次。

## 6. 兼容与迁移

- **配置**：唯一的破坏性改动是键改名（D2 已接受）。无外部消费者；`browser.session` 前缀下不留残键。
- **代码**：`BrowserSessionProperties` 的构造签名与静态工厂签名变化会影响 5 处 `of(...)` 调用 + 5 处三参构造（共 10 处，全在测试里），但全在编译期暴露（位置传参 ⇒ 编译器全拦）。无运行期兼容问题。
- **数据**：存档文件格式与路径都不变（R3 只改"路径从哪来"，不改"路径是什么"）。
- **无迁移脚本需求。**

## 7. 风险与回滚

| 风险 | 等级 | 处置 |
|---|---|---|
| R1 漏改测试调用点 | 低 | 位置传参 ⇒ 编译期必报，不可能静默漏。以 `mvn -o test` 为准 |
| R1 漏改某处引用导致绑定到不存在的键 | 低 | 依 F16 的 Pre-Modification Rule：改前 `grep -rn "maxTextChars\|max-text-chars"`，改后再 grep 一次；启动一次应用确认无绑定告警 |
| R1 误删 `of(...)` 导致测试编译不过 | 低 | F4 已查明它有调用方，保留改签名。若实现时发现判断有误，编译期即报 |
| R3 路径算错导致抓取退化为匿名且**不报错** | 中 | AC8 的逐字节比对是硬门槛；改造后首次跑 CLI 时确认日志里仍有"已登录浏览器（存档 …）"字样 |
| R3 误以为 CLI 会读 yml 配置 | 低 | §4.2 已写明；R4.2 的 spec 例外同步记录 |
| spec 改动引入与代码不符的描述 | 低 | AC9 逐条对照；R4 的三处改动点在 `implement.md` 里各带一条验证命令 |

**整体回滚**：全部改动集中在 4 个生产文件 + 1 个新增 + 2 个 yml + 5 个测试 + 1 个 spec，不涉及数据迁移。`git revert` 单个提交即可完全还原。三步各自可独立编译、独立回滚，边界见 `implement.md`。

---

## 8. R5（执行期追加）：`topicGuard` 的 bean 歧义

**这不是设计变更，是把代码恢复成它 javadoc 已经声明的语义。**

### 8.1 缺陷形状

`ZsxqTopicGuardConfig.topicGuard` 声明为：

```java
public TopicGuard topicGuard(ObjectProvider<OpenAiChatModel> classifyModelProvider)
```

作者意图在其 javadoc 里写得很清楚（`ZsxqTopicGuardConfig.java:24-26`）——接 `deepseekClassifyModel`，**按名**可选获取。
但 `ObjectProvider<OpenAiChatModel>` 是按**类型**解析的，而 `DeepSeekModelConfig` 里
`deepseekChatModel`（温度 1.3，聊天用）与 `deepseekClassifyModel`（温度 0.1，分类用）都是无条件注册的
同类型 bean。于是 `getIfAvailable()` 撞上 `NoUniqueBeanDefinitionException`。

故障位置与表现严重不对称：炸在**容器刷新期**，经 `topicGuard` → `zsxqCleaningService` 一路传播，
**整个 `DemoApplication` 上下文起不来**；而报错信息只说"找到 2 个候选"，不指向 `ZsxqTopicGuardConfig`。

### 8.2 修法

```java
public TopicGuard topicGuard(
        @Qualifier("deepseekClassifyModel") ObjectProvider<OpenAiChatModel> classifyModelProvider) {
```

### 8.3 为什么是 `@Qualifier`，不是别的

| 备选 | 否决理由 |
|---|---|
| 给 `deepseekClassifyModel` 加 `@Primary` | 影响全局：任何按类型要 `OpenAiChatModel` 的地方都会拿到低温度的**分类**模型。为一个消费点改全局默认，方向反了 |
| 改成 `ctx.getBean("deepseekClassifyModel", OpenAiChatModel.class)` | 丢掉"可选"语义——bean 不在时直接抛，正是 javadoc 要避免的 |
| `getIfAvailable()` 前自己 `stream()` 过滤名字 | 把一次可声明的限定写成运行期筛选，读的人得推一遍才知道要哪个 |
| **`@Qualifier` + `ObjectProvider`** | 在**注入点**限定，只影响这一处；且 Spring 对 `ObjectProvider` 的限定是惰性的——没有匹配 bean 时 provider 为空、`getIfAvailable()` 返回 null，回退链原样保留 |

### 8.4 回退链没被破坏的证据（现成的，不必新写测试）

`ZsxqCleaningServiceTest`（`ZsxqCleaningServiceTest.java:91-92`）构造的轻量上下文里**只有**
`ZsxqTopicGuardConfig` 与 `ZsxqCleaningService`，没有任何 `OpenAiChatModel` bean。
它保持绿色，就证明"取不到 → 回退"没被 `@Qualifier` 破坏。这是一条**既有**护栏 ——
`@Qualifier` 若把可选语义改成必需语义，它会立刻变红。

### 8.5 为什么不新写测试

缺陷是"两个同类型 bean 共存时才发生"，而复现它需要拉起完整的 `DeepSeekModelConfig` 上下文。
`AIChatServiceChatTest` / `AIChatServiceScreenshotTest` / `AIChatServiceStreamTest`（三个都以
`classes = [DemoApplication]` 起全量上下文）已经是这个场景的现成覆盖：修复前它们是红的，修复后转绿，
本身就是回归证据。再写一个同形状的测试只是重复。
