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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoryOutcomeConsistencyValidatorTest {
    private final StoryOutcomeConsistencyValidator validator = new StoryOutcomeConsistencyValidator();

    @Test
    void rejectsSuccessfulLearningWithoutADurableAbilityPatch() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(learningContract(), patch(OutcomeResolution.SUCCEEDED, List.of())));
    }

    @Test
    void acceptsSuccessfulLearningWhenAbilityBecomesMastered() {
        assertDoesNotThrow(() -> validator.validate(learningContract(),
                patch(OutcomeResolution.SUCCEEDED, List.of(ability(AbilityState.MASTERED)))));
    }

    @Test
    void acceptsPartialLearningWhenAbilityRemainsLearning() {
        assertDoesNotThrow(() -> validator.validate(learningContract(),
                patch(OutcomeResolution.PARTIAL, List.of(ability(AbilityState.LEARNING)))));
    }

    @Test
    void rejectsCreatingAbilityWhenLearningFailed() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(learningContract(),
                        patch(OutcomeResolution.FAILED, List.of(ability(AbilityState.MASTERED)))));
    }

    @Test
    void ignoresExistingAbilityUsedAsPrerequisiteRatherThanNewResult() {
        EntityReference source = new EntityReference("施展照影诀", ReferenceResolution.EXISTING,
                "ability-reflection", "ABILITY", "照影诀", "SOURCE", null, List.of(),
                "ability-source", ReferencePersistence.DURABLE, false);
        StoryActionContract contract = contract(source, "施展照影诀");

        assertDoesNotThrow(() -> validator.validate(contract,
                patch(OutcomeResolution.FAILED, List.of())));
    }

    private StoryActionContract learningContract() {
        EntityReference target = new EntityReference("学习御剑术", ReferenceResolution.NEW,
                null, "ABILITY", "御剑术", "RESULT", null, List.of(), "ability-result",
                ReferencePersistence.PROMOTE_ON_CHANGE, true);
        return contract(target, "尝试学习御剑术");
    }

    private StoryActionContract contract(EntityReference reference, String intent) {
        return new StoryActionContract(intent, ActionMode.ATTEMPT, false, List.of(reference), List.of(),
                List.of(), null, "本轮尝试结束后停下", new Conflict(false, "", ""));
    }

    private StoryPatch patch(OutcomeResolution outcome, List<EntityPatch> entities) {
        return new StoryPatch("本轮结果", "你完成了本轮尝试。",
                new TimePassage(60, TimeResolution.COMPLETE, null), null,
                entities, List.of(), List.of(), List.of(), new DecisionCheckpoint(false, null),
                List.of(), "本轮尝试已经结束。", new TurnOutcome(outcome, "本轮尝试结果"));
    }

    private EntityPatch ability(AbilityState state) {
        return new EntityPatch(EntityOperation.CREATE, null, "ABILITY", "御剑术",
                "学习结果", "本轮学习形成的能力状态", null, null, null, null,
                state, null, null, "ability-result");
    }
}
