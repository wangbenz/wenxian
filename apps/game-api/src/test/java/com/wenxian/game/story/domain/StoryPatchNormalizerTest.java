package com.wenxian.game.story.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoryPatchNormalizerTest {

    @Test
    void removesMeaninglessDefaultValueWhenModelMarksQuantityUnknown() {
        StoryPatch.EntityPatch item = new StoryPatch.EntityPatch(
                StoryPatch.EntityOperation.UPDATE, "item-moon-stone", "ITEM", null,
                "随身携带", null, null, new StoryPatch.QuantityValue(false, 0),
                true, new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null),
                null, null, null);
        StoryPatch proposed = new StoryPatch("询问旧事", "你问清眼前线索后停下等待回答。",
                new StoryPatch.TimePassage(new SemanticDuration(SemanticDuration.Unit.SCENE, 1, ""),
                        StoryPatch.TimeResolution.COMPLETE, null), null, List.of(item), List.of(), List.of(),
                List.of(), new StoryPatch.DecisionCheckpoint(false, null), List.of(), "");

        StoryPatch normalized = new StoryPatchNormalizer().normalize(proposed);

        assertEquals(new StoryPatch.QuantityValue(false, null), normalized.entities().getFirst().quantity());
    }
}
