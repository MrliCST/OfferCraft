# Browser Automation (Playwright) Guidelines

> 项目里所有"打开网页"的能力（长截图、抓正文，以及以后可能加的）都遵循这里的约定。

---

## 唯一入口：BrowserSessionProvider

**不要自己 `playwright.chromium().launch(...)`。** 任何需要打开网页的新工具，一律走
`domain/browser/BrowserSessionProvider`，它负责决定用哪种浏览器、带不带登录态，并负责正确关闭。

三种模式按配置优先级（CDP 端点 → 存档注入 → 无头），没配就是无头，行为与没有该功能时一致。

---

## 三种模式的关闭语义不同（最容易出事的地方）

| 模式 | 拿到的东西 | `close()` 的实际效果 |
|---|---|---|
| CDP（接管） | `Browser` | **只断开连接**，不会关掉用户的 Chrome |
| STORED（存档） | `Browser` | 关掉自己启动的那个浏览器 |
| HEADLESS | `Browser` | 关掉自己启动的那个浏览器 |

`PageSession` 已经把这些差异封装好了，用 try-with-resources 即可，不要在外部再手动关一层。

> 历史上的 `PROFILE`（用户数据目录）模式已移除：它依赖一份会过期的 profile 副本，且实测在知识星球上无头下拿不到登录态。
> 现在登录态统一走 **STORED 存档注入**（见下）。

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

### 站点名单：登录态只发给指定的站

**默认策略是白名单**——只有 `browser.session.sites` 里列出的站点才会被注入登录态，其余一律匿名。
这样"顺手抓个陌生站"不会把你唯一的登录态也带过去。

```yaml
browser:
  session:
    sites:
      - host: wx.zsxq.com         # 精确匹配，只管这一个主机
        state-file: ~/.config/JLRADemo/state/wx.zsxq.com.json
      - host: i.zhaopin.com       # 名单里再加一个站点即可，无需其它配置
        state-file: ~/.config/JLRADemo/state/i.zhaopin.com.json
      - host: .zsxq.com           # 点号开头 = 后缀匹配，管 zsxq.com 及所有子域
        # state-file 不写，就落在 ~/.config/JLRADemo/state/<host>.json
```

规则：

- 匹配**忽略大小写**，但**不自动补** `www.` / `m.` —— 猜错比不猜更糟，要用哪个就写出来；
- 每个站点一份存档文件。重存某个过期站点不会波及其它站；
- 命中名单但存档文件还不存在时**不报错**，只打一条 warning 后按匿名继续（"配了名单还没存档"是常见中间状态）。

存档一条命令搞定（接管已登录的 Chrome，把里面每个站点各存一份）：

```bash
mvn -o -q compile exec:java
# 可选参数：调试端口（默认 9222）、输出目录、只存 host 含这个串的站点
mvn -o -q compile exec:java -Dexec.args="http://127.0.0.1:9222 ~/.config/JLRADemo/state zsxq"
```

> 名单之外的站点**永远匿名**，没有"名单外也给登录态"的开关——那等于把登录态发给任意站点，风险远大于方便。

---

### 登录态的三种来源（按优先级）

| 优先级 | 模式 | 适用 | 代价 |
|---|---|---|---|
| 1 | `CDP` 接管常开浏览器 | 任何站点，最准 | 浏览器必须一直开着；默认 profile 下端口不监听，要用非默认 `--user-data-dir` |
| 2 | `STORED` 存档恢复（**推荐**） | 任何站点 | 登录态会过期，过期重存一次 |
| 3 | `HEADLESS` 无头 | 公开页面 | 无登录态 |

> `PROFILE`（用户数据目录）模式已移除，登录态统一走 STORED。

**存档（STORED）是首选**：登录成功时把 `cookie + localStorage + sessionStorage` 抄成
`<host>.json` + `<host>-session.json`，之后无头启动再灌回去。它同时解决了两件事——
不用养常开浏览器，也不受 sessionStorage"关窗即失效"的限制（因为主动写回）。
注意 HttpOnly 的 cookie 只能用 Playwright 的 storageState 写，JS 写不进去。

### 一个具体站点的坑：知识星球（wx.zsxq.com）

实测数据（同一页面，抓回正文字数）：

| 模式 | 结果 |
|---|---|
| `HEADLESS` 无头 | 登录页，抓不到内容 |
| `CDP` 接管常开浏览器 | **10071 字**（真实内容） |
| `STORED` 存档恢复 + 无头 | **10071 字**（真实内容） |

> `PROFILE`（持久化用户目录）模式曾经测出"无头=登录页 / 有头=2109 字"——这是历史数据，该模式已移除，仅作对比保留。

结论：**知识星球的登录态实际落在 cookie + localStorage，不在 sessionStorage**（存档里 sessionStorage 是 `{}`，
但恢复后照样是登录态）。所以坑不是"sessionStorage 关窗即失效"，而是——

- （历史数据）持久化 profile 目录下的 cookie 在**无头**模式下不生效（有头也只到 2109 字，疑似副本里的 cookie 已过期/被判坏）；
  该 `PROFILE` 模式已移除，不再使用；
- 现在只有"实时接管（CDP）"或"把 cookie 抄成存档再注入（STORED）"这两种方式能拿到完整登录态。

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

> 以上两条（坑 1 / 坑 2）只在走 **CDP / PROFILE** 时才相关。STORED 存档绕开了复制 profile 的麻烦。

---

## 抓正文

取 `page.innerText("body")` 而不是 `page.content()`：后者连 script/style 一起给，
白占模型上下文。正文按 `browser.session.max-text-chars`（默认 8000）截断，
并在返回值里写明原文字数 —— 不截断会把模型上下文撑爆。
