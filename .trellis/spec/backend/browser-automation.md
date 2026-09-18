# Browser Automation (Playwright) Guidelines

> 项目里所有"打开网页"的能力（长截图、抓正文，以及以后可能加的）都遵循这里的约定。

---

## 唯一入口：BrowserSessionProvider

**不要自己 `playwright.chromium().launch(...)`。** 任何需要打开网页的新工具，一律走
`domain/browser/BrowserSessionProvider`，它负责决定用哪种浏览器、带不带登录态，并负责正确关闭。

三种模式按配置优先级（CDP 端点 → 用户数据目录 → 无头），没配就是无头，行为与没有该功能时一致。

---

## 三种模式的关闭语义不同（最容易出事的地方）

| 模式 | 拿到的东西 | `close()` 的实际效果 |
|---|---|---|
| CDP（接管） | `Browser` | **只断开连接**，不会关掉用户的 Chrome |
| PROFILE | `BrowserContext` | 关掉自己启动的那个浏览器 |
| HEADLESS | `Browser` | 关掉自己启动的那个浏览器 |

`PageSession` 已经把这些差异封装好了，用 try-with-resources 即可，不要在外部再手动关一层。

---

## 硬性规则

1. **禁止让 Playwright 下载浏览器。** 一律用系统 Chrome（`channel = chrome`）。
   `BrowserConfig` 里通过 `CreateOptions.setEnv` 传了 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`；
   少了它，`Playwright.create()` 会去 CDN 下 Chromium，下不动就干等超时（曾导致启动卡 5 分钟）。
   这个开关写在代码里而不是靠环境变量，是为了让终端 / VS Code / `java -jar` 三种方式都生效。
2. **CDP 模式不要调 `page.setViewportSize()`。** 连的是用户正在用的浏览器，
   它会真的改动用户窗口大小（走 CDP Emulation），用户能看见窗口跳一下。截图宽度如实上报即可。
3. **URL 校验放在 Provider 里**（只放行 http/https），工具层不要各写一遍。

---

### 登录态的四种来源（按优先级）

| 优先级 | 模式 | 适用 | 代价 |
|---|---|---|---|
| 1 | `CDP` 接管常开浏览器 | 任何站点，最准 | 浏览器必须一直开着；默认 profile 下端口不监听，要用非默认 `--user-data-dir` |
| 2 | `STORED` 存档恢复（**推荐**） | 任何站点 | 登录态会过期，过期重存一次 |
| 3 | `PROFILE` 指定用户目录 | 登录态在 cookie/localStorage 的站点 | 目录不能被另一个 Chrome 占用；cookie 可能被判坏丢弃 |
| 4 | `HEADLESS` 无头 | 公开页面 | 无登录态 |

**存档（STORED）是首选**：登录成功时把 `cookie + localStorage + sessionStorage` 抄成
`storage-state.json`，之后无头启动再灌回去。它同时解决了两件事——
不用养常开浏览器，也不受 sessionStorage"关窗即失效"的限制（因为主动写回）。
注意 HttpOnly 的 cookie 只能用 Playwright 的 storageState 写，JS 写不进去。

### 一个具体站点的坑：知识星球（wx.zsxq.com）

实测数据（同一页面，抓回正文字数）：

| 模式 | 结果 |
|---|---|
| `HEADLESS` 无头 | 登录页，抓不到内容 |
| `PROFILE` 持久化目录 + 无头 | 登录页，抓不到内容 |
| `PROFILE` 持久化目录 + 有头 | 2109 字（只有公开介绍页） |
| `CDP` 接管常开浏览器 | **10071 字**（真实内容） |
| `STORED` 存档恢复 + 无头 | **10071 字**（真实内容） |

结论：**知识星球的登录态实际落在 cookie + localStorage，不在 sessionStorage**（存档里 sessionStorage 是 `{}`，
但恢复后照样是登录态）。所以坑不是"sessionStorage 关窗即失效"，而是——

- 持久化 profile 目录下的 cookie 在**无头**模式下不生效（有头也只到 2109 字，疑似副本里的 cookie 已过期/被判坏）；
- 只有"实时接管"或"把 cookie 抄成存档再注入"这两种方式能拿到完整登录态。

sessionStorage 的提取与注入代码保留：它对本站点是空操作，但对**确实**把登录态放 sessionStorage 的站点是唯一的解法，
成本可以忽略。

### 坑 1：默认用户数据目录下，调试端口根本不监听
Chrome 136+ 出于安全考虑，使用**默认** `--user-data-dir` 时会直接忽略 `--remote-debugging-port`，
进程照常起来、端口却不监听，表现是"命令明明执行了，9222 就是连不上"。

要走 CDP 这条路，必须同时指定一个**非默认**的 `--user-data-dir`。

### 坑 2：复制 profile 时漏掉 WAL，cookie 会几乎全丢
Chrome 运行时，最新的 cookie 还在 `Cookies-wal` 里。只复制 `Cookies` 会拿到一份近乎空的旧库
（实测 1273 条 → 1 条）。

必须 `cp Cookies*`（带通配符），并且一并复制 `Local State`（cookie 的解密密钥，
缺了它 cookie 全是乱码）和 `Local Storage` / `IndexedDB`（很多站把 token 存在这里，
光复制 cookie 不够）。

### 副本是快照，会过期
程序用的是 profile **副本**（正本被 Chrome 占着）。正本里重新登录或 token 刷新后，
不同步就会掉线，表现为"代码没动，突然又抓到登录墙"。

同步命令：`./scripts/sync-chrome-profile.sh`

---

## 抓正文

取 `page.innerText("body")` 而不是 `page.content()`：后者连 script/style 一起给，
白占模型上下文。正文按 `browser.session.max-text-chars`（默认 8000）截断，
并在返回值里写明原文字数 —— 不截断会把模型上下文撑爆。
