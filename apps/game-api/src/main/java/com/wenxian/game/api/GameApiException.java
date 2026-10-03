package com.wenxian.game.api;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * A player-safe, stable API failure. Internal exception text belongs in diagnostics and is never
 * returned to the H5.
 */
public final class GameApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String category;
    private final String title;
    private final List<String> suggestions;
    private final boolean retryable;
    private final boolean turnConsumed;
    private final boolean stateChanged;
    private final Map<String, Object> details;
    private final String diagnostic;

    public GameApiException(HttpStatus status, String code, String category, String title, String message,
                            List<String> suggestions, boolean retryable, boolean turnConsumed,
                            boolean stateChanged, Map<String, Object> details, String diagnostic, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.category = category;
        this.title = title;
        this.suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        this.retryable = retryable;
        this.turnConsumed = turnConsumed;
        this.stateChanged = stateChanged;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public static GameApiException of(HttpStatus status, String code, String category, String title,
                                      String message, List<String> suggestions) {
        return new GameApiException(status, code, category, title, message, suggestions,
                false, false, false, Map.of(), "", null);
    }

    public static GameApiException temporary(String code, String title, String message,
                                              List<String> suggestions, String diagnostic, Throwable cause) {
        return new GameApiException(HttpStatus.BAD_GATEWAY, code, "SYSTEM", title, message, suggestions,
                true, false, false, Map.of(), diagnostic, cause);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String category() { return category; }
    public String title() { return title; }
    public List<String> suggestions() { return suggestions; }
    public boolean retryable() { return retryable; }
    public boolean turnConsumed() { return turnConsumed; }
    public boolean stateChanged() { return stateChanged; }
    public Map<String, Object> details() { return details; }
    public String diagnostic() { return diagnostic; }
}
