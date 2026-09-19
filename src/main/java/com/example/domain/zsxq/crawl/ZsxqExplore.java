package com.example.domain.zsxq.crawl;

import java.util.List;
import java.util.Map;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import com.example.domain.browser.LoginStateStore;

/**
 * 调试探测脚本
 * 一次性探查（第六轮）：dump 单帖 app-topic 内部结构（去水印）。
 */
public final class ZsxqExplore {

    public static void main(String[] args) throws Exception {
        String url = "https://wx.zsxq.com/group/51121244585524";
        LoginStateStore store = new LoginStateStore("~/.config/JLRADemo/state/wx.zsxq.com.json");

        Map<String, String> env = Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        try (Playwright pw = Playwright.create(new Playwright.CreateOptions().setEnv(env))) {
            Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions()
                    .setChannel("chrome")
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

            Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions();
            store.applyTo(ctxOpts);
            BrowserContext context = browser.newContext(ctxOpts);
            String init = store.sessionStorageInitScript();
            if (!init.isEmpty()) {
                context.addInitScript(init);
            }

            Page page = context.newPage();
            page.navigate(url);
            Thread.sleep(5000);
            clickChip(page, "RagentAI");
            Thread.sleep(3000);
            for (int i = 0; i < 4; i++) {
                page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                Thread.sleep(1200);
            }
            Thread.sleep(1500);

            String html = page.evaluate("() => {\n"
                    + "  const it = document.querySelector('app-topic[type=\"flow\"]');\n"
                    + "  if (!it) return 'NONE';\n"
                    + "  let h = it.outerHTML;\n"
                    + "  h = h.replace(/background-image:url\\(data:image\\/png;base64,[^)]*\\)/g, 'WM-STRIPPED');\n"
                    + "  return h.slice(0, 6000);\n"
                    + "}").toString();
            System.out.println("=== 首帖 app-topic 内部结构(去水印,前6000) ===");
            System.out.println(html);

            // 再用结构化方式抽前 3 帖的 作者/角色/时间/正文/评论数
            String structured = page.evaluate("() => {\n"
                    + "  const items = [...document.querySelectorAll('app-topic[type=\"flow\"]')].slice(0,3);\n"
                    + "  return items.map(it => {\n"
                    + "    const txt = it.innerText.replace(/\\n+/g,' | ');\n"
                    + "    return txt.slice(0, 800);\n"
                    + "  }).join('\\n\\n=====POST=====\\n\\n');\n"
                    + "}").toString();
            System.out.println("=== 前3帖 innerText（结构化预览）===");
            System.out.println(structured);

            context.close();
            browser.close();
        }
    }

    private static void clickChip(Page page, String name) {
        for (ElementHandle el : page.querySelectorAll("div.item")) {
            try {
                String t = el.innerText();
                if (t != null && t.contains(name)) {
                    el.click();
                    return;
                }
            } catch (Exception ignored) {
            }
        }
    }
}
