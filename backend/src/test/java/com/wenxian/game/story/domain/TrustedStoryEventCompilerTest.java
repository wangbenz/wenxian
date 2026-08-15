package com.wenxian.game.story.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleValue;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
import com.wenxian.game.story.domain.StoryPatch.TimePassage;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedStoryEventCompilerTest {
    private final TrustedStoryEventCompiler compiler = new TrustedStoryEventCompiler();

    @Test
    void removesCrossTypeOptionalFieldsAndKeepsMeaningfulCharacterEvents() {
        EntityPatch character = new EntityPatch(EntityOperation.CREATE, null, "CHARACTER", "沈青禾",
                "仍在宗门", "师姐沈青禾已经进入长期记录", new ObjectMapper().createObjectNode(),
                new StoryPatch.QuantityValue(true, 1), true,
                new CustodyValue(HolderType.PLAYER, null), AbilityState.MASTERED,
                new LifecycleValue(LifecycleState.ACTIVE, "本轮相认"),
                new RelationshipValue("师姐", "本轮确认身份"), null, "r2");
        StoryPatch patch = patch(new TimePassage(0, TimeResolution.COMPLETE, null), List.of(character));

        var compiled = compiler.compile(projection(), patch, null);
        EntityPatch normalized = compiled.normalizedPatch().entities().getFirst();

        assertNull(normalized.quantity());
        assertNull(normalized.inPossession());
        assertNull(normalized.custody());
        assertNull(normalized.abilityState());
        assertEquals(2, compiled.events().stream()
                .filter(event -> event instanceof TrustedStoryEvent.CharacterChanged).count());
    }

    @Test
    void createsSemanticTimeEventWithoutExposingMinuteScaleAsMeaning() {
        SemanticDuration planned = new SemanticDuration(SemanticDuration.Unit.MONTH, 3, "");
        StoryPatch patch = patch(new TimePassage(129_600, TimeResolution.COMPLETE, null), List.of());

        var compiled = compiler.compile(projection(), patch, planned);
        TrustedStoryEvent.TimeElapsed time = compiled.events().stream()
                .filter(TrustedStoryEvent.TimeElapsed.class::isInstance)
                .map(TrustedStoryEvent.TimeElapsed.class::cast).findFirst().orElseThrow();

        assertEquals(planned, time.planned());
        assertEquals(planned, time.actual());
        assertTrue(compiled.events().stream().anyMatch(TrustedStoryEvent.ActionResolved.class::isInstance));
    }

    private StoryPatch patch(TimePassage time, List<EntityPatch> entities) {
        return new StoryPatch("本轮", "你完成本轮行动并停下。", time, null, entities,
                List.of(), List.of(), List.of(), null, List.of(), "本轮已完成。");
    }

    private StoryProjection projection() {
        EntityState item = new EntityState("item-1", "ITEM", "青木药牌", "完好", "由玩家持有",
                null, true, new StoryPatch.QuantityValue(true, 1), true,
                new CustodyValue(HolderType.PLAYER, null), null, null);
        return new StoryProjection(0, new CoreState("洞府", "平静", "炼气五层", "问仙"),
                Map.of(item.id(), item), Map.of(), Map.of(), List.of());
    }
}
