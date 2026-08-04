package com.wenxian.game.v014.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Model-facing tolerant envelope. Every collection is optional. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiTurnEnvelope(
        String title,
        String narrative,
        Long elapsedMinutes,
        String location,
        String condition,
        String realm,
        String lifeGoal,
        List<ChoiceDraft> choices,
        List<EntityPatch> items,
        List<EntityPatch> abilities,
        List<CharacterPatch> characters,
        List<MemoryDraft> memories,
        String rollingSummary
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChoiceDraft(String title, String description) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EntityPatch(String operation, String name, String type, String description,
                              String status, String source, Integer quantity) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CharacterPatch(String operation, String name, String tier, String identity,
                                 String relationship, String status, String location) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MemoryDraft(String title, String summary, String importance) {}
}
