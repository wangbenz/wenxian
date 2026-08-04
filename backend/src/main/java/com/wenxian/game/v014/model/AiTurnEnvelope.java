package com.wenxian.game.v014.model;

import java.util.List;

/** Model-facing tolerant envelope. Every collection is optional. */
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
    public record ChoiceDraft(String title, String description) {}
    public record EntityPatch(String operation, String name, String type, String description,
                              String status, String source, Integer quantity) {}
    public record CharacterPatch(String operation, String name, String tier, String identity,
                                 String relationship, String status, String location) {}
    public record MemoryDraft(String title, String summary, String importance) {}
}
