package com.wenxian.game.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ApiModels {
    private ApiModels() {}

    public record RaceView(
            String code, String name, String description, List<String> strengths,
            List<String> weaknesses, String lifespan
    ) {}

    public record GoalView(String code, String name, String description) {}

    public record DifficultyView(String code, String name, String description) {}

    public record BootstrapResponse(
            String gameName, String version, GameState.WorldProfile world, List<RaceView> races,
            List<GoalView> lifeGoals, List<DifficultyView> difficulties, List<String> realmPath
    ) {}

    public record GenerateNameRequest(
            @NotBlank @Pattern(regexp = "MALE|FEMALE|UNSPECIFIED") String gender,
            @NotBlank @Pattern(regexp = "HUMAN|DEMON|SPIRIT") String race,
            @Size(max = 20) List<@Size(max = 12) String> exclude
    ) {}

    public record GenerateNameResponse(String name, List<String> alternatives) {}

    public record ValidateNameRequest(@NotBlank @Size(max = 12) String name) {}

    public record ValidateNameResponse(boolean valid, String normalizedName, String reasonCode, String message) {}

    public record CreateSaveRequest(
            @NotBlank @Size(max = 12) String playerName,
            @NotBlank @Pattern(regexp = "HUMAN|DEMON|SPIRIT") String race,
            @NotBlank @Pattern(regexp = "MALE|FEMALE|UNSPECIFIED") String gender,
            @NotBlank @Pattern(regexp = "LONGEVITY|KINSHIP|POWER|TRUTH|FREEDOM") String lifeGoal,
            @NotBlank @Pattern(regexp = "NORMAL|HARD") String difficulty,
            @Size(max = 160) String originPremise,
            @Size(max = 160) String preference
    ) {}

    public record ReleaseNoteView(String id, String version, String title, String content,
                                  Instant publishedAt, boolean unread) {}

    public record AiSettingsView(
            int turnLimit, int usedTurns, int remainingTurns, boolean customApiEnabled,
            boolean usingCustomApi, boolean credentialConfigured, String maskedSuffix,
            String credentialStatus, Instant lastVerifiedAt
    ) {}

    public record SaveAiCredentialRequest(@NotBlank @Size(min = 20, max = 512) String apiKey) {}

    public record SelectProtagonistRequest(@NotBlank @Size(max = 80) String candidateId) {}

    public record SelectTalentRequest(@NotBlank @Size(max = 80) String talentId) {}

    public record TurnRequest(
            @NotBlank @Size(max = 80) String requestId,
            long stateVersion,
            @NotBlank @Pattern(regexp = "CHOICE|TEXT|USE_ITEM|USE_ABILITY|PRACTICE") String inputType,
            @Size(max = 80) String choiceId,
            @Size(max = 80) String targetId,
            @Size(max = 300) String content
    ) {}

    public record GameResponse(
            GameState state,
            long revision,
            Instant updatedAt,
            List<GameState.StoryChoice> choices,
            Map<String, Object> agent,
            ActionResolutionView actionResolution
    ) {}

    public record ActionResolutionView(
            String result, String code, String title, String reason,
            List<String> suggestions, boolean turnConsumed
    ) {}

    public record CurrentSaveResponse(boolean exists, GameResponse game) {}

    public record SaveSummaryView(
            String saveId, int slotNo, String phase, String playerName, String race,
            String realm, String currentLocation, String sceneTitle, int turn, Instant updatedAt
    ) {}

    public record SaveListResponse(int maxActiveSaves, List<SaveSummaryView> saves) {}

    public record AbandonSaveRequest(@NotBlank @Size(max = 12) String playerName) {}

    public record CharacterSheetResponse(
            GameState.PlayerState player,
            List<GameState.AbilityRecord> talents,
            List<GameState.AbilityRecord> techniques,
            List<GameState.QuestThread> activeThreads
    ) {}

    public record WorldResponse(
            GameState.WorldProfile world,
            String currentLocation,
            int worldYear,
            int day,
            int minute,
            List<GameState.CharacterRecord> characters,
            List<GameState.QuestThread> threads
    ) {}
}
