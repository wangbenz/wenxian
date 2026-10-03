package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.agent.StoryModelSelectionGateway;
import com.wenxian.game.story.agent.StoryModelSelectionGateway.StoryModelSelection;
import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryRuntimeState;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.model.StoryApiModels.Ability;
import com.wenxian.game.story.model.StoryApiModels.Choice;
import com.wenxian.game.story.model.StoryApiModels.EngineDescriptor;
import com.wenxian.game.story.model.StoryApiModels.RealmStageView;
import com.wenxian.game.story.model.StoryApiModels.RealmView;
import com.wenxian.game.story.model.StoryApiModels.Item;
import com.wenxian.game.story.model.StoryApiModels.Memory;
import com.wenxian.game.story.model.StoryApiModels.PendingDecision;
import com.wenxian.game.story.model.StoryApiModels.Protagonist;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.SaveSummary;
import com.wenxian.game.story.model.StoryApiModels.StoryState;
import com.wenxian.game.story.model.StoryApiModels.WorldProfile;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSaveIndex;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 从通用命卷事实表组装 H5 所需的只读视图。
 *
 * <p>命卷引擎负责推进和提交，当前服务只负责读取核心快照、补充可见实体与记忆并转换 API DTO。
 * 这样角色页投影规则不会继续散落在回合编排器中，也不会因为展示字段变化影响写事务。</p>
 */
@Service
public class StorySaveViewService {
    private static final int STATE_SCHEMA_VERSION = StorySaveCreationService.STATE_SCHEMA_VERSION;
    private final StoryEntityRepository entities;
    private final StoryMemoryRepository memories;
    private final StoryModelSelectionGateway modelSelections;
    private final ObjectMapper mapper;
    private final StoryProjectionPersistenceMapper projections;

    public StorySaveViewService(StoryEntityRepository entities,
                                StoryMemoryRepository memories,
                                StoryModelSelectionGateway modelSelections,
                                ObjectMapper mapper) {
        this.entities = entities;
        this.memories = memories;
        this.modelSelections = modelSelections;
        this.mapper = mapper;
        this.projections = new StoryProjectionPersistenceMapper(mapper);
    }

