package com.surf.ledger.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code());
        if (e.detail() != null && !e.detail().isBlank()) body.put("message", e.detail());
        return ResponseEntity.status(e.status()).body(body);
    }

    /** 缺参数、类型不对、JSON 坏了都是客户端问题，不该报 500 */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, Object>> badRequest(Exception e) {
        log.debug("bad request: {}", e.getMessage());
        return ResponseEntity.status(400).body(Map.of("error", "BAD_REQUEST"));
    }

    /** 路由不存在时给 404，而不是 500 INTERNAL（历史上漏挂 controller 就是这样被吃掉的） */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        log.debug("no route: {}", e.getResourcePath());
        return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(500).body(Map.of("error", "INTERNAL"));
    }
}
