package com.example.service;

import com.example.config.AiStreamEvent;
import com.example.domain.prompt.ChatPrompt;

import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

/**
 * 声明式 AI 服务：接口只声明方法，实现由 langchain4j 在启动时生成并注册成 bean。
 * 参数里的 {@link ChatPrompt} 由框架渲染成提示词正文；带 {@code Flux} 返回值的走流式模型。
 *
 * <p>tools = "webScreenshotTool"：把网页长截图工具挂上来，模型自己决定什么时候调
 * （见 domain/tool/WebScreenshotTool）。按 bean 名点名，跟 chatModel 一个路子。
 */

@AiService(
    wiringMode = AiServiceWiringMode.EXPLICIT,  //显式指定Bean,适合多模型或者多组件
    chatModel = "deepseekChatModel",
    streamingChatModel = "deepseekStreamModel",
    chatMemory = "msgWindowsDB",
    tools = {"webScreenshotTool", "webPageTextTool"}
)
public interface AIChatService {

    String chat(ChatPrompt prompt);

    Flux<AiStreamEvent> stream(ChatPrompt prompt);
}
