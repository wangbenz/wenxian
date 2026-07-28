package com.wenxian.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class AdminApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AdminApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException error) {
        HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
        return ResponseEntity.status(status).body(Map.of(
                "message", error.getReason() == null ? "请求未能完成" : error.getReason(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception error) {
        String errorId = UUID.randomUUID().toString().substring(0, 8);
        log.error("管理台操作失败，错误编号 {}", errorId, error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "message", "操作失败，请稍后重试（错误编号 " + errorId + "）",
                "timestamp", Instant.now().toString()
        ));
    }
}
