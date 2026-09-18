# Design：登录态网页访问

## 1. 总体思路

把"**用什么浏览器、带不带登录态打开页面**"抽成独立一层，上层工具（截图、抓正文）只拿到一个
`Page`，不关心背后是哪种浏览器。

```
LLM 工具层      WebScreenshotTool          WebPageTextTool
                      │                          │
                      └──────────┬───────────────┘
                                 ▼
                      BrowserSessionProvider   ← 按配置选模式、打开页面、负责关闭
                                 │
              ┌──────────────────┼──────────────────┐
              ▼                  ▼                  ▼
      connectOverCDP      launchPersistent-      launch()
      （接管已登录）      Context（profile）     （无头，现状）
```

好处：以后再加"登录后点某个按钮""抓表格"之类的工具，直接复用 provider，不用碰浏览器细节。

## 2. 配置（新增 `browser.session.*`）

```yaml
browser:
  session:
    # 优先级 1：接管已经打开的 Chrome（登录态来自用户当前会话）
    # cdp-endpoint: http://127.0.0.1:9222
    # 优先级 2：用这个用户数据目录启动（登录态存在目录里）
    # user-data-dir: ~/.config/google-chrome-jlra
    # profile 模式是否无头。默认 true：cookie 在目录里，无头同样带登录态，且不弹窗打扰。
    # 遇到检测 headless 的站点，改成 false。
    profile-headless: true
    # 抓正文的截断字数
    max-text-chars: 8000
```

**关键约束**：三个来源按"配了才算"判定，空串/不写就等于没配。没配任何一项 → 走现在的无头模式，
现有行为一字不改（这是验收标准第 1 条）。

## 3. 核心类

### 3.1 `BrowserSessionProperties`（record，`@ConfigurationProperties("browser.session")`）
字段：`cdpEndpoint`、`userDataDir`、`profileHeadless`、`maxTextChars`。
跟 `ScreenshotProperties` 一样：紧凑构造器里给兜底值，yml 不写也能跑。

### 3.2 `PageSession`（`AutoCloseable`）
一次"打开页面"的句柄，字段：
- `Page page`
- `SessionMode mode` — `CDP` / `PROFILE` / `HEADLESS`
- `String describe()` — 给模型/日志看的一句话，如 `已登录浏览器（接管 127.0.0.1:9222）`

`close()` 按模式做正确的事：
- CDP：关掉自己开的那个 page，**只断开连接**（`browser.close()` 不会关掉用户的 Chrome —— 已核实）
- PROFILE：`context.close()`（这个模式关的就是自己启动的浏览器）
- HEADLESS：关 page + browser

### 3.3 `BrowserSessionProvider`（`@Component`）
- `PageSession open(String url)`：选模式 → 打开/接管 → `newPage()` → `navigate(url)` → 等 DOM。
- 模式选择顺序：CDP → PROFILE → HEADLESS，并在 `log.info` 里说明选了哪个、为什么。
- 视口：CDP 模式**不调用** `setViewportSize`（会真改用户窗口大小，已核实）；另两种用 `screenshot.viewport-*`。
- 错误统一转成"人话"：
  - 连不上 9222 → `连不上 Chrome 的调试端口 9222：请先带 --remote-debugging-port=9222 启动 Chrome`
  - profile 被占用 → `用户数据目录 XXX 正被另一个 Chrome 占用，请先关掉它`
  - 打不开页面 → 原样带上超时/网络原因

### 3.4 改造 `WebScreenshotTool`
- 删除自己 `launchBrowser()` 的逻辑，改成从 provider 拿 `PageSession`。
- 滚动脚本、`fullPage` 截图、存盘、文件名逻辑**原样保留**。
- 返回值追加登录态说明：`（来源：已登录浏览器（接管 127.0.0.1:9222））`。
- 宽度不再无条件等于配置值（CDP 模式用实际窗口宽），返回值里报的是截图真实尺寸。

### 3.5 新增 `WebPageTextTool`
`@Tool(name = "fetch_page_text")`：
- 入参 `url`（必填）
- 取 `page.title()` + `page.innerText("body")`
- 超过 `maxTextChars` 就截断，并注明 `（原文 N 字，已截断到 M 字）`
- 返回里同样带登录态来源

### 3.6 注册
`@AiService(tools = {"webScreenshotTool", "webPageTextTool"})`。

## 4. 取舍与权衡

| 决策 | 选了什么 | 为什么不选另一边 |
|---|---|---|
| 跨语言 | 不用 Python / DrissionPage | 引入 Python 要多管一个运行环境和一个进程；Playwright 的 `connectOverCDP` 能达成同样的"接管已登录浏览器"，还少一层 |
| CDP 模式的视口 | 不强制，用窗口实际尺寸 | 强行设置会真的改动用户浏览器窗口，用户能看见窗口跳一下；截图宽度如实上报即可 |
| profile 模式是否无头 | 默认 true（无头） | 登录态在 cookie 里，无头同样有效，还不弹窗；留了开关应对检测 headless 的站点 |
| 关闭语义 | 按模式分别处理 | 这两种模式的 `close()` 语义不同（断开 vs 真关），混在一起会把用户的浏览器关掉 |
| 正文截断 | 默认 8000 字符 | 长页面几万字会撑爆模型上下文；截断同时告知原文长度 |

## 5. 兼容性 / 回滚

- 不改动 `screenshot.*` 配置，不改工具名 `capture_webpage_screenshot`，现有测试不需改断言。
- 新增配置全部可空，默认等于"不启用登录态"。
- 回滚方式：删掉 `browser.session.*` 配置即回到旧行为；代码层面删 Provider 相关类即可，
  `WebScreenshotTool` 只需把 `provider.open()` 换回 `playwright.chromium().launch(...)`。

## 6. 风险

- **profile 独占**：用户若把日常用的默认 profile 配进来，会与自己开着的 Chrome 抢锁。
  缓解：文档里明确要求用专用目录，并在启动失败时给出上面那条人话错误。
- **接管模式会在用户浏览器上开标签页**：用完必关；若进程被强杀可能残留一个标签页（可接受）。
- **反爬**：无头 + 自动化特征可能被识别。缓解：`profile-headless: false` 可切换为有头。
