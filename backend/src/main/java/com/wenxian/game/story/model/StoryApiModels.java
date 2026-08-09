package com.wenxian.game.story.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 统一命卷 API 的稳定数据契约。
 *
 * <p>这些类型只表达 H5 需要的命卷视图，不携带产品发布版本名称。底层旧引擎必须先通过
 * 适配器转换成这些类型，避免旧表结构和旧 Java 类型直接泄漏到长期接口。</p>
 */
public final class StoryApiModels {
    private StoryApiModels() {}

    public record EngineDescriptor(String code, int version, int stateSchemaVersion) {}

    public record BootstrapResponse(String releaseVersion, int maxActiveSaves,
                                    EngineDescriptor defaultEngine, List<WorldOption> worlds,
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
            @NotBlank @Size(max = 120) String origin,
            @Size(max = 240) String opening,
            @JsonAlias("storyTone") @Size(max = 160) String storyPreference
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
                                 String opening, String preference) {}

    public record CharacterRouteResponse(List<CharacterRoute> routes) {}

    public record TurnRequest(
            @NotBlank @Size(min = 1, max = 1000) String action,
            @NotBlank @Size(max = 80) String requestId,
            @Positive long expectedRevision
    ) {}

    public record CorrectionRequest(
            @NotBlank @Size(min = 2, max = 500) String instruction,
            @Positive long expectedRevision
    ) {}

    /** 只生成修正提案，不改变命卷。 */
    public record CorrectionPreviewRequest(
            @NotBlank @Size(min = 2, max = 500) String instruction,
            @Positive long expectedRevision
    ) {}

    /** 玩家确认预览时只回传服务端签发的不可读令牌。 */
    public record CorrectionConfirmRequest(
            @NotBlank @Size(max = 24_000) String proposalToken,
            @Positive long expectedRevision
    ) {}

    /** H5 用于二次确认的自然语言预览，不暴露供应商、模型或技术解析字段。 */
    public record CorrectionPreviewResponse(
            String kind,
            String explanation,
            String confirmationPrompt,
            String proposalToken
    ) {}

    public record SaveSummary(String saveId, int slotNo, String mode,
                              String engineCode, int engineVersion, int stateSchemaVersion,
                              long revision, int turnNumber, List<String> capabilities,
                              String worldName, String playerName, String race, String realm,
                              String status, Instant updatedAt) {}

    public record SaveResponse(String saveId, String mode,
                               String engineCode, int engineVersion, int stateSchemaVersion,
                               long revision, int turnNumber, List<String> capabilities,
                               StoryState state, String generationMode,
                               String personalProvider, String personalModel) {}

    /**
     * 轻叙事命卷的当前权威视图。
     *
     * <p>数值、背包、能力和人物只保存跨回合必须一致的事实；该 DTO 不承担战力、经济或职业树规则。</p>
     */
    public record StoryState(
            String saveId,
            int turnNumber,
            String status,
            WorldProfile world,
            Protagonist protagonist,
            long worldTimeMinutes,
            String currentLocation,
            String condition,
            String sceneTitle,
            String narrative,
            List<Choice> choices,
            List<Item> inventory,
            List<Ability> abilities,
            List<Character> characters,
            List<Memory> memories
    ) {
        public StoryState {
            choices = safe(choices);
            inventory = safe(inventory);
            abilities = safe(abilities);
            characters = safe(characters);
            memories = safe(memories);
        }

        private static <T> List<T> safe(List<T> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    public record WorldProfile(String code, String name, String source, String premise,
                               String cultivationStyle, String openingRegion) {}

    public record Protagonist(String name, String race, String gender, String origin,
                              String realm, String lifeGoal, String storyPreference) {}

    public record Choice(String id, String title, String description) {}

    public record Item(String id, String name, String type, String description,
                       String status, String source, int quantity, boolean inPossession) {}

    public record Ability(String id, String name, String type, String description,
                          String status, String source) {}

    public record Character(String id, String name, String tier, String identity,
                            String relationship, String status, String lastKnownLocation,
                            boolean tracked) {}

    public record Memory(String id, String title, String summary, long worldTimeMinutes,
                         String importance) {}
}
