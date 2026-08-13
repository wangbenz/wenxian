package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.ItemTransition;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryProjection.EntityState;

import java.util.Objects;

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
        for (ItemTransition transition : contract.itemTransitions()) validateTransition(before, transition, patch);
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

    private void validateNewItem(StoryActionContract.EntityReference reference, StoryPatch patch) {
        var matches = patch.entities().stream()
                .filter(value -> value != null && value.operation() == StoryPatch.EntityOperation.CREATE)
                .filter(value -> "ITEM".equalsIgnoreCase(value.entityType()))
                .filter(value -> reference.canonicalName().equals(value.name()))
                .toList();
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

    private void validateTransition(StoryProjection before, ItemTransition transition, StoryPatch patch) {
        EntityPatch entityPatch = patch.entities().stream()
                .filter(value -> value != null && transition.itemId().equals(value.entityId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "本轮物品行动没有写入对应状态变化：" + transition.itemId()));
        CustodyValue actual = patchCustody(entityPatch);
        CustodyValue expected = normalized(transition.target());
        if (actual == null || expected == null || !same(actual, expected)) {
            throw new IllegalArgumentException("物品状态补丁没有落实行动契约中的目标保管者："
                    + transition.itemId());
        }
        validatePartialQuantity(before, transition, entityPatch);
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
        String ref = value.holderRef() == null || value.holderRef().isBlank() ? null : value.holderRef().strip();
        return new CustodyValue(value.holderType(), ref);
    }

    private boolean same(CustodyValue left, CustodyValue right) {
        return left.holderType() == right.holderType() && Objects.equals(left.holderRef(), right.holderRef());
    }
}
