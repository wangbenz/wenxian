package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.CultivationSystemValidator;
import com.wenxian.game.story.domain.CreationContract;
import com.wenxian.game.story.domain.PlayPreference;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.DecisionCheckpoint;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryProjection.KnowledgeState;
import com.wenxian.game.story.domain.StoryProjection.MemoryState;
import com.wenxian.game.story.domain.StoryProjection.WorldFactState;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.persistence.StoryMemoryRepository.StoredMemory;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在通用命卷表与中性事实投影之间进行无版本业务命名的映射。
 *
 * <p>核心状态、世界事实和人物认知继续保存到 {@code story_save.state_json}；物品、能力与人物的
 * 稳定身份继续保存到 {@code story_entity}。可信扩展字段只写入保留的 JSON 子对象；普通 attributes
 * 仅保存展示属性，不能再作为数量、保管关系、能力状态或人物关系的备用事实来源。</p>
 */
public final class StoryProjectionPersistenceMapper {
    static final String TRUSTED_PROJECTION = "trustedProjection";
    static final String TRUSTED_ENTITY = "_trusted";
    static final String TRUSTED_SCENE = "scene";

    private final ObjectMapper mapper;
    private final CultivationSystemValidator cultivationValidator = new CultivationSystemValidator();

    public StoryProjectionPersistenceMapper(ObjectMapper mapper) {
        if (mapper == null) throw new IllegalArgumentException("命卷投影映射器不能为空");
        this.mapper = mapper;
    }

    /** 从当前快照、全部实体事实和有效记忆构建权威投影。 */
    public StoryProjection load(StoredSave save, List<Entity> entities, List<StoredMemory> memories) {
        if (save == null) throw new IllegalArgumentException("统一命卷不能为空");
        JsonNode state = save.state();
        JsonNode trusted = trustedProjection(state);
        CultivationSnapshot cultivation = cultivation(trusted);
        JsonNode storedCore = state.path("coreState");
        if (!storedCore.isObject()) throw new IllegalStateException("命卷缺少核心状态");
        CoreState core = new CoreState(text(storedCore, "currentLocationName", "未知"),
                text(storedCore, "condition", "平静"), displayRealm(cultivation),
                text(storedCore, "lifeGoal", ""));
        Map<String, EntityState> entityStates = new LinkedHashMap<>();
        safe(entities).forEach(entity -> {
            EntityState mapped = entity(entity);
            if (entityStates.putIfAbsent(mapped.id(), mapped) != null) {
                throw new IllegalStateException("统一命卷存在重复实体ID：" + mapped.id());
            }
        });

        Map<String, WorldFactState> worldFacts = indexed(
                convertList(trusted.path("worldFacts"), new TypeReference<>() {}), WorldFactState::id, "世界事实");
        Map<String, KnowledgeState> knowledge = indexed(
                convertList(trusted.path("knowledge"), new TypeReference<>() {}), this::knowledgeKey, "人物认知");
        List<MemoryState> memoryStates = safe(memories).stream()
                .map(value -> new MemoryState(value.id(), value.memoryType(), value.title(), value.summary(),
                        value.importance(), List.of()))
                .toList();
        JsonNode storedTime = state.path("worldTime");
        if (!storedTime.isObject()) throw new IllegalStateException("命卷缺少语义世界时间，请重新创建命卷");
        StoryWorldTime worldTime = mapper.convertValue(storedTime, StoryWorldTime.class);
        CreationContract creation = required(trusted, "creationContract", CreationContract.class, "创角契约");
        PlayPreference preference = required(trusted, "playPreference", PlayPreference.class, "游玩偏好");
        return new StoryProjection(worldTime, core,
                entityStates, worldFacts, knowledge, memoryStates, creation, preference,
                cultivation == null ? null : cultivation.system(),
                cultivation == null ? null : cultivation.protagonist());
    }

