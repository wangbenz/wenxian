package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.ai.DirectorProtocolException;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.ModelCall;
import com.wenxian.game.story.agent.ai.StoryCandidateGenerator;
import com.wenxian.game.story.agent.ai.StoryCandidateProtocol;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryRecords;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.domain.WorldPackage;
import com.wenxian.game.story.support.StoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证正式完整候选的唯一修复与一次归并；生成会话为离线固定响应，不调用供应商或数据库。 */
class StoryCandidatePreparationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StoryCandidatePreparation preparation = new StoryCandidatePreparation();

    @Test
    void updatesTheSameLearnedAbilityAndAddsEachRecordMemoryOnlyOnce() throws Exception {
        var ability = new StoryProjection.EntityState("ability-breath", "ABILITY", "调息口诀", "ACTIVE",
                "已能静坐调息，行动时仍易乱。", null, true, null, null,
                StoryPatch.AbilityState.LEARNING, null);
        StoryProjection before = projection(Map.of(ability.id(), ability), null);
        ObjectNode raw = candidate("你依照原先学过的口诀缓步前行，停下后也能自行接续气息；快走仍会散乱。");
        ((ObjectNode) raw.get("changes")).putArray("abilityChanges").addObject()
                .put("existingEntityId", ability.id()).put("entityType", "ABILITY")
                .put("summary", "已能静坐、缓步调息，停歇后能自行接续；快走仍易散乱。")
                .put("abilityState", "LEARNING");
        raw.withArray("records").addObject().put("kind", "EVENT")
                .put("text", "缓步运用原口诀，停歇后自行接续；快走时气息仍散乱。")
                .putArray("about").add(ability.id());
        raw.withArray("records").addObject().put("kind", "STATEMENT")
                .put("text", "明日若愿意，可以到摊位帮工并继续请教。")
                .put("sourceLabel", "路边药农").putArray("about").add("PLAYER");
        ScriptedSession session = new ScriptedSession((attempt, rejected, alignment) -> generated(raw, attempt));

        PreparedStoryTurn prepared = preparation.prepare(before, context(before, null), List.of(), session, List.of(), null);

        assertEquals(1, session.attempts);
        assertEquals(before.entities().keySet(), prepared.applied().projection().entities().keySet());
        var learned = prepared.applied().projection().entities().get(ability.id());
        assertEquals(StoryPatch.AbilityState.LEARNING, learned.abilityState());
        assertEquals(raw.at("/changes/abilityChanges/0/summary").textValue(), learned.summary());
        assertEquals(2, prepared.storyRecords().size());
        assertEquals(2, prepared.applied().acceptedPatch().memories().size());
        assertEquals(2, prepared.applied().projection().memories().size(), "组合最终正文不能再次归并记录记忆");
        assertEquals(2, prepared.applied().projection().memories().stream().map(StoryProjection.MemoryState::id).distinct().count());
        assertEquals(List.of(ability.id()), prepared.storyRecords().getFirst().about());
        assertTrue(prepared.applied().projection().worldFacts().isEmpty());
        assertEquals(raw.at("/story/narrative").textValue(), prepared.generation().patch().narrative());
        assertSame(prepared.generation().patch(), prepared.applied().acceptedPatch());
        assertNull(prepared.acceptedResult());
        assertEquals(11, prepared.generation().inputTokens());
        assertEquals(7, prepared.generation().outputTokens());
        assertTrue(before.memories().isEmpty());
        assertEquals("已能静坐调息，行动时仍易乱。", before.entities().get(ability.id()).summary());
    }

    @Test
    void realmAlignmentRepairsOneCompleteDraftAndPreservesRawAndAllKnownUsage() throws Exception {
        var cultivation = new ProtagonistCultivation("cultivation:test", "stage:test:1",
                WorldPackage.RealmPhase.PERFECT, 1, 2, "此前已经圆满。");
        StoryProjection before = projection(Map.of(), cultivation);
        ObjectNode original = cultivationCandidate("FAILED", "你尝试突破，却仍停留在原境界。");
        ObjectNode repaired = cultivationCandidate("SUCCEEDED", "你完成突破，随后停下观察新的气息。");
        ScriptedSession session = new ScriptedSession((attempt, rejected, alignment) -> {
            if (attempt == 1) {
                assertNull(rejected);
                assertNull(alignment);
                return generated(original, attempt);
            }
            assertEquals(original.toString(), rejected.rejectedDraft());
            assertEquals(DirectorProtocolException.Phase.COMPILATION, rejected.phase());
            var feedback = assertInstanceOf(StoryCandidatePreparation.ServerAlignment.class, alignment);
            assertEquals("stage:test:2", feedback.realmEffect().stageId());
            assertEquals(StoryPatch.CultivationResolution.RANK_ADVANCED, feedback.realmEffect().resolution());
            assertEquals(StoryPatch.OutcomeResolution.FAILED, feedback.localOutcomeEffect().proposed());
            assertEquals(StoryPatch.OutcomeResolution.SUCCEEDED, feedback.localOutcomeEffect().actual());
            return generated(repaired, attempt);
        });

        var prepared = preparation.prepare(before, context(before, null), List.of(), session, List.of(), null);

        assertEquals(2, session.attempts);
        assertEquals("stage:test:2", prepared.applied().projection().protagonistCultivation().currentStageId());
        assertEquals(StoryPatch.OutcomeResolution.SUCCEEDED, prepared.generation().patch().cultivation().actionOutcome());
        assertEquals(repaired.at("/story/narrative").textValue(), prepared.generation().patch().narrative());
        assertEquals(23, prepared.generation().inputTokens());
        assertEquals(15, prepared.generation().outputTokens());
        assertEquals("stage:test:1", before.protagonistCultivation().currentStageId());
    }

    @Test
    void explicitDurationMustBeReflectedByTheSecondWholeCandidate() throws Exception {
        StoryProjection before = projection(Map.of(), null);
        SemanticDuration planned = new SemanticDuration(SemanticDuration.Unit.YEAR, 5, "");
        ObjectNode first = candidate("你暂时整理了行装。");
        ObjectNode second = candidate("五年过去，你结束这段生活，停在此刻。");
        ((ObjectNode) second.at("/changes/duration")).put("unit", "YEAR").put("value", 5);
        ScriptedSession session = new ScriptedSession((attempt, rejected, alignment) -> {
            if (attempt == 1) return generated(first, attempt);
            assertEquals(first.toString(), rejected.rejectedDraft());
            var feedback = assertInstanceOf(StoryCandidatePreparation.ServerAlignment.class, alignment);
            assertEquals(planned, feedback.timeEffect().resolvedDuration());
            return generated(second, attempt);
        });

        var prepared = preparation.prepare(before, context(before, planned), List.of(), session, List.of(), null);

        assertEquals(2, session.attempts);
        assertEquals(before.worldTime().advance(planned), prepared.applied().projection().worldTime());
        assertEquals(planned, prepared.generation().patch().time().duration());
        assertEquals(second.at("/story/narrative").textValue(), prepared.generation().patch().narrative());
    }

    @Test
    void unchangedTimeMismatchAfterTheOnlyRepairRejectsTheTurnWithoutAThirdCall() throws Exception {
        StoryProjection before = projection(Map.of(), null);
        ObjectNode wrong = candidate("你只整理了行装。");
        ScriptedSession session = new ScriptedSession((attempt, rejected, alignment) -> generated(wrong, attempt));
        var planned = new SemanticDuration(SemanticDuration.Unit.YEAR, 5, "");

        var error = assertThrows(StoryTurnProtocolException.class,
                () -> preparation.prepare(before, context(before, planned), List.of(), session, List.of(), null));

        assertEquals(2, session.attempts);
        var exhausted = assertInstanceOf(DirectorProtocolException.class, error.getCause());
        assertEquals(wrong.toString(), exhausted.rejectedDraft());
        assertEquals(2, exhausted.modelCalls().size());
        assertTrue(exhausted.getMessage().contains("ALIGNMENT_CHANGED"));
        assertEquals(StoryWorldTime.opening(), before.worldTime());
        assertTrue(before.memories().isEmpty());
    }

    @Test
    void typedFailureAndNewRealmAlignmentShareTheSameSingleRepairBudget() throws Exception {
        var cultivation = new ProtagonistCultivation("cultivation:test", "stage:test:1",
                WorldPackage.RealmPhase.PERFECT, 1, 2, "此前已经圆满。");
        StoryProjection before = projection(Map.of(), cultivation);
        String broken = "{\"story\":{\"narrative\":7},\"changes\":{},\"records\":[]}";
        ObjectNode rank = cultivationCandidate("SUCCEEDED", "你完成突破。");
        ScriptedSession session = new ScriptedSession((attempt, rejected, alignment) -> {
            if (attempt == 1) throw new DirectorProtocolException(DirectorProtocolException.Phase.DECODING,
                    broken, List.of(call(attempt)), "story.narrative 类型错误", null);
            assertEquals(broken, rejected.rejectedDraft());
            assertNull(alignment, "第一次解码失败并未预演过境界，不能伪造已经反馈过晋升");
            return generated(rank, attempt);
        });

        var failure = assertThrows(StoryTurnProtocolException.class,
                () -> preparation.prepare(before, context(before, null), List.of(), session, List.of(), null));

        assertEquals(2, session.attempts);
        var exhausted = assertInstanceOf(DirectorProtocolException.class, failure.getCause());
        assertEquals(2, exhausted.modelCalls().size());
        assertEquals(rank.toString(), exhausted.rejectedDraft());
        assertEquals("stage:test:1", before.protagonistCultivation().currentStageId());
    }

    @Test
    void transportOrProgrammingFailureDoesNotBecomeAProtocolRepair() {
        StoryProjection before = projection(Map.of(), null);
        RuntimeException unavailable = new IllegalStateException("模拟连接中断");
        ScriptedSession session = new ScriptedSession((attempt, rejected, alignment) -> { throw unavailable; });

        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> preparation.prepare(before, context(before, null), List.of(), session, List.of(), null)));
        assertEquals(1, session.attempts);
        assertTrue(before.memories().isEmpty());
    }

    private StoryCandidateGenerator.Generated generated(ObjectNode raw, int attempt) throws Exception {
        var draft = mapper.treeToValue(raw, StoryCandidateProtocol.Draft.class);
        return new StoryCandidateGenerator.Generated(draft, StoryCandidateProtocol.adapt(draft),
                List.of(call(attempt)), raw.toString(), List.of());
    }

    private ObjectNode cultivationCandidate(String outcome, String narrative) throws Exception {
        ObjectNode raw = candidate(narrative);
        ((ObjectNode) raw.get("changes")).putObject("cultivation").put("progressGained", false)
                .put("breakthroughAttempted", true).put("reason", "按已有积累主动尝试突破。")
                .put("actionOutcome", outcome).putArray("evidenceRefs").add("PLAYER_ACTION");
        return raw;
    }

    private ObjectNode candidate(String narrative) throws Exception {
        ObjectNode raw = (ObjectNode) mapper.readTree("""
                {"story":{"title":"本轮经历","narrative":"待写正文","decision":null,"choices":[],"summary":""},
                "changes":{"duration":{"unit":"SCENE","value":1,"targetPhase":""},"timeResolution":"COMPLETE"},
                "records":[]}
                """);
        ((ObjectNode) raw.get("story")).put("narrative", narrative);
        return raw;
    }

    private StoryProjection projection(Map<String, StoryProjection.EntityState> entities, ProtagonistCultivation cultivation) {
        return new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("石门前", "平静", "炼气初期", "问道"), entities,
                Map.of(), Map.of(), List.of(), StoryTestFixtures.creationContract(), StoryTestFixtures.playPreference(),
                cultivation == null ? null : StoryTestFixtures.cultivationSystem(), cultivation);
    }

    private StoryPromptContext context(StoryProjection before, SemanticDuration duration) {
        return new StoryPromptContext(new StoryPromptContext.SystemContext(List.of(), List.of()),
                new StoryPromptContext.ObjectiveContext(before.worldTime(), before.coreState(), List.of(), List.of(), List.of()),
                new StoryPromptContext.CognitionContext(List.of()), new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("此前的经历。", null),
                new StoryPromptContext.ActionContext("执行已经明确的本轮行动，到结果处停下。", null, duration));
    }

    private static ModelCall call(int attempt) {
        return new ModelCall("OFFLINE", "fixed-candidate", "PLATFORM", false, "SUCCESS", 2,
                10 + attempt, 6 + attempt, 0, 10 + attempt, null);
    }

    @FunctionalInterface
    private interface Response {
        StoryCandidateGenerator.Generated get(int attempt, DirectorProtocolException rejected, Object alignment) throws Exception;
    }

    private static final class ScriptedSession implements StoryCandidateGenerator.Session {
        private final Response response;
        private int attempts;
        private ScriptedSession(Response response) { this.response = response; }
        @Override public CredentialSource credentialSource() { return CredentialSource.PLATFORM; }
        @Override public StoryCandidateGenerator.Generated generate(StoryPromptContext context, List<StoryRecords.Envelope> history,
                                                                    DirectorProtocolException rejected, Object alignment) {
            ++attempts;
            try { return response.get(attempts, rejected, alignment); }
            catch (RuntimeException error) { throw error; }
            catch (Exception error) { throw new AssertionError("离线候选构造失败", error); }
        }
    }
}
