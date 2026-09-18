# 研究：Playwright 带登录态访问网页的三条路

目标：让 Java 项目能"以登录状态"打开网页（截长图、抓正文），且**不引入 Python**。
调研对象：Playwright Java 1.57.0（本地仓库已有），系统 Chrome 151。

---

## 1. 三种入口的 API（已用 javap 逐一核对 1.57.0 签名）

| 入口 | 签名 | 返回 | 登录态来源 |
|---|---|---|---|
| 无头启动 | `chromium().launch(LaunchOptions)` | `Browser` | 无 |
| 接管已开的浏览器 | `chromium().connectOverCDP(String endpoint)` | `Browser` | 用户当前浏览器里已登录的会话 |
| 用指定 profile 启动 | `chromium().launchPersistentContext(Path userDataDir, LaunchPersistentContextOptions)` | `BrowserContext` | 目录里存着的 cookie/登录态 |

`LaunchPersistentContextOptions` 支持 `setChannel / setHeadless / setViewportSize / setArgs / setTimeout`，
所以"用系统 Chrome + 指定 profile + 指定视口"是可以同时做到的。
`BrowserContext` 继承 `AutoCloseable`，可以用 try-with-resources。

---

## 2. 关键行为差异（决定了代码怎么写）

### 2.1 关闭语义完全不同 —— 最需要注意的一点
- `connectOverCDP(...).close()`：只是**断开连接**，不会关掉用户的 Chrome。✅ 可以放心关。
- `launchPersistentContext(...)` 得到的 `context.close()`：会**关掉整个浏览器进程**。
- 因此两种模式不能共用一套"用完就关"的代码：接管模式关 page 和连接即可；profile 模式关 context。

### 2.2 视口（viewport）
- profile 模式：可以在 options 里 `setViewportSize(w, h)`，跟无头模式一致。
- 接管模式：连的是用户正在用的浏览器，**没有独立视口**。强行 `page.setViewportSize()` 会真的改动用户浏览器窗口尺寸（走 CDP 的 Emulation），用户会看见窗口跳一下。
  → 决策：接管模式下**不强制视口**，用窗口实际尺寸；截图宽度因此不等于配置值，返回值里如实报告实际尺寸。

### 2.3 profile 目录的独占冲突
Chrome 对同一个 user-data-dir 有单例锁。如果用户日常就用默认 profile，而程序又拿同一个目录
`launchPersistentContext`，会启动失败或抢锁。
→ 决策：文档里明确要求"配 profile 目录时，该目录不能同时被另一个 Chrome 实例占用"，建议复制一份
 专用目录（例如 `~/.config/google-chrome-jlra`），第一次用它登录一次，之后就一直带登录态。

### 2.4 接管模式下会真的开标签页
`context.newPage()` 会在用户浏览器里新开一个可见标签页并跳转。
→ 决策：用完必须 `page.close()`，别把标签留给用户；跳转前保留原标签不动。

### 2.5 怎么让用户准备好"可被接管的 Chrome"
手动启动（Linux）：
```bash
/usr/bin/google-chrome --remote-debugging-port=9222 --user-data-dir=$HOME/.config/google-chrome &
```
启动后 `http://127.0.0.1:9222/json/version` 应返回 JSON。
`connectOverCDP` 传 `http://127.0.0.1:9222` 即可（Playwright 内部会去拿 webSocketDebuggerUrl）。

---

## 3. 抓正文怎么取

| 方法 | 得到什么 | 适用 |
|---|---|---|
| `page.content()` | 完整 HTML（含 script/style） | 太脏，不适合直接喂模型 |
| `page.innerText("body")` | 渲染后可见文本（不含标签、不含隐藏元素文本） | ✅ 主力 |
| `page.title()` | 标题 | 附带信息 |

长页面正文动辄几万字，直接塞回模型会撑爆上下文。
→ 决策：按字符数截断（可配，默认 8000），并在返回值里写明"原文 N 字，已截断"。

---

## 4. 与现有代码的关系

- `BrowserConfig` 里已经用 `CreateOptions.setEnv` 关掉了浏览器下载（见上次踩坑），新增代码复用同一个 `Playwright` 单例即可。
- 现有 `WebScreenshotTool` 的滚动 + fullPage 逻辑与"用哪个浏览器打开"是两件事，
  应当把"打开页面的方式"抽成独立的一层，截图/抓正文都复用它。
- `screenshot.*` 现有配置保持不变，新增一段登录态配置。

---

## 5. 结论

采用**"浏览器会话提供者"**这一层：
按配置优先级（CDP 端点 → profile 目录 → 无头启动）选出一种方式打开页面，
上层（截图、抓正文）只拿到一个 `Page`，不关心它背后是哪种浏览器。
这样新增任何"需要访问网页"的工具都只需要复用它。