    /** 读取当前核心快照，并以实体表和记忆表覆盖快照中的旧展示副本。 */
    public StoryRuntimeState read(StoredSave saved) {
        if (saved.stateSchemaVersion() != STATE_SCHEMA_VERSION
                || saved.state().path("schemaVersion").asInt(-1) != STATE_SCHEMA_VERSION) {
            throw new IllegalStateException("命卷结构版本不受支持，请删除后重新创建命卷：" + saved.id());
        }
        JsonNode state = saved.state();
        JsonNode coreState = state.path("coreState");
        JsonNode scene = state.path("scene");
        if (!coreState.isObject() || !scene.isObject() || !state.path("worldTime").isObject()) {
            throw new IllegalStateException("统一命卷核心状态无法读取：" + saved.id());
        }
        var creation = projections.loadCreation(state);
        if (creation.cultivation() == null) throw new IllegalStateException("命卷缺少已确认修行体系");
        var contract = creation.contract();
        var system = creation.cultivation().system();
        var protagonistCultivation = creation.cultivation().protagonist();
        String realm = system.stages().stream()
                .filter(stage -> stage.stageId().equals(protagonistCultivation.currentStageId()))
                .map(CultivationSystem.Stage::name).findFirst().orElseThrow()
                + phaseName(protagonistCultivation.phase());
        StoryRuntimeState.WorldProfile world = new StoryRuntimeState.WorldProfile(
                contract.worldBinding().packageId(), contract.worldBinding().nameSnapshot(), "WORLD_PACKAGE",
                contract.worldBinding().premiseSnapshot(), system.name(),
                text(coreState.path("currentLocationName"), contract.worldBinding().openingRegionRef()));
        StoryRuntimeState.Protagonist protagonist = new StoryRuntimeState.Protagonist(
                contract.identity().protagonistName(), contract.identity().raceNameSnapshot(),
                contract.identity().genderCode(), contract.origin().summary(), realm,
                text(coreState.path("lifeGoal"), ""), preferenceSummary(creation.preference()));
        List<StoryRuntimeState.Choice> currentChoices = new ArrayList<>();
        JsonNode choices = scene.path("choices");
        if (!choices.isArray()) throw new IllegalStateException("命卷当前场景缺少推荐行动");
        choices.forEach(value -> currentChoices.add(new StoryRuntimeState.Choice(
                text(value.path("id"), ""), text(value.path("title"), ""),
                text(value.path("description"), ""))));
        StoryRuntimeState core = new StoryRuntimeState(STATE_SCHEMA_VERSION, saved.id(), saved.turnNumber(),
                saved.status(), world, protagonist,
                mapper.convertValue(state.path("worldTime"), StoryWorldTime.class),
                text(coreState.path("currentLocationName"), "未知"),
                text(coreState.path("condition"), "平静"), text(scene.path("title"), "命卷新页"),
                text(scene.path("narrative"), ""), currentChoices, List.of(), List.of(), List.of(), List.of());

        List<StoryRuntimeState.Item> inventory = new ArrayList<>();
        List<StoryRuntimeState.Ability> abilities = new ArrayList<>();
        List<StoryRuntimeState.Character> characters = new ArrayList<>();
        for (Entity entity : entities.listVisible(saved.id())) {
            JsonNode attributes = entity.attributes();
            switch (entity.type()) {
                case "ITEM" -> {
                    if (isCurrentlyHeldByPlayer(attributes)) {
                        boolean quantityKnown = trustedQuantityKnown(attributes);
                        inventory.add(new StoryRuntimeState.Item(entity.id(), entity.name(),
                                attributes.path("itemType").asText("物品"), entity.summary(), entity.status(),
                                attributes.path("source").asText("故事所得"),
                                quantityKnown ? Math.max(0, trustedQuantityValue(attributes)) : 0,
                                quantityKnown, true));
                    }
                }
                case "ABILITY" -> abilities.add(new StoryRuntimeState.Ability(entity.id(), entity.name(),
                        attributes.path("abilityType").asText("能力"), entity.summary(), entity.status(),
                        attributes.path("source").asText("故事所得"), abilityState(entity)));
                case "CHARACTER" -> characters.add(new StoryRuntimeState.Character(entity.id(), entity.name(),
                        attributes.path("tier").asText("KNOWN"), attributes.path("identity").asText("身份未明"),
                        characterRelationship(attributes), characterLifecycle(attributes),
                        attributes.path("lastKnownLocation").asText("未知"), true));
                default -> {
                    // 势力和地点保留为可信实体，但暂不属于当前角色页视图。
                }
            }
        }
        List<StoryRuntimeState.Memory> activeMemories = memories.recentActive(saved.id(), 80).stream()
                .map(memory -> new StoryRuntimeState.Memory(memory.id(), memory.title(), memory.summary(),
                        StoryWorldTime.fromOrder(memory.worldTime()), memory.importance())).toList();
        return new StoryRuntimeState(STATE_SCHEMA_VERSION, saved.id(), saved.turnNumber(), saved.status(),
                core.world(), core.protagonist(), core.worldTime(), core.currentLocation(), core.condition(),
                core.sceneTitle(), core.narrative(), core.choices(), inventory, abilities, characters, activeMemories);
    }

    /**
     * 只从命卷索引组装列表摘要，不反序列化完整运行态，也不读取实体或记忆关联表。
     * 缺损快照保留原槽位和删除能力，避免一份异常命卷拖垮整个列表。
     */
    public SaveSummary summary(StoredSaveIndex saved, EngineDescriptor currentEngine) {
        JsonNode state = saved.state();
        StoryProjectionPersistenceMapper.CreationSnapshot creation = null;
        if (saved.stateReadable() && saved.stateSchemaVersion() == STATE_SCHEMA_VERSION) {
            try {
                creation = projections.loadCreation(state);
            } catch (RuntimeException ignored) {
                // 单份损坏命卷只降级自身列表项，不能阻断同账号其他命卷。
            }
        }
        boolean summaryReadable = creation != null && creation.cultivation() != null;
        boolean runtimeReadable = summaryReadable
                && currentEngine.code().equals(saved.engineCode())
                && currentEngine.version() == saved.engineVersion()
                && currentEngine.stateSchemaVersion() == saved.stateSchemaVersion();
        String currentStageId = summaryReadable ? creation.cultivation().protagonist().currentStageId() : "";
        String realm = summaryReadable ? creation.cultivation().system().stages().stream()
                .filter(stage -> stage.stageId().equals(currentStageId))
                .map(CultivationSystem.Stage::name).findFirst().orElse("境界未明")
                + phaseName(creation.cultivation().protagonist().phase()) : "境界未明";
        return new SaveSummary(saved.id(), saved.activeSlotNo(), runtimeReadable ? mode(saved.status()) : "MIGRATION_REQUIRED",
                saved.engineCode(),
                saved.engineVersion(), saved.stateSchemaVersion(), saved.revision(), saved.turnNumber(),
                runtimeReadable ? capabilities(saved.status()) : List.of("DELETE"),
                summaryReadable ? creation.contract().worldBinding().nameSnapshot() : saved.worldCode(),
                summaryReadable ? creation.contract().identity().protagonistName() : "异常命卷 " + saved.activeSlotNo(),
                summaryReadable ? creation.contract().identity().raceNameSnapshot() : "未知",
                realm, saved.status(), saved.updatedAt());
    }

