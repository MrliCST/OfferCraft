package com.example.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.example.config.AiStreamEvent;
import com.example.domain.prompt.ChatPrompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式对话的冒烟测试：{@link AIChatService#stream} 返回 {@code Flux<AiStreamEvent>}，
 * 本地订上，收到一片打一片，看事件名和正文拼出来对不对。
 *
 * <p>服务层返回的就是 Flux（不是 SseEmitter，也不是阻塞字符串），所以这里不用看 Web 层，
 * 直接 {@code blockLast} 把流消费完 —— 在测试里本地打印，正是"流式输出"最省事的看法。
 *
 * <p>记忆不归测试管：AI 服务自己声明了 chatMemory = msgWindowsDB，读写由框架在调用链里做，
 * 这里只管把问题上交、把事件收下来。落库就落库，测试不绕过去改它。
 *
 * <p>会真调付费 API，key 从 {@code ~/.config/JLRADemo/secret.yml} 读（仓库外，不依赖环境变量），记忆那条线要连库。
 *
 * <p>跑在 test profile 下：application-test.yml 把日志压到 WARN、关掉 banner，控制台只留测试自己的打印。
 */
@SpringBootTest
@ActiveProfiles("test")
class AIChatServiceStreamTest {

    private static final String QUESTION = "用大白话讲讲 Spring Boot 的自动配置是怎么生效的。";

    /** 兜底时限，别让流卡住把测试挂死 */
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    @Autowired
    private AIChatService aiChatService;

    @Test
    void stream() {
        StringBuilder answer = new StringBuilder();
        // 一片片来的，段落标题各打一次就够
        AtomicBoolean thinkingHeaded = new AtomicBoolean();
        AtomicBoolean answerHeaded = new AtomicBoolean();
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> errorEvent = new AtomicReference<>();

        long start = System.currentTimeMillis();
        System.out.println("===== 提问：" + QUESTION + " =====");

        aiChatService.stream(new ChatPrompt(QUESTION))
            .doOnNext(event -> {
                switch (event.event()) {
                    case AiStreamEvent.EVENT_THINKING -> {
                        if (thinkingHeaded.compareAndSet(false, true)) {
                            System.out.println("\n【思考】");
                        }
                        System.out.print(event.data());
                    }
                    case AiStreamEvent.EVENT_DELTA -> {
                        if (answerHeaded.compareAndSet(false, true)) {
                            System.out.println("\n【回答】");
                        }
                        System.out.print(event.data());
                        answer.append(event.data());
                    }
                    case AiStreamEvent.EVENT_DONE -> {
                        done.set(true);
                        System.out.println("\n===== done，正文 " + answer.length()
                                + " 字，耗时 " + (System.currentTimeMillis() - start) + "ms =====");
                    }
                    case AiStreamEvent.EVENT_ERROR -> {
                        errorEvent.set(event.data());
                        System.out.println("\n===== error：" + event.data() + " =====");
                    }
                    default -> System.out.println("\n[" + event.event() + "] " + event.meta());
                }
                System.out.flush();
            })
            .blockLast(TIMEOUT);

        assertThat(errorEvent.get()).as("不应收到 error 事件").isNull();
        assertThat(done.get()).as("应以 done 事件收尾").isTrue();
        assertThat(answer).as("应收到增量正文").isNotEmpty();
    }
}
