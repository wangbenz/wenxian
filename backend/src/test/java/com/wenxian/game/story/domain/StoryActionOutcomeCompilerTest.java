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
import org.junit.jupiter.api.Test;

import java.util.List;

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
