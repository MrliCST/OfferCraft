package com.example.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.domain.prompt.ChatPrompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 长截图工具的"真·用法"测试：不直接调工具，把需求丢给模型，看它会不会自己挑工具、把路径报回来。
 *
 * <p>跟 {@link AIChatServiceChatTest} 同一条链路（同一个 AI 服务、同一份记忆），
 * 区别只在提问里带了一个 URL —— 模型应该判断出"这活儿得用工具"，而不是自己编一段文字糊弄。
 *
 * <p>会真调付费 API 并真开一次浏览器，所以放在需要环境的那一类测试里。
 *
 * <p>跑在 test profile 下：application-test.yml 把日志压到 WARN、关掉 banner，控制台只留测试自己的打印。
 */
@SpringBootTest
@ActiveProfiles("test")
class AIChatServiceScreenshotTest {

    private static final String QUESTION = "帮我把这个网页整页截成一张长图保存下来：https://www.example.com，"
            + "截完把文件的完整路径告诉我。";

    @Autowired
    private AIChatService aiChatService;

    @Test
    void llmCallsScreenshotToolAndReportsPath() {
        long start = System.currentTimeMillis();
        String answer = aiChatService.chat(new ChatPrompt(QUESTION));
        long cost = System.currentTimeMillis() - start;

        System.out.println("===== 提问：" + QUESTION + " =====");
        System.out.println(answer);
        System.out.println("===== 回答 " + answer.length() + " 字，耗时 " + cost + "ms =====");

        assertThat(answer).isNotBlank();
        // 路径只能来自工具返回值，模型回答里出现 .png 就说明它确实调了工具而不是自己编
        assertThat(answer).as("模型应把工具返回的图片路径报回来").contains(".png");
    }
}
