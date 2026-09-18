package com.example.controller;

import java.util.function.Supplier;

import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.config.AiStreamEvent;
import com.example.domain.prompt.ChatPrompt;
import com.example.service.AIChatService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 对话接口层：只做参数接收、线程调度和结果返回。
 * 提问直接交给声明式 AI 服务；把 question 包成 {@link ChatPrompt} 就算拼完了，
 * 渲染成最终提示词文本由框架内部完成（见 {@link AIChatService} 的类注释）。
 * 记忆的查看与清空在 {@link ChatHistoryController}，两边共用 /chat 前缀。
 * question 是必填的，不设默认值；缺参数或传空白由 com.example.exception.GlobalExceptionHandler 转成 400。
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    /*
     * 方法一览（行号对应当前文件，增删方法后要同步更新）：
     * L44  chat     阻塞式对话，跑在 boundedElastic 上，等模型说完一次性返回
     * L57  stream   流式对话，返回 Flux<AiStreamEvent>，一行一个 JSON 往下推
     * L67  blocking 私有，把阻塞调用挪到 boundedElastic 线程池
     * 异常统一由 exception 包的 GlobalExceptionHandler 兜，本类不再写 @ExceptionHandler
     */

    private final AIChatService aiChatService;

    @GetMapping
    public Mono<String> chat(@RequestParam String question) {
        Assert.hasText(question, "question 不能为空");

        return blocking(() -> aiChatService.chat(new ChatPrompt(question)));
    }

    /**
     * 流式回答：不返回 SseEmitter，直接把 Flux 交给 WebFlux。
     * 响应体是 NDJSON（一行一个 {@link AiStreamEvent} 的 JSON），事件名在 body 里；
     * 不用 text/event-stream 是因为 WebFlux 见到那个 media type 就会套上 SSE 编码，
     * 每个事件前面多一行 data: 前缀，对纯 JSON 客户端是多余的。
     */
    @GetMapping(value = "/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<AiStreamEvent> stream(@RequestParam String question) {
        Assert.hasText(question, "question 不能为空");

        return aiChatService.stream(new ChatPrompt(question));
    }

    /**
     * 模型调用和 JDBC 都是阻塞的（一等等几十秒），必须挪出 Netty 的事件循环线程，否则一个请求就把 IO 线程占死。
     * 放在接口层是刻意的：调度属于"怎么把结果送出去"，AI 服务只关心业务本身。
     */
    private static <T> Mono<T> blocking(Supplier<T> call) {
        return Mono.fromCallable(call::get).subscribeOn(Schedulers.boundedElastic());
    }
}
