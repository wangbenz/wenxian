package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.ActionMode;
import com.wenxian.game.story.domain.StoryActionContract.Conflict;
import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ReferencePersistence;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.DecisionCheckpoint;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.OutcomeResolution;
import com.wenxian.game.story.domain.StoryPatch.TimePassage;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryPatch.TurnOutcome;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryActionOutcomeCompilerTest {
    private final StoryActionOutcomeCompiler compiler = new StoryActionOutcomeCompiler();

    @Test
    void materializesSuccessfulNewAbilityWithoutAskingModelToRepeatMechanicalFields() {
        StoryPatch compiled = compiler.compile(contract(learningTarget()),
                patch(OutcomeResolution.SUCCEEDED, List.of()));

        assertEquals(1, compiled.entities().size());
        assertEquals("御剑术", compiled.entities().getFirst().name());
        assertEquals(AbilityState.MASTERED, compiled.entities().getFirst().abilityState());
        assertEquals("ability-result", compiled.entities().getFirst().referenceKey());
    }

    @Test
    void materializesPartialLearningAsLearningState() {
        StoryPatch compiled = compiler.compile(contract(learningTarget()),
                patch(OutcomeResolution.PARTIAL, List.of()));

        assertEquals(AbilityState.LEARNING, compiled.entities().getFirst().abilityState());
    }

    @Test
    void removesContradictoryAbilityCreationWhenOutcomeFailed() {
        StoryPatch compiled = compiler.compile(contract(learningTarget()),
                patch(OutcomeResolution.FAILED, List.of(ability(AbilityState.MASTERED))));

        assertTrue(compiled.entities().isEmpty());
    }

    @Test
    void dropsLongTermPatchForExplicitlyTransientSceneCharacter() {
        EntityReference transientOwner = new EntityReference("摊主", ReferenceResolution.NEW, null,
                "CHARACTER", "摊主", "SCENE", null, List.of(), "scene-owner",
                ReferencePersistence.TRANSIENT, false);
        EntityPatch character = new EntityPatch(EntityOperation.CREATE, null, "CHARACTER", "摊主",
                "在摊位后", "本轮问路的普通摊主", null, null, null,
                null, null, null, null, "scene-owner");

        StoryPatch compiled = compiler.compile(contract(transientOwner),
                patch(OutcomeResolution.SUCCEEDED, List.of(character)));

        assertTrue(compiled.entities().isEmpty());
    }

    @Test
    void promotesUniqueEstablishedSourceItemInsteadOfKeepingInventedUpdateId() {
        EntityReference sourceItem = new EntityReference("《照泉诀》玉简", ReferenceResolution.ESTABLISHED,
                null, "ITEM", "《照泉诀》玉简", "TARGET",
                new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null),
                List.of("initial:origin"), "source-jade",
                ReferencePersistence.PROMOTE_ON_CHANGE, false);
        StoryActionContract contract = new StoryActionContract("调整来源玉简保管位置",
                ActionMode.ATTEMPT, true, List.of(sourceItem), List.of(), List.of(), null,
                "放好后停下", new Conflict(false, "", ""));
        EntityPatch inventedUpdate = new EntityPatch(EntityOperation.UPDATE,
                "item-zhaocquan-yujian", "ITEM", "《照泉诀》玉简", "完好",
                "放在行囊内层", null, new StoryPatch.QuantityValue(true, 1), true,
                new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null),
                null, null, null, "");

        StoryPatch compiled = compiler.compile(contract,
                patch(OutcomeResolution.SUCCEEDED, List.of(inventedUpdate)));

        EntityPatch promoted = compiled.entities().getFirst();
        assertEquals(EntityOperation.CREATE, promoted.operation());
        assertEquals(null, promoted.entityId());
        assertEquals("source-jade", promoted.referenceKey());
        assertEquals("《照泉诀》玉简", promoted.name());
        assertEquals(StoryPatch.HolderType.PLAYER, promoted.custody().holderType());
    }

    @Test
    void materializesSuccessfulNewItemWithContractCustodyWhenPlanOmitsIt() {
        EntityReference parcel = new EntityReference("领取药包", ReferenceResolution.NEW,
                null, "ITEM", "送药药包", "TARGET",
                new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null),
                List.of(), "delivery-parcel", ReferencePersistence.PROMOTE_ON_CHANGE, true);
        StoryActionContract contract = new StoryActionContract("领取送药药包", ActionMode.ATTEMPT,
                true, List.of(parcel), List.of(), List.of(), null,
                "领到药包后停下", new Conflict(false, "", ""));

        StoryPatch compiled = compiler.compile(contract,
                patch(OutcomeResolution.SUCCEEDED, List.of()));

        assertEquals(1, compiled.entities().size());
        EntityPatch created = compiled.entities().getFirst();
        assertEquals(EntityOperation.CREATE, created.operation());
        assertEquals("送药药包", created.name());
        assertEquals("delivery-parcel", created.referenceKey());
        assertEquals(StoryPatch.HolderType.PLAYER, created.custody().holderType());
        assertTrue(created.inPossession());
    }

    @Test
    void doesNotMaterializeNewItemWhenAttemptFailed() {
        EntityReference parcel = new EntityReference("领取药包", ReferenceResolution.NEW,
                null, "ITEM", "送药药包", "TARGET",
                new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null),
                List.of(), "delivery-parcel", ReferencePersistence.PROMOTE_ON_CHANGE, true);
        StoryActionContract contract = new StoryActionContract("尝试领取送药药包", ActionMode.ATTEMPT,
                true, List.of(parcel), List.of(), List.of(), null,
                "尝试结束后停下", new Conflict(false, "", ""));

        StoryPatch compiled = compiler.compile(contract,
                patch(OutcomeResolution.FAILED, List.of()));

        assertTrue(compiled.entities().isEmpty());
    }

    @Test
    void successfulTransferUsesContractRecipientInsteadOfModelGuessedCharacterId() {
        var player = new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null);
        var recipient = new EntityReference("百草堂掌柜", ReferenceResolution.NEW, null,
                "CHARACTER", "百草堂掌柜", "TARGET", null, List.of(), "merchant",
                ReferencePersistence.PROMOTE_ON_CHANGE, false);
        var transition = new StoryActionContract.ItemTransition("item-stone", player,
                new StoryPatch.CustodyValue(StoryPatch.HolderType.CHARACTER, "contract-ref:merchant"),
                1, "merchant");
        StoryActionContract contract = new StoryActionContract("尝试把灵石交给掌柜", ActionMode.ATTEMPT,
                true, List.of(recipient), List.of(transition), List.of(), null,
                "掌柜回应后停下", new Conflict(false, "", ""));
        EntityState stone = new EntityState("item-stone", "ITEM", "灵石", "完好", "一枚灵石",
                null, true, new StoryPatch.QuantityValue(true, 1), true, player, null, null);
        StoryProjection before = new StoryProjection(3,
                new CoreState("百草堂", "平静", "启灵初期", "问仙"),
                Map.of(stone.id(), stone), Map.of(), Map.of(), List.of());
        EntityPatch guessedWrongHolder = new EntityPatch(EntityOperation.UPDATE, stone.id(), "ITEM",
                "灵石", "已经交付", "掌柜接受预付", null,
                new StoryPatch.QuantityValue(true, 0), false,
                new StoryPatch.CustodyValue(StoryPatch.HolderType.CHARACTER, "character-master"),
                null, null, null, "");

        StoryPatch compiled = compiler.compile(before, contract,
                patch(OutcomeResolution.SUCCEEDED, List.of(guessedWrongHolder)));

        EntityPatch item = compiled.entities().stream()
                .filter(value -> "ITEM".equals(value.entityType())).findFirst().orElseThrow();
        EntityPatch character = compiled.entities().stream()
                .filter(value -> "CHARACTER".equals(value.entityType())).findFirst().orElseThrow();
        assertEquals("contract-ref:merchant", item.custody().holderRef());
        assertEquals(1, item.quantity().value());
        assertEquals("百草堂掌柜", character.name());
        assertEquals("merchant", character.referenceKey());
    }

    private EntityReference learningTarget() {
        return new EntityReference("学习御剑术", ReferenceResolution.NEW, null,
                "ABILITY", "御剑术", "RESULT", null, List.of(), "ability-result",
                ReferencePersistence.PROMOTE_ON_CHANGE, true);
    }

    private StoryActionContract contract(EntityReference reference) {
        return new StoryActionContract("完成本轮行动", ActionMode.ATTEMPT, false, List.of(reference),
                List.of(), List.of(), null, "行动完成后停下", new Conflict(false, "", ""));
    }

    private StoryPatch patch(OutcomeResolution outcome, List<EntityPatch> entities) {
        return new StoryPatch("本轮结果", "你完成了本轮行动。",
                new TimePassage(60, TimeResolution.COMPLETE, null), null,
                entities, List.of(), List.of(), List.of(), new DecisionCheckpoint(false, null),
                List.of(), "本轮行动已经结束。", new TurnOutcome(outcome, "本轮行动结果"));
    }

    private EntityPatch ability(AbilityState state) {
        return new EntityPatch(EntityOperation.CREATE, null, "ABILITY", "御剑术", "已掌握",
                "你掌握了御剑术", null, null, null, null, state, null, null,
                "ability-result");
    }
}
