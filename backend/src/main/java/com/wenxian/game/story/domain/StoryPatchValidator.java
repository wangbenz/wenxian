package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.StoryPatch.WorldFactPatch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 校验 Story Agent 变化提案的通用结构不变量。
 *
 * <p>本校验器只保护稳定引用、时间、数量、认知主体和修订审计等跨世界通用边界，
 * 不判断某个种族能否修炼某功法，也不根据关键词裁决战斗和剧情合理性。</p>
 */
public final class StoryPatchValidator {

    /** 校验失败时拒绝整份提案，调用方不得部分落库。 */
    public void validate(StoryPatch patch) {
        validate(patch, null);
    }

    /**
     * 在通用结构校验之外，保护玩家明确授权的行动时长。
     *
     * @param explicitDurationMinutes 玩家明确写出的持续分钟数；无法可靠识别时为 {@code null}
     */
    public void validate(StoryPatch patch, Long explicitDurationMinutes) {
        require(patch != null, "推演结果不能为空");
        require(present(patch.narrative()), "推演结果必须包含正文");
        validateOutcome(patch.outcome());
        validateTime(patch, explicitDurationMinutes);
        patch.entities().forEach(this::validateEntity);
        validateEntityAliases(patch);
        patch.worldFacts().forEach(this::validateWorldFact);
        patch.knowledge().forEach(knowledge -> {
            require(present(knowledge.subjectRef()), "人物认知必须标明认知主体");
            require(present(knowledge.topicRef()), "人物认知必须标明认知对象");
            require(present(knowledge.summary()), "人物认知必须包含摘要");
        });
        patch.memories().forEach(memory -> {
            require(present(memory.title()), "关键记忆必须包含标题");
            require(present(memory.summary()), "关键记忆必须包含摘要");
        });
        if (patch.decisionCheckpoint() != null && patch.decisionCheckpoint().required()) {
            require(present(patch.decisionCheckpoint().prompt()), "重大决定检查点必须说明待玩家决定的内容");
        }
    }

    private void validateOutcome(StoryPatch.TurnOutcome outcome) {
        if (outcome == null) return;
        require(outcome.resolution() != null, "本轮结果必须说明成功、部分成功或失败");
        require(present(outcome.summary()), "本轮结果必须包含直接结果摘要");
    }

    private void validateTime(StoryPatch patch, Long explicitDurationMinutes) {
        require(patch.time() != null, "推演结果必须说明实际经过时间");
        require(patch.time().elapsedMinutes() >= 0, "实际经过时间不能为负数");
        require(patch.time().resolution() != null, "时间结果必须说明是否完整执行");
        if (patch.time().resolution() == StoryPatch.TimeResolution.INTERRUPTED) {
            require(present(patch.time().interruptionReason()), "行动被中断时必须说明原因");
        }
        if (explicitDurationMinutes == null) return;
        long elapsed = patch.time().elapsedMinutes();
        if (patch.time().resolution() == StoryPatch.TimeResolution.COMPLETE) {
            require(elapsed == explicitDurationMinutes,
                    "玩家明确要求推进" + explicitDurationMinutes + "分钟；完整执行必须推进同样时长，当前为"
                            + elapsed + "分钟。如行动提前结束，请改为INTERRUPTED并说明中断原因");
        } else {
            require(elapsed < explicitDurationMinutes,
                    "被中断的行动必须早于玩家要求的" + explicitDurationMinutes + "分钟结束");
        }
    }

    private void validateEntity(EntityPatch patch) {
        require(patch != null, "实体补丁不能为空");
        require(patch.operation() != null, "实体补丁必须包含操作");
        require(present(patch.entityType()), "实体补丁必须包含类型");
        if (patch.operation() == EntityOperation.CREATE) {
            require(!present(patch.entityId()), "新实体的正式ID必须由服务端分配");
            require(present(patch.name()), "新实体必须包含名称");
            require(present(patch.status()), "新实体必须包含当前状态");
            require(present(patch.summary()), "新实体必须包含事实摘要");
        } else {
            require(present(patch.entityId()), "更新已有实体必须引用稳定ID");
            require(!present(patch.referenceKey()), "只有本轮新增实体可以声明回合内引用键");
        }
        validateQuantity(patch.quantity());
        if (patch.custody() != null) {
            require(patch.custody().holderType() != null, "物品保管关系必须包含保管者类型");
            if (patch.custody().holderType() == StoryPatch.HolderType.CHARACTER
                    || patch.custody().holderType() == StoryPatch.HolderType.LOCATION) {
                require(present(patch.custody().holderRef()), "人物或地点保管关系必须包含稳定引用");
            } else {
                require(!present(patch.custody().holderRef()), "只有人物或地点保管关系可以包含引用");
            }
        }
        if (patch.lifecycle() != null) {
            require(patch.lifecycle().state() != null, "人物生命周期变化必须包含目标状态");
        }
        if (patch.relationship() != null) {
            require("CHARACTER".equalsIgnoreCase(patch.entityType()), "只有人物实体可以记录关系变化");
            require(present(patch.relationship().label()), "人物关系必须包含当前关系名称");
        }
    }

    private void validateEntityAliases(StoryPatch patch) {
        Map<String, EntityPatch> createsByKey = new LinkedHashMap<>();
        for (EntityPatch entity : patch.entities()) {
            if (entity == null || !present(entity.referenceKey())) continue;
            String key = StoryReferenceAlias.normalizeKey(entity.referenceKey());
            require(entity.operation() == EntityOperation.CREATE, "回合内引用键只能绑定本轮新增实体");
            require(createsByKey.putIfAbsent(key, entity) == null, "同一回合内引用键不能重复");
        }
        for (EntityPatch entity : patch.entities()) {
            if (entity == null || entity.custody() == null
                    || entity.custody().holderType() != StoryPatch.HolderType.CHARACTER) continue;
            String key = StoryReferenceAlias.keyOf(entity.custody().holderRef());
            if (key == null) continue;
            EntityPatch holder = createsByKey.get(key);
            require(holder != null, "物品保管关系引用了未创建的回合内人物");
            require("CHARACTER".equalsIgnoreCase(holder.entityType()), "物品保管关系的回合内引用必须指向人物");
        }
    }

    private void validateQuantity(QuantityValue quantity) {
        if (quantity == null) return;
        if (quantity.known()) {
            require(quantity.value() != null, "已知数量必须包含具体值");
            require(quantity.value() >= 0, "物品数量不能为负数");
        } else {
            require(quantity.value() == null, "未知数量不能伪造具体值");
        }
    }

    private void validateWorldFact(WorldFactPatch patch) {
        require(patch != null, "世界事实补丁不能为空");
        require(patch.operation() != null, "世界事实补丁必须包含操作");
        require(present(patch.statement()), "世界事实补丁必须包含有效陈述");
        if (patch.operation() != WorldFactOperation.EXPAND) {
            require(present(patch.factId()), "转变或修订世界事实必须引用稳定ID");
        }
        if (patch.operation() == WorldFactOperation.REVISE) {
            require(present(patch.reason()), "修订已有世界事实必须保存玩家确认的原因");
        }
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