    /** 将中性核心投影写回 state_json，同时保留世界模板、主角姓名、正文和选项等现有字段。 */
    public ObjectNode writeState(JsonNode existingState, StoryProjection projection) {
        if (projection == null) throw new IllegalArgumentException("待保存的命卷投影不能为空");
        ObjectNode state = existingState instanceof ObjectNode object ? object.deepCopy() : mapper.createObjectNode();
        state.set("worldTime", mapper.valueToTree(projection.worldTime()));
        ObjectNode core = state.path("coreState") instanceof ObjectNode object
                ? object.deepCopy() : mapper.createObjectNode();
        core.put("currentLocationName", projection.coreState().location());
        core.put("condition", projection.coreState().condition());
        core.put("lifeGoal", projection.coreState().lifeGoal());
        state.set("coreState", core);

        ObjectNode trusted = state.path(TRUSTED_PROJECTION) instanceof ObjectNode object
                ? object.deepCopy() : mapper.createObjectNode();
        trusted.set("worldFacts", mapper.valueToTree(projection.worldFacts().values().stream()
                .sorted(Comparator.comparing(WorldFactState::id)).toList()));
        trusted.set("knowledge", mapper.valueToTree(projection.knowledge().values().stream()
                .sorted(Comparator.comparing(KnowledgeState::subjectRef)
                        .thenComparing(KnowledgeState::topicRef)).toList()));
        CreationContract storedContract = required(trusted, "creationContract", CreationContract.class, "创角契约");
        if (!storedContract.equals(projection.creationContract())) {
            throw new IllegalStateException("普通回合不能改写创角契约");
        }
        trusted.set("playPreference", mapper.valueToTree(projection.playPreference()));
        if (projection.cultivationSystem() != null) {
            cultivationValidator.validateState(projection.cultivationSystem(), projection.protagonistCultivation());
            trusted.set("cultivationSystem", mapper.valueToTree(projection.cultivationSystem()));
            trusted.set("protagonistCultivation", mapper.valueToTree(projection.protagonistCultivation()));
        }
        state.set(TRUSTED_PROJECTION, trusted);
        return state;
    }

    /** 创建命卷时一次性保存创角契约、游玩偏好和已确认修行状态。 */
    public ObjectNode writeInitialCreation(JsonNode state, CreationContract contract, PlayPreference preference,
                                           CultivationSystem system, ProtagonistCultivation protagonist) {
        if (contract == null) throw new IllegalArgumentException("创角契约不能为空");
        if (preference == null) throw new IllegalArgumentException("游玩偏好不能为空");
        cultivationValidator.validateState(system, protagonist);
        ObjectNode result = state instanceof ObjectNode object ? object.deepCopy() : mapper.createObjectNode();
        ObjectNode trusted = result.path(TRUSTED_PROJECTION) instanceof ObjectNode object
                ? object.deepCopy() : mapper.createObjectNode();
        trusted.set("creationContract", mapper.valueToTree(contract));
        trusted.set("playPreference", mapper.valueToTree(preference));
        trusted.set("cultivationSystem", mapper.valueToTree(system));
        trusted.set("protagonistCultivation", mapper.valueToTree(protagonist));
        if (!trusted.has("worldFacts")) trusted.putArray("worldFacts");
        if (!trusted.has("knowledge")) trusted.putArray("knowledge");
        result.set(TRUSTED_PROJECTION, trusted);
        return result;
    }

    /** 只读取命卷级修行体系，用于 API 展示而不重复加载全部实体和记忆。 */
    public CultivationSnapshot loadCultivation(JsonNode state) {
        return cultivation(trustedProjection(state));
    }

    /** 列表与只读视图使用的轻量创角状态，不装配实体和记忆。 */
    public CreationSnapshot loadCreation(JsonNode state) {
        JsonNode trusted = trustedProjection(state);
        return new CreationSnapshot(required(trusted, "creationContract", CreationContract.class, "创角契约"),
                required(trusted, "playPreference", PlayPreference.class, "游玩偏好"), cultivation(trusted));
    }

    /**
     * 保存当前场景正等待玩家确认的重大决定。非必选检查点会清除旧值，避免玩家完成决定后仍看到过期提示。
     */
    public ObjectNode writeDecisionCheckpoint(ObjectNode state, DecisionCheckpoint checkpoint) {
        if (state == null) throw new IllegalArgumentException("待保存的命卷状态不能为空");
        ObjectNode scene = state.path(TRUSTED_SCENE) instanceof ObjectNode object
                ? object.deepCopy() : mapper.createObjectNode();
        if (checkpoint == null || !checkpoint.required()) {
            scene.putNull("pendingDecision");
            state.set(TRUSTED_SCENE, scene);
            return state;
        }
        if (checkpoint.prompt() == null || checkpoint.prompt().isBlank()) {
            throw new IllegalArgumentException("待玩家决定的场景必须包含明确提示");
        }
        scene.set("pendingDecision", mapper.valueToTree(checkpoint));
        state.set(TRUSTED_SCENE, scene);
        return state;
    }

