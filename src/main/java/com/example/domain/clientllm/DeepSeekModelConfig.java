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

    /** 图片概括的采样温度：抽取任务要确定性，同图同描述，便于向量检索稳定。 */
    private static final double VISION_TEMPERATURE = 0.1;
    /**
     * 图片概括的输出上限。必须给足 —— 模型是推理型，reasoning token 会占掉一大块额度，
     * 加上「技术描述 + 图内文字 OCR」本身也长。给小了会只留下思考、拿不到正文。
     */
    private static final int VISION_MAX_TOKENS = 8000;

    /**
     * 视觉任务（看图说话）的超时。比普通对话长得多：图片要先经视觉编码器过一遍，
     * 推理模型还会在描述前"想"一阵，实测单张图的端到端耗时明显高于纯文本。
     */
    private static final Duration VISION_TIMEOUT = Duration.ofSeconds(180);

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

    /**
     * S4 图片概括专用：搬 {@code deepseek-flash} 的原生多模态视觉能力。
     *
     * <p><b>为什么不复用 {@link #deepseekClassifyModel()}</b>：分类闸是纯文本的短 JSON 输出，
     * 用默认 max_tokens 就够；而这个模型是<b>推理模型</b>，看图时会先"想"一大段
     * ——实测一张 64×64 纯色图，输出 76 个 token 里 <b>74 个是 reasoning</b>，
     * 真正的内容只占 2 个。若 max_tokens 不放大，reasoning 会把额度吃光，
     * 结果是 {@code finish_reason=length}、{@code content} 为空——调用方拿到空串还以为模型"罢工"了。
     * 所以这里单独把额度抬到 {@value #VISION_MAX_TOKENS}，两个模型各按自己的形态配。
     *
     * <p>温度仍取 0.1：图片概括是<b>抽取</b>不是<b>创作</b>，要的是同一张图稳定给出同一份描述，
     * 便于向量检索；温度高了描述会飘，同一张图两次跑出来召回位置都不一样。
     *
     * <p>超时给 {@link #VISION_TIMEOUT}（180s）：图先过视觉编码器，再加上推理耗时，比纯文本慢一截。
     */
    @Bean
    public OpenAiChatModel deepseekVisionModel() {
        return OpenAiChatModel.builder()
                .apiKey(properties.apiKey())
                .baseUrl(properties.baseUrl())
                .modelName(properties.modelName())
                .temperature(VISION_TEMPERATURE)
                .maxTokens(VISION_MAX_TOKENS)
                .timeout(VISION_TIMEOUT)
                .logRequests(true)
                // 描述文本会长（带 OCR），响应日志关掉避免刷屏；排查模型能力时再临时打开
                .logResponses(false)
                .build();
    }
}
