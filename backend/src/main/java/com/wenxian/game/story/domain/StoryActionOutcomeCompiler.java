package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ReferencePersistence;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.OutcomeResolution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将行动契约与结果计划中已经明确的结构化结论编译为一致的长期事实补丁。
 *
 * <p>本类不阅读玩家中文、不判断胜负，也不创造模型尚未给出的结果。它只处理两类协议级工作：删除明确声明为
 * {@code TRANSIENT} 的对象补丁；将 {@code resultTarget=true} 的新能力与模型给出的成功、部分成功或失败结论
 * 对齐。这样即使模型漏写了机械性的能力实体，正文已经确认的学习结果也不会在下一回合丢失。</p>
 */
public final class StoryActionOutcomeCompiler {

    public StoryPatch compile(StoryActionContract contract, StoryPatch patch) {
        if (contract == null || patch == null) return patch;
        Map<String, EntityReference> references = references(contract);
        List<EntityPatch> entities = patch.entities().stream()
                .filter(entity -> !belongsToTransientReference(references, entity))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (patch.outcome() != null) {
            contract.references().stream()
                    .filter(this::isNewAbilityResult)
                    .forEach(reference -> compileAbilityResult(reference, patch, entities));
        }
        if (entities.equals(patch.entities())) return patch;
        return new StoryPatch(patch.title(), patch.narrative(), patch.time(), patch.coreState(), entities,
                patch.worldFacts(), patch.knowledge(), patch.memories(), patch.decisionCheckpoint(),
                patch.choices(), patch.rollingSummary(), patch.outcome());
    }

    private Map<String, EntityReference> references(StoryActionContract contract) {
        Map<String, EntityReference> values = new LinkedHashMap<>();
        contract.references().stream().filter(value -> value != null).forEach(reference -> {
            String key = StoryReferenceAlias.normalizeKey(reference.referenceKey());
            if (key != null) values.putIfAbsent(key, reference);
        });
        return values;
    }

    private boolean belongsToTransientReference(Map<String, EntityReference> references, EntityPatch entity) {
        if (entity == null || entity.operation() != EntityOperation.CREATE) return false;
        String key = StoryReferenceAlias.normalizeKey(entity.referenceKey());
        EntityReference reference = key == null ? null : references.get(key);
        return reference != null && reference.persistence() == ReferencePersistence.TRANSIENT;
    }

    private boolean isNewAbilityResult(EntityReference reference) {
        return reference != null && reference.resultTarget()
                && reference.persistence() != ReferencePersistence.TRANSIENT
                && reference.resolution() != ReferenceResolution.EXISTING
                && "ABILITY".equalsIgnoreCase(reference.entityType());
    }

    private void compileAbilityResult(EntityReference reference, StoryPatch patch, List<EntityPatch> entities) {
        String key = StoryReferenceAlias.normalizeKey(reference.referenceKey());
        if (key == null) return;
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < entities.size(); index++) {
            EntityPatch entity = entities.get(index);
            if (entity != null && entity.operation() == EntityOperation.CREATE
                    && key.equals(StoryReferenceAlias.normalizeKey(entity.referenceKey()))) {
                matches.add(index);
            }
        }
        OutcomeResolution resolution = patch.outcome().resolution();
        if (resolution == OutcomeResolution.FAILED) {
            for (int index = matches.size() - 1; index >= 0; index--) {
                entities.remove(matches.get(index).intValue());
            }
            return;
        }
        AbilityState state = resolution == OutcomeResolution.SUCCEEDED
                ? AbilityState.MASTERED : AbilityState.LEARNING;
        if (matches.isEmpty()) {
            entities.add(new EntityPatch(EntityOperation.CREATE, null, "ABILITY", reference.canonicalName(),
                    state == AbilityState.MASTERED ? "已掌握" : "学习中",
                    outcomeSummary(patch, reference), null, null, null, null,
                    state, null, null, key));
            return;
        }
        if (matches.size() == 1) {
            int index = matches.getFirst();
            EntityPatch current = entities.get(index);
            entities.set(index, new EntityPatch(current.operation(), current.entityId(), "ABILITY",
                    textOr(current.name(), reference.canonicalName()),
                    textOr(current.status(), state == AbilityState.MASTERED ? "已掌握" : "学习中"),
                    textOr(current.summary(), outcomeSummary(patch, reference)), current.attributes(),
                    current.quantity(), current.inPossession(), current.custody(), state,
                    current.lifecycle(), current.relationship(), current.nameChange(), key));
        }
    }

    private String outcomeSummary(StoryPatch patch, EntityReference reference) {
        String summary = patch.outcome() == null ? null : patch.outcome().summary();
        return textOr(summary, "本轮对“" + reference.canonicalName() + "”形成了可持续的修习结果。");
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
