package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.Conflict;
import com.wenxian.game.story.domain.StoryActionContract.ItemTransition;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoryActionContractCanonicalizerTest {
    private final StoryActionContractCanonicalizer canonicalizer = new StoryActionContractCanonicalizer();

    @Test
    void keepsRemainingStackWithOriginalHolderWhenOnlyPartIsConsumed() {
        StoryActionContract result = canonicalizer.canonicalize(projection(3), contract(1));

        assertEquals(HolderType.PLAYER, result.itemTransitions().getFirst().target().holderType());
        assertEquals(1, result.itemTransitions().getFirst().quantity());
    }

    @Test
    void preservesTargetWhenWholeStackIsConsumed() {
        StoryActionContract result = canonicalizer.canonicalize(projection(3), contract(3));

        assertEquals(HolderType.NONE, result.itemTransitions().getFirst().target().holderType());
    }

    private StoryActionContract contract(int used) {
        CustodyValue player = new CustodyValue(HolderType.PLAYER, null);
        return new StoryActionContract("服用一颗", true, List.of(),
                List.of(new ItemTransition("item-pill", player,
                        new CustodyValue(HolderType.NONE, null), used)),
                List.of(), "服用后停下", new Conflict(false, "", ""));
    }

    private StoryProjection projection(int quantity) {
        CustodyValue player = new CustodyValue(HolderType.PLAYER, null);
        EntityState item = new EntityState("item-pill", "ITEM", "莹白丹药", "完好", "瓷瓶内丹药",
                null, true, new QuantityValue(true, quantity), true, player, null, null);
        return new StoryProjection(0, new CoreState("石室", "平静", "凡俗", "问仙"),
                Map.of(item.id(), item), Map.of(), Map.of(), List.of());
    }
}
