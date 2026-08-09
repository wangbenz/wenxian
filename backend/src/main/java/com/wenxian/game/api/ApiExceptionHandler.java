package com.wenxian.game.api;

import com.wenxian.game.auth.GameMaintenanceException;
import com.wenxian.game.persistence.OperationErrorStore;
import com.wenxian.game.service.PublicTurnLimitException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final Pattern SAVE_PATH = Pattern.compile("/saves/([^/]+)");
    private static final DateTimeFormatter REFERENCE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final OperationErrorStore errorStore;

    public ApiExceptionHandler(OperationErrorStore errorStore) {
        this.errorStore = errorStore;
    }

    @ExceptionHandler(GameApiException.class)
    public ResponseEntity<ApiError> game(GameApiException error, HttpServletRequest request) {
        return respond(error.status(), error.code(), error.category(), error.title(), error.getMessage(),
                error.suggestions(), error.retryable(), error.turnConsumed(), error.stateChanged(),
                error.details(), error.diagnostic(), error, request);
    }

    @ExceptionHandler(GameMaintenanceException.class)
    public ResponseEntity<ApiError> maintenance(GameMaintenanceException error, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", error.title());
        details.put("resumeAt", error.resumeAt() == null ? "" : error.resumeAt());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "GAME_MAINTENANCE", "ACCOUNT",
                error.title(), error.getMessage(), List.of("稍后重新进入游戏"), true,
                false, false, details, "maintenance access policy", error, request);
    }

    @ExceptionHandler(PublicTurnLimitException.class)
    public ResponseEntity<ApiError> publicTurnLimit(PublicTurnLimitException error, HttpServletRequest request) {
        return respond(HttpStatus.TOO_MANY_REQUESTS, "PUBLIC_TURN_LIMIT_REACHED", "QUOTA",
                "赠送回合已经用完", error.getMessage(), List.of("接入自己的推演密钥", "加入测试群了解后续安排"),
                false, false, false,
                Map.of("turnLimit", error.turnLimit(), "contactText", error.contactText(),
                        "supportGroup", error.supportGroup()), "public turn limit reached", error, request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> status(ResponseStatusException error, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
        String message = error.getReason() == null ? status.getReasonPhrase() : error.getReason();
        FailureDefaults defaults = defaults(status);
        return respond(status, defaults.code(), defaults.category(), defaults.title(), message,
                defaults.suggestions(), defaults.retryable(), false, false, Map.of(),
                diagnostic(error), error, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException error, HttpServletRequest request) {
        String message = error.getBindingResult().getFieldErrors().stream().findFirst()
                .map(value -> value.getField() + "：" + value.getDefaultMessage())
                .orElse("请求参数不正确");
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "INPUT", "填写内容需要调整",
                message, List.of("检查标红字段后重新提交"), false, false, false, Map.of(),
                diagnostic(error), error, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> illegalArgument(IllegalArgumentException error, HttpServletRequest request) {
        String message = error.getMessage() == null || error.getMessage().isBlank()
                ? "填写内容不符合当前要求" : error.getMessage();
        return respond(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "INPUT", "填写内容需要调整",
                message, List.of("根据提示修改后重新提交"), false, false, false, Map.of(),
                diagnostic(error), error, request);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clientDisconnected(AsyncRequestNotUsableException error, HttpServletRequest request) {
        LOGGER.info("客户端已在响应完成前断开连接: {} {}", request.getMethod(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception error, HttpServletRequest request) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "UNEXPECTED_ERROR", "SYSTEM", "命卷暂时无法响应",
                "本次操作没有完成，请稍后重试。若重复出现，请将问题编号发给管理员。",
                List.of("稍后重新尝试", "将问题编号发给管理员"), true, false, false, Map.of(),
                diagnostic(error), error, request);
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String category, String title,
                                             String message, List<String> suggestions, boolean retryable,
                                             boolean turnConsumed, boolean stateChanged, Map<String, Object> details,
                                             String diagnostic, Throwable error, HttpServletRequest request) {
        String referenceId = referenceId();
        record(referenceId, request, category, code, status.value(), message, diagnostic);
        if (status.is5xxServerError()) {
            LOGGER.error("{} {} {} {}", referenceId, request.getMethod(), request.getRequestURI(), code, error);
        } else {
            LOGGER.warn("{} {} {} {}: {}", referenceId, request.getMethod(), request.getRequestURI(), code, message);
        }
        return ResponseEntity.status(status).body(new ApiError(
                code, category, title, message, suggestions == null ? List.of() : suggestions,
                retryable, turnConsumed, stateChanged, referenceId,
                details == null ? Map.of() : details, Instant.now()));
    }

    private void record(String referenceId, HttpServletRequest request, String category, String code,
                        int status, String message, String diagnostic) {
        try {
            errorStore.record(referenceId, saveId(request.getRequestURI()),
                    request.getMethod() + " " + request.getRequestURI(), category, code,
                    status, message, diagnostic);
        } catch (Exception storeError) {
            LOGGER.warn("{} 问题记录写入失败: {}", referenceId, storeError.getMessage());
        }
    }

    private String referenceId() {
        return "WX-" + REFERENCE_DATE.format(Instant.now()) + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private String saveId(String uri) {
        Matcher matcher = SAVE_PATH.matcher(uri == null ? "" : uri);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String diagnostic(Throwable error) {
        StringBuilder value = new StringBuilder(error.getClass().getSimpleName());
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth++ < 4) {
            if (cursor.getMessage() != null && !cursor.getMessage().isBlank()) {
                value.append(": ").append(cursor.getMessage());
            }
            cursor = cursor.getCause();
        }
        return value.toString();
    }

    private FailureDefaults defaults(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> new FailureDefaults("INVALID_REQUEST", "INPUT", "填写内容需要调整",
                    List.of("检查输入后重新提交"), false);
            case UNAUTHORIZED -> new FailureDefaults("AUTHENTICATION_REQUIRED", "ACCOUNT", "需要重新登录",
                    List.of("重新输入邮箱和密码"), false);
            case FORBIDDEN -> new FailureDefaults("ACCESS_DENIED", "ACCOUNT", "当前账号无法执行此操作",
                    List.of("返回上一页", "联系管理员核对账号状态"), false);
            case NOT_FOUND -> new FailureDefaults("NOT_FOUND", "STATE", "没有找到对应记录",
                    List.of("刷新页面后重新进入"), false);
            case CONFLICT -> new FailureDefaults("STATE_CONFLICT", "STATE", "命卷状态已经变化",
                    List.of("刷新当前命卷后继续"), false);
            case TOO_MANY_REQUESTS -> new FailureDefaults("REQUEST_LIMITED", "ACCOUNT", "操作过于频繁",
                    List.of("稍后再试"), true);
            case SERVICE_UNAVAILABLE -> new FailureDefaults("WORLD_UNAVAILABLE", "SYSTEM", "命卷暂时封存",
                    List.of("稍后重新进入游戏"), true);
            case BAD_GATEWAY, GATEWAY_TIMEOUT -> new FailureDefaults("DIVINATION_FAILED", "SYSTEM", "本次推演未能完成",
                    List.of("稍后重新尝试", "将问题编号发给管理员"), true);
            default -> new FailureDefaults("REQUEST_FAILED", "SYSTEM", "本次操作未能完成",
                    List.of("稍后重新尝试"), status.is5xxServerError());
        };
    }

    private record FailureDefaults(String code, String category, String title,
                                   List<String> suggestions, boolean retryable) {}

    public record ApiError(String code, String category, String title, String message,
                           List<String> suggestions, boolean retryable, boolean turnConsumed,
                           boolean stateChanged, String referenceId, Map<String, Object> details,
                           Instant timestamp) {}
}
