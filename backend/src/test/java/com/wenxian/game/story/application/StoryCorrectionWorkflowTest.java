package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.StoryCorrectionAgent;
import com.wenxian.game.story.agent.StoryModelGateway;
import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
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
        StoryCorrectionAgent agent = (context, instruction, route) -> {
            calls.incrementAndGet();
            StoryCorrectionProposal proposal = new StoryCorrectionProposal(
                    StoryCorrectionProposal.CorrectionKind.FACT,
                    "将当前地点从青岐城南门修正为北门。",
                    "确认将当前地点修正为青岐城北门？",
                    new StoryPatch.CoreStatePatch("青岐城北门", null, null, null),
                    List.of(), List.of(), List.of());
            return new StoryCorrectionAgent.Generated(proposal,
                    new StoryModelGateway.Result("{}", "response-1", "flash", 20, 8, 60));
        };
        StoryCorrectionWorkflow workflow = new StoryCorrectionWorkflow(agent, tokenCodec());

        StoryCorrectionWorkflow.Preview preview = workflow.preview("user-1", "story-save-1", REVISION,
                "当前地点应为北门", context(), route());
        StoryCorrectionWorkflow.Confirmed confirmed = workflow.confirm("user-1", "story-save-1", REVISION,
                preview.proposalToken(), projection());

        assertEquals(1, calls.get(), "确认阶段不得重新调用模型");
        assertEquals("青岐城北门", confirmed.projection().coreState().location());
        assertEquals(120, confirmed.projection().worldTimeMinutes(), "修正不能推进世界时间");
        assertEquals(StoryCorrectionProposal.CorrectionKind.FACT, preview.kind());
    }

    @Test
    void staleOrCrossAccountPreviewCannotBeConfirmed() {
        StoryCorrectionAgent agent = (context, instruction, route) -> new StoryCorrectionAgent.Generated(
                new StoryCorrectionProposal(StoryCorrectionProposal.CorrectionKind.FACT, "修正地点。", "确认？",
                        new StoryPatch.CoreStatePatch("青岐城北门", null, null, null),
                        List.of(), List.of(), List.of()),
                new StoryModelGateway.Result("{}", "response-1", "flash", 1, 1, 1));
        StoryCorrectionWorkflow workflow = new StoryCorrectionWorkflow(agent, tokenCodec());
        String token = workflow.preview("user-1", "story-save-1", REVISION, "修正地点", context(), route())
                .proposalToken();

        assertThrows(IllegalArgumentException.class,
                () -> workflow.confirm("user-2", "story-save-1", REVISION, token, projection()));
        assertThrows(IllegalArgumentException.class,
                () -> workflow.confirm("user-1", "story-save-1", REVISION + 1, token, projection()));
    }

    private CorrectionProposalTokenCodec tokenCodec() {
        return new CorrectionProposalTokenCodec(new ObjectMapper().findAndRegisterModules(),
                "test-only-correction-workflow-secret", Duration.ofMinutes(10),
                Clock.fixed(Instant.parse("2026-08-09T08:00:00Z"), ZoneOffset.UTC));
    }

    private StoryProjection projection() {
        return new StoryProjection(120,
                new StoryProjection.CoreState("青岐城南门", "平静", "引气", "问道"),
                Map.of(), Map.of(), Map.of(), List.of());
    }

    private StoryPromptContext context() {
        return new StoryPromptContext(
                new StoryPromptContext.SystemContext(List.of(), List.of()),
                new StoryPromptContext.ObjectiveContext(120, projection().coreState(),
                        List.of(), List.of(), List.of()),
                new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("", null),
                new StoryPromptContext.ActionContext(""));
    }

    private StoryModelRoute route() {
        return new StoryModelRoute("https://example.invalid", "test-secret-key", "DEEPSEEK",
                "OPENAI_COMPATIBLE", "flash", "PUBLIC");
    }
}
