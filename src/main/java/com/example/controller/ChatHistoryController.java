package com.example.controller;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.service.ChatHistoryService;

import dev.langchain4j.data.message.ChatMessageSerializer;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 记忆接口层：看现在记着什么、把它清掉，不涉及提问。
 * 对话接口在 {@link ChatController}，两边共用 /chat 前缀，路由与拆分前完全一致。
 * 异常统一由 exception 包的 GlobalExceptionHandler 兜，本类不再写 @ExceptionHandler。
 */
@RestController
@RequestMapping("/chat/history")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    /** 当前记忆里的原始消息，逐条走 langchain4j 的序列化器转成 JSON 字符串返回 */
    @GetMapping
    public Mono<List<String>> history() {
        // ChatMessage 是接口，直接交给 Jackson 会序列化成 {}，用 langchain4j 自己的序列化器
        return blocking(() -> chatHistoryService.history().stream()
                .map(ChatMessageSerializer::messageToJson)
                .toList());
    }

    /** 清空记忆，返回个固定串给前端确认 */
    @DeleteMapping
    public Mono<String> clear() {
        return blocking(() -> {
            chatHistoryService.clear();
            return "cleared";
        });
    }

    /**
     * 与 ChatController.blocking 同一套道理：记忆读取可能落到 JDBC（阻塞），必须挪出 Netty 事件循环线程。
     * 放在接口层是刻意的 —— 调度属于"怎么把结果送出去"，服务层只关心业务本身。
     */
    private static <T> Mono<T> blocking(Supplier<T> call) {
        return Mono.fromCallable(call::get).subscribeOn(Schedulers.boundedElastic());
    }
}
