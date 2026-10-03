package com.wenxian.game.story.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CultivationTransitionPolicyTest {
    private final CultivationTransitionPolicy policy = new CultivationTransitionPolicy();
    private final CultivationSystem system = CultivationSystemValidatorTest.validSystem();

    @Test
    void resolvesStableIdDisplayNameAndAliasToTheSameStage() {
        assertEquals("stage-02", policy.resolveStageId(system, "stage-02").orElseThrow());
        assertEquals("stage-02", policy.resolveStageId(system, "炼气").orElseThrow());
        assertEquals("stage-02", policy.resolveStageId(system, " 练气 ").orElseThrow());
    }

    @Test
    void allowsKeepingCurrentStageAndEnteringDirectSuccessor() {
        CultivationTransitionPolicy.Decision unchanged = policy.evaluate(system, "stage-02", "练气");
        assertEquals(CultivationTransitionPolicy.Status.UNCHANGED, unchanged.status());
        assertEquals("stage-02", unchanged.targetStageId());

        CultivationTransitionPolicy.Decision allowed = policy.evaluate(system, "stage-02", "筑基");
        assertEquals(CultivationTransitionPolicy.Status.ALLOWED, allowed.status());
        assertEquals("stage-03", allowed.targetStageId());
    }

    @Test
    void rejectsUnknownTargetAndSkippingMultipleStagesWithoutChangingCurrentStage() {
        CultivationTransitionPolicy.Decision unknown = policy.evaluate(system, "stage-02", "虚空真仙");
        assertEquals(CultivationTransitionPolicy.Status.UNKNOWN_TARGET, unknown.status());
        assertEquals("stage-02", unknown.effectiveStageId());

        CultivationTransitionPolicy.Decision skipped = policy.evaluate(system, "stage-02", "元婴");
        assertEquals(CultivationTransitionPolicy.Status.NOT_ALLOWED, skipped.status());
        assertEquals("stage-05", skipped.targetStageId());
        assertEquals("stage-02", skipped.effectiveStageId());
    }

    @Test
    void reportsInvalidCurrentStageAsAStateProblemInsteadOfGuessing() {
        CultivationTransitionPolicy.Decision invalid = policy.evaluate(system, "legacy-realm", "筑基");

        assertEquals(CultivationTransitionPolicy.Status.INVALID_CURRENT_STAGE, invalid.status());
        assertEquals("legacy-realm", invalid.effectiveStageId());
    }
}
