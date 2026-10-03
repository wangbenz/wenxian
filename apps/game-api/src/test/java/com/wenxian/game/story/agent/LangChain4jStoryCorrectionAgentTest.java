package com.wenxian.game.story.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.application.ModelInvocationService;
import com.wenxian.game.model.application.ModelRoutePolicy;
import com.wenxian.game.model.application.ModelTaskRouter;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelRouteCandidate;
import com.wenxian.game.model.domain.ModelTask;
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import com.wenxian.game.story.agent.ai.StoryAiServiceRegistry;
import com.wenxian.game.story.agent.ai.StoryCorrectionAi;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangChain4jStoryCorrectionAgentTest {
    @Test
    void routesTypedProposalAndValidatesSeparatedContext() {
        AtomicReference<String> payload = new AtomicReference<>();
        StoryCorrectionProposal proposal = new StoryCorrectionProposal(
                StoryCorrectionProposal.CorrectionKind.FACT, "修正当前地点。",
                "确认将当前地点改为青岐城北门？",
                new StoryPatch.CoreStatePatch("青岐城北门", null, null, null),
                List.of(), List.of(), List.of());
        StoryCorrectionAi ai = context -> {
            payload.set(context);
            return Result.<StoryCorrectionProposal>builder().content(proposal).build();
        };

        StoryCorrectionAgent.Generated generated = agent(ai).propose(
                "user-1", "correction-1", context(), "当前地点应为青岐城北门");

        assertThat(generated.proposal().kind()).isEqualTo(StoryCorrectionProposal.CorrectionKind.FACT);
        assertThat(generated.proposal().coreState().location()).isEqualTo("青岐城北门");
        assertThat(payload.get()).contains("青岐城南门", "当前地点应为青岐城北门");
    }

    @Test
    void rejectsProposalThatMixesFactAndCognitionLayers() {
        StoryCorrectionProposal proposal = new StoryCorrectionProposal(
                StoryCorrectionProposal.CorrectionKind.FACT, "同时改两个层级。", "确认？",
                new StoryPatch.CoreStatePatch("青岐城北门", null, null, null), List.of(), List.of(),
                List.of(new StoryPatch.KnowledgePatch("character-1", "fact-1", "他已经知道真相", List.of())));

        assertThatThrownBy(() -> agent(ignored -> Result.<StoryCorrectionProposal>builder()
                .content(proposal).build()).propose(
                        "user-1", "correction-2", context(), "修正地点并让他知情"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("修正说明未能形成可确认提案")
                .hasRootCauseMessage("事实修正不能同时改写人物认知");
    }

    @Test
    void identifiesNarrativeClarificationWithoutObjectiveChange() {
        StoryCorrectionProposal proposal = new StoryCorrectionProposal(
                StoryCorrectionProposal.CorrectionKind.COGNITION, "玩家尚未决定下一步。",
                "当前权威事实未记录该决定。", new StoryPatch.CoreStatePatch(null, null, null, null),
                List.of(), List.of(), List.of());

        assertThatThrownBy(() -> agent(ignored -> Result.<StoryCorrectionProposal>builder()
                .content(proposal).build()).propose(
                        "user-1", "correction-3", context(), "我尚未决定下一步"))
                .isInstanceOf(StoryCorrectionNoChangeException.class)
                .hasMessageContaining("权威事实");
    }

    @Test
    void normalizesStructuredCultivationCorrectionAndRemovesDuplicateRealmText() {
        StoryPatch.CultivationPatch cultivation = new StoryPatch.CultivationPatch(
                "stage-04", "金丹已成", StoryPatch.CultivationResolution.CORRECTED,
                "玩家明确纠正命卷事实");
        StoryCorrectionProposal proposal = new StoryCorrectionProposal(
                StoryCorrectionProposal.CorrectionKind.FACT, "修正当前境界。", "确认修正为金丹？",
                new StoryPatch.CoreStatePatch(null, null, "金丹", null), cultivation,
                List.of(), List.of(), List.of());

        StoryCorrectionProposal generated = agent(ignored -> Result.<StoryCorrectionProposal>builder()
                .content(proposal).build()).propose(
                "user-1", "correction-4", cultivationContext(), "当前境界应为金丹").proposal();

        assertThat(generated.cultivation()).isEqualTo(cultivation);
        assertThat(generated.coreState().realm()).isNull();
    }

    @Test
    void rejectsUnknownStageAndFreeRealmBypassForStructuredSave() {
        StoryCorrectionProposal unknown = new StoryCorrectionProposal(
                StoryCorrectionProposal.CorrectionKind.FACT, "修正境界。", "确认？", null,
                new StoryPatch.CultivationPatch("stage-99", "未知", StoryPatch.CultivationResolution.CORRECTED,
                        "玩家要求"), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> agent(ignored -> Result.<StoryCorrectionProposal>builder()
                .content(unknown).build()).propose(
                "user-1", "correction-5", cultivationContext(), "修正境界"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("修正目标不属于当前命卷已确认的主修体系");

        StoryCorrectionProposal freeRealm = new StoryCorrectionProposal(
                StoryCorrectionProposal.CorrectionKind.FACT, "修正境界。", "确认？",
                new StoryPatch.CoreStatePatch(null, null, "金丹", null),
                List.of(), List.of(), List.of());
        assertThatThrownBy(() -> agent(ignored -> Result.<StoryCorrectionProposal>builder()
                .content(freeRealm).build()).propose(
                "user-1", "correction-6", cultivationContext(), "修正境界"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("当前命卷的境界修正必须引用已确认体系中的稳定阶段ID");
    }

    @Test
    void rejectsCorrectionContextWithoutCurrentCultivationSystem() {
        StoryCorrectionProposal proposal = new StoryCorrectionProposal(
                StoryCorrectionProposal.CorrectionKind.FACT, "修正当前地点。", "确认？",
                new StoryPatch.CoreStatePatch("青岐城北门", null, null, null),
                List.of(), List.of(), List.of());

        assertThatThrownBy(() -> agent(ignored -> Result.<StoryCorrectionProposal>builder()
                .content(proposal).build()).propose(
                "user-1", "correction-7", baseContext(), "修正地点"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("当前命卷缺少已确认主修体系，不能生成修正提案");
    }

    private LangChain4jStoryCorrectionAgent agent(StoryCorrectionAi ai) {
        ModelDefinition definition = new ModelDefinition("TEST", "flash", "https://example.invalid",
                ModelAdapterType.OPENAI_COMPATIBLE, "flash",
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL),
                64_000, 8_000);
        ModelRouteCandidate candidate = new ModelRouteCandidate(definition, ModelCredential.platform("secret"),
                ModelInvocationOptions.defaults());
        ModelRoutePolicy policy = request -> {
            assertThat(request.task()).isEqualTo(ModelTask.STORY_CORRECTION);
            return new ModelRoutePolicy.ResolvedCandidates(CredentialSource.PLATFORM, List.of(candidate));
        };
        ChatModelRegistry models = new ChatModelRegistry(
                (model, credential, options) -> mock(ChatModel.class), 4);
        StoryAiServiceRegistry services = mock(StoryAiServiceRegistry.class);
        when(services.storyCorrection(any())).thenReturn(ai);
        return new LangChain4jStoryCorrectionAgent(
                new ModelInvocationService(new ModelTaskRouter(policy), models), services,
                new ObjectMapper().findAndRegisterModules());
    }

    private StoryPromptContext context() {
        return cultivationContext();
    }

    private StoryPromptContext baseContext() {
        return new StoryPromptContext(
                new StoryPromptContext.SystemContext(List.of("已确认事实不可静默改写"), List.of()),
                new StoryPromptContext.ObjectiveContext(com.wenxian.game.story.domain.StoryWorldTime.opening(),
                        new CoreState("青岐城南门", "平静", "引气", "问道"),
                        List.of(), List.of(), List.of()),
                new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("此前抵达城门。", null),
                new StoryPromptContext.ActionContext(""));
    }

    private StoryPromptContext cultivationContext() {
        StoryPromptContext base = baseContext();
        StoryPromptContext.CultivationView cultivation = new StoryPromptContext.CultivationView(
                "cultivation-test", 1, "青冥炼真道", "借天地清气磨炼真形。",
                "stage-02", "炼气", "L2", "EARLY", 0, 2, "气息渐稳", List.of(
                stage("stage-01", 1, "引气", "stage-02"),
                stage("stage-02", 2, "炼气", "stage-03"),
                stage("stage-03", 3, "筑基", "stage-04"),
                stage("stage-04", 4, "金丹", "stage-05"),
                stage("stage-05", 5, "元婴", "stage-06"),
                stage("stage-06", 6, "化神", null)));
        StoryPromptContext.ObjectiveContext objective = base.objective();
        return new StoryPromptContext(base.system(), new StoryPromptContext.ObjectiveContext(
                objective.worldTime(), objective.protagonist(), objective.worldFacts(),
                objective.entityIndex(), objective.relatedEntities(), objective.currentInventory(),
                objective.pendingDecision(), objective.creationContract(), objective.playPreference(),
                objective.sourceExcerpts(), cultivation),
                base.cognition(), base.memory(), base.narrative(), base.action());
    }

    private StoryPromptContext.CultivationStageView stage(String id, int order, String name, String nextId) {
        return new StoryPromptContext.CultivationStageView(id, order, name, name + "阶段。", List.of(),
                nextId == null ? List.of() : List.of(nextId));
    }
}
