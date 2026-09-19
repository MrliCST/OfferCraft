package com.example.domain.zsxq.classify;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

/**
 * 清洗管道「分类闸」的装配（Spring Boot 规范：装配集中在配置类，领域类只收注入、不自己 new 模型）。
 *
 * <p>只暴露一个 {@link TopicGuard} bean，按运行环境选择实现：
 * <ol>
 *   <li>完整 Spring Boot 应用（后续面试 agent）：{@code deepseekClassifyModel} bean 已存在 → 直接复用，
 *       走 {@link LangchainTopicGuard}（DeepSeek 低温度 LLM 闸，few-shot 来自 cankao 七类）。</li>
 *   <li>轻量离线上下文（{@code ZsxqCleaner} 的 {@code AnnotationConfigApplicationContext}，不 boot WebFlux）：
 *       没有该 bean，则回退到环境变量 {@code DEEPSEEK_WIN_KEY}——有就按 env 现建低温度模型走 LLM 闸，
 *       没有就走 {@link HeuristicTopicGuard}。</li>
 *   <li>两者都没有 → 纯启发式兜底（离线、确定性）。</li>
 * </ol>
 *
 * <p>用 {@link ObjectProvider} 接 {@code deepseekClassifyModel} 是把「可选依赖」做对的关键：
 * 轻量上下文不注册 {@code DeepSeekModelConfig}，provider 取空也不报错，从而无需 {@code application.yml}
 * 绑定、也不启动任何 Web 容器，适合「跑一次、长期不动」的批量清洗 + 高频 AI 辅助测试。
 *
 * <p><b>{@code @Qualifier} 不能省。</b>{@code DeepSeekModelConfig} 里有两个同类型的
 * {@link OpenAiChatModel} bean（{@code deepseekChatModel} 温度 1.3 用于聊天、{@code deepseekClassifyModel}
 * 温度 0.1 用于分类）。{@link ObjectProvider} 是按<b>类型</b>取的，不写名字就会撞上
 * {@code NoUniqueBeanDefinitionException} —— 而且是在容器刷新时炸，表现为整个应用起不来，
 * 报错信息也只说"找到 2 个候选"，不会指向这里。加上名字后语义才跟上面第 1 条一致：
 * 按名取，取不到返回 null，回退链照常走。
 */
@Configuration
public class ZsxqTopicGuardConfig {

    @Bean
    @Primary
    public TopicGuard topicGuard(
            @Qualifier("deepseekClassifyModel") ObjectProvider<OpenAiChatModel> classifyModelProvider) {
        OpenAiChatModel model = classifyModelProvider.getIfAvailable();
        if (model == null && hasDeepSeekKey()) {
            model = OpenAiChatModel.builder()
                    .apiKey(System.getenv("DEEPSEEK_WIN_KEY"))
                    .baseUrl(getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))
                    .modelName(getenv("DEEPSEEK_MODEL", "deepseek-chat"))
                    .temperature(0.1)
                    .build();
        }
        if (model != null) {
            TopicGuardAi ai = AiServices.create(TopicGuardAi.class, model);
            return new LangchainTopicGuard(ai, new HeuristicTopicGuard());
        }
        return new HeuristicTopicGuard();
    }

    private static boolean hasDeepSeekKey() {
        String k = System.getenv("DEEPSEEK_WIN_KEY");
        return k != null && !k.isBlank();
    }

    private static String getenv(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
