package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.ItemTransition;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryProjection.EntityState;

import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 检查候选 StoryPatch 是否真正实现了生成前已确认的行动事实契约。
 *
 * <p>该校验只比较稳定 ID 与结构化保管关系，不从正文关键词反推状态。缺少对应补丁时整轮进入一次
 * 语义修复，修复后仍不一致则拒绝提交。</p>
 */
public final class StoryActionContractPatchValidator {

    public void validate(StoryActionContract contract, StoryPatch patch) {
        validate(null, contract, patch);
    }

    /**
     * 同时依据提交前投影检查数量型物品的实际扣减，避免“保管者正确但数量没有变化”的空操作通过。
     */
    public void validate(StoryProjection before, StoryActionContract contract, StoryPatch patch) {
        if (contract == null || patch == null) throw new IllegalArgumentException("行动契约校验缺少候选提案");
        Map<String, StoryActionContract.EntityReference> referencesByKey = contract.references().stream()
                .filter(value -> value != null && value.referenceKey() != null && !value.referenceKey().isBlank())
                .collect(Collectors.toMap(value -> StoryReferenceAlias.normalizeKey(value.referenceKey()),
                        Function.identity(), (left, right) -> {
                            throw new IllegalArgumentException("行动契约存在重复的回合内引用键");
                        }));
        validateNoUnplannedExistingItemMutation(before, contract, patch);
        for (ItemTransition transition : contract.itemTransitions()) {
            validateTransition(before, referencesByKey, transition, patch);
        }
        contract.references().stream()
                .filter(value -> value != null && value.resolution() == ReferenceResolution.NEW)
                .filter(value -> "ITEM".equalsIgnoreCase(value.entityType()) && value.expectedCustody() != null)
                .forEach(value -> validateNewItem(value, patch));
        if (contract.itemActionDetected()) {
            contract.references().stream()
                    .filter(value -> value != null && value.resolution() == ReferenceResolution.ESTABLISHED)
                    .filter(value -> "ITEM".equalsIgnoreCase(value.entityType()))
                    .forEach(value -> validateEstablishedItemMutation(value, patch));
        }
    }

    /**
     * 既有物品的数量或保管者不能绕过行动契约直接改变。
     *
     * <p>这条边界只比较结构化前后状态，不理解“赠送”“出售”等中文。临时拿给别人观看但尚未确认交付时，
     * 可以只写在本轮叙事中；一旦要形成跨回合的数量或保管变化，行动解析必须先给出 itemTransition，
     * 否则结果计划进入一次语义整理而不能直接落库。</p>
     */
    private void validateNoUnplannedExistingItemMutation(
            StoryProjection before, StoryActionContract contract, StoryPatch patch) {
        // 空意图只存在于确定性测试替身和不支持行动解析的兼容 Agent，不能把它误当成完整生产契约。
        if (before == null || contract.intentSummary() == null || contract.intentSummary().isBlank()) return;
        Set<String> declared = contract.itemTransitions().stream()
                .filter(Objects::nonNull)
                .map(ItemTransition::itemId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        for (EntityPatch change : patch.entities()) {
            if (change == null || change.entityId() == null || change.entityId().isBlank()) continue;
            EntityState current = before.entities().get(change.entityId());
            if (current == null || !"ITEM".equalsIgnoreCase(current.entityType())) continue;
            boolean custodyChanged = changesCustody(current, change);
            boolean quantityChanged = change.quantity() != null
                    && !Objects.equals(current.quantity(), change.quantity());
            if ((custodyChanged || quantityChanged) && !declared.contains(change.entityId())) {
                throw new IllegalArgumentException("既有物品的数量或保管变化未在行动契约中声明："
                        + current.name());
            }
        }
    }

    private boolean changesCustody(EntityState current, EntityPatch change) {
        if (change.custody() == null && change.inPossession() == null) return false;
        CustodyValue before = normalized(current.custody());
        if (before == null && current.inPossession() != null) {
            before = Boolean.TRUE.equals(current.inPossession())
                    ? new CustodyValue(HolderType.PLAYER, null)
                    : new CustodyValue(HolderType.UNKNOWN, null);
        }
        CustodyValue after = patchCustody(change);
        return !Objects.equals(before, after);
    }

    private void validateNewItem(StoryActionContract.EntityReference reference, StoryPatch patch) {
        var matches = patch.entities().stream()
                .filter(value -> value != null && value.operation() == StoryPatch.EntityOperation.CREATE)
                .filter(value -> "ITEM".equalsIgnoreCase(value.entityType()))
                .filter(value -> reference.canonicalName().equals(value.name()))
                .toList();
        StoryPatch.OutcomeResolution resolution = patch.outcome() == null
                ? StoryPatch.OutcomeResolution.SUCCEEDED : patch.outcome().resolution();
        if (resolution == StoryPatch.OutcomeResolution.FAILED) {
            if (!matches.isEmpty()) {
                throw new IllegalArgumentException("本轮取得物品失败，但结果计划仍创建了物品："
                        + reference.canonicalName());
            }
            return;
        }
        if (resolution == StoryPatch.OutcomeResolution.PARTIAL && matches.isEmpty()) return;
        if (matches.size() != 1) {
            throw new IllegalArgumentException("本轮新增物品没有形成唯一状态记录：" + reference.canonicalName());
        }
        CustodyValue actual = patchCustody(matches.getFirst());
        CustodyValue expected = normalized(reference.expectedCustody());
        if (actual == null || expected == null || !same(actual, expected)) {
            throw new IllegalArgumentException("新增物品没有写入行动契约中的保管者：" + reference.canonicalName());
        }
    }

    /**
     * 根源或上一回合中的物品第一次发生数量、持有者或销毁变化时，必须晋升为稳定实体。
     * 否则初始文本会在后续回合反复被当成“仍然拥有”，造成丹药无限服用等状态回滚。
     */
    private void validateEstablishedItemMutation(StoryActionContract.EntityReference reference, StoryPatch patch) {
        var matches = patch.entities().stream()
                .filter(value -> value != null && value.operation() == StoryPatch.EntityOperation.CREATE)
                .filter(value -> "ITEM".equalsIgnoreCase(value.entityType()))
                .filter(value -> reference.canonicalName().equals(value.name()))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("来源物品发生变化后没有晋升为唯一状态记录："
                    + reference.canonicalName());
        }
        if (patchCustody(matches.getFirst()) == null) {
            throw new IllegalArgumentException("来源物品晋升后没有记录最终保管状态："
                    + reference.canonicalName());
        }
    }