    /** 组装单份命卷响应；平台推演不向玩家暴露具体供应商和模型。 */
    public SaveResponse response(String userId, StoredSave saved, StoryRuntimeState state, String engineCode) {
        StoryModelSelection preference = modelSelections.currentSelection(userId);
        boolean personal = preference.personal();
        return new SaveResponse(saved.id(), mode(saved.status()), engineCode, saved.engineVersion(),
                saved.stateSchemaVersion(), saved.revision(), saved.turnNumber(), capabilities(saved.status()),
                mapState(saved, state), personal ? "PERSONAL" : "PLATFORM",
                personal ? preference.provider() : null, personal ? preference.model() : null);
    }

    private StoryState mapState(StoredSave saved, StoryRuntimeState state) {
        return new StoryState(saved.id(), saved.turnNumber(), saved.status(),
                new WorldProfile(state.world().code(), state.world().name(), state.world().source(),
                        state.world().premise(), state.world().cultivationStyle(), state.world().openingRegion()),
                new Protagonist(state.protagonist().name(), state.protagonist().race(),
                        state.protagonist().gender(), state.protagonist().origin(), state.protagonist().realm(),
                        state.protagonist().lifeGoal(), state.protagonist().storyTone()),
                state.worldTime(), state.currentLocation(), state.condition(), state.sceneTitle(),
                state.narrative(), pendingDecision(saved), state.choices().stream()
                        .map(value -> new Choice(value.id(), value.title(), value.description())).toList(),
                state.inventory().stream().map(value -> new Item(value.id(), value.name(), value.type(),
                        value.description(), value.status(), value.source(), value.quantity(),
                        Boolean.TRUE.equals(value.quantityKnown()), Boolean.TRUE.equals(value.inPossession()))).toList(),
                state.abilities().stream().map(value -> new Ability(value.id(), value.name(), value.type(),
                        value.description(), value.status(), value.source(), value.abilityState())).toList(),
                state.characters().stream().map(value -> new com.wenxian.game.story.model.StoryApiModels.Character(
                        value.id(), value.name(), value.tier(), value.identity(), value.relationship(), value.status(),
                        value.lastKnownLocation(), Boolean.TRUE.equals(value.tracked()))).toList(),
                state.memories().stream().map(value -> new Memory(value.id(), value.title(), value.summary(),
                        value.worldTime(), value.importance())).toList(), realm(saved.state()));
    }

    private RealmView realm(JsonNode state) {
        var snapshot = projections.loadCultivation(state);
        if (snapshot == null) return null;
        CultivationSystem system = snapshot.system();
        ProtagonistCultivation protagonist = snapshot.protagonist();
        String currentName = system.stages().stream()
                .filter(stage -> stage.stageId().equals(protagonist.currentStageId()))
                .map(CultivationSystem.Stage::name).findFirst().orElseThrow();
        return new RealmView(system.systemId(), system.schemaVersion(), system.race(),
                protagonist.currentStageId(), currentName,
                "L" + system.stages().stream().filter(stage -> stage.stageId().equals(protagonist.currentStageId()))
                        .map(CultivationSystem.Stage::order).findFirst().orElseThrow(),
                protagonist.phase().name(), protagonist.progressMarks(), protagonist.requiredProgressMarks(),
                system.stages().stream().sorted(Comparator.comparingInt(CultivationSystem.Stage::order))
                        .map(stage -> new RealmStageView(stage.stageId(), stage.order(), stage.name(),
                                stage.description(), stage.aliases(), stage.allowedNextStageIds())).toList());
    }

