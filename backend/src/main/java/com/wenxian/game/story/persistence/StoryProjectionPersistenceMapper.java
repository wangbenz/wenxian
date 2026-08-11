package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 稳定身份继续保存到 {@code story_entity}。可信扩展字段写入保留的 JSON 子对象，不新增 V0.15 表，
 * 同时保留旧运行时尚需读取的其他 JSON 字段。</p>
 */
public final class StoryProjectionPersistenceMapper {
    static final String TRUSTED_PROJECTION = "_trustedProjection";
    static final String TRUSTED_ENTITY = "_trusted";
    static final String TRUSTED_SCENE = "_trustedScene";
    private static final int TRUSTED_SCHEMA_VERSION = 1;

    private final ObjectMapper mapper;

    public StoryProjectionPersistenceMapper(ObjectMapper mapper) {
        if (mapper == null) throw new IllegalArgumentException("命卷投影映射器不能为空");
        this.mapper = mapper;
    }

    /** 从当前快照、全部实体事实和有效记忆构建权威投影。 */
    public StoryProjection load(StoredSave save, List<Entity> entities, List<StoredMemory> memories) {
        if (save == null) throw new IllegalArgumentException("统一命卷不能为空");
        JsonNode state = save.state();
        CoreState core = new CoreState(text(state, "currentLocation", text(state, "location", "未知")),
                text(state, "condition", "平静"), text(state.path("protagonist"), "realm", "凡俗"),
                text(state.path("protagonist"), "lifeGoal", ""));
        Map<String, EntityState> entityStates = new LinkedHashMap<>();
        safe(entities).forEach(entity -> {
            EntityState mapped = entity(entity);
            if (entityStates.putIfAbsent(mapped.id(), mapped) != null) {
                throw new IllegalStateException("统一命卷存在重复实体ID：" + mapped.id());
            }
        });

        JsonNode trusted = trustedProjection(state);
        Map<String, WorldFactState> worldFacts = indexed(
                convertList(trusted.path("worldFacts"), new TypeReference<>() {}), WorldFactState::id, "世界事实");
        Map<String, KnowledgeState> knowledge = indexed(
                convertList(trusted.path("knowledge"), new TypeReference<>() {}), this::knowledgeKey, "人物认知");
        List<MemoryState> memoryStates = safe(memories).stream()
                .map(value -> new MemoryState(value.id(), value.memoryType(), value.title(), value.summary(),
                        value.importance(), List.of()))
                .toList();
        return new StoryProjection(Math.max(0, state.path("worldTimeMinutes").asLong(0)), core,
                entityStates, worldFacts, knowledge, memoryStates);
    }

    /** 将中性核心投影写回 state_json，同时保留世界模板、主角姓名、正文和选项等现有字段。 */
    public ObjectNode writeState(JsonNode existingState, StoryProjection projection) {
        if (projection == null) throw new IllegalArgumentException("待保存的命卷投影不能为空");
        ObjectNode state = existingState instanceof ObjectNode object ? object.deepCopy() : mapper.createObjectNode();
        state.put("worldTimeMinutes", projection.worldTimeMinutes());
        state.put("currentLocation", projection.coreState().location());
        state.put("condition", projection.coreState().condition());
        ObjectNode protagonist = state.path("protagonist") instanceof ObjectNode object
                ? object.deepCopy() : mapper.createObjectNode();
        protagonist.put("realm", projection.coreState().realm());
        protagonist.put("lifeGoal", projection.coreState().lifeGoal());
        state.set("protagonist", protagonist);

        ObjectNode trusted = state.path(TRUSTED_PROJECTION) instanceof ObjectNode object
                ? object.deepCopy() : mapper.createObjectNode();
        trusted.put("schemaVersion", TRUSTED_SCHEMA_VERSION);
        trusted.set("worldFacts", mapper.valueToTree(projection.worldFacts().values().stream()
                .sorted(Comparator.comparing(WorldFactState::id)).toList()));
        trusted.set("knowledge", mapper.valueToTree(projection.knowledge().values().stream()
                .sorted(Comparator.comparing(KnowledgeState::subjectRef)
                        .thenComparing(KnowledgeState::topicRef)).toList()));
        state.set(TRUSTED_PROJECTION, trusted);
        return state;
    }

    /**
     * 保存当前场景正等待玩家确认的重大决定。非必选检查点会清除旧值，避免玩家完成决定后仍看到过期提示。
     */
    public ObjectNode writeDecisionCheckpoint(ObjectNode state, DecisionCheckpoint checkpoint) {
        if (state == null) throw new IllegalArgumentException("待保存的命卷状态不能为空");
        if (checkpoint == null || !checkpoint.required()) {
            state.remove(TRUSTED_SCENE);
            return state;
        }
        if (checkpoint.prompt() == null || checkpoint.prompt().isBlank()) {
            throw new IllegalArgumentException("待玩家决定的场景必须包含明确提示");
        }
        ObjectNode scene = mapper.createObjectNode();
        scene.put("schemaVersion", TRUSTED_SCHEMA_VERSION);
        scene.set("pendingDecision", mapper.valueToTree(checkpoint));
        state.set(TRUSTED_SCENE, scene);
        return state;
    }