    /** 读取当前尚待玩家回答的决定。 */
    public DecisionCheckpoint loadDecisionCheckpoint(JsonNode state) {
        if (state == null || !state.has(TRUSTED_SCENE)) throw new IllegalStateException("命卷缺少当前场景");
        JsonNode scene = state.get(TRUSTED_SCENE);
        if (!scene.isObject()) throw new IllegalStateException("命卷当前场景格式错误");
        JsonNode pending = scene.path("pendingDecision");
        if (pending.isNull() || pending.isMissingNode()) return null;
        if (!pending.isObject()) throw new IllegalStateException("命卷待决定信息格式错误");
        DecisionCheckpoint checkpoint = mapper.convertValue(pending, DecisionCheckpoint.class);
        if (!checkpoint.required() || checkpoint.prompt() == null || checkpoint.prompt().isBlank()) {
            throw new IllegalStateException("命卷待决定信息缺少有效提示");
        }
        return checkpoint;
    }

    /** 将投影中的全部实体转换为 upsert 所需记录；隐藏实体不会从结果中消失。 */
    public List<Entity> writeEntities(String saveId, StoryProjection projection, List<Entity> existing,
                                      int changedTurn, Instant now) {
        if (saveId == null || saveId.isBlank()) throw new IllegalArgumentException("实体映射缺少命卷ID");
        if (projection == null) throw new IllegalArgumentException("实体映射缺少命卷投影");
        Instant timestamp = now == null ? Instant.now() : now;
        Map<String, Entity> previous = new LinkedHashMap<>();
        safe(existing).forEach(value -> previous.put(value.id(), value));
        return projection.entities().values().stream().sorted(Comparator.comparing(EntityState::id))
                .map(value -> writeEntity(saveId, value, previous.get(value.id()), changedTurn, timestamp))
                .toList();
    }

    private EntityState entity(Entity entity) {
        JsonNode attributes = entity.attributes();
        boolean item = "ITEM".equalsIgnoreCase(entity.type());
        boolean abilityEntity = "ABILITY".equalsIgnoreCase(entity.type());
        boolean character = "CHARACTER".equalsIgnoreCase(entity.type());
        JsonNode trusted = trustedEntity(attributes, entity.type());
        QuantityValue quantity = item && trusted.has("quantity") ? trustedQuantity(trusted.get("quantity")) : null;
        CustodyValue custody = item ? trustedCustody(trusted.get("custody")) : null;
        Boolean possession = item ? custody.holderType() == HolderType.PLAYER : null;
        if (item && !entity.visible()) {
            possession = false;
            custody = new CustodyValue(HolderType.NONE, null);
        }
        AbilityState ability = abilityEntity
                ? requiredEnum(trusted, "abilityState", AbilityState.class, "能力状态") : null;
        LifecycleState lifecycle = character
                ? requiredEnum(trusted, "lifecycle", LifecycleState.class, "人物生命周期") : null;
        RelationshipValue relationship = character && trusted.has("relationship")
                ? trustedRelationship(trusted.get("relationship")) : null;
        JsonNode publicAttributes = attributes == null ? null : attributes.deepCopy();
        if (publicAttributes instanceof ObjectNode object) object.remove(TRUSTED_ENTITY);
        return new EntityState(entity.id(), entity.type(), entity.name(), entity.status(), entity.summary(),
                publicAttributes, entity.visible(), quantity, possession, custody,
                ability, lifecycle, relationship);
    }

