package com.wenxian.game.story.reducer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.StoryPatch.WorldFactPatch;
import com.wenxian.game.story.domain.StoryPatchValidator;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryProjection.KnowledgeState;
import com.wenxian.game.story.domain.StoryProjection.MemoryState;
import com.wenxian.game.story.domain.StoryProjection.WorldFactState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 将通过结构校验的 StoryPatch 归并为新的当前事实投影。
 *
 * <p>Reducer 只执行通用不变量：稳定 ID、实体类型、数量、归属、能力状态、人物生命周期、
 * 时间和世界修订版本。它不读取玩家输入关键词，也不计算战力、物价或剧情胜负。</p>
 */
public final class StoryStateReducer {
    private final StoryPatchValidator validator;
    private final Function<String, String> idFactory;

    public StoryStateReducer() {
        this(new StoryPatchValidator(), prefix -> prefix + "-" + UUID.randomUUID());
    }

    /** 允许测试注入确定性 ID；生产环境必须使用不会重复的服务端 ID 生成器。 */
    public StoryStateReducer(StoryPatchValidator validator, Function<String, String> idFactory) {
        this.validator = validator;
        this.idFactory = idFactory;
    }

    /** 归并失败时拒绝整份提案，调用方不得保存部分实体或推进世界时间。 */
    public Applied reduce(StoryProjection before, StoryPatch proposal) {
        return reduce(before, proposal, null);
    }

    /**
     * 按玩家本轮明确授权的持续时间归并提案；无法可靠识别明确时长时传 {@code null}。
     */
    public Applied reduce(StoryProjection before, StoryPatch proposal, Long explicitDurationMinutes) {
        if (before == null) throw new IllegalArgumentException("当前事实投影不能为空");
        validator.validate(proposal, explicitDurationMinutes);

        Map<String, EntityState> entities = new LinkedHashMap<>(before.entities());
        Map<String, WorldFactState> worldFacts = new LinkedHashMap<>(before.worldFacts());
        Map<String, KnowledgeState> knowledge = new LinkedHashMap<>(before.knowledge());
        List<MemoryState> memories = new ArrayList<>(before.memories());
        List<EntityPatch> acceptedEntities = new ArrayList<>();
        List<WorldFactPatch> acceptedWorldFacts = new ArrayList<>();

        for (EntityPatch patch : proposal.entities()) acceptedEntities.add(applyEntity(entities, patch));
        for (WorldFactPatch patch : proposal.worldFacts()) acceptedWorldFacts.add(applyWorldFact(worldFacts, patch));
        proposal.knowledge().forEach(value -> knowledge.put(knowledgeKey(value.subjectRef(), value.topicRef()),
                new KnowledgeState(value.subjectRef(), value.topicRef(), value.summary(), value.evidenceRefs())));
        proposal.memories().forEach(value -> memories.add(new MemoryState(idFactory.apply("memory"),
                value.memoryType(), value.title(), value.summary(), value.importance(), value.evidenceRefs())));

        long nextTime;
        try {
            nextTime = Math.addExact(before.worldTimeMinutes(), proposal.time().elapsedMinutes());
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("世界时间超出可保存范围", error);
        }
        StoryProjection next = new StoryProjection(nextTime, applyCore(before.coreState(), proposal.coreState()),
                entities, worldFacts, knowledge, memories, before.initialContract());
        StoryPatch accepted = new StoryPatch(proposal.title(), proposal.narrative(), proposal.time(),
                proposal.coreState(), acceptedEntities, acceptedWorldFacts, proposal.knowledge(),
                proposal.memories(), proposal.decisionCheckpoint(), proposal.choices(), proposal.rollingSummary());
        return new Applied(next, accepted);
    }

