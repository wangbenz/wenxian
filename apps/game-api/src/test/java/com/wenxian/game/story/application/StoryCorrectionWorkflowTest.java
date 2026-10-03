package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.StoryCorrectionAgent;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryWorldTime;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoryCorrectionWorkflowTest {
    private static final long REVISION = 9;

    @Test
    void previewCallsAgentOnceAndConfirmOnlyAppliesSignedProposal() {
        AtomicInteger calls = new AtomicInteger();
        StoryCorrectionAgent agent = (userId, requestId, context, instruction) -> {
            calls.incrementAndGet();
            StoryCorrectionProposal proposal = new StoryCorrectionProposal(
                    StoryCorrectionProposal.CorrectionKind.FACT,
                    "将当前地点从青岐城南门修正为北门。",
                    "确认将当前地点修正为青岐城北门？",
                    new StoryPatch.CoreStatePatch("青岐城北门", null, null, null),
                    List.of(), List.of(), List.of());
            return new StoryCorrectionAgent.Generated(proposal);
        };
        StoryCorrectionWorkflow workflow = new StoryCorrectionWorkflow(agent, tokenCodec());

        StoryCorrectionWorkflow.Preview preview = workflow.preview("user-1", "story-save-1", REVISION,
                "当前地点应为北门", context());
        StoryCorrectionWorkflow.Confirmed confirmed = workflow.confirm("user-1", "story-save-1", REVISION,
                preview.proposalToken(), projection());

        assertEquals(1, calls.get(), "确认阶段不得重新调用模型");
        assertEquals("当前地点应为北门", confirmed.instruction());
        assertEquals("青岐城北门", confirmed.projection().coreState().location());
        assertEquals(StoryWorldTime.opening(), confirmed.projection().worldTime(), "修正不能推进世界时间");
        assertEquals(StoryCorrectionProposal.CorrectionKind.FACT, preview.kind());
    }

    @Test
    void staleOrCrossAccountPreviewCannotBeConfirmed() {
        StoryCorrectionAgent agent = (userId, requestId, context, instruction) -> new StoryCorrectionAgent.Generated(
                new StoryCorrectionProposal(StoryCorrectionProposal.CorrectionKind.FACT, "修正地点。", "确认？",
                        new StoryPatch.CoreStatePatch("青岐城北门", null, null, null),
                        List.of(), List.of(), List.of()));
        StoryCorrectionWorkflow workflow = new StoryCorrectionWorkflow(agent, tokenCodec());
        String token = workflow.preview("user-1", "story-save-1", REVISION, "修正地点", context())
                .proposalToken();

        assertThrows(IllegalArgumentException.class,
                () -> workflow.confirm("user-2", "story-save-1", REVISION, token, projection()));
        assertThrows(IllegalArgumentException.class,
                () -> workflow.confirm("user-1", "story-save-1", REVISION + 1, token, projection()));
    }

    @Test
    void signedPreviewCarriesStructuredCultivationCorrectionThroughConfirmation() {
        StoryCorrectionAgent agent = (userId, requestId, context, instruction) ->
                new StoryCorrectionAgent.Generated(new StoryCorrectionProposal(
                        StoryCorrectionProposal.CorrectionKind.FACT, "修正已误记的当前阶段。", "确认修正为金丹？",
                        null, new StoryPatch.CultivationPatch("stage-04", "金丹已成",
                        StoryPatch.CultivationResolution.CORRECTED, "玩家明确纠正命卷事实"),
                        List.of(), List.of(), List.of()));
        StoryCorrectionWorkflow workflow = new StoryCorrectionWorkflow(agent, tokenCodec());
        StoryProjection before = cultivationProjection();

        StoryCorrectionWorkflow.Preview preview = workflow.preview("user-1", "story-save-1", REVISION,
                "当前境界应为金丹", context());
        StoryCorrectionWorkflow.Confirmed confirmed = workflow.confirm("user-1", "story-save-1", REVISION,
                preview.proposalToken(), before);

        assertEquals("stage-04", confirmed.projection().protagonistCultivation().currentStageId());
        assertEquals("金丹初期", confirmed.projection().coreState().realm());
        assertEquals(StoryPatch.CultivationResolution.CORRECTED,
                confirmed.acceptedProposal().cultivation().resolution());
        assertEquals(before.worldTime(), confirmed.projection().worldTime());
    }

    private CorrectionProposalTokenCodec tokenCodec() {
        return new CorrectionProposalTokenCodec(new ObjectMapper().findAndRegisterModules(),
                "test-only-correction-workflow-secret", Duration.ofMinutes(10),
                Clock.fixed(Instant.parse("2026-08-09T08:00:00Z"), ZoneOffset.UTC));
    }

    private StoryProjection projection() {
        return new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("青岐城南门", "平静", "引气", "问道"),
                Map.of(), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), null, null);
    }

    private StoryPromptContext context() {
        return new StoryPromptContext(
                new StoryPromptContext.SystemContext(List.of(), List.of()),
                new StoryPromptContext.ObjectiveContext(StoryWorldTime.opening(), projection().coreState(),
                        List.of(), List.of(), List.of()),
                new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("", null),
                new StoryPromptContext.ActionContext(""));
    }

    private StoryProjection cultivationProjection() {
        CultivationSystem system = new CultivationSystem("cultivation-test", 1, "青冥炼真道", "修行真气。",
                "人族", "stage-01", List.of(
                stage("stage-01", 1, "引气", "stage-02"),
                stage("stage-02", 2, "炼气", "stage-03"),
                stage("stage-03", 3, "筑基", "stage-04"),
                stage("stage-04", 4, "金丹", "stage-05"),
                stage("stage-05", 5, "元婴", "stage-06"),
                stage("stage-06", 6, "化神", null)),
                new CultivationSystem.Confirmation(
                        CultivationSystem.ConfirmationSource.PLAYER_CONFIRMED_RECOMMENDATION,
                        Instant.parse("2026-08-20T12:00:00Z")));
        return new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("青岐城南门", "平静", "炼气", "问道"),
                Map.of(), Map.of(), Map.of(), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(),
                system, new ProtagonistCultivation(system.systemId(), "stage-02", "气息渐稳"));
    }

    private CultivationSystem.Stage stage(String id, int order, String name, String nextId) {
        return new CultivationSystem.Stage(id, order, name, name + "阶段。", List.of(),
                nextId == null ? List.of() : List.of(nextId));
    }

}
