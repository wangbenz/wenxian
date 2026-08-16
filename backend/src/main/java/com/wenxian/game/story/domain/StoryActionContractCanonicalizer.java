package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.ItemTransition;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryProjection.EntityState;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 将模型提取的行动契约归一为当前状态模型能够无歧义表达的结构。
 *
 * <p>本类不读取玩家行动关键词，也不决定剧情结果。对于已经确认数量的堆叠物品，如果本轮只处置
 * 其中一部分，稳定物品实体代表的是剩余部分，因此最终保管者仍是原保管者；处置数量继续保留，
 * 供候选补丁校验实际扣减。该归一化避免模型偶尔把“一颗被消耗”误写成“整瓶不存在”。</p>
 */
public final class StoryActionContractCanonicalizer {

    public StoryActionContract canonicalize(StoryProjection projection, StoryActionContract contract) {
        if (projection == null || contract == null || contract.itemTransitions().isEmpty()) return contract;
        Map<String, StoryActionContract.EntityReference> referencesByKey = contract.references().stream()
                .filter(value -> value != null && value.referenceKey() != null && !value.referenceKey().isBlank())
                .collect(Collectors.toMap(value -> StoryReferenceAlias.normalizeKey(value.referenceKey()),
                        Function.identity(), (left, right) -> {
                            throw new IllegalArgumentException("行动契约存在重复的回合内引用键");
                        }));
        List<ItemTransition> transitions = contract.itemTransitions().stream()
                .map(value -> canonicalize(projection, value, referencesByKey))
                .toList();
        Map<String, StoryPatch.CustodyValue> expectedCustodyByItem = transitions.stream()
                .filter(value -> value != null && value.itemId() != null && value.expectedFrom() != null)
                .collect(Collectors.toMap(ItemTransition::itemId, ItemTransition::expectedFrom,
                        (left, right) -> {
                            if (!left.equals(right)) {
                                throw new IllegalArgumentException("同一物品存在互相冲突的起始保管关系");
                            }
                            return left;
                        }));
        List<StoryActionContract.EntityReference> references = contract.references().stream()
                .map(value -> canonicalizeItemReference(value, expectedCustodyByItem))
                .toList();
        if (transitions.equals(contract.itemTransitions()) && references.equals(contract.references())) return contract;
        return new StoryActionContract(contract.intentSummary(), contract.actionMode(), contract.actionScope(),
                contract.itemActionDetected(),
                references, transitions, contract.characterPreconditions(),
                contract.agencyBoundary(), contract.stopBoundary(), contract.conflict());
    }

    /**
     * 同一物品在 reference 与 itemTransition 中重复出现时，以专门描述变化的 expectedFrom 为唯一前置归属，
     * 避免模型把 reference.expectedCustody 误写成行动完成后的目标保管者。
     */
    private StoryActionContract.EntityReference canonicalizeItemReference(
            StoryActionContract.EntityReference reference,
            Map<String, StoryPatch.CustodyValue> expectedCustodyByItem) {
        if (reference == null || reference.resolution() != StoryActionContract.ReferenceResolution.EXISTING
                || !"ITEM".equalsIgnoreCase(reference.entityType())) return reference;
        StoryPatch.CustodyValue expected = expectedCustodyByItem.get(reference.entityId());
        if (expected == null || expected.equals(reference.expectedCustody())) return reference;
        return new StoryActionContract.EntityReference(reference.mention(), reference.resolution(),
                reference.entityId(), reference.entityType(), reference.canonicalName(), reference.role(), expected,
                reference.evidenceRefs(), reference.referenceKey());
    }

    private ItemTransition canonicalize(StoryProjection projection, ItemTransition transition,
                                        Map<String, StoryActionContract.EntityReference> referencesByKey) {
        if (transition == null) return null;
        ItemTransition linked = linkTargetReference(transition, referencesByKey);
        if (linked.quantity() == null) return linked;
        if (linked.itemId() == null || linked.itemId().isBlank()) {
            throw new IllegalArgumentException(
                    "来源物品尚无稳定ID，不能写入 itemTransition；应通过 ESTABLISHED 物品引用晋升");
        }
        EntityState item = projection.entities().get(linked.itemId());
        if (item == null || !"ITEM".equalsIgnoreCase(item.entityType())) return linked;
        QuantityValue current = item.quantity();
        if (current == null || !current.known() || current.value() == null
                || linked.quantity() >= current.value()) {
            return linked;
        }
        return new ItemTransition(linked.itemId(), linked.expectedFrom(),
                linked.expectedFrom(), linked.quantity(), "");
    }

    /**
     * 将模型输出的引用键转换成明确的候选补丁别名，避免模型猜测尚未晋升人物的正式 ID。
     */
    private ItemTransition linkTargetReference(
            ItemTransition transition,
            Map<String, StoryActionContract.EntityReference> referencesByKey) {
        String key = StoryReferenceAlias.normalizeKey(transition.targetReferenceKey());
        if (key == null) return transition;
        StoryActionContract.EntityReference reference = referencesByKey.get(key);
        if (reference == null) throw new IllegalArgumentException("物品目标引用不存在：" + key);
        if (!"CHARACTER".equalsIgnoreCase(reference.entityType())) {
            throw new IllegalArgumentException("物品目标引用必须指向人物：" + key);
        }
        if (transition.target() == null || transition.target().holderType() != StoryPatch.HolderType.CHARACTER) {
            throw new IllegalArgumentException("人物目标引用与保管者类型不一致：" + key);
        }
        return switch (reference.resolution()) {
            case EXISTING -> new ItemTransition(transition.itemId(), transition.expectedFrom(),
                    new StoryPatch.CustodyValue(StoryPatch.HolderType.CHARACTER, reference.entityId()),
                    transition.quantity(), key);
            case ESTABLISHED, NEW -> new ItemTransition(transition.itemId(), transition.expectedFrom(),
                    new StoryPatch.CustodyValue(StoryPatch.HolderType.CHARACTER,
                            StoryReferenceAlias.holderRef(key)), transition.quantity(), key);
            case UNRESOLVED -> throw new IllegalArgumentException("物品不能交给尚未解析的人物引用：" + key);
        };
    }
}
