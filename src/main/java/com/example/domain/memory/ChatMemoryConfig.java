package com.example.domain.memory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

/**
 * 记忆管理的领域配置：三种实现各注册一个 bean，**bean 名就是方法名**，交给 Spring 管，本地不再自己维护 Map。
 * 谁用哪一个由消费方按名字点名 —— AI 服务那边就是 @AiService(chatMemory = "msgWindowsDB")。
 * 三个里 msgWindowsDB 标了 @Primary，也就是默认的那一个。
 */
@Configuration
public class ChatMemoryConfig {

    private static final int MAX_MESSAGES = 20;
    private static final int MAX_TOKENS = 2000;

    /**
     * 默认记忆：MessageWindow + 数据库，落库，进程重启后记忆还在。
     * @Primary 让按类型注入（不写 @Qualifier）的地方拿到它 —— 少了它容器里三个 ChatMemory
     * 会直接报 "expected single matching bean but found 3"。
     */
    @Bean
    @Primary
    public ChatMemory msgWindowsDB(DbChatMemoryStore dbChatMemoryStore) {
        return MessageWindowChatMemory.builder()
                .id("msgWindowsDB")
                .maxMessages(MAX_MESSAGES)
                .chatMemoryStore(dbChatMemoryStore)
                .alwaysKeepSystemMessageFirst(true)
                .build();
    }

    /** MessageWindow + 内存，进程一停就没了 */
    @Bean
    public ChatMemory msgWindowsInMemory() {
        return MessageWindowChatMemory.builder()
                .id("msgWindowsInMemory")
                .maxMessages(MAX_MESSAGES)
                .chatMemoryStore(new InMemoryChatMemoryStore())
                .alwaysKeepSystemMessageFirst(true)
                .build();
    }

    /** TokenWindow + 内存，按 token 卡窗口 */
    @Bean
    public ChatMemory tokenWindowsInMemory() {
        // DeepSeek 没有公开 tokenizer，借 OpenAI 的 o200k 编码近似估算，只用来卡上下文长度，误差无所谓
        TokenCountEstimator estimator = new OpenAiTokenCountEstimator("gpt-4o");

        return TokenWindowChatMemory.builder()
                .id("tokenWindowsInMemory")
                .maxTokens(MAX_TOKENS, estimator)
                .chatMemoryStore(new InMemoryChatMemoryStore())
                .alwaysKeepSystemMessageFirst(true)
                .build();
    }
}