    private EntityPatch applyEntity(Map<String, EntityState> entities, EntityPatch patch) {
        if (patch.operation() == EntityOperation.CREATE) {
            String id = idFactory.apply(entityPrefix(patch.entityType()));
            CustodyValue custody = normalizedCustody(patch.custody(), patch.inPossession());
            EntityState created = new EntityState(id, normalizedType(patch.entityType()), patch.name(), patch.status(),
                    patch.summary(), copy(patch.attributes()), true, patch.quantity(), possession(custody, patch.inPossession()), custody,
                    patch.abilityState(), patch.lifecycle() == null ? null : patch.lifecycle().state(),
                    patch.relationship());
            validateEntitySemantics(created, null, patch);
            if (entities.putIfAbsent(id, created) != null) throw new IllegalArgumentException("服务端生成了重复实体ID");
            return withEntityId(patch, id);
        }

        EntityState previous = entities.get(patch.entityId());
        if (previous == null) throw new IllegalArgumentException("更新目标实体不存在：" + patch.entityId());
        if (!normalizedType(previous.entityType()).equals(normalizedType(patch.entityType()))) {
            throw new IllegalArgumentException("实体类型与稳定ID不一致：" + patch.entityId());
        }
        boolean visible = switch (patch.operation()) {
            case HIDE -> false;
            case RESTORE -> true;
            default -> previous.visible();
        };
        CustodyValue custody = patch.custody() == null
                ? normalizedCustody(previous.custody(), previous.inPossession())
                : normalizedCustody(patch.custody(), patch.inPossession());
        EntityState updated = new EntityState(previous.id(), previous.entityType(),
                text(patch.name(), previous.name()), text(patch.status(), previous.status()),
                text(patch.summary(), previous.summary()), merge(previous.attributes(), patch.attributes()), visible,
                patch.quantity() == null ? previous.quantity() : patch.quantity(),
                possession(custody, patch.inPossession() == null ? previous.inPossession() : patch.inPossession()), custody,
                patch.abilityState() == null ? previous.abilityState() : patch.abilityState(),
                patch.lifecycle() == null ? previous.lifecycle() : patch.lifecycle().state(),
                patch.relationship() == null ? previous.relationship() : patch.relationship());
        validateEntitySemantics(updated, previous, patch);
        entities.put(updated.id(), updated);
        return patch;
    }

    private void validateEntitySemantics(EntityState next, EntityState previous, EntityPatch patch) {
        String type = normalizedType(next.entityType());
        if ("ITEM".equals(type)) {
            if (patch.operation() == EntityOperation.CREATE && next.custody() == null) {
                throw new IllegalArgumentException("新增长期物品必须明确当前保管者");
            }
            validateCustody(next.custody());
            QuantityValue quantity = next.quantity();
            if (quantity != null && quantity.known() && quantity.value() == 0 && Boolean.TRUE.equals(next.inPossession())) {
                throw new IllegalArgumentException("数量为0的物品不能同时标记为仍持有");
            }
            if (quantity != null && quantity.known() && quantity.value() > 0
                    && next.custody() != null && next.custody().holderType() == HolderType.NONE) {
                throw new IllegalArgumentException("仍有明确数量的物品不能标记为已经不存在");
            }
        }
        if ("ABILITY".equals(type) && patch.operation() == EntityOperation.CREATE && next.abilityState() == null) {
            throw new IllegalArgumentException("新增能力必须明确当前掌握状态");
        }
        if ("CHARACTER".equals(type)) {
            if (patch.operation() == EntityOperation.CREATE && next.lifecycle() == null) {
                throw new IllegalArgumentException("新增重要人物必须明确客观生命周期");
            }
            if (previous != null && previous.lifecycle() == LifecycleState.DEAD
                    && next.lifecycle() != null && next.lifecycle() != LifecycleState.DEAD
                    && (patch.lifecycle() == null || !present(patch.lifecycle().changeReason()))) {
                throw new IllegalArgumentException("已死亡人物改变生命周期必须记录明确依据");
            }
            validateRelationship(previous, next, patch);
        }
    }

    private void validateRelationship(EntityState previous, EntityState next, EntityPatch patch) {
        if (patch.attributes() != null && patch.attributes().has("relationship")) {
            String attributeLabel = patch.attributes().path("relationship").asText("").strip();
            String trustedLabel = patch.relationship() == null || patch.relationship().label() == null
                    ? "" : patch.relationship().label().strip();
            if (!attributeLabel.equals(trustedLabel)) {
                throw new IllegalArgumentException("人物关系不能只通过自由属性静默改写");
            }
        }
        if (next.relationship() == null || !present(next.relationship().label())) return;
        if (previous == null || previous.relationship() == null) return;
        String before = previous.relationship().label() == null ? "" : previous.relationship().label().strip();
        String after = next.relationship().label().strip();
        if (!before.equals(after)
                && (patch.relationship() == null || !present(patch.relationship().changeReason()))) {
            throw new IllegalArgumentException("人物关系发生变化时必须记录明确原因");
        }
    }

