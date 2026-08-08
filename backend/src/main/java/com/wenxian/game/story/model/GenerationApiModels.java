package com.wenxian.game.story.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 玩家推演来源与个人密钥的稳定 API 契约。
 *
 * <p>平台模式只暴露赠送回合和可用状态，不向玩家展示平台供应商、模型名称或平台密钥。</p>
 */
public final class GenerationApiModels {
    private GenerationApiModels() {}

    public record SettingsRequest(
            @NotBlank @Pattern(regexp = "PLATFORM|PERSONAL") String mode,
            @Size(max = 32) String provider,
            @Size(max = 80) String model) {}

    public record CredentialRequest(
            @NotBlank @Size(min = 20, max = 512) String apiKey,
            @Size(max = 80) String model) {}

    public record PlatformAccess(int turnLimit, int usedTurns, int remainingTurns,
                                 boolean available) {}

    public record PersonalModel(String code, String name) {}

    public record PersonalProvider(String code, String name, boolean credentialConfigured,
                                   String credentialStatus, String maskedSuffix,
                                   Instant lastVerifiedAt, List<PersonalModel> models) {}

    public record SettingsResponse(String mode, PlatformAccess platform,
                                   List<PersonalProvider> personalProviders,
                                   String selectedPersonalProvider,
                                   String selectedPersonalModel) {}
}
