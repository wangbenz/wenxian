package com.wenxian.v2.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class V2ExceptionHandler {
    @ExceptionHandler(V2ApiException.class)
    ResponseEntity<ErrorResponse> business(V2ApiException error) {
        return ResponseEntity.status(error.status()).body(new ErrorResponse(
                error.code(), error.category(), error.getMessage(), error.suggestions(), null, Instant.now()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> validation(Exception error) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "REQUEST_INVALID", "INPUT", "输入内容不完整或不符合当前规则。",
                List.of("检查必填信息后重新提交"), null, Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception error) {
        String reference = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                "SERVICE_UNAVAILABLE", "TECHNICAL", "命卷暂时无法继续处理，现有数据没有改变。",
                List.of("稍后使用同一操作重试", "向管理员提供问题编号"), reference, Instant.now()));
    }

    public record ErrorResponse(
            String code,
            String category,
            String message,
            List<String> suggestions,
            String problemReference,
            Instant timestamp
    ) {
    }
}
