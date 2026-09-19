package com.example.domain.clientllm;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;

/**
 * 模型客户端的领域配置：DeepSeek 的两种调用形态各注册一个 bean，**bean 名就是方法名**。
 * 消费方按名字点名 —— AI 服务那边是
 * {@code @AiService(chatModel = "deepseekChatModel", streamingChatModel = "deepseekStreamModel")}。
 *
 * <p>为什么不让 starter 自动配置去建：yml 里同一段配置只能给一个温度，阻塞式和流式没法分开调。
 * 现在两种形态各是一个 bean，各自带自己的采样策略，要改哪个动哪个。
 * 代价是 yml 里的 {@code langchain4j.open-ai.*} 段必须删掉 —— 自动配置的条件就是那几个 api-key 键存在，
 * 留着它会在容器里再造一份同款模型 bean，跟这里重复。
 *
 * <p>没显式传 httpClientBuilder：builder 内部会用 ServiceLoader 找实现，
 * classpath 上 langchain4j-http-client-spring-restclient 注册的 Spring 版就是唯一实现，
 * 走的是 RestClient，和之前 starter 那条路等价（RestClient 用裸 builder 建，不再注入容器的定制实例）。
 *
 * <p>连接参数（api-key / base-url / model-name）不在这里散着读，统一由 {@link DeepSeekProperties} 从 yml 绑；
 * 这个类只负责把它们和采样策略组装成模型。
 */
@Configuration
@EnableConfigurationProperties(DeepSeekProperties.class)
@RequiredArgsConstructor
public class DeepSeekModelConfig {

    private static final double CHAT_TEMPERATURE = 1.3;
    private static final double STREAM_TEMPERATURE = 0.7;

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final DeepSeekProperties properties;

    @Bean
    public OpenAiChatModel deepseekChatModel() {
        return OpenAiChatModel.builder()
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .modelName(properties.modelName())
                .temperature(CHAT_TEMPERATURE)
                .timeout(TIMEOUT)
                .logRequests(true)
                .logResponses(true)
                .build();
    }
    @Bean
    public OpenAiStreamingChatModel deepseekStreamModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .modelName(properties.modelName())
                .temperature(STREAM_TEMPERATURE)
                .timeout(TIMEOUT)
                .returnThinking(true)
                .logRequests(true)
                // 流式每片响应都打日志会刷屏，排查完再打开
                .logResponses(false)
                .build();
    }

    /**
     * 分类/抽取闸专用：低温度、确定性。知识星球帖子分类（S2 Topic Guard）和 Q4 金句抽取要求稳定输出，
     * 不能沿用聊天模型 1.3 的创造性温度。复用同一套连接参数，只把采样策略压到 0.1。
     */
    @Bean
    public OpenAiChatModel deepseekClassifyModel() {
        return OpenAiChatModel.builder()
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .modelName(properties.modelName())
                .temperature(0.1)
                .timeout(TIMEOUT)
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
