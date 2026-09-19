package com.example.domain.zsxq.image;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

/**
 * S4 图片概括的装配：视觉模型 + 概括器 + URL→ImageContent 转换。
 *
 * <p><b>为什么模型 bean 在这里又建了一份</b>：应用里跑时用 {@code DeepSeekModelConfig#deepseekVisionModel}
 * （走 {@code @ConfigurationProperties} 绑 yml）；但本管道的 CLI 入口是轻量
 * {@code AnnotationConfigApplicationContext}，<b>没有 Boot 的属性绑定基础设施</b>，
 * {@code @ConfigurationProperties} 起不来，yml 也不会被加载。
 * 所以这里像 {@link com.example.domain.zsxq.vector.ZsxqVectorConfig} 一样，
 * 直接读 {@link Environment}（环境变量 / -D 系统属性），把 CLI 这条路走通。
 *
 * <p>接口上仍保留 {@code @AiService} 注解：在应用内（starter 在场）由它自动装配；
 * CLI 里则由本配置手工 {@code AiServices.builder(...)} 装配。两条路共用同一个接口定义。
 */
@Configuration
public class ZsxqImageConfig {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_MODEL = "deepseek-flash";
    /** 图片概括的采样温度：抽取任务要确定性，同图同描述，便于向量检索稳定。 */
    private static final double TEMPERATURE = 0.1;
    /**
     * 输出上限必须给足：deepseek-flash 是推理模型，看图时会先"想"一大段
     * ——实测一张 64×64 纯色图，输出 76 token 里 74 个是 reasoning。
     * 额度给小了 reasoning 会吃光配额，结果 {@code finish_reason=length} 且正文为空。
     */
    private static final int MAX_TOKENS = 8000;
    /** 图先过视觉编码器、再加推理耗时，比纯文本慢一截。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(180);

    private final Environment env;

    public ZsxqImageConfig(Environment env) {
        this.env = env;
    }

    @Bean
    public ChatModel zsxqVisionModel() {
        String apiKey = firstNonBlank(
                env.getProperty("llm.deepseek.api-key"),
                env.getProperty("DEEPSEEK_WIN_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 DeepSeek 密钥：设 DEEPSEEK_WIN_KEY 环境变量，"
                    + "或写在 ~/.config/JLRADemo/secret.yml 里（仓库外，权限 600）。");
        }
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(firstNonBlank(env.getProperty("llm.deepseek.base-url"), DEFAULT_BASE_URL))
                .modelName(firstNonBlank(env.getProperty("llm.deepseek.model-name"), DEFAULT_MODEL))
                .temperature(TEMPERATURE)
                .maxTokens(MAX_TOKENS)
                .timeout(TIMEOUT)
                .logRequests(true)
                // 描述带 OCR 文本会长，响应日志关掉避免刷屏
                .logResponses(false)
                .build();
    }

    /**
     * 图片概括器。单测里用同一个方法塞 fake {@link ChatModel} 造实例，不必启动容器。
     *
     * <p>bean 名 {@code zsxqImageDescriber} 与 {@code @AiService} 自动生成的名字保持一致，
     * 避免「应用里注进来的是一个、CLI 里是另一个」这种隐性分叉。
     */
    @Bean
    public ZsxqImageDescriberAi zsxqImageDescriber(ChatModel zsxqVisionModel) {
        return AiServices.builder(ZsxqImageDescriberAi.class)
                .chatModel(zsxqVisionModel)
                .build();
    }

    /**
     * 图片 URL → {@link ImageContent}。知识星球的图是公开 CDN 地址，模型侧能直接拉取，
     * 不需要下载再转 base64。收成一个 bean 是为了让这个决策集中一处：
     * 将来若某些图防盗链必须走 base64，只改这里。
     */
    @Bean
    public ZsxqImageContentFactory zsxqImageContentFactory() {
        return ImageContent::from;
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}
