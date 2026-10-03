package com.wenxian.game.story.agent.ai;

import com.wenxian.game.story.domain.*;
import com.wenxian.game.story.support.StoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NarrativeEndStateTest {
    @Test
    void consumedItemRemainsAnAcceptedChangeButIsAbsentFromFinalInventory() {
        var consumed = new StoryProjection.EntityState("item-used", "ITEM", "清心丹", "耗尽", "已服用最后一枚",
                null, true, new StoryPatch.QuantityValue(true, 0), true,
                new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null), null, null);
        var after = new StoryProjection(StoryWorldTime.opening(), new StoryProjection.CoreState("石门前", "平稳", "炼气初期", "求道"),
                Map.of(consumed.id(), consumed), Map.of(), Map.of(), List.of(), StoryTestFixtures.creationContract(),
                StoryTestFixtures.playPreference(), null, null);
        var change = new StoryPatch.EntityPatch(StoryPatch.EntityOperation.UPDATE, consumed.id(), "ITEM", null,
                "耗尽", "已服用最后一枚", null, consumed.quantity(), true, consumed.custody(), null, null, null, null, null);
        var plan = new StoryPatch("服药", "你服下最后一枚清心丹。", new StoryPatch.TimePassage(
                new SemanticDuration(SemanticDuration.Unit.SCENE, 1, ""), StoryPatch.TimeResolution.COMPLETE, null),
                null, List.of(change), List.of(), List.of(), List.of(), new StoryPatch.DecisionCheckpoint(false, null),
                List.of(), "", new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.SUCCEEDED, "服药完成"));
        var end = NarrativeEndState.from(after, plan);
        assertThat(end.inventory()).isEmpty();
        assertThat(end.changedEntities()).containsExactly(consumed);
    }
}