    private void validateTransition(StoryProjection before,
                                    Map<String, StoryActionContract.EntityReference> referencesByKey,
                                    ItemTransition transition, StoryPatch patch) {
        EntityPatch entityPatch = patch.entities().stream()
                .filter(value -> value != null && transition.itemId().equals(value.entityId()))
                .findFirst()
                .orElse(null);
        StoryPatch.OutcomeResolution resolution = patch.outcome() == null
                ? StoryPatch.OutcomeResolution.SUCCEEDED : patch.outcome().resolution();
        if (resolution == StoryPatch.OutcomeResolution.FAILED) {
            validateUnchangedTransition(before, transition, entityPatch);
            return;
        }
        if (resolution == StoryPatch.OutcomeResolution.PARTIAL && entityPatch == null) return;
        if (entityPatch == null) {
            throw new IllegalArgumentException("本轮物品行动没有写入对应状态变化：" + transition.itemId());
        }
        CustodyValue actual = effectiveCustody(before, transition, entityPatch);
        CustodyValue expected = normalized(transition.target());
        if (resolution == StoryPatch.OutcomeResolution.PARTIAL
                && sameNullable(actual, normalized(transition.expectedFrom()))) {
            validateUnchangedTransition(before, transition, entityPatch);
            return;
        }
        if (actual == null || expected == null || !same(actual, expected)) {
            throw new IllegalArgumentException("物品状态补丁没有落实行动契约中的目标保管者："
                    + transition.itemId());
        }
        validateReferencedHolder(referencesByKey, transition, patch);
        validatePartialQuantity(before, transition, entityPatch);
    }

