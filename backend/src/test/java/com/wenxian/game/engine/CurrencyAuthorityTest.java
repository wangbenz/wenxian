package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyAuthorityTest {
    @Test
    void migratesLegacySilverOnceAndRemovesDuplicateCurrencyItems() {
        GameState state = stateWithCopper(22);
        state.inventory.add(new GameState.InventoryItem(
                "old-copper", "copper-coin", "铜钱", "CURRENCY", "凡物", 8, "旧重复余额", false));

        assertTrue(CurrencyAuthority.repairLegacyState(state));

        assertEquals(22, state.wallet.copperWen);
        assertEquals(CurrencyAuthority.ECONOMY_VERSION, state.economyVersion);
        assertTrue(state.inventory.isEmpty());
    }

    @Test
    void appliesIncomeAndExpenseAsLedgerEntries() {
        GameState state = stateWithCopper(22);
        CurrencyAuthority.repairLegacyState(state);
        List<AgentResult.CurrencyChange> changes = CurrencyAuthority.validate(List.of(
                new AgentResult.CurrencyChange(CurrencyAuthority.COPPER_WEN, 60, "出售矿石"),
                new AgentResult.CurrencyChange(CurrencyAuthority.COPPER_WEN, -50, "购买药材")
        ), state);

        CurrencyAuthority.apply(state, changes);

        assertEquals(32, state.wallet.copperWen);
        assertEquals(32, state.player.silver);
    }

    @Test
    void rejectsSpendingMoreThanTheWalletContains() {
        GameState state = stateWithCopper(22);

        CurrencyAuthority.CurrencyValidationException error = assertThrows(
                CurrencyAuthority.CurrencyValidationException.class,
                () -> CurrencyAuthority.validate(List.of(
                        new AgentResult.CurrencyChange(CurrencyAuthority.COPPER_WEN, -50, "购买药材")
                ), state));

        assertEquals("CURRENCY_INSUFFICIENT", error.code());
    }

    @Test
    void gameEnginePersistsTheValidatedWalletChange() {
        GameState state = stateWithCopper(22);
        state.phase = "playing";
        state.sceneId = "market";
        state.sceneTitle = "集市";
        state.currentLocation = "青木镇";
        AgentResult result = new AgentResult(
                "market-end", "药摊", "青木镇", 10, "CONTINUE",
                List.of("你卖掉药材。", "掌柜递来铜钱。"), List.of("交易完成"), List.of(),
                Map.of("condition", "STABLE"), new AgentResult.EventDraft("卖出药材", "药材换得六十文。", "gain"),
                null, null, null, null,
                new AgentResult.ActionResolution("ACCEPTED", "", "", List.of()),
                new AgentResult.DeclarationResolution("NONE", "NOT_APPLICABLE", "无声明")
        ).withCurrencyChanges(List.of(
                new AgentResult.CurrencyChange(CurrencyAuthority.COPPER_WEN, 60, "出售药材")
        ));

        GameState next = new GameEngine(new ObjectMapper().findAndRegisterModules()).apply(state, result);

        assertEquals(82, next.wallet.copperWen);
        assertEquals(82, next.player.silver);
    }

    private GameState stateWithCopper(int copper) {
        GameState state = new GameState();
        state.player = new GameState.PlayerState("陆沉", "HUMAN", "MALE", 16, 75,
                "凡人", "LONGEVITY", "活下去", "旧债", "凡俗", "凡胎", 60, copper, 6, 0);
        return state;
    }
}
