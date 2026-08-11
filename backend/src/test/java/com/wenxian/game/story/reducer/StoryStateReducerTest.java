package com.wenxian.game.story.reducer;

import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleValue;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
import com.wenxian.game.story.domain.StoryPatch.TimePassage;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.StoryPatch.WorldFactPatch;
import com.wenxian.game.story.domain.StoryPatchValidator;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryProjection.WorldFactState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryStateReducerTest {
    private final StoryStateReducer reducer = new StoryStateReducer(
            new StoryPatchValidator(), prefix -> prefix + "-generated");

    @Test
    void keepsMasteredAbilityWhenNarrativeOnlyUpdatesItsDescription() {
        EntityState ability = entity("ability-reflection", "ABILITY", "照影诀",
                null, null, AbilityState.MASTERED, null);
        StoryProjection before = projection(Map.of(ability.id(), ability), Map.of());
        EntityPatch update = entityPatch(EntityOperation.UPDATE, ability.id(), "ABILITY", null,
                "运转如常", null, null, null, null);

        StoryStateReducer.Applied applied = reducer.reduce(before, patch(List.of(update), List.of()));

        assertEquals(AbilityState.MASTERED,
                applied.projection().entities().get(ability.id()).abilityState());
        assertEquals(60, applied.projection().worldTimeMinutes());
    }

    @Test
    void preservesUnknownQuantityAndAppliesKnownInventoryConsumption() {
        EntityState known = entity("item-pill", "ITEM", "凝气丹",
                new QuantityValue(true, 3), true, null, null);
        EntityState unknown = entity("item-herbs", "ITEM", "未清点灵材",
                new QuantityValue(false, null), true, null, null);
        StoryProjection before = projection(Map.of(known.id(), known, unknown.id(), unknown), Map.of());
        EntityPatch consumeOne = entityPatch(EntityOperation.UPDATE, known.id(), "ITEM", null,
                "剩余两枚", new QuantityValue(true, 2), true, null, null);
        EntityPatch describeUnknown = entityPatch(EntityOperation.UPDATE, unknown.id(), "ITEM", null,
                "仍未清点", null, null, null, null);

        StoryProjection after = reducer.reduce(before,
                patch(List.of(consumeOne, describeUnknown), List.of())).projection();

        assertEquals(2, after.entities().get(known.id()).quantity().value());
        assertFalse(after.entities().get(unknown.id()).quantity().known());
        assertEquals(null, after.entities().get(unknown.id()).quantity().value());
    }

    @Test
    void rejectsSilentRevivalAndAcceptsExplicitAuditedLifecycleChange() {
        EntityState character = entity("character-mentor", "CHARACTER", "旧日师尊",
                null, null, null, LifecycleState.DEAD);
        StoryProjection before = projection(Map.of(character.id(), character), Map.of());
        EntityPatch silentRevival = entityPatch(EntityOperation.UPDATE, character.id(), "CHARACTER", null,
                "重新现身", null, null, null, new LifecycleValue(LifecycleState.ACTIVE, null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> reducer.reduce(before, patch(List.of(silentRevival), List.of())));
        assertEquals("已死亡人物改变生命周期必须记录明确依据", error.getMessage());

        EntityPatch auditedRevival = entityPatch(EntityOperation.UPDATE, character.id(), "CHARACTER", null,
                "以残魂重塑形体", null, null, null,
                new LifecycleValue(LifecycleState.ACTIVE, "玩家确认此人此前留下独立残魂"));
        StoryProjection after = reducer.reduce(before, patch(List.of(auditedRevival), List.of())).projection();
        assertEquals(LifecycleState.ACTIVE, after.entities().get(character.id()).lifecycle());
    }

    @Test
    void rejectsSilentRelationshipRewriteAndAcceptsAuditedChange() {
        EntityState character = new EntityState("character-companion", "CHARACTER", "阿禾", "同行",
                "曾共同脱险", null, true, null, null, null, null, LifecycleState.ACTIVE,
                new RelationshipValue("同行者", "共同脱险后确认"));
        StoryProjection before = projection(Map.of(character.id(), character), Map.of());
        EntityPatch silent = new EntityPatch(EntityOperation.UPDATE, character.id(), "CHARACTER", null,
                "反目", null, null, null, null, null, null, null,
                new RelationshipValue("仇敌", null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> reducer.reduce(before, patch(List.of(silent), List.of())));
        assertEquals("人物关系发生变化时必须记录明确原因", error.getMessage());

        EntityPatch audited = new EntityPatch(EntityOperation.UPDATE, character.id(), "CHARACTER", null,
                "因背叛而反目", null, null, null, null, null, null, null,
                new RelationshipValue("仇敌", "本轮亲眼确认对方出卖行踪"));
        StoryProjection after = reducer.reduce(before, patch(List.of(audited), List.of())).projection();
        assertEquals("仇敌", after.entities().get(character.id()).relationship().label());
    }

    @Test
    void revisesWorldFactWithoutChangingItsStableIdentity() {
        WorldFactState fact = new WorldFactState("world-fact-ascension", "世人认为天门已绝",
                1, true, WorldFactOperation.EXPAND, "世界起点");
        StoryProjection before = projection(Map.of(), Map.of(fact.id(), fact));
        WorldFactPatch revision = new WorldFactPatch(WorldFactOperation.REVISE, fact.id(),
                "天门仍在，只是被大阵遮蔽", "玩家明确修订世界设定");

        StoryStateReducer.Applied applied = reducer.reduce(before, patch(List.of(), List.of(revision)));
        WorldFactState after = applied.projection().worldFacts().get(fact.id());

        assertEquals(fact.id(), after.id());
        assertEquals(2, after.revision());
        assertEquals(WorldFactOperation.REVISE, after.lastOperation());
        assertEquals("玩家明确修订世界设定", after.lastReason());
    }

    @Test
    void assignsServerIdentityToCreatedEntityAndReturnsAcceptedPatch() {
        EntityPatch create = entityPatch(EntityOperation.CREATE, null, "ITEM", "青纹玉简",
                "随身携带", new QuantityValue(true, 1), true, null, null);

        StoryStateReducer.Applied applied = reducer.reduce(projection(Map.of(), Map.of()),
                patch(List.of(create), List.of()));

        assertTrue(applied.projection().entities().containsKey("item-generated"));
        assertEquals("item-generated", applied.acceptedPatch().entities().getFirst().entityId());
    }

    @Test
    void recordsCharacterCustodyWithoutPretendingGiftedItemWasDestroyed() {
        EntityState item = entity("item-sword-tip", "ITEM", "断剑尖",
                new QuantityValue(true, 1), true, null, null);
        EntityPatch gifted = new EntityPatch(EntityOperation.UPDATE, item.id(), "ITEM", null,
                "已交给老妇保管", null, null, new QuantityValue(true, 1), false,
                new CustodyValue(HolderType.CHARACTER, "character-old-woman"), null, null);

        StoryProjection after = reducer.reduce(projection(Map.of(item.id(), item), Map.of()),
                patch(List.of(gifted), List.of())).projection();

        assertFalse(after.entities().get(item.id()).inPossession());
        assertEquals(HolderType.CHARACTER, after.entities().get(item.id()).custody().holderType());
        assertEquals("character-old-woman", after.entities().get(item.id()).custody().holderRef());
        assertEquals(1, after.entities().get(item.id()).quantity().value());
    }

    private StoryProjection projection(Map<String, EntityState> entities,
                                       Map<String, WorldFactState> worldFacts) {
        return new StoryProjection(0, new CoreState("青石镇", "安稳", "凡俗", "寻仙问道"),
                entities, worldFacts, Map.of(), List.of());
    }

    private EntityState entity(String id, String type, String name, QuantityValue quantity,
                               Boolean inPossession, AbilityState abilityState, LifecycleState lifecycle) {
        return new EntityState(id, type, name, null, null, null, true,
                quantity, inPossession, abilityState, lifecycle);
    }

    private EntityPatch entityPatch(EntityOperation operation, String id, String type, String name,
                                    String status, QuantityValue quantity, Boolean inPossession,
                                    AbilityState abilityState, LifecycleValue lifecycle) {
        String summary = operation == EntityOperation.CREATE ? name + "的当前事实。" : null;
        return new EntityPatch(operation, id, type, name, status, summary, null,
                quantity, inPossession, abilityState, lifecycle);
    }

    private StoryPatch patch(List<EntityPatch> entities, List<WorldFactPatch> worldFacts) {
        return new StoryPatch("命卷新页", "你完成了本轮行动，并停在下一个需要选择的位置。",
                new TimePassage(60, TimeResolution.COMPLETE, null), null,
                entities, worldFacts, List.of(), List.of(),
                new StoryPatch.DecisionCheckpoint(false, null), List.of(), "");
    }
}