    private WorldFactPatch applyWorldFact(Map<String, WorldFactState> worldFacts, WorldFactPatch patch) {
        if (patch.operation() == WorldFactOperation.EXPAND) {
            String id = idFactory.apply("world-fact");
            WorldFactState created = new WorldFactState(id, patch.statement(), 1, true,
                    WorldFactOperation.EXPAND, patch.reason());
            if (worldFacts.putIfAbsent(id, created) != null) throw new IllegalArgumentException("服务端生成了重复世界事实ID");
            return new WorldFactPatch(patch.operation(), id, patch.statement(), patch.reason());
        }
        WorldFactState previous = worldFacts.get(patch.factId());
        if (previous == null || !previous.active()) {
            throw new IllegalArgumentException("要改变的世界事实不存在或已经失效：" + patch.factId());
        }
        WorldFactState updated = new WorldFactState(previous.id(), patch.statement(), previous.revision() + 1,
                true, patch.operation(), patch.reason());
        worldFacts.put(updated.id(), updated);
        return patch;
    }

    private CoreState applyCore(CoreState before, StoryPatch.CoreStatePatch patch) {
        if (patch == null) return before;
        return new CoreState(text(patch.location(), before.location()),
                text(patch.condition(), before.condition()), text(patch.realm(), before.realm()),
                text(patch.lifeGoal(), before.lifeGoal()));
    }

    private EntityPatch withEntityId(EntityPatch patch, String id) {
        return new EntityPatch(patch.operation(), id, patch.entityType(), patch.name(), patch.status(), patch.summary(),
                patch.attributes(), patch.quantity(), patch.inPossession(), patch.custody(),
                patch.abilityState(), patch.lifecycle(), patch.relationship());
    }

    private CustodyValue normalizedCustody(CustodyValue custody, Boolean inPossession) {
        if (custody != null && custody.holderType() != null) {
            String ref = present(custody.holderRef()) ? custody.holderRef().strip() : null;
            CustodyValue normalized = new CustodyValue(custody.holderType(), ref);
            Boolean derived = possession(normalized, inPossession);
            if (inPossession != null && !inPossession.equals(derived)) {
                throw new IllegalArgumentException("物品保管者与是否随身持有相互矛盾");
            }
            return normalized;
        }
        if (Boolean.TRUE.equals(inPossession)) return new CustodyValue(HolderType.PLAYER, null);
        if (Boolean.FALSE.equals(inPossession)) return new CustodyValue(HolderType.UNKNOWN, null);
        return null;
    }

    private Boolean possession(CustodyValue custody, Boolean fallback) {
        if (custody == null || custody.holderType() == null) return fallback;
        return custody.holderType() == HolderType.PLAYER;
    }

    private void validateCustody(CustodyValue custody) {
        if (custody == null || custody.holderType() == null) {
            throw new IllegalArgumentException("物品保管关系必须包含保管者类型");
        }
        if ((custody.holderType() == HolderType.CHARACTER || custody.holderType() == HolderType.LOCATION)
                && !present(custody.holderRef())) {
            throw new IllegalArgumentException("人物或地点保管关系必须包含稳定引用");
        }
    }

    private String knowledgeKey(String subjectRef, String topicRef) {
        return subjectRef.strip() + "\u0000" + topicRef.strip();
    }

    private String entityPrefix(String entityType) {
        return normalizedType(entityType).toLowerCase().replace('_', '-');
    }

    private String normalizedType(String value) {
        return value == null ? "" : value.strip().toUpperCase();
    }

    private String text(String value, String fallback) {
        return present(value) ? value.strip() : fallback;
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }

    private JsonNode merge(JsonNode previous, JsonNode patch) {
        if (patch == null) return copy(previous);
        if (previous instanceof ObjectNode previousObject && patch instanceof ObjectNode patchObject) {
            ObjectNode merged = previousObject.deepCopy();
            merged.setAll(patchObject);
            return merged;
        }
        return copy(patch);
    }

    public record Applied(StoryProjection projection, StoryPatch acceptedPatch) {}
}
