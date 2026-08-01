package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthoritativeEntityResolverTest {
    @Test
    void bindsInventedSemanticAbilityIdToTheOwnedAbilityNamedInTheAction() {
        GameState state = state();
        state.abilities.add(ability("ability-wind-vacuum", "wind-vacuum", "风息掌控"));
        ActionIntent source = new ActionIntent("USE_ABILITY", "EXECUTE", false,
                null, null, null, "UNKNOWN", List.of("ability-fengxi-control"),
                "MEDIUM", false, List.of());

        AuthoritativeEntityResolver.IntentResolution resolution =
                AuthoritativeEntityResolver.resolveIntent(source, "施展风息掌控压住洞口乱流", state);

        assertNull(resolution.issue());
        assertEquals(List.of("ability-wind-vacuum"), resolution.intent().usedAbilityIds());
    }

    @Test
    void bindsDefinitionIdAndExactNameWithoutFuzzyGuessing() {
        GameState state = state();
        state.abilities.add(ability("ability-wind-vacuum", "wind-vacuum", "风息掌控"));

        assertEquals("ability-wind-vacuum",
                AuthoritativeEntityResolver.ability(state, "wind-vacuum", null, null, null).value().id);
        assertEquals("ability-wind-vacuum",
                AuthoritativeEntityResolver.ability(state, null, null, "《风息掌控》", null).value().id);
        assertFalse(AuthoritativeEntityResolver.ability(
                state, "similar-wind-power", null, null, null).found());
    }

    @Test
    void bindsARequiredItemClaimToTheCanonicalInventoryId() {
        GameState state = state();
        state.inventory.add(new GameState.InventoryItem("item-red-herb", "red-herb", "暗红三叶草",
                "MATERIAL", "凡物", 1, "药草", false));
        ActionIntent source = new ActionIntent("USE_ITEM", "EXECUTE", false,
                null, null, null, "UNKNOWN", List.of(), "LOW", false,
                List.of(new ActionIntent.ClaimedFact("ITEM", "invented-red-herb", "existence",
                        "暗红三叶草", true, "REQUIRED_EXISTING")));

        AuthoritativeEntityResolver.IntentResolution resolution =
                AuthoritativeEntityResolver.resolveIntent(source, "服下暗红三叶草", state);

        assertNull(resolution.issue());
        assertEquals("item-red-herb", resolution.intent().claims().getFirst().subjectId());
    }

    @Test
    void ambiguousOwnedNamesAskForClarificationInsteadOfChoosingAtRandom() {
        GameState state = state();
        state.abilities.add(ability("ability-wind-a", "wind-a", "风息"));
        state.abilities.add(ability("ability-wind-b", "wind-b", "风息"));
        ActionIntent source = new ActionIntent("USE_ABILITY", "EXECUTE", false,
                null, null, null, "UNKNOWN", List.of("风息"), "LOW", false, List.of());

        AuthoritativeEntityResolver.IntentResolution resolution =
                AuthoritativeEntityResolver.resolveIntent(source, "施展风息", state);

        assertNotNull(resolution.issue());
        assertEquals("ABILITY_REFERENCE_AMBIGUOUS", resolution.issue().code());
        assertNull(resolution.intent());
    }

    @Test
    void canonicalCharacterNameRepairsAMadeUpModelId() {
        GameState state = state();
        state.characters.add(new GameState.CharacterRecord("npc-a-jiu", "阿九", "药童",
                "凡俗", "STABLE", "常人", "存活", "后巷", "相识", 0));
        ActionIntent source = new ActionIntent("NEGOTIATE", "ATTEMPT", false,
                "npc-ahjiu", "阿九", null, "UNKNOWN", List.of(), "LOW", false, List.of());

        AuthoritativeEntityResolver.IntentResolution resolution =
                AuthoritativeEntityResolver.resolveIntent(source, "追上阿九问清药草来历", state);

        assertNull(resolution.issue());
        assertEquals("npc-a-jiu", resolution.intent().targetCharacterId());
    }

    private GameState state() {
        GameState state = new GameState();
        state.player = new GameState.PlayerState("测试者", "HUMAN", "MALE", 20, 75,
                "散修", "LONGEVITY", "问道", "旧债", "引气", "凡胎", 60, 0, 5, 0);
        return state;
    }

    private GameState.AbilityRecord ability(String id, String definitionId, String name) {
        return new GameState.AbilityRecord(id, definitionId, name, "TALENT", "小成", 30,
                "命格", "操纵风息", "引动附近气流", 1, 1, "DAILY", 0, "气息不稳");
    }
}
