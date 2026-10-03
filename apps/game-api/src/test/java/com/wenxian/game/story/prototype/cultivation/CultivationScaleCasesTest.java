package com.wenxian.game.story.prototype.cultivation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.wenxian.game.story.prototype.cultivation.CultivationGrowthPrototype.*;
import static com.wenxian.game.story.prototype.cultivation.CultivationGrowthReplay.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只验证已审阅合成前态、场景传递、历史引用与资格保护，不证明中文经历足以晋升。 */
class CultivationScaleCasesTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void legacyConstructorKeepsItsOriginalScenario() {
        var replay = new CultivationGrowthReplay(mapper);
        JsonNode scenario = mapper.valueToTree(replay.directorInput(replay.initial(), "回顾旧记录，不晋升。").get("scenario"));
        assertEquals("沈知微，人族散修，炼气初期，当前只有吐纳诀抄本和普通随身物品。希望学到能实际用上的本领。",
                scenario.path("character").asText());
        assertEquals("吐纳诀", scenario.path("ability").path("name").asText());
        assertEquals("持有抄本，不代表已经学会", scenario.path("ability").path("initialStatus").asText());
        assertEquals("讲习者许先生愿意回答当前练习问题，双方没有师徒关系或必须履行的新约定。", scenario.path("people").asText());
    }

    @Test
    void fourCasesHaveConsistentSyntheticHistoryAndNeverExposeReviewLabelsOrOldScenario() throws Exception {
        var cases = CultivationScaleCases.load(mapper);
        var replay = new CultivationGrowthReplay(mapper, cases.scenario());
        assertEquals(4, cases.cases().size());
        Object firstScenario = null;
        for (var scaleCase : cases.cases()) {
            var before = scaleCase.before();
            assertEquals(2, before.growth().revision());
            assertEquals(2, before.growth().lastTurn());
            assertEquals(2, before.turns().size());
            assertEquals(List.of("experience:scale:1", "experience:scale:2"),
                    before.experiences().stream().map(ExperienceRecord::id).toList());
            assertEquals(before.turns().getFirst().afterGrowth(), before.turns().getLast().beforeGrowth());
            assertEquals(before.growth(), before.turns().getLast().afterGrowth());
            assertEquals(Set.of("experience:scale:1"), before.turns().getFirst().afterGrowth().learning().get(ABILITY).evidenceRefs());
            assertEquals(0, before.turns().getFirst().beforeGrowth().lastTurn());
            for (var turn : before.turns()) {
                assertNotNull(turn.proposed());
                assertNotNull(turn.accepted());
                assertNotNull(turn.prose());
                assertEquals(turn.accepted().experiences().getFirst().statement(), turn.prose().narrative());
                assertEquals(turn.beforeGrowth().position(), turn.afterGrowth().position());
            }
            var input = replay.directorInput(before, cases.playerAction());
            assertEquals(cases.playerAction(), input.get("playerAction"));
            assertEquals(3, input.get("turn"));
            if (firstScenario == null) firstScenario = input.get("scenario");
            else assertEquals(firstScenario, input.get("scenario"));
            String encoded = mapper.writeValueAsString(input);
            for (String forbidden : List.of("reviewHints", "syntheticNote", "C01", "C02", "C03", "C04",
                    "许先生", "民间讲习院", "持有抄本，不代表已经学会", "尚未实际研习")) {
                assertFalse(encoded.contains(forbidden), "outbound input leaked " + forbidden);
            }
            assertFalse(encoded.contains(cases.syntheticNote()));
            for (var candidate : cases.cases()) for (String hint : candidate.reviewHints()) assertFalse(encoded.contains(hint));
            JsonNode scenario = mapper.valueToTree(input.get("scenario"));
            assertEquals(cases.scenario().character(), scenario.path("character").asText());
            assertEquals(cases.scenario().abilityName(), scenario.path("ability").path("name").asText());
            assertEquals(cases.scenario().abilityInitialStatus(), scenario.path("ability").path("initialStatus").asText());
            assertEquals(cases.scenario().people(), scenario.path("people").asText());
            assertEquals(new Position("realm:xuanxu:human:l1", "phase:xuanxu:middle"), input.get("legalNextPosition"));

            var prepared = replay.prepare(before, cases.playerAction(), review(Readiness.NONE, null, List.of()));
            assertEquals(input.get("scenario"), prepared.writerInput().get("scenario"));
            assertEquals(before.growth().learning(), prepared.after().learning());
            assertEquals(before.growth().accumulation(), prepared.after().accumulation());
            assertEquals(before.growth().qualification(), prepared.after().qualification());
            assertEquals(before.growth().position(), prepared.after().position());
            assertTrue(prepared.outcome().experiences().isEmpty());
            assertEquals(2, before.turns().size(), "prepare does not commit a synthetic turn or invoke prose");
            assertEquals(2, before.growth().revision());
        }
        var withoutQualification = cases.cases().stream().filter(value -> "C02".equals(value.id())).findFirst().orElseThrow().before();
        var qualified = cases.cases().stream().filter(value -> "C04".equals(value.id())).findFirst().orElseThrow().before();
        assertEquals(withoutQualification.experiences(), qualified.experiences());
        assertEquals(withoutQualification.growth().learning(), qualified.growth().learning());
        assertEquals(withoutQualification.growth().accumulation(), qualified.growth().accumulation());
        assertNull(withoutQualification.growth().qualification());
        assertNotNull(qualified.growth().qualification());
    }

    @Test
    void readyMayUseCommittedHistoricalReferencesWithoutPretendingToPracticeAgain() {
        var cases = CultivationScaleCases.load(mapper);
        var replay = new CultivationGrowthReplay(mapper, cases.scenario());
        var before = cases.cases().stream().filter(value -> "C02".equals(value.id())).findFirst().orElseThrow().before();
        Position next = (Position) replay.directorInput(before, cases.playerAction()).get("legalNextPosition");
        var prepared = replay.prepare(before, cases.playerAction(), review(Readiness.READY, next,
                List.of("experience:scale:1", "experience:scale:2")));
        assertEquals(next, prepared.after().qualification().target());
        assertEquals(Set.of("experience:scale:1", "experience:scale:2"), prepared.after().qualification().evidenceRefs());
        assertEquals(3, prepared.after().qualification().grantedTurn());
        assertEquals(before.growth().position(), prepared.after().position());
        assertEquals(before.growth().learning(), prepared.after().learning());
        assertEquals(before.growth().accumulation(), prepared.after().accumulation());
        assertTrue(prepared.outcome().experiences().isEmpty());
        assertNull(before.growth().qualification());
        assertEquals(Failure.INVALID_EVIDENCE, assertThrows(Rejected.class, () -> replay.prepare(before, cases.playerAction(),
                review(Readiness.READY, next, List.of("PLAYER_ACTION")))).failure());
    }

    @Test
    void priorQualificationSurvivesNotReadyAndRepeatedReadyWithoutChangingItsGrant() {
        var cases = CultivationScaleCases.load(mapper);
        var replay = new CultivationGrowthReplay(mapper, cases.scenario());
        var before = cases.cases().stream().filter(value -> "C04".equals(value.id())).findFirst().orElseThrow().before();
        Qualification original = before.growth().qualification();
        assertEquals(2, original.grantedTurn());
        for (Readiness readiness : Readiness.values()) {
            var draft = review(readiness, readiness == Readiness.READY ? original.target() : null,
                    readiness == Readiness.READY ? List.of("experience:scale:1", "experience:scale:2") : List.of());
            var prepared = replay.prepare(before, cases.playerAction(), draft);
            assertEquals(original, prepared.after().qualification());
            assertEquals(before.growth().position(), prepared.after().position());
            assertEquals(before.growth().learning(), prepared.after().learning());
            assertEquals(before.growth().accumulation(), prepared.after().accumulation());
        }
    }

    private DirectorDraft review(Readiness readiness, Position target, List<String> refs) {
        return new DirectorDraft(Resolution.SUCCEEDED, "核对了此前记录，没有增加练习或执行晋升。", "短暂回顾",
                List.of(), new GrowthDraft(List.of(), null, new QualificationDraft(readiness, target,
                "固定结构测试：只检验历史引用和资格保留，不判断中文经历是否充分。", refs)),
                List.of("回顾既有记录"), "仍在原处，未新增修炼或执行晋升。");
    }
}
