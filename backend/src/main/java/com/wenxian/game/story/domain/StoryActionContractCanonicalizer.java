package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.ItemTransition;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryProjection.EntityState;

import java.util.List;

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
        List<ItemTransition> transitions = contract.itemTransitions().stream()
                .map(value -> canonicalize(projection, value))
                .toList();
        if (transitions.equals(contract.itemTransitions())) return contract;
        return new StoryActionContract(contract.intentSummary(), contract.actionMode(), contract.itemActionDetected(),
                contract.references(), transitions, contract.characterPreconditions(),
                contract.agencyBoundary(), contract.stopBoundary(), contract.conflict());
    }

    private ItemTransition canonicalize(StoryProjection projection, ItemTransition transition) {
        if (transition == null || transition.quantity() == null) return transition;
        EntityState item = projection.entities().get(transition.itemId());
        if (item == null || !"ITEM".equalsIgnoreCase(item.entityType())) return transition;
        QuantityValue current = item.quantity();
        if (current == null || !current.known() || current.value() == null
                || transition.quantity() >= current.value()) {
            return transition;
        }
        return new ItemTransition(transition.itemId(), transition.expectedFrom(),
                transition.expectedFrom(), transition.quantity());
    }
}
