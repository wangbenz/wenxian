package com.wenxian.game.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ApiModels {
    private ApiModels() {}

    public record Choice(String id, String label, String hint, String tone) {}

    public record ActionRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId,
            @Size(max = 60) String actionId,
            @Size(max = 300) String freeText
    ) {}

    public record ResetRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId
    ) {}

    public record GameResponse(
            GameState state,
            long revision,
            Instant updatedAt,
            List<Choice> choices,
            Map<String, Object> agent
    ) {}
}
