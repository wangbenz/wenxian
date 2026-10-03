package com.wenxian.game.story.agent.ai;

import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryWorldTime;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 正文使用的回合结束事实，只能从已归并投影建立，不能由模型反填。
 * 包含完整可见行囊、功法及本轮变化实体；不加载无关历史，也不表示事务已经提交。
 */
public record NarrativeEndState(StoryWorldTime worldTime, StoryProjection.CoreState protagonist,
                                ProtagonistCultivation cultivation, List<EntityState> inventory,
                                List<EntityState> abilities, List<EntityState> changedEntities,
                                AssetProcess.Accepted assetProcess,
                                StoryTurnDecision.ActionUnderstanding directorActionUnderstanding,
                                OrderedTurnResult.Accepted acceptedResult) {
    /** 兼容旧固定样本，未提供有序结果时保持为空。 */
    public NarrativeEndState(StoryWorldTime worldTime, StoryProjection.CoreState protagonist,
                             ProtagonistCultivation cultivation, List<EntityState> inventory,
                             List<EntityState> abilities, List<EntityState> changedEntities,
                             AssetProcess.Accepted process, StoryTurnDecision.ActionUnderstanding understanding) {
        this(worldTime, protagonist, cultivation, inventory, abilities, changedEntities, process, understanding, null);
    }

    /** 与提交、下一轮读取共享服务端已接受结果；不会从正文补造。 */
    public NarrativeEndState withAcceptedResult(OrderedTurnResult.Accepted result) {
        return new NarrativeEndState(worldTime, protagonist, cultivation, inventory, abilities,
                changedEntities, assetProcess, directorActionUnderstanding, result);
    }
    /** 兼容旧固定样本；正式新模型链路额外交付已接受的资产过程。 */
    public NarrativeEndState(StoryWorldTime worldTime, StoryProjection.CoreState protagonist,
                             ProtagonistCultivation cultivation, List<EntityState> inventory,
                             List<EntityState> abilities, List<EntityState> changedEntities) {
        this(worldTime, protagonist, cultivation, inventory, abilities, changedEntities, null, null);
    }

    /** 行动承接是模型解释，不是权限依据；资产过程必须已通过归并并解析为正式 ID。 */
    public NarrativeEndState withProcess(AssetProcess.Accepted process,
                                         StoryTurnDecision.ActionUnderstanding understanding) {
        return new NarrativeEndState(worldTime, protagonist, cultivation, inventory, abilities,
                changedEntities, process, understanding, acceptedResult);
    }
    public NarrativeEndState {
        if (worldTime == null || protagonist == null) throw new IllegalArgumentException("正文缺少回合结束事实");
        inventory = List.copyOf(inventory);
        abilities = List.copyOf(abilities);
        changedEntities = List.copyOf(changedEntities);
    }

    public static NarrativeEndState from(StoryProjection after, StoryPatch accepted) {
        Set<String> changedIds = accepted.entities().stream().map(StoryPatch.EntityPatch::entityId)
                .collect(Collectors.toSet());
        List<EntityState> visible = after.entities().values().stream().filter(EntityState::visible)
                .sorted(Comparator.comparing(EntityState::id)).toList();
        return new NarrativeEndState(after.worldTime(), after.coreState(), after.protagonistCultivation(),
                visible.stream().filter(e -> "ITEM".equals(e.entityType()) && Boolean.TRUE.equals(e.inPossession()))
                        .filter(e -> e.quantity() == null || !e.quantity().known()
                                || e.quantity().value() == null || e.quantity().value() > 0).toList(),
                visible.stream().filter(e -> "ABILITY".equals(e.entityType())).toList(),
                visible.stream().filter(e -> changedIds.contains(e.id())).toList());
    }
}
