package com.wenxian.game.v014.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class V014ApiModels {
    private V014ApiModels() {}

    public record BootstrapResponse(String version, int maxActiveSaves, List<WorldOption> worlds,
                                    List<String> races, List<String> origins) {}

    public record WorldOption(String code, String name, String premise,
                              String cultivationStyle, String openingRegion,
                              boolean custom, boolean available, String availabilityNote) {}

    public record CreateSaveRequest(
            @NotBlank @Pattern(regexp = "[A-Z_]{3,40}") String worldCode,
            @Size(max = 1200) String customWorld,
            @NotBlank @Size(min = 1, max = 20) String playerName,
            @NotBlank @Size(max = 20) String race,
            @NotBlank @Size(max = 20) String gender,
            @NotBlank @Size(max = 160) String origin,
            @Size(max = 240) String opening,
            @Size(max = 160) String storyTone
    ) {}

    public record CharacterRouteRequest(
            @NotBlank @Pattern(regexp = "[A-Z_]{3,40}") String worldCode,
            @Size(max = 1200) String customWorld,
            @NotBlank @Size(min = 1, max = 20) String playerName,
            @NotBlank @Size(max = 20) String race,
            @NotBlank @Size(max = 20) String gender,
            @Size(max = 160) String roleSeed,
            @Size(max = 160) String preferenceSeed
    ) {}

    public record CharacterRoute(String id, String title, String origin,
                                 String opening, String preference, String startingRealm) {
        public CharacterRoute(String id, String title, String origin, String opening, String preference) {
            this(id, title, origin, opening, preference, "境界未明");
        }
    }

    public record CharacterRouteResponse(List<CharacterRoute> routes) {}

    public record TurnRequest(@NotBlank @Size(min = 1, max = 1000) String action,
                              @Size(max = 80) String requestId) {}

    public record CorrectionRequest(@NotBlank @Size(min = 2, max = 500) String instruction) {}

    public record GenerationSettingsRequest(
            @NotBlank @Pattern(regexp = "PLATFORM|PERSONAL") String mode,
            @Size(max = 32) String provider,
            @Size(max = 80) String model) {}

    public record ProviderCredentialRequest(
            @NotBlank @Size(min = 20, max = 512) String apiKey,
            @Size(max = 80) String model) {}

    public record SaveSummary(String id, int slotNo, String worldName, String playerName,
                              String race, String realm, int turnNumber, String status,
                              Instant updatedAt) {}

    public record SaveResponse(V014State state, long revision, String generationMode,
                               String personalProvider, String personalModel) {}

    public record ModelPreference(String provider, String model, String credentialSource) {}

    /** Player-facing platform access deliberately omits the provider and model. */
    public record PlatformAccess(int turnLimit, int usedTurns, int remainingTurns,
                                 boolean available) {}

    public record PersonalModel(String code, String name) {}

    public record PersonalProvider(String code, String name, boolean credentialConfigured,
                                   String credentialStatus, String maskedSuffix,
                                   Instant lastVerifiedAt, List<PersonalModel> models) {}

    public record GenerationSettings(String mode, PlatformAccess platform,
                                     List<PersonalProvider> personalProviders,
                                     String selectedPersonalProvider,
                                     String selectedPersonalModel) {}
}
