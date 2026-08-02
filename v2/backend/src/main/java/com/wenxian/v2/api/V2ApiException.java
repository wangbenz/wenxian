package com.wenxian.v2.api;

import org.springframework.http.HttpStatus;

import java.util.List;

public final class V2ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String category;
    private final List<String> suggestions;

    public V2ApiException(HttpStatus status, String code, String category, String message, List<String> suggestions) {
        super(message);
        this.status = status;
        this.code = code;
        this.category = category;
        this.suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public static V2ApiException badRequest(String code, String message, String... suggestions) {
        return new V2ApiException(HttpStatus.BAD_REQUEST, code, "BUSINESS", message, List.of(suggestions));
    }

    public static V2ApiException conflict(String code, String message, String... suggestions) {
        return new V2ApiException(HttpStatus.CONFLICT, code, "BUSINESS", message, List.of(suggestions));
    }

    public static V2ApiException notFound(String code, String message) {
        return new V2ApiException(HttpStatus.NOT_FOUND, code, "BUSINESS", message, List.of());
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String category() {
        return category;
    }

    public List<String> suggestions() {
        return suggestions;
    }
}