    /** 读取当前尚待玩家回答的决定；旧命卷没有该扩展时返回空。 */
    public DecisionCheckpoint loadDecisionCheckpoint(JsonNode state) {
        if (state == null || !state.has(TRUSTED_SCENE)) return null;
        JsonNode scene = state.get(TRUSTED_SCENE);
        if (!scene.isObject() || scene.path("schemaVersion").asInt(-1) != TRUSTED_SCHEMA_VERSION) {
            throw new IllegalStateException("命卷场景控制结构版本不受支持");
        }
        JsonNode pending = scene.path("pendingDecision");
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
        JsonNode trusted = attributes == null ? mapper.createObjectNode() : attributes.path(TRUSTED_ENTITY);
        boolean item = "ITEM".equalsIgnoreCase(entity.type());
        boolean abilityEntity = "ABILITY".equalsIgnoreCase(entity.type());
        boolean character = "CHARACTER".equalsIgnoreCase(entity.type());
        QuantityValue quantity = trusted.has("quantity")
                ? trustedQuantity(trusted.get("quantity")) : legacyQuantity(attributes);
        Boolean possession = trusted.has("inPossession") ? booleanValue(trusted.get("inPossession"))
                : attributes != null && attributes.has("inPossession")
                ? booleanValue(attributes.get("inPossession")) : null;
        CustodyValue custody = trusted.has("custody")
                ? trustedCustody(trusted.get("custody")) : legacyCustody(possession, entity.visible(), entity.type());
        // 历史版本的“整理记录”只隐藏了物品，未同步清除持有标记。统一投影以当前可见性兜底归一，
        // 避免 H5 已经移出行囊、Agent 却仍收到 inPossession=true 的互相冲突事实。
        if ("ITEM".equalsIgnoreCase(entity.type()) && !entity.visible()) {
            possession = false;
            custody = new CustodyValue(HolderType.NONE, null);
        }
        AbilityState ability = enumValue(trusted.get("abilityState"), AbilityState.class);
        LifecycleState lifecycle = enumValue(trusted.get("lifecycle"), LifecycleState.class);
        RelationshipValue relationship = trusted.has("relationship")
                ? trustedRelationship(trusted.get("relationship")) : legacyRelationship(attributes, entity.type());
        // 通用模型契约为不同实体共用一套字段，但跨类型字段不是权威事实。
        // 例如人物不存在“是否放在行囊”，物品也不存在“人物关系”。
        if (!item) {
            quantity = null;
            possession = null;
            custody = null;
        }
        if (!abilityEntity) ability = null;
        if (!character) {
            lifecycle = null;
            relationship = null;
        }
        // 旧 V0.14 人物没有独立生命周期字段。只能归一为 UNKNOWN，不能根据“居住、失踪、已故”等
        // 自然语言状态猜测；后续由已提交的新回合或玩家修正逐步形成明确事实。
        if ("CHARACTER".equalsIgnoreCase(entity.type()) && lifecycle == null) {
            lifecycle = LifecycleState.UNKNOWN;
        }
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
        ObjectNode trusted = mapper.createObjectNode();
        if (item && value.quantity() != null) trusted.set("quantity", mapper.valueToTree(value.quantity()));
        if (item && value.inPossession() != null) trusted.put("inPossession", value.inPossession());
        if (item && value.custody() != null) trusted.set("custody", mapper.valueToTree(value.custody()));
        if ("ABILITY".equalsIgnoreCase(value.entityType()) && value.abilityState() != null) {
            trusted.put("abilityState", value.abilityState().name());
        }
        if (character && value.lifecycle() != null) trusted.put("lifecycle", value.lifecycle().name());
        if (character && value.relationship() != null) {
            trusted.set("relationship", mapper.valueToTree(value.relationship()));
        }
        attributes.set(TRUSTED_ENTITY, trusted);

        // 兼容当前角色页读取，不能把未知数量伪造为1。
        if (item && value.quantity() != null && value.quantity().known()) {
            attributes.put("quantity", value.quantity().value());
        } else {
            attributes.remove("quantity");
        }
        if (item && value.inPossession() != null) attributes.put("inPossession", value.inPossession());
        else attributes.remove("inPossession");
        if (character && value.relationship() != null) attributes.put("relationship", value.relationship().label());
        else if (!character) attributes.remove("relationship");
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

    private RelationshipValue legacyRelationship(JsonNode attributes, String entityType) {
        if (!"CHARACTER".equalsIgnoreCase(entityType) || attributes == null) return null;
        String label = attributes.path("relationship").asText("").strip();
        return label.isBlank() ? null : new RelationshipValue(label, "旧存档关系导入");
    }

    private CustodyValue legacyCustody(Boolean possession, boolean visible, String entityType) {
        if (!"ITEM".equalsIgnoreCase(entityType)) return null;
        if (!visible) return new CustodyValue(HolderType.NONE, null);
        if (Boolean.TRUE.equals(possession)) return new CustodyValue(HolderType.PLAYER, null);
        return new CustodyValue(HolderType.UNKNOWN, null);
    }

    private QuantityValue legacyQuantity(JsonNode attributes) {
        if (attributes == null || !attributes.has("quantity") || !attributes.get("quantity").canConvertToInt()) {
            return null;
        }
        int value = attributes.get("quantity").asInt();
        if (value < 0) throw new IllegalStateException("统一实体存在负数数量");
        return new QuantityValue(true, value);
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
        if (state == null || !state.has(TRUSTED_PROJECTION)) return mapper.createObjectNode();
        JsonNode trusted = state.get(TRUSTED_PROJECTION);
        if (!trusted.isObject()) throw new IllegalStateException("命卷可信投影格式错误");
        if (trusted.path("schemaVersion").asInt(-1) != TRUSTED_SCHEMA_VERSION) {
            throw new IllegalStateException("命卷可信投影结构版本不受支持");
        }
        return trusted;
    }

    private Boolean booleanValue(JsonNode value) {
        if (value == null || !value.isBoolean()) throw new IllegalStateException("统一实体可信布尔字段格式错误");
        return value.booleanValue();
    }

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
