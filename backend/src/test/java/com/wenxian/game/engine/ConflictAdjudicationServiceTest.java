package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictAdjudicationServiceTest {
    private final ConflictAdjudicationService service = new ConflictAdjudicationService(null);

    @Test
    void yuanYingCannotBeDefeatedHeadOnByKnownFoundationOpponent() {
        GameState state = state("元婴", "NORMAL", "STABLE");
        state.characters.add(character("guard", "筑基"));

        ConflictVerdict verdict = service.resolve(combat("guard", "筑基", "CHARACTER_STATE", "HIGH", false), state);

        assertEquals("DOMINANT_SUCCESS", verdict.verdict());
        assertEquals("OVERWHELMING_ADVANTAGE", verdict.powerRelation());
        assertEquals("STABLE", verdict.playerConditionAfter());
        assertFalse(verdict.allowsDeath());
    }

    @Test
    void foundationAttackingYuanYingFailsAndHardModeMakesTheInjuryPersistent() {
        GameState state = state("筑基", "HARD", "STABLE");
        state.characters.add(character("elder", "元婴"));

        ConflictVerdict verdict = service.resolve(combat("elder", "元婴", "CHARACTER_STATE", "HIGH", false), state);

        assertEquals("OVERMATCHED", verdict.verdict());
        assertEquals("SEVERE", verdict.playerConditionAfter());
        assertFalse(verdict.allowsDeath());
    }

    @Test
    void onlyAnAlreadyDyingPlayerWhoAcceptsLethalOvermatchCanDie() {
        GameState state = state("筑基", "HARD", "DYING");
        state.characters.add(character("elder", "元婴"));

        ConflictVerdict verdict = service.resolve(combat("elder", "元婴", "CHARACTER_STATE", "LETHAL", true), state);

        assertEquals("DEAD", verdict.verdict());
        assertTrue(verdict.allowsDeath());
    }

    @Test
    void unverifiedPlayerClaimDoesNotBecomeOpponentRealmFact() {
        GameState state = state("元婴", "NORMAL", "STABLE");

        ConflictVerdict verdict = service.resolve(combat(null, "筑基", "USER_CLAIM", "MEDIUM", false), state);

        assertEquals("UNRESOLVED_CONFLICT", verdict.verdict());
        assertEquals("UNKNOWN", verdict.powerRelation());
    }

    @Test
    void repairsLegacyLowNumericVitalityWithoutTreatingItAsDying() {
        GameState state = state("元婴", "NORMAL", null);
        state.player.vitality = 5;

        assertTrue(service.repairLegacyState(state));
        assertEquals("STABLE", state.player.condition);
        assertEquals(60, state.player.vitality);
    }

    @Test
    void seekingAnAbsentItemIsAnAllowedGoalRatherThanAFalseInventoryClaim() {
        GameState state = state("筑基", "NORMAL", "STABLE");
        ActionIntent intent = intent("INVESTIGATE", "SEEK", List.of(
                claim("ITEM", "qinglong-vine", "青龙藤", "TARGET")
        ));

        assertEquals(null, service.validateFacts(intent, state));
    }

    @Test
    void declaringAnExplorationResultRequestsARewriteWithoutChangingTheWorld() {
        GameState state = state("筑基", "NORMAL", "STABLE");
        ActionIntent intent = intent("INVESTIGATE", "DECLARE_OUTCOME", List.of(
                claim("ITEM", "qinglong-vine", "青龙藤", "DECLARED_RESULT")
        ));

        ActionAdjudication result = service.validateFacts(intent, state);

        assertEquals("CLARIFY", result.actionResolution().result());
        assertEquals("DECLARED_OUTCOME", result.actionResolution().code());
    }

    @Test
    void learningFromAnOwnedManualDoesNotRequireTheTechniqueToBeMasteredAlready() {
        GameState state = state("筑基", "NORMAL", "STABLE");
        state.inventory.add(new GameState.InventoryItem("manual-qingmu", "manual-qingmu", "《青木长生诀》玉简",
                "MANUAL", "凡物", 1, "尚未参悟", true));
        ActionIntent intent = intent("CULTIVATE", "LEARN", List.of(
                claim("ITEM", "manual-qingmu", "《青木长生诀》玉简", "REQUIRED_EXISTING"),
                claim("ABILITY", "ability-qingmu", "青木长生诀", "TARGET")
        ));

        assertEquals(null, service.validateFacts(intent, state));
    }

    @Test
    void directlyUsingAnUnlearnedTechniqueIsStillRejected() {
        GameState state = state("筑基", "NORMAL", "STABLE");
        ActionIntent intent = intent("USE_ABILITY", "EXECUTE", List.of(
                claim("ABILITY", "ability-qingmu", "青木长生诀", "REQUIRED_EXISTING")
        ));

        ActionAdjudication result = service.validateFacts(intent, state);

        assertEquals("REJECTED", result.actionResolution().result());
        assertEquals("MISSING_ABILITY", result.actionResolution().code());
    }

    @Test
    void missingRequiredItemIsReportedBeforeADeclaredSuccess() {
        GameState state = state("凡俗", "NORMAL", "WOUNDED");
        ActionIntent intent = intent("USE_ITEM", "DECLARE_OUTCOME", List.of(
                claim("ITEM", "nine-turn-pill", "九转还魂丹", "REQUIRED_EXISTING"),
                claim("PLAYER", "player", "伤势痊愈", "DECLARED_RESULT")
        ));

        ActionAdjudication result = service.validateFacts(intent, state);

        assertEquals("REJECTED", result.actionResolution().result());
        assertEquals("MISSING_ITEM", result.actionResolution().code());
    }

    @Test
    void anOwnedButExhaustedAbilityIsNotTreatedAsMissing() {
        GameState state = state("筑基", "NORMAL", "STABLE");
        state.abilities.add(new GameState.AbilityRecord("ability-wind", "wind", "风息掌控",
                "TALENT", "入门", 10, "命格", "控风", "引风", 1, 0,
                "DAILY", 0, "疲惫"));
        ActionIntent intent = new ActionIntent("USE_ABILITY", "EXECUTE", false,
                null, null, null, "UNKNOWN", List.of("ability-wind"),
                "MEDIUM", false, List.of());

        ActionAdjudication result = service.validateFacts(intent, state);

        assertEquals("REJECTED", result.actionResolution().result());
        assertEquals("ABILITY_USES_EXHAUSTED", result.actionResolution().code());
    }

    private GameState state(String realm, String difficulty, String condition) {
        GameState state = new GameState();
        state.difficulty = difficulty;
        state.player = new GameState.PlayerState("测试者", "HUMAN", "MALE", 20, 75,
                "散修", "LONGEVITY", "问道", "旧债", realm, "凡胎", 60, 0, 5, 0);
        state.player.condition = condition;
        return state;
    }

    private GameState.CharacterRecord character(String id, String realm) {
        return new GameState.CharacterRecord(id, "对手", "修士", realm, "STABLE", "正面斗法",
                "存活", "当前场景", "敌对", 0);
    }

    private ActionIntent combat(String targetId, String realm, String source, String risk, boolean acceptsDeath) {
        return new ActionIntent("COMBAT", targetId, "对手", realm, source,
                List.of(), risk, acceptsDeath, List.of());
    }

    private ActionIntent intent(String actionType, String mode, List<ActionIntent.ClaimedFact> claims) {
        return new ActionIntent(actionType, mode, false, null, null, null, "UNKNOWN",
                List.of(), "MEDIUM", false, claims);
    }

    private ActionIntent.ClaimedFact claim(String type, String id, String value, String usage) {
        return new ActionIntent.ClaimedFact(type, id, "existence", value,
                "REQUIRED_EXISTING".equals(usage), usage);
    }
}