    private Entity writeEntity(String saveId, EntityState value, Entity previous, int changedTurn, Instant now) {
        // 写边界再做一次增量合并：一次关系或归属更新没有携带 identity、
        // lastKnownLocation 等旧属性时，不能把已确认事实整块覆盖掉。
        ObjectNode attributes = previous != null && previous.attributes() instanceof ObjectNode object
                ? object.deepCopy() : mapper.createObjectNode();
        if (value.attributes() instanceof ObjectNode next) attributes.setAll(next);
        attributes.remove(TRUSTED_ENTITY);
        boolean item = "ITEM".equalsIgnoreCase(value.entityType());
        boolean character = "CHARACTER".equalsIgnoreCase(value.entityType());
        boolean ability = "ABILITY".equalsIgnoreCase(value.entityType());
        attributes.remove(List.of("quantity", "inPossession", "relationship"));
        ObjectNode trusted = mapper.createObjectNode();
        if (item && value.quantity() != null) trusted.set("quantity", mapper.valueToTree(value.quantity()));
        if (item) {
            CustodyValue custody = value.visible() ? value.custody() : new CustodyValue(HolderType.NONE, null);
            if (custody == null) throw new IllegalStateException("当前物品缺少可信保管关系：" + value.id());
            trusted.set("custody", mapper.valueToTree(custody));
        }
        if (ability) {
            if (value.abilityState() == null) throw new IllegalStateException("当前能力缺少可信状态：" + value.id());
            trusted.put("abilityState", value.abilityState().name());
        }
        if (character) {
            if (value.lifecycle() == null) throw new IllegalStateException("当前人物缺少可信生命周期：" + value.id());
            trusted.put("lifecycle", value.lifecycle().name());
        }
        if (character && value.relationship() != null) {
            trusted.set("relationship", mapper.valueToTree(value.relationship()));
        }
        attributes.set(TRUSTED_ENTITY, trusted);
        return new Entity(value.id(), saveId, value.entityType(), value.name(), value.status(), value.visible(),
                value.summary(), attributes, previous == null ? changedTurn : previous.firstTurn(), changedTurn,
                previous == null ? now : previous.createdAt(), now);
    }

    private CustodyValue trustedCustody(JsonNode value) {
        if (value == null || !value.isObject()) throw new IllegalStateException("统一实体可信保管关系格式错误");
        CustodyValue custody = mapper.convertValue(value, CustodyValue.class);
        if (custody.holderType() == null) throw new IllegalStateException("统一实体可信保管关系缺少类型");
        if ((custody.holderType() == HolderType.CHARACTER || custody.holderType() == HolderType.LOCATION)
                && (custody.holderRef() == null || custody.holderRef().isBlank())) {
            throw new IllegalStateException("人物或地点保管关系缺少稳定引用");
        }
        return custody;
    }

    private RelationshipValue trustedRelationship(JsonNode value) {
        if (value == null || !value.isObject()) throw new IllegalStateException("统一实体可信人物关系格式错误");
        RelationshipValue relationship = mapper.convertValue(value, RelationshipValue.class);
        if (relationship.label() == null || relationship.label().isBlank()) {
            throw new IllegalStateException("统一实体可信人物关系缺少关系名称");
        }
        return new RelationshipValue(relationship.label().strip(), relationship.changeReason());
    }

    private QuantityValue trustedQuantity(JsonNode value) {
        if (value == null || !value.isObject()) throw new IllegalStateException("统一实体可信数量格式错误");
        QuantityValue quantity = mapper.convertValue(value, QuantityValue.class);
        if (quantity.known() && (quantity.value() == null || quantity.value() < 0)) {
            throw new IllegalStateException("统一实体已知数量缺少有效值");
        }
        if (!quantity.known() && quantity.value() != null) {
            throw new IllegalStateException("统一实体未知数量不能包含具体值");
        }
        return quantity;
    }

    private JsonNode trustedProjection(JsonNode state) {
        if (state == null || !state.has(TRUSTED_PROJECTION)) throw new IllegalStateException("命卷缺少可信投影");
        JsonNode trusted = state.get(TRUSTED_PROJECTION);
        if (!trusted.isObject()) throw new IllegalStateException("命卷可信投影格式错误");
        return trusted;
    }

    private JsonNode trustedEntity(JsonNode attributes, String entityType) {
        boolean requiresTrustedState = "ITEM".equalsIgnoreCase(entityType)
                || "ABILITY".equalsIgnoreCase(entityType) || "CHARACTER".equalsIgnoreCase(entityType);
        if (!requiresTrustedState) return mapper.createObjectNode();
        if (attributes == null || !attributes.has(TRUSTED_ENTITY)) {
            throw new IllegalStateException("当前" + entityType + "实体缺少可信状态");
        }
        JsonNode trusted = attributes.get(TRUSTED_ENTITY);
        if (!trusted.isObject()) throw new IllegalStateException("当前实体可信状态格式错误");
        return trusted;
    }

