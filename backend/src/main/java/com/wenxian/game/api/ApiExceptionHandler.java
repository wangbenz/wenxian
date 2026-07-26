package com.wenxian.game.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> status(ResponseStatusException error) {
        HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
        String message = error.getReason() == null ? status.getReasonPhrase() : error.getReason();
        return ResponseEntity.status(status).body(new ApiError(code(status), message, Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream().findFirst()
                .map(value -> value.getField() + "：" + value.getDefaultMessage())
                .orElse("请求参数不正确");
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", message, Instant.now()));
    }

    private String code(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "INVALID_REQUEST";
            case UNAUTHORIZED -> "AUTHENTICATION_REQUIRED";
            case NOT_FOUND -> "NOT_FOUND";
            case CONFLICT -> "STATE_CONFLICT";
            case SERVICE_UNAVAILABLE -> "WORLD_UNAVAILABLE";
            case BAD_GATEWAY -> "DIVINATION_FAILED";
            default -> "REQUEST_FAILED";
        };
    }

    public record ApiError(String code, String message, Instant timestamp) {}
}
