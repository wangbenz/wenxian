package com.wenxian.v2.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class V013ActionProfileTest {
    private static final Set<String> V2_ACTIONS = Set.of(
            "INVESTIGATE", "INTERACT", "TRAVEL", "CULTIVATE", "WAIT",
            "USE_ABILITY", "COMBAT", "USE_ITEM", "OTHER", "TRADE");

    @Test
    void keepsTheAnonymousHistoricalProfileInternallyConsistent() throws Exception {
        JsonNode profile;
        try (InputStream input = getClass().getResourceAsStream("/corpus/v013-action-profile.json")) {
            assertThat(input).isNotNull();
            profile = new ObjectMapper().readTree(input);
        }

        assertThat(profile.path("method").path("rawPlayerTextRetained").asBoolean()).isFalse();
        assertThat(profile.path("source").path("totalTurns").asInt()).isEqualTo(42_547);
        assertThat(profile.path("interactionMode").path("exactChoiceClicks").asInt()
                + profile.path("interactionMode").path("nonClickedActions").asInt()).isEqualTo(42_547);

        Set<String> projected = new HashSet<>();
        int projectedTurns = 0;
        for (JsonNode row : profile.path("projectedPrimaryActions")) {
            projected.add(row.path("actionType").asText());
            projectedTurns += row.path("turns").asInt();
        }
        assertThat(projected).isEqualTo(V2_ACTIONS);
        assertThat(projectedTurns).isEqualTo(42_547);

        Set<String> testActions = new HashSet<>();
        int testTurns = 0;
        var fields = profile.path("testTargetPer1000").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            testActions.add(field.getKey());
            testTurns += field.getValue().asInt();
        }
        assertThat(testActions).isEqualTo(V2_ACTIONS);
        assertThat(testTurns).isEqualTo(1_000);
        assertThat(profile.toString()).doesNotContain("player_action", "@qq.com", "@163.com");
    }
}
