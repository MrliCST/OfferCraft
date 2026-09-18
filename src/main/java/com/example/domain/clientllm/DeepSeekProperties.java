package com.example.domain.clientllm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepSeek 的连接参数，统一从 yml 的 {@code llm.deepseek.*} 绑过来。
 * record 走构造器绑定：yml 里写 kebab-case（base-url），Spring 自动对上驼峰（baseUrl）。
 * 注册靠 {@link DeepSeekModelConfig} 上的 {@code @EnableConfigurationProperties}，不用额外加 @Component。
 *
 * @param apiKey    API key，yml 里取自环境变量 DEEPSEEK_WIN_KEY 或 ~/.config/JLRADemo/secret.yml，别写死在文件里
 * @param baseUrl   服务地址
 * @param modelName 模型名，如 deepseek-flash
 */
@ConfigurationProperties(prefix = "llm.deepseek")
public record DeepSeekProperties(String apiKey, String baseUrl, String modelName) {
}
