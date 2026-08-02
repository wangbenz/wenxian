package com.wenxian.v2.ai.application;

import com.wenxian.v2.ai.domain.ActionAdjudication.Decision;
import com.wenxian.v2.ai.domain.ActionIntent;
import com.wenxian.v2.ai.domain.IntentContext;
import com.wenxian.v2.ai.domain.IntentContext.EntityCandidate;
import com.wenxian.v2.ai.domain.IntentContext.ProtagonistFacts;
import com.wenxian.v2.game.persistence.LocationRepository;
import com.wenxian.v2.game.persistence.LocationRepository.TravelEdge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionAdjudicatorTest {
    private LocationRepository locations;
    private ActionAdjudicator adjudicator;
    private IntentContext context;

    @BeforeEach
    void setUp() {
        locations = mock(LocationRepository.class);
        adjudicator = new ActionAdjudicator(locations);
        context = new IntentContext("user", "save", "operation", 0, "行动",
                new ProtagonistFacts("self", "石生", "SPIRIT", "SPROUTING_SPIRIT", "萌灵", "安稳", "见天地"),
                "loc-zhaogu-mine", "照骨矿", 1,
                List.of(new EntityCandidate("self", "石生", "CHARACTER", "ALIVE")),
                List.of(new EntityCandidate("loc-zhaogu-mine", "照骨矿", "LOCATION", "ACCESSIBLE"),
                        new EntityCandidate("loc-qingmu-town", "青木镇", "LOCATION", "ACCESSIBLE")),
                List.of(new EntityCandidate("item-keepsake", "身世旧物", "ITEM", "AVAILABLE")),
                List.of(new EntityCandidate("ability-stone-sense", "石脉感知", "TALENT", "ACTIVE")));
    }

    @Test
    void acceptsCandidateIdsInsteadOfGuessingFromNames() {
        when(locations.directEdge("loc-zhaogu-mine", "loc-qingmu-town"))
                .thenReturn(new TravelEdge("edge", "loc-zhaogu-mine", "loc-qingmu-town", "WALK", 240));
        var result = adjudicator.adjudicate(context, intent("TRAVEL", "ATTEMPT",
                "loc-qingmu-town", List.of(), List.of(), null, false, List.of()));

        assertThat(result.decision()).isEqualTo(Decision.ACCEPT);
    }

    @Test
    void rejectsInventedInventoryAndCrossRaceRealmJump() {
        var item = adjudicator.adjudicate(context, intent("USE_ITEM", "EXECUTE", null,
                List.of("item-invented-sword"), List.of(), null, false, List.of()));
        var realm = adjudicator.adjudicate(context, intent("CULTIVATE", "ATTEMPT", null,
                List.of(), List.of(), "GOLDEN_CORE", false, List.of()));

        assertThat(item.code()).isEqualTo("ITEM_NOT_OWNED");
        assertThat(realm.code()).isEqualTo("REALM_TRANSITION_INVALID");
        assertThat(item.consumesTurn()).isFalse();
        assertThat(realm.changesState()).isFalse();
    }

    @Test
    void declaredOutcomeAndAmbiguityNeverReachWorldMutation() {
        var declared = adjudicator.adjudicate(context, intent("COMBAT", "DECLARE_OUTCOME", null,
                List.of(), List.of(), null, true, List.of()));
        var ambiguous = adjudicator.adjudicate(context, intent("INTERACT", "ATTEMPT", null,
                List.of(), List.of(), null, false, List.of("无法确认所说的老人是谁")));

        assertThat(declared.code()).isEqualTo("OUTCOME_CANNOT_BE_DECLARED");
        assertThat(ambiguous.decision()).isEqualTo(Decision.CLARIFY);
        assertThat(declared.consumesTurn()).isFalse();
        assertThat(ambiguous.consumesTurn()).isFalse();
    }

    private ActionIntent intent(String type, String mode, String targetLocation, List<String> items,
                                List<String> abilities, String targetRealm, boolean declared,
                                List<String> ambiguities) {
        return new ActionIntent(type, mode, null, targetLocation, null, items, abilities, targetRealm,
                declared, ambiguities, "测试行动");
    }
}
