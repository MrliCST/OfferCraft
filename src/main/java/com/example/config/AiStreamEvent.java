package com.example.config;

import java.util.Map;

/**
 * 推给前端的流式事件：事件名 + 正文 + 附加信息。
 * 序列化后**一行一个 JSON 对象**（NDJSON），事件名放在 body 里，
 * 不占用 SSE 的 event: / data: 字段——那套前缀对纯 JSON 客户端是多余的一层包装。
 *
 * @param event 事件名，取值见本类的常量
 * @param data  正文；done 这种没有正文的事件给空串，保证前端拿到的永远是字符串，不用判 null
 * @param meta  附加信息（工具名、调用 id 之类）；没有就给空 Map，同样不返回 null
 */
public record AiStreamEvent(String event, String data, Map<String, Object> meta) {

    /** 回答正文的增量，前端按顺序拼起来就是完整回答 */
    public static final String EVENT_DELTA = "delta";
    /** 思考过程，模型返回 reasoning 时才有 */
    public static final String EVENT_THINKING = "thinking";
    /** 工具调用参数还在拼，此时可能是半截 JSON */
    public static final String EVENT_TOOL_CALL = "tool-call";
    /** 工具调用参数拼完 */
    public static final String EVENT_TOOL_CALL_DONE = "tool-call-done";
    /** 全部结束 */
    public static final String EVENT_DONE = "done";
    /** 出错，data 里是错误信息 */
    public static final String EVENT_ERROR = "error";

    /** 没有附加信息的事件走这个 */
    public static AiStreamEvent of(String event, String data) {
        return new AiStreamEvent(event, data, Map.of());
    }
}
