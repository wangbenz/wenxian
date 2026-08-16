package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ReferencePersistence;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleValue;
import com.wenxian.game.story.domain.StoryPatch.OutcomeResolution;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryProjection.EntityState;

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
        return compile(null, contract, patch);
    }

    /**
     * 依据提交前投影，将模型已经判定成功的物品变化编译成无歧义的稳定补丁。
     *
     * <p>模型仍决定行动成功或失败；服务器只落实行动契约已经声明的物品、接收者与数量关系。这样模型即使
     * 把接收者误写成另一个稳定人物 ID，也不会污染长期事实，更不会因为同一次修复仍猜错 ID 而卡住回合。</p>
     */
    public StoryPatch compile(StoryProjection before, StoryActionContract contract, StoryPatch patch) {
        if (contract == null || patch == null) return patch;
        Map<String, EntityReference> references = references(contract);
        List<EntityPatch> entities = patch.entities().stream()
                .filter(entity -> !belongsToTransientReference(references, entity))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        compileEstablishedItemPromotions(contract, entities);
        compileNewItemResults(contract, patch, entities);
        compileSuccessfulItemTransitions(before, contract, patch, references, entities);
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

    /** 成功交付时，物品归属只能落到行动契约的目标，且回合内接收者必须同时晋升为稳定人物。 */
    private void compileSuccessfulItemTransitions(
            StoryProjection before, StoryActionContract contract, StoryPatch patch,
            Map<String, EntityReference> references, List<EntityPatch> entities) {
        if (patch.outcome() == null || patch.outcome().resolution() != OutcomeResolution.SUCCEEDED) return;
        contract.itemTransitions().stream().filter(java.util.Objects::nonNull).forEach(transition -> {
            StoryPatch.CustodyValue target = transition.target();
            if (target == null) return;
            String key = StoryReferenceAlias.keyOf(target.holderRef());
            if (target.holderType() == HolderType.CHARACTER && key != null) {
                materializeRecipient(references.get(key), key, patch, entities);
            }
            materializeSuccessfulItemTransition(before, transition, patch, entities);
        });
    }

    private void materializeRecipient(EntityReference reference, String key, StoryPatch patch,
                                      List<EntityPatch> entities) {
        if (reference == null || reference.resolution() == ReferenceResolution.EXISTING) return;
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < entities.size(); index++) {
            EntityPatch entity = entities.get(index);
            if (entity != null && entity.operation() == EntityOperation.CREATE
                    && "CHARACTER".equalsIgnoreCase(entity.entityType())
                    && key.equals(StoryReferenceAlias.normalizeKey(entity.referenceKey()))) {
                matches.add(index);
            }
        }
        if (matches.size() == 1) return;
        if (!matches.isEmpty()) return;
        String summary = outcomeSummary(patch, reference);
        entities.add(new EntityPatch(EntityOperation.CREATE, null, "CHARACTER",
                reference.canonicalName(), "本轮已确认", summary, null, null, null, null,
                null, new LifecycleValue(LifecycleState.ACTIVE, "本轮形成持续物品保管关系"),
                null, null, key));
    }

    private void materializeSuccessfulItemTransition(
            StoryProjection before, StoryActionContract.ItemTransition transition,
            StoryPatch patch, List<EntityPatch> entities) {
        EntityState current = before == null ? null : before.entities().get(transition.itemId());
        int index = -1;
        for (int candidate = 0; candidate < entities.size(); candidate++) {
            EntityPatch entity = entities.get(candidate);
            if (entity != null && transition.itemId().equals(entity.entityId())) {
                index = candidate;
                break;
            }
        }
        EntityPatch planned = index < 0 ? null : entities.get(index);
        QuantityValue quantity = successfulTransferQuantity(current, transition, planned);
        boolean playerHeld = transition.target().holderType() == HolderType.PLAYER;
        EntityPatch compiled = new EntityPatch(EntityOperation.UPDATE, transition.itemId(), "ITEM",
                planned == null ? null : planned.name(),
                planned == null ? "本轮状态已更新" : planned.status(),
                planned == null ? patch.outcome().summary() : planned.summary(),
                planned == null ? null : planned.attributes(), quantity, playerHeld, transition.target(),
                planned == null ? null : planned.abilityState(),
                planned == null ? null : planned.lifecycle(),
                planned == null ? null : planned.relationship(),
                planned == null ? null : planned.nameChange(), "");
        if (index < 0) entities.add(compiled);
        else entities.set(index, compiled);
    }

    /**
     * 整组转交给其他保管者时数量不应归零；只有目标为 NONE 的消耗/销毁才扣除整组数量。
     * 部分处置已经由行动契约归一为“原保管者持有剩余部分”，此处保留结果模型给出的剩余数量。
     */
    private QuantityValue successfulTransferQuantity(
            EntityState current, StoryActionContract.ItemTransition transition, EntityPatch planned) {
        if (current == null || current.quantity() == null || !current.quantity().known()
                || current.quantity().value() == null || transition.quantity() == null) {
            return planned == null ? current == null ? null : current.quantity() : planned.quantity();
        }
        int currentValue = current.quantity().value();
        if (transition.quantity() >= currentValue) {
            int value = transition.target().holderType() == HolderType.NONE ? 0 : currentValue;
            return new QuantityValue(true, value);
        }
        return planned == null ? new QuantityValue(true, currentValue - transition.quantity())
                : planned.quantity();
    }

    /**
     * 把“尝试取得一个新物品”的裁定结果编译为稳定物品事实。
     *
     * <p>行动契约只声明目标物品与成功后的保管者，真正成功、部分成功或失败仍由结果模型决定。成功时若
     * 结果计划漏掉机械性的 CREATE，本方法补出最小记录；失败时移除同一回合引用下的误创建；部分成功
     * 只有模型明确返回物品时才保留。这里不读取玩家中文，也不根据物品名称猜测业务。</p>
     */
    private void compileNewItemResults(StoryActionContract contract, StoryPatch patch,
                                       List<EntityPatch> entities) {
        if (!contract.itemActionDetected() || patch.outcome() == null) return;
        contract.references().stream()
                .filter(reference -> reference != null
                        && reference.resolution() == ReferenceResolution.NEW
                        && reference.persistence() != ReferencePersistence.TRANSIENT
                        && "ITEM".equalsIgnoreCase(reference.entityType())
                        && reference.expectedCustody() != null)
                .forEach(reference -> compileNewItemResult(reference, patch, entities));
    }

    private void compileNewItemResult(EntityReference reference, StoryPatch patch,
                                      List<EntityPatch> entities) {
        String referenceKey = StoryReferenceAlias.normalizeKey(reference.referenceKey());
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < entities.size(); index++) {
            EntityPatch entity = entities.get(index);
            if (entity == null || !"ITEM".equalsIgnoreCase(entity.entityType())) continue;
            String entityKey = StoryReferenceAlias.normalizeKey(entity.referenceKey());
            if (referenceKey != null && referenceKey.equals(entityKey)
                    || reference.canonicalName().equals(entity.name())) {
                matches.add(index);
            }
        }
        OutcomeResolution resolution = patch.outcome().resolution();
        if (resolution == OutcomeResolution.FAILED) {
            for (int index = matches.size() - 1; index >= 0; index--) {
                EntityPatch entity = entities.get(matches.get(index));
                if (entity.operation() == EntityOperation.CREATE) entities.remove(matches.get(index).intValue());
            }
            return;
        }
        if (matches.isEmpty()) {
            if (resolution != OutcomeResolution.SUCCEEDED) return;
            boolean playerHeld = reference.expectedCustody().holderType() == HolderType.PLAYER;
            entities.add(new EntityPatch(EntityOperation.CREATE, null, "ITEM", reference.canonicalName(),
                    playerHeld ? "已收入行囊" : "已完成交付", outcomeSummary(patch, reference), null,
                    new QuantityValue(false, null), playerHeld, reference.expectedCustody(), null,
                    null, null, null, referenceKey));
            return;
        }
        if (matches.size() != 1) return;
        int index = matches.getFirst();
        EntityPatch current = entities.get(index);
        if (current.operation() != EntityOperation.CREATE) return;
        boolean playerHeld = reference.expectedCustody().holderType() == HolderType.PLAYER;
        entities.set(index, new EntityPatch(EntityOperation.CREATE, null, "ITEM",
                textOr(current.name(), reference.canonicalName()), current.status(), current.summary(),
                current.attributes(), current.quantity(), playerHeld, reference.expectedCustody(), null,
                current.lifecycle(), current.relationship(), current.nameChange(), referenceKey));
    }

    /**
     * 来源中已确立但尚无稳定 ID 的物品发生持久变化时，必须以 CREATE 晋升。
     *
     * <p>模型偶尔会为此类对象臆造一个可读 ID 并返回 UPDATE。行动契约已经证明该对象来自可信来源且
     * 当前尚无 ID，因此这里只把“类型、标准名称都唯一匹配”的补丁机械改成 CREATE；不匹配、重名或
     * 未声明物品变化时一律不处理，继续交给严格校验拒绝。</p>
     */
    private void compileEstablishedItemPromotions(StoryActionContract contract, List<EntityPatch> entities) {
        if (!contract.itemActionDetected()) return;
        contract.references().stream()
                .filter(reference -> reference != null
                        && reference.resolution() == ReferenceResolution.ESTABLISHED
                        && reference.persistence() != ReferencePersistence.TRANSIENT
                        && "ITEM".equalsIgnoreCase(reference.entityType()))
                .forEach(reference -> promoteUniqueMatchingItem(reference, entities));
    }

    private void promoteUniqueMatchingItem(EntityReference reference, List<EntityPatch> entities) {
        String referenceKey = StoryReferenceAlias.normalizeKey(reference.referenceKey());
        if (referenceKey == null) return;
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < entities.size(); index++) {
            EntityPatch entity = entities.get(index);
            if (entity != null && "ITEM".equalsIgnoreCase(entity.entityType())
                    && reference.canonicalName().equals(entity.name())) {
                matches.add(index);
            }
        }
        if (matches.size() != 1) return;
        int index = matches.getFirst();
        EntityPatch current = entities.get(index);
        if (current.operation() == EntityOperation.CREATE && current.entityId() == null
                && referenceKey.equals(StoryReferenceAlias.normalizeKey(current.referenceKey()))) {
            return;
        }
        entities.set(index, new EntityPatch(EntityOperation.CREATE, null, "ITEM",
                reference.canonicalName(), current.status(), current.summary(), current.attributes(),
                current.quantity(), current.inPossession(), current.custody(), null, null, null,
                current.nameChange(), referenceKey));
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
