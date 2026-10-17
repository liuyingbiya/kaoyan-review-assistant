package com.aikb.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理。
 *
 * 作用：把后端抛出的异常统一转成 { message: ... } 的 JSON，
 * 而不是把 Java 堆栈直接甩给前端（既不安全，也没法展示）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        return body(e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> runtime(RuntimeException e) {
        log.error("业务异常", e);
        return body(e.getMessage() == null ? "服务器内部错误" : e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> other(Exception e) {
        log.error("未捕获异常", e);
        return body("服务器内部错误：" + e.getClass().getSimpleName());
    }

    private Map<String, Object> body(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("message", msg);
        return m;
    }
}