    private PendingDecision pendingDecision(StoredSave saved) {
        StoryPatch.DecisionCheckpoint checkpoint = projections.loadDecisionCheckpoint(saved.state());
        return checkpoint == null ? null : new PendingDecision(checkpoint.prompt());
    }

    /**
     * 学习状态优先读取可信字段。仅旧记录缺字段时兼容明确的旧枚举；ACTIVE 或陌生文本不猜成学习中。
     * 已有但损坏的可信字段只降级本项显示，不借旧 status 覆盖，也不改写持久化记录。
     */
    private StoryPatch.AbilityState abilityState(Entity entity) {
        JsonNode stored = entity.attributes().path("_trusted").path("abilityState");
        String value = stored.isMissingNode() || stored.isNull() ? entity.status() : stored.asText("");
        if (value == null) return StoryPatch.AbilityState.UNKNOWN;
        try {
            return StoryPatch.AbilityState.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return StoryPatch.AbilityState.UNKNOWN;
        }
    }

    /** 人物生命周期只认可信投影，不根据自由叙述猜测生死。 */
    private String characterLifecycle(JsonNode attributes) {
        String lifecycle = attributes == null ? "" : attributes.path("_trusted").path("lifecycle").asText("");
        return switch (lifecycle) {
            case "ACTIVE", "MISSING", "DORMANT", "DEAD" -> lifecycle;
            default -> "UNKNOWN";
        };
    }

    private String characterRelationship(JsonNode attributes) {
        JsonNode relationship = attributes == null ? null : attributes.path("_trusted").path("relationship");
        String label = relationship == null ? "" : relationship.path("label").asText("").strip();
        return label.isBlank() ? "初次相识" : label;
    }

    private boolean trustedQuantityKnown(JsonNode attributes) {
        JsonNode quantity = attributes.path("_trusted").path("quantity");
        return quantity.isObject() && quantity.has("known") && quantity.path("known").asBoolean(false);
    }

    private int trustedQuantityValue(JsonNode attributes) {
        JsonNode quantity = attributes.path("_trusted").path("quantity");
        if (quantity.isObject() && quantity.path("known").asBoolean(false)) {
            return quantity.path("value").asInt(0);
        }
        return 0;
    }

    /** 物品是否在行囊只由可信保管关系决定。 */
    private boolean isCurrentlyHeldByPlayer(JsonNode attributes) {
        JsonNode custody = attributes.path("_trusted").path("custody");
        if (!custody.isObject() || !"PLAYER".equalsIgnoreCase(custody.path("holderType").asText())) return false;
        return !trustedQuantityKnown(attributes) || trustedQuantityValue(attributes) > 0;
    }

    private String mode(String status) {
        return "ACTIVE".equals(status) ? "PLAYABLE" : "ENDED";
    }

    private List<String> capabilities(String status) {
        if (!"ACTIVE".equals(status)) return List.of("READ");
        return List.of("READ", "PLAY", "DELETE", "CORRECT", "REMOVE_ENTITY");
    }

    private String text(JsonNode value, String fallback) {
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private String preferenceSummary(com.wenxian.game.story.domain.PlayPreference value) {
        String focus = value.focus().stream().map(this::focusName)
                .collect(java.util.stream.Collectors.joining("、"));
        String summary = "关注" + focus + "；战斗" + switch (value.combatFrequency()) {
            case LOW -> "较少";
            case BALANCED -> "适中";
            case HIGH -> "较多";
        } + "；节奏" + switch (value.pace()) {
            case RELAXED -> "舒缓";
            case BALANCED -> "适中";
            case FAST -> "紧凑";
        } + "；引导" + switch (value.guidance()) {
            case LIGHT -> "较少";
            case BALANCED -> "适中";
            case EXPLICIT -> "明确";
        };
        return value.additionalNote().isBlank() ? summary : summary + "。补充：" + value.additionalNote();
    }

    private String focusName(com.wenxian.game.story.domain.PlayPreference.Focus focus) {
        return switch (focus) {
            case EXPLORATION -> "探索";
            case RELATIONSHIP -> "人物关系";
            case CULTIVATION -> "修炼";
            case MANAGEMENT -> "经营";
            case COMBAT -> "战斗";
        };
    }

    private String phaseName(com.wenxian.game.story.domain.WorldPackage.RealmPhase phase) {
        return switch (phase) {
            case EARLY -> "初期";
            case MIDDLE -> "中期";
            case LATE -> "后期";
            case PERFECT -> "圆满";
        };
    }
}
