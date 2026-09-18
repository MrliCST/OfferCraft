package com.example.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.domain.prompt.ChatPrompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阻塞式对话的冒烟测试：调 {@link AIChatService#chat}，等模型把话说完再一次性拿回字符串。
 *
 * <p>走的正是生产那条链路：ChatPrompt 渲染成正文 → 按 bean 名点到的 deepseekChatModel → 记忆 msgWindowsDB。
 * 记忆是 AI 服务自己声明的，读写都在框架的调用链里，测试不另外注入 ChatMemory 去插手。
 *
 * <p>会真调付费 API，key 从 {@code ~/.config/JLRADemo/secret.yml} 读（仓库外，不依赖环境变量），记忆那条线要连库。
 *
 * <p>跑在 test profile 下：application-test.yml 把日志压到 WARN、关掉 banner，控制台只留测试自己的打印。
 */
@SpringBootTest
@ActiveProfiles("test")
class AIChatServiceChatTest {

    private static final String QUESTION = "给我截取一张https://wx.zsxq.com/group/51121244585524的长图";

    @Autowired
    private AIChatService aiChatService;

    @Test
    void chat() {
        long start = System.currentTimeMillis();
        String answer = aiChatService.chat(new ChatPrompt(QUESTION));
        long cost = System.currentTimeMillis() - start;

        System.out.println("===== 提问：" + QUESTION + " =====");
        System.out.println(answer);
        System.out.println("===== 正文 " + answer.length() + " 字，耗时 " + cost + "ms =====");

        assertThat(answer).isNotBlank();
    }
}
