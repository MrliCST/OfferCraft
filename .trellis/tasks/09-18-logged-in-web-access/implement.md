# Implement：登录态网页访问

任务目录：`.trellis/tasks/09-18-logged-in-web-access`
设计见 `design.md`，需求见 `prd.md`，调研见 `research/playwright-login-session.md`。

## 执行清单

### 步骤 1 — 配置类 `BrowserSessionProperties`
- [ ] 新建 `src/main/java/com/example/domain/browser/BrowserSessionProperties.java`
      record + `@ConfigurationProperties("browser.session")`，紧凑构造器兜底（空串视为未配置）
- [ ] `application.yml` 增加 `browser.session.*`（**全部注释掉**，保证默认不启用登录态）
- 验证：`mvn -q compile`

### 步骤 2 — 会话层 `PageSession` + `BrowserSessionProvider`
- [ ] 新建 `PageSession`（`AutoCloseable`，持有 page / mode / describe，`close()` 按模式分别处理）
- [ ] 新建 `BrowserSessionProvider`：`open(url)` 按 CDP → PROFILE → HEADLESS 选模式，
      CDP 模式不设视口，错误转人话，`log.info` 说明实际选中的模式
- 验证：`mvn -q compile`

### 步骤 3 — 改造 `WebScreenshotTool`
- [ ] 删掉自带的 `launchBrowser()`，改为 `provider.open(url)` + try-with-resources
- [ ] 保留滚动脚本 / fullPage / 存盘 / 文件名逻辑
- [ ] 返回值追加登录态来源说明；宽度改为报告截图实际宽度
- 验证：**`mvn -B test -Dtest=WebScreenshotToolTest`（必须仍 4/4 全绿 —— 这是向后兼容的硬闸）**

### 步骤 4 — 新增 `WebPageTextTool`
- [ ] 新建 `src/main/java/com/example/domain/tool/WebPageTextTool.java`
      `@Tool(name = "fetch_page_text")`，返回标题 + 正文，超长按 `max-text-chars` 截断并注明
- [ ] `AIChatService` 的 `tools` 改为 `{"webScreenshotTool", "webPageTextTool"}`
- 验证：`mvn -q compile`

### 步骤 5 — 测试
- [ ] 新建 `BrowserSessionProviderTest`
      - 未配置 → 模式是 HEADLESS，且能正常打开页面
      - 配了 CDP 端点但端口没开 → 返回的错误信息里带"9222"和"调试端口"
      - 配了 profile 目录（用 `@TempDir`）→ 模式是 PROFILE，能打开页面，目录被创建
- [ ] 新建 `WebPageTextToolTest`：抓一个公开页面，断言返回里有标题、正文非空、带来源说明
- 验证：`mvn -B test -Dtest='BrowserSessionProviderTest,WebPageTextToolTest'`

### 步骤 6 — 全量回归
- [ ] `mvn -B test`（全量，确认没有把现有用例带崩）
- [ ] 手动验证一次接管模式：
      ```bash
      /usr/bin/google-chrome --remote-debugging-port=9222 &
      curl -s http://127.0.0.1:9222/json/version | head -3     # 应返回 JSON
      ```
      临时把 `browser.session.cdp-endpoint` 打开，跑一次截图/抓正文，确认不留标签页、
      **用户浏览器不被关闭**；验完把配置重新注释掉。

## 评审点（Review gates）

- **步骤 3 之后**：确认向后兼容（未配置时行为不变）再继续 —— 这条不过就不要往下做。
- **步骤 5 之后**：确认三个模式的错误信息都是人话，再进全量回归。

## 回滚点

- 步骤 1~2 只新增文件 + 注释掉的配置，删掉即回滚，无破坏性。
- 步骤 3 改动了 `WebScreenshotTool`；如需回滚，把它恢复成自己 `launch()` 即可，
  provider 层可以整体删掉而不影响其他代码。
- 全程不删改现有 `screenshot.*` 配置与工具名，旧调用方无感知。
