package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RealmProgressionTest {
    @Test
    void validatesAndAppliesOnlyTheNextRealmForAnExplicitBreakthrough() {
        GameState state = stateAt("引气");
        ActionIntent intent = new ActionIntent("CULTIVATE", true, null, null, null,
                "UNKNOWN", List.of(), "HIGH", false, List.of());

        AgentResult.RealmTransition transition = RealmProgression.validate(
                new AgentResult.RealmTransition("SUCCESS", "引气", "筑基", "闭关后筑基成功"),
                state, intent);
        RealmProgression.apply(state.player, transition);

        assertEquals("筑基", state.player.realm);
    }

    @Test
    void rejectsARealmChangeDuringConsultationEvenIfProseMentionsBreakthroughs() {
        GameState state = stateAt("引气");
        ActionIntent intent = new ActionIntent("NEGOTIATE", null, "秦霜", null,
                "UNKNOWN", List.of(), "LOW", false, List.of());

        RealmProgression.RealmValidationException error = assertThrows(
                RealmProgression.RealmValidationException.class,
                () -> RealmProgression.validate(
                        new AgentResult.RealmTransition("SUCCESS", "引气", "筑基",
                                "将来突破筑基时会有隐患"), state, intent));

        assertEquals("REALM_NOT_AUTHORIZED", error.code());
        assertEquals("引气", state.player.realm);
    }

    @Test
    void rejectsARealmChangeBasedOnAnNpcRealmMention() {
        GameState state = stateAt("筑基");
        ActionIntent intent = new ActionIntent("NEGOTIATE", null, "孙长老", "金丹",
                "CHARACTER_STATE", List.of(), "LOW", false, List.of());

        assertThrows(RealmProgression.RealmValidationException.class,
                () -> RealmProgression.validate(
                        new AgentResult.RealmTransition("SUCCESS", "筑基", "金丹",
                                "韩雪衣有望踏入金丹"), state, intent));
        assertEquals("筑基", state.player.realm);
    }

    @Test
    void failedCultivationNeverChangesRealm() {
        GameState state = stateAt("引气");
        ActionIntent intent = new ActionIntent("CULTIVATE", true, null, null, null,
                "UNKNOWN", List.of(), "HIGH", false, List.of());

        assertNull(RealmProgression.validate(
                new AgentResult.RealmTransition("FAILED", "引气", "筑基", "筑基失败"),
                state, intent));
        assertEquals("引气", state.player.realm);
    }

    private GameState stateAt(String realm) {
        GameState state = new GameState();
        state.player = new GameState.PlayerState("顾砚舟", "HUMAN", "MALE", 16, 75,
                "外门弟子", "LONGEVITY", "求长生", "旧债", realm, "凡胎", 60, 0, 10, 72);
        return state;
    }
}