    /** 失败或尚未落实物品转移的部分成功，必须保持行动开始前的归属与数量。 */
    private void validateUnchangedTransition(StoryProjection before, ItemTransition transition,
                                             EntityPatch entityPatch) {
        if (entityPatch == null) return;
        if (entityPatch.operation() != StoryPatch.EntityOperation.UPDATE) {
            throw new IllegalArgumentException("未完成的物品行动不能隐藏、恢复或重建原物品："
                    + transition.itemId());
        }
        CustodyValue actual = effectiveCustody(before, transition, entityPatch);
        CustodyValue original = normalized(transition.expectedFrom());
        if (actual != null && original != null && !same(actual, original)) {
            throw new IllegalArgumentException("未完成的物品行动改变了原保管者：" + transition.itemId());
        }
        if (before == null || entityPatch.quantity() == null) return;
        EntityState current = before.entities().get(transition.itemId());
        if (current == null || current.quantity() == null || !current.quantity().known()
                || current.quantity().value() == null) return;
        QuantityValue quantity = entityPatch.quantity();
        if (!quantity.known() || quantity.value() == null
                || !Objects.equals(current.quantity().value(), quantity.value())) {
            throw new IllegalArgumentException("未完成的物品行动改变了原数量：" + transition.itemId());
        }
    }

    private CustodyValue effectiveCustody(StoryProjection before, ItemTransition transition,
                                          EntityPatch entityPatch) {
        CustodyValue actual = patchCustody(entityPatch);
        if (actual != null || before == null) return actual;
        EntityState current = before.entities().get(transition.itemId());
        if (current == null) return null;
        CustodyValue custody = normalized(current.custody());
        if (custody != null) return custody;
        if (current.inPossession() == null) return null;
        return Boolean.TRUE.equals(current.inPossession())
                ? new CustodyValue(HolderType.PLAYER, null)
                : new CustodyValue(HolderType.UNKNOWN, null);
    }

    private boolean sameNullable(CustodyValue left, CustodyValue right) {
        return left != null && right != null && same(left, right);
    }

    private void validateReferencedHolder(
            Map<String, StoryActionContract.EntityReference> referencesByKey,
            ItemTransition transition, StoryPatch patch) {
        String key = StoryReferenceAlias.normalizeKey(transition.targetReferenceKey());
        if (key == null || transition.target() == null
                || !StoryReferenceAlias.isAlias(transition.target().holderRef())) {
            return;
        }
        StoryActionContract.EntityReference reference = referencesByKey.get(key);
        if (reference == null) throw new IllegalArgumentException("物品人物目标缺少行动引用：" + key);
        var matches = patch.entities().stream()
                .filter(value -> value != null && value.operation() == StoryPatch.EntityOperation.CREATE)
                .filter(value -> "CHARACTER".equalsIgnoreCase(value.entityType()))
                .filter(value -> key.equals(StoryReferenceAlias.normalizeKey(value.referenceKey())))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException("来源人物接收物品时没有晋升为唯一人物记录："
                    + reference.canonicalName());
        }
        if (!reference.canonicalName().equals(matches.getFirst().name())) {
            throw new IllegalArgumentException("来源人物晋升记录与行动引用名称不一致："
                    + reference.canonicalName());
        }
    }

    private void validatePartialQuantity(StoryProjection before, ItemTransition transition, EntityPatch patch) {
        if (before == null || transition.quantity() == null) return;
        EntityState item = before.entities().get(transition.itemId());
        if (item == null || item.quantity() == null || !item.quantity().known()
                || item.quantity().value() == null || transition.quantity() >= item.quantity().value()) {
            return;
        }
        QuantityValue actual = patch.quantity();
        int expected = item.quantity().value() - transition.quantity();
        if (actual == null || !actual.known() || actual.value() == null || actual.value() != expected) {
            throw new IllegalArgumentException("物品状态补丁没有落实本轮处置数量：" + transition.itemId());
        }
    }

    private CustodyValue patchCustody(EntityPatch patch) {
        CustodyValue explicit = normalized(patch.custody());
        if (explicit != null) return explicit;
        if (patch.inPossession() == null) return null;
        return Boolean.TRUE.equals(patch.inPossession())
                ? new CustodyValue(HolderType.PLAYER, null)
                : new CustodyValue(HolderType.UNKNOWN, null);
    }

    private CustodyValue normalized(CustodyValue value) {
        if (value == null || value.holderType() == null) return null;
        String ref = value.holderType() == HolderType.CHARACTER || value.holderType() == HolderType.LOCATION
                ? value.holderRef() == null || value.holderRef().isBlank() ? null : value.holderRef().strip()
                : null;
        return new CustodyValue(value.holderType(), ref);
    }

    private boolean same(CustodyValue left, CustodyValue right) {
        return left.holderType() == right.holderType() && Objects.equals(left.holderRef(), right.holderRef());
    }
}
