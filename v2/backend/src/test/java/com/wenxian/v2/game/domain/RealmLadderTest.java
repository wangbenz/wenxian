package com.wenxian.v2.game.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RealmLadderTest {
    @Test
    void keepsRaceSpecificNamesAndAdjacentProgression() {
        assertThat(RealmLadder.initial(Race.HUMAN).displayName()).isEqualTo("凡俗");
        assertThat(RealmLadder.initial(Race.DEMON).displayName()).isEqualTo("开智");
        assertThat(RealmLadder.initial(Race.SPIRIT).displayName()).isEqualTo("萌灵");
        assertThat(RealmLadder.isAdjacent(Race.HUMAN, "QI_DRAWING", "FOUNDATION")).isTrue();
        assertThat(RealmLadder.isAdjacent(Race.HUMAN, "QI_DRAWING", "GOLDEN_CORE")).isFalse();
    }
}
