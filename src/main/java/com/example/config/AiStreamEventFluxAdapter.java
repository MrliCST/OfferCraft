package com.example.config;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Objects;

import org.springframework.core.ResolvableType;

import dev.langchain4j.service.TokenStream;
import dev.langchain4j.spi.services.TokenStreamAdapter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * TokenStream → Flux 的钩子，以 langchain4j 的 SPI 形式挂上去：
 * {@code DefaultAiServices} 构造时会 {@code ServiceHelper.loadFactories(TokenStreamAdapter.class)}，
 * 之后遇到"返回类型不是 TokenStream 但需要流式"的接口方法，就遍历这些适配器，
 * 命中 {@link #canAdaptTokenStreamTo(Type)} 的那个负责 {@link #adapt(TokenStream)}，
 * 返回值直接当成方法结果交出去。注册文件见 resources/META-INF/services 下同名文件。
 *
 * <p>所以 {@code AIChatService.stream(...)} 可以直接声明返回 {@code Flux<AiStreamEvent>}——
 * 框架不认识这个类型，但认出了这个适配器。好处是事件名由我们定，而不是被框架的
 * {@code Flux<String>} 之类固定下来：正文、思考、工具调用各有各的事件名，全在 {@link AiStreamEvent} 里。
 *
 * <p>只认 {@code Flux<AiStreamEvent>} 这一种返回类型，别的（比如 {@code Flux<String>}）不抢：
 * 官方 langchain4j-reactor 的适配器认的正是 {@code Flux<String>}，两边各管各的，
 * 以后真把那个依赖加进来也不会撞。
 *
 * <p>本类由 ServiceLoader 直接 new，不是 Spring bean，别往里注入东西。
 */
@Slf4j
public class AiStreamEventFluxAdapter implements TokenStreamAdapter {

    /** 
     * 能提供 flux<AiStreamEvent> 的转换的适配器
     * @see dev.langchain4j.spi.services.TokenStreamAdapter#canAdaptTokenStreamTo(java.lang.reflect.Type)
     */
    @Override
    public boolean canAdaptTokenStreamTo(Type type) {
        ResolvableType resolvable = ResolvableType.forType(type);

        return resolvable.resolve() == Flux.class
                && resolvable.getGeneric(0).resolve() == AiStreamEvent.class;
    }

    /**
     * 挂回调、返回冷 Flux。框架不碰 start()，什么时候真发请求由订阅决定；
     * 一个 TokenStream 只能 start 一次，所以这个 Flux 也只该被订阅一次。
     *
     * <p>异常分两处收：一是 start() 当场就炸（参数不对、连不上），二是模型跑到一半报错。
     * 两种都翻成一条 error 事件推出去再正常收尾，而不是让流直接断掉——
     * 断了前端只看到"连接关闭"，读不到到底为什么失败。
     */
    @Override
    public Object adapt(TokenStream tokenStream) {
        return Flux.create(sink -> {
            try {
                tokenStream
                    .onPartialResponse(text -> sink.next(AiStreamEvent.of(AiStreamEvent.EVENT_DELTA, text)))
                    .onPartialThinking(thinking -> sink.next(
                            AiStreamEvent.of(AiStreamEvent.EVENT_THINKING, thinking.text())))
                    .onPartialToolCall(call -> sink.next(toolEvent(
                            AiStreamEvent.EVENT_TOOL_CALL, call.partialArguments(), call.name(), call.id())))
                    // TokenStream 层面没有"参数拼完"的回调，能拿到的是工具执行完这个节点
                    .onToolExecuted(execution -> sink.next(toolEvent(
                            AiStreamEvent.EVENT_TOOL_CALL_DONE,
                            execution.result(),
                            execution.request().name(),
                            execution.request().id())))
                    .onCompleteResponse(response -> {
                        sink.next(AiStreamEvent.of(AiStreamEvent.EVENT_DONE, ""));
                        sink.complete();
                    })
                    .onError(error -> {
                        sink.next(AiStreamEvent.of(AiStreamEvent.EVENT_ERROR,
                                String.valueOf(error.getMessage())));
                        sink.complete();
                    })
                    .start();
            } catch (Exception e) {
                log.error("流式请求发起失败", e);
                sink.next(AiStreamEvent.of(AiStreamEvent.EVENT_ERROR, String.valueOf(e.getMessage())));
                sink.complete();
            }
        });
    }

    /** 工具调用事件：正文放参数/结果，工具名和调用 id 放 meta（增量阶段这两个字段可能还没到，兜成空串） */
    private static AiStreamEvent toolEvent(String event, String data, String name, String id) {
        return new AiStreamEvent(event, data, Map.of(
                "name", Objects.requireNonNullElse(name, ""),
                "id", Objects.requireNonNullElse(id, "")));
    }
}
