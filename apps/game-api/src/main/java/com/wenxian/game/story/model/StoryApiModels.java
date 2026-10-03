package com.wenxian.game.story.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.domain.StoryPatch;

/**
 * 统一命卷 API 的稳定数据契约。
 *
 * <p>这些类型只表达 H5 需要的命卷视图，不携带产品发布版本名称。底层旧引擎必须先通过
 * 适配器转换成这些类型，避免旧表结构和旧 Java 类型直接泄漏到长期接口。</p>
 */
public final class StoryApiModels {
    private StoryApiModels() {}

    public record EngineDescriptor(String code, int version, int stateSchemaVersion) {}

    /** 登录后进入创角流程所需的版本中立目录，不包含故事运行态。 */
    public record BootstrapResponse(String releaseVersion, int maxActiveSaves,
                                    EngineDescriptor defaultEngine, CreationCatalog creation,
                                    List<String> capabilities) {
        public BootstrapResponse {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        }
    }

    /** 服务端权威的世界、种族和游玩偏好目录。 */
    public record CreationCatalog(WorldPackageOption world,
                                  PlayPreferenceOptions playPreferences) {}

    /** 当前可以创建命卷的世界包修订及其世界内选项。 */
    public record WorldPackageOption(
            String packageId,
            long revision,
            String name,
            String premise,
            List<String> coreRules,
            String narrativeGuidance,
            List<RaceOption> races,
            List<RegionOption> openingRegions,
            RealmScaleOption realmScale
    ) {
        public WorldPackageOption {
            coreRules = List.copyOf(coreRules);
            races = List.copyOf(races);
            openingRegions = List.copyOf(openingRegions);
        }
    }

    /** 当前世界内可选择的种族及其可观察影响。 */
    public record RaceOption(String raceId, String name, String description,
                             List<String> identityFacts, List<String> socialHooks) {
        public RaceOption {
            identityFacts = List.copyOf(identityFacts);
            socialHooks = List.copyOf(socialHooks);
        }
    }

    /** 当前世界内允许作为开场位置的地域。 */
    public record RegionOption(String regionId, String name, String description) {}

    /** 当前世界固定的跨种族境界标尺；玩家只能查看，不能提交改写。 */
    public record RealmScaleOption(String scaleId, long revision, List<String> phases,
                                   List<RealmRankOption> ranks) {
        public RealmScaleOption {
            phases = List.copyOf(phases);
            ranks = List.copyOf(ranks);
        }
    }

    /** 同一等级位置及三个种族在该位置上的显示定义。 */
    public record RealmRankOption(String rankKey, int order, List<RaceRealmOption> raceRealms) {
        public RealmRankOption {
            raceRealms = List.copyOf(raceRealms);
        }
    }

    /** 单个种族在某一共享等级上的名称与能力边界。 */
    public record RaceRealmOption(String raceId, String stageId, String name, String description,
                                  String capabilityBoundary, List<String> aliases) {
        public RaceRealmOption {
            aliases = List.copyOf(aliases);
        }
    }

    /** 游玩偏好的四组可选值。 */
    public record PlayPreferenceOptions(
            List<CatalogOption> focus,
            List<CatalogOption> combatFrequency,
            List<CatalogOption> pace,
            List<CatalogOption> guidance,
            List<String> defaultFocus,
            String defaultCombatFrequency,
            String defaultPace,
            String defaultGuidance
    ) {
        public PlayPreferenceOptions {
            focus = List.copyOf(focus);
            combatFrequency = List.copyOf(combatFrequency);
            pace = List.copyOf(pace);
            guidance = List.copyOf(guidance);
            defaultFocus = List.copyOf(defaultFocus);
        }
    }

    /** 一个可提交的稳定编码及玩家可读影响说明。 */
    public record CatalogOption(String code, String name, String description) {}

    public record CreateSaveRequest(
            @NotBlank @Pattern(regexp = "[A-Z_]{3,40}") String worldPackageId,
            @Positive long worldRevision,
            @NotNull @Valid CharacterIdentitySubmission identity,
            @NotNull @Valid CharacterOriginSubmission origin,
            @NotNull @Valid OpeningSubmission opening,
            @NotNull @Valid PlayPreferenceSubmission playPreference,
            @Size(max = 80) String replaceSaveId
    ) {}

