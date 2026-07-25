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

    public record BootstrapResponse(
            String gameName, GameState.WorldProfile world, List<RaceView> races,
            List<GoalView> lifeGoals, List<String> realmPath
    ) {}

    public record GenerateNameRequest(
            @Pattern(regexp = "MALE|FEMALE|UNSPECIFIED") String gender,
            @Pattern(regexp = "HUMAN|DEMON|SPIRIT") String race,
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
            @Size(max = 160) String preference
    ) {}

    public record SelectProtagonistRequest(@NotBlank @Size(max = 80) String candidateId) {}

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
            Map<String, Object> agent
    ) {}

    public record CurrentSaveResponse(boolean exists, GameResponse game) {}

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
