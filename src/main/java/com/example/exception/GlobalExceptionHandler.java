package com.example.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

import lombok.extern.slf4j.Slf4j;

/**
 * 全局异常处理：各层抛出来的错统一在这里翻译成 HTTP 状态码，Controller 里不再散落 @ExceptionHandler。
 * 单独成包是因为它横切所有 Controller，不属于哪一个接口。
 * WebFlux 下这里收到的是响应式栈自己的异常类型，跟 servlet 那套（MissingServletRequestParameterException）不通用。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 必填参数没传、参数类型对不上，比如 /chat 少给 question → 400 */
    @ExceptionHandler(ServerWebInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badInput(ServerWebInputException e) {
        log.warn("请求参数不合法: {}", e.getReason());
        return "请求参数不合法: " + e.getReason();
    }

    /** 参数不合法，比如 question 是空白串 → 400（不是 500） */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(IllegalArgumentException e) {
        log.warn("请求参数不合法: {}", e.getMessage());
        return e.getMessage();
    }
}