    /** H5 已确认、但尚未写入命卷的角色身份。 */
    public record CharacterIdentitySubmission(
            @NotBlank @Size(min = 1, max = 20) String name,
            @NotBlank @Pattern(regexp = "MALE|FEMALE|UNSPECIFIED") String genderCode,
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._:-]{2,79}") String raceId
    ) {}

    /** 玩家最终确认的根源；六条候选事实之外允许一条必需的种族身份事实。 */
    public record CharacterOriginSubmission(
            @NotBlank @Size(max = 240) String summary,
            @NotNull @Size(min = 1, max = 7) List<@Valid OriginFactSubmission> establishedFacts,
            @NotNull @Size(max = 6) List<@Valid StoryHookSubmission> openHooks,
            @NotNull @Size(max = 6) List<@Valid StartingEntitySubmission> startingEntities
    ) {}

    public record OriginFactSubmission(
            @NotBlank @Pattern(regexp = "origin:fact:[a-z0-9-]{1,40}") String localRef,
            @NotBlank @Pattern(regexp = "IDENTITY|PAST_EVENT|OBLIGATION|KNOWLEDGE") String type,
            @NotBlank @Size(max = 240) String statement
    ) {}

    public record StoryHookSubmission(
            @NotBlank @Pattern(regexp = "origin:hook:[a-z0-9-]{1,40}") String localRef,
            @NotBlank @Size(max = 200) String summary
    ) {}

    /** 模型或 H5 只能提交局部引用，数据库实体 ID 始终由服务端分配。 */
    public record StartingEntitySubmission(
            @NotBlank @Pattern(regexp = "origin:entity:[a-z0-9-]{1,40}") String localRef,
            @NotBlank @Pattern(regexp = "ITEM|ABILITY|CHARACTER") String entityType,
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 300) String summary
    ) {}

    public record OpeningSubmission(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._:-]{2,79}") String regionId,
            @NotBlank @Size(max = 300) String situation,
            @Valid OpeningSceneSubmission scene
    ) {
        /** 兼容旧客户端；没有预览正文时仍使用确定性开场。 */
        public OpeningSubmission(String regionId, String situation) {
            this(regionId, situation, null);
        }
    }

    /** 玩家在创角确认中查看的开篇；仅作为叙事保存，不从正文派生身份或资产。 */
    public record OpeningSceneSubmission(
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 2400) String narrative,
            @NotNull @Size(min = 1, max = 4) List<@NotNull @Valid OpeningChoiceSubmission> choices
    ) {}

    public record OpeningChoiceSubmission(
            @NotBlank @Size(max = 80) String title,
            @NotBlank @Size(max = 240) String description
    ) {}

    /** 偏好只影响后续场景比重与提示方式，不是玩家已采取的行动。 */
    public record PlayPreferenceSubmission(
            @NotNull @Size(min = 1, max = 5) List<@Pattern(regexp = "EXPLORATION|RELATIONSHIP|CULTIVATION|MANAGEMENT|COMBAT") String> focus,
            @NotBlank @Pattern(regexp = "LOW|BALANCED|HIGH") String combatFrequency,
            @NotBlank @Pattern(regexp = "RELAXED|BALANCED|FAST") String pace,
            @NotBlank @Pattern(regexp = "LIGHT|BALANCED|EXPLICIT") String guidance,
            @Size(max = 160) String additionalNote
    ) {}

    public record CharacterRouteRequest(
            @NotBlank @Pattern(regexp = "[A-Z_]{3,40}") String worldPackageId,
            @Positive long worldRevision,
            @NotNull @Valid CharacterIdentitySubmission identity,
            @Size(max = 400) String roleSeed,
            @Size(max = 400) String preferenceSeed,
            @Pattern(regexp = "RECOMMEND|FINALIZE") String mode
    ) {
        /** 旧客户端省略模式时仍生成三份推荐。 */
        public CharacterRouteRequest(String worldPackageId, long worldRevision,
                                     CharacterIdentitySubmission identity, String roleSeed, String preferenceSeed) {
            this(worldPackageId, worldRevision, identity, roleSeed, preferenceSeed, null);
        }
    }

    public record CharacterRoute(String id, String title, CharacterOriginSubmission origin,
                                 OpeningSubmission opening) {}

    public record CharacterRouteResponse(List<CharacterRoute> routes) {}

    /** 公共境界标尺投影到当前种族后的单个有序大境界。 */
    public record RealmStageView(
            String stageId,
            int order,
            String name,
            String description,
            List<String> aliases,
            List<String> allowedNextStageIds
    ) {}

    public record TurnRequest(
            @NotBlank @Size(min = 1, max = 1000) String action,
            @NotBlank @Size(max = 80) String requestId,
            @Positive long expectedRevision,
            @Size(max = 100) String choiceId
    ) {
        /** 兼容自由输入和旧 H5；只有点击系统选项时才携带 choiceId。 */
        public TurnRequest(String action, String requestId, long expectedRevision) {
            this(action, requestId, expectedRevision, null);
        }
    }

    /**
     * H5 在网关超时或页面重载后查询原 requestId 的处理结果。
     * `snapshot` 只在已经提交时返回，处理中状态不会暴露内部模型或审阅阶段。
     */
    public record TurnRequestStatusResponse(
            String requestId,
            String status,
            boolean terminal,
            boolean retryable,
            int retryAfterMs,
            String message,
            Instant updatedAt,
            SaveResponse snapshot
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
            StoryWorldTime worldTime,
            String currentLocation,
            String condition,
            String sceneTitle,
            String narrative,
            PendingDecision pendingDecision,
            List<Choice> choices,
            List<Item> inventory,
            List<Ability> abilities,
            List<Character> characters,
            List<Memory> memories,
            RealmView realm
    ) {
        /** 没有可读公共境界状态时返回空境界视图。 */
        public StoryState(String saveId, int turnNumber, String status, WorldProfile world,
                          Protagonist protagonist, StoryWorldTime worldTime, String currentLocation,
                          String condition, String sceneTitle, String narrative, PendingDecision pendingDecision,
                          List<Choice> choices, List<Item> inventory, List<Ability> abilities,
                          List<Character> characters, List<Memory> memories) {
            this(saveId, turnNumber, status, world, protagonist, worldTime, currentLocation, condition,
                    sceneTitle, narrative, pendingDecision, choices, inventory, abilities, characters, memories, null);
        }

        public StoryState {
            if (worldTime == null) throw new IllegalArgumentException("命卷响应缺少语义世界时间");
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

    /** 玩家只读的当前公共境界位置和本族八境名称。 */
    public record RealmView(String scaleId, long scaleRevision, String race,
                            String currentStageId, String currentStageName,
                            String currentRankKey, String currentPhase,
                            int progressMarks, int requiredProgressMarks,
                            List<RealmStageView> stages) {}

    public record Choice(String id, String title, String description) {}

    /** 当前正文已经停下并等待玩家亲自回答的重大决定；为空表示没有待确认事项。 */
    public record PendingDecision(String prompt) {}

    /** 数量未知时 quantity 仅为兼容占位值，H5 必须依据 quantityKnown 展示“未清点”。 */
    public record Item(String id, String name, String type, String description,
                       String status, String source, int quantity, boolean quantityKnown,
                       boolean inPossession) {}

    /** 学习状态与旧通用 status 分开返回；旧客户端仍可读取原字段，缺失学习状态不代表正在学习。 */
    public record Ability(String id, String name, String type, String description,
                          String status, String source, StoryPatch.AbilityState abilityState) {
        public Ability(String id, String name, String type, String description,
                       String status, String source) {
            this(id, name, type, description, status, source, null);
        }
    }

    public record Character(String id, String name, String tier, String identity,
                            String relationship, String status, String lastKnownLocation,
                            boolean tracked) {}

    public record Memory(String id, String title, String summary, StoryWorldTime worldTime,
                         String importance) {}
}