    private <T> T required(JsonNode parent, String field, Class<T> type, String label) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw new IllegalStateException("命卷缺少" + label);
        try {
            return mapper.convertValue(value, type);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("命卷" + label + "结构无效：" + error.getMessage(), error);
        }
    }

    private CultivationSnapshot cultivation(JsonNode trusted) {
        JsonNode systemNode = trusted.path("cultivationSystem");
        JsonNode protagonistNode = trusted.path("protagonistCultivation");
        boolean hasSystem = systemNode.isObject();
        boolean hasProtagonist = protagonistNode.isObject();
        if (!hasSystem && !hasProtagonist) return null;
        if (hasSystem != hasProtagonist) {
            throw new IllegalStateException("命卷修行体系与角色当前阶段必须同时存在");
        }
        try {
            CultivationSystem system = mapper.convertValue(systemNode, CultivationSystem.class);
            ProtagonistCultivation protagonist = mapper.convertValue(protagonistNode, ProtagonistCultivation.class);
            cultivationValidator.validateState(system, protagonist);
            return new CultivationSnapshot(system, protagonist);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("命卷修行体系结构无效：" + error.getMessage(), error);
        }
    }

    private String displayRealm(CultivationSnapshot cultivation) {
        if (cultivation == null) throw new IllegalStateException("命卷缺少已确认修行体系");
        return displayRealm(cultivation.system(), cultivation.protagonist(), "境界未明");
    }

    private String displayRealm(StoryProjection projection) {
        if (projection.cultivationSystem() == null) return projection.coreState().realm();
        return displayRealm(projection.cultivationSystem(), projection.protagonistCultivation(),
                projection.coreState().realm());
    }

    private String displayRealm(CultivationSystem system, ProtagonistCultivation protagonist, String fallback) {
        cultivationValidator.validateState(system, protagonist);
        String name = system.stages().stream()
                .filter(stage -> stage.stageId().equals(protagonist.currentStageId()))
                .map(CultivationSystem.Stage::name)
                .findFirst().orElse(fallback);
        return name + phaseName(protagonist.phase());
    }

    private String phaseName(com.wenxian.game.story.domain.WorldPackage.RealmPhase phase) {
        return switch (phase) {
            case EARLY -> "初期";
            case MIDDLE -> "中期";
            case LATE -> "后期";
            case PERFECT -> "圆满";
        };
    }

    public record CultivationSnapshot(CultivationSystem system, ProtagonistCultivation protagonist) {}

    public record CreationSnapshot(CreationContract contract, PlayPreference preference,
                                   CultivationSnapshot cultivation) {}

    private <E extends Enum<E>> E enumValue(JsonNode value, Class<E> type) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("统一实体可信枚举字段格式错误");
        }
        try {
            return Enum.valueOf(type, value.asText());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("统一实体可信枚举字段值无效：" + value.asText(), error);
        }
    }

    private <E extends Enum<E>> E requiredEnum(JsonNode parent, String field, Class<E> type, String label) {
        E value = enumValue(parent.get(field), type);
        if (value == null) throw new IllegalStateException("当前实体缺少可信" + label);
        return value;
    }

    private <T> List<T> convertList(JsonNode value, TypeReference<List<T>> type) {
        if (value == null || value.isMissingNode() || value.isNull()) return List.of();
        if (!value.isArray()) throw new IllegalStateException("命卷可信投影集合格式错误");
        return mapper.convertValue(value, type);
    }

    private <T> Map<String, T> indexed(List<T> values, java.util.function.Function<T, String> key,
                                       String label) {
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T value : values) {
            String id = key.apply(value);
            if (id == null || id.isBlank()) throw new IllegalStateException(label + "缺少稳定引用");
            if (indexed.putIfAbsent(id, value) != null) throw new IllegalStateException(label + "存在重复引用：" + id);
        }
        return indexed;
    }

    private String knowledgeKey(KnowledgeState value) {
        return value.subjectRef().strip() + "\u0000" + value.topicRef().strip();
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
