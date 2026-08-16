package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ReferencePersistence;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.OutcomeResolution;

import java.util.List;

/**
 * 校验模型已结构化的直接结果与长期事实变化是否一致。
 *
 * <p>本类不从中文行动或正文猜测玩家是否“学会”。行动理解 Agent 必须把本轮希望获得的能力标记为
 * {@code resultTarget=true}；结果计划再明确成功、部分成功或失败。Java 只比较目标引用、结果枚举和能力补丁，
 * 防止正文宣布入门但可信投影没有能力记录。</p>
 */
public final class StoryOutcomeConsistencyValidator {

    public void validate(StoryActionContract contract, StoryPatch patch) {
        if (contract == null || patch == null || patch.outcome() == null) return;
        contract.references().stream()
                .filter(reference -> reference != null && reference.resultTarget())
                .filter(reference -> reference.persistence() != ReferencePersistence.TRANSIENT)
                .filter(reference -> "ABILITY".equalsIgnoreCase(reference.entityType()))
                .forEach(reference -> validateAbilityTarget(reference, patch));
    }

    private void validateAbilityTarget(EntityReference reference, StoryPatch patch) {
        List<EntityPatch> changes = patch.entities().stream()
                .filter(value -> value != null && "ABILITY".equalsIgnoreCase(value.entityType()))
                .filter(value -> matches(reference, value))
                .toList();
        OutcomeResolution result = patch.outcome().resolution();
        if (result == OutcomeResolution.SUCCEEDED) {
            EntityPatch change = unique(reference, changes, "成功掌握能力却没有形成唯一能力状态变化");
            if (change.abilityState() != AbilityState.MASTERED) {
                throw new IllegalArgumentException("成功掌握能力必须写入MASTERED状态："
                        + reference.canonicalName());
            }
        } else if (result == OutcomeResolution.PARTIAL) {
            EntityPatch change = unique(reference, changes, "能力取得部分进展却没有形成唯一学习状态变化");
            if (change.abilityState() != AbilityState.KNOWN && change.abilityState() != AbilityState.LEARNING) {
                throw new IllegalArgumentException("能力部分进展必须写入KNOWN或LEARNING状态："
                        + reference.canonicalName());
            }
        } else if (result == OutcomeResolution.FAILED && reference.resolution() != ReferenceResolution.EXISTING
                && !changes.isEmpty()) {
            throw new IllegalArgumentException("能力学习结果为失败时不能同时创建新的能力记录："
                    + reference.canonicalName());
        }
    }

    private EntityPatch unique(EntityReference reference, List<EntityPatch> changes, String problem) {
        if (changes.size() != 1) {
            throw new IllegalArgumentException(problem + "：" + reference.canonicalName());
        }
        return changes.getFirst();
    }

    private boolean matches(EntityReference reference, EntityPatch patch) {
        if (reference.resolution() == ReferenceResolution.EXISTING) {
            return reference.entityId() != null && reference.entityId().equals(patch.entityId());
        }
        String key = StoryReferenceAlias.normalizeKey(reference.referenceKey());
        return patch.operation() == EntityOperation.CREATE && key != null
                && key.equals(StoryReferenceAlias.normalizeKey(patch.referenceKey()));
    }
}
