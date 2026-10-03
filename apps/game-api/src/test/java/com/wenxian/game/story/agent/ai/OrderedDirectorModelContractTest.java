package com.wenxian.game.story.agent.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.model.application.ModelInvocationService;
import com.wenxian.game.model.application.ModelTaskRouter;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelRoute;
import com.wenxian.game.model.domain.ModelRouteCandidate;
import com.wenxian.game.model.domain.ModelRouteRequest;
import com.wenxian.game.model.domain.ModelTask;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryWorldTime;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/** 验证模型边界不从旧摘要补造结果，也不在进入领域裁决前生成第二份全局结果或记忆。 */
class OrderedDirectorModelContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ModelTaskRouter router = mock(ModelTaskRouter.class);
    private final ModelInvocationService invocations = mock(ModelInvocationService.class);
    private final LangChain4jDirectedNarrativeGenerator generator = new LangChain4jDirectedNarrativeGenerator(
            router, invocations, mock(StoryAiServiceRegistry.class), mapper);

    @Test
    void missingOrIncompleteOrderedResultUsesProtocolFailureAndPreservesKnownUsage() {
        for (var proposal : Arrays.asList(null,
                new OrderedTurnResult.Proposal(List.of(), stop()),
                new OrderedTurnResult.Proposal(proposal().steps(), null))) {
            var draft = draft(proposal);
            stub(draft);

            assertThatThrownBy(() -> session().plan(context()))
                    .isInstanceOfSatisfying(DirectorProtocolException.class, failure -> {
                        assertThat(failure.phase()).isEqualTo(DirectorProtocolException.Phase.ADAPTATION);
                        assertThat(failure.rejectedDraft()).isSameAs(draft);
                        assertThat(failure.getMessage()).contains("orderedResult");
                        assertThat(failure.modelCalls()).singleElement().satisfies(call -> {
                            assertThat(call.inputTokens()).isEqualTo(30);
                            assertThat(call.outputTokens()).isEqualTo(12);
                        });
                    });
        }
    }

    @Test
    void modelAdaptationRetainsProposalWithoutInventingGlobalOutcomeOrMemories() {
        var proposal = proposal();
        stub(draft(proposal));

        var decision = session().plan(context()).decision();

        assertThat(decision.orderedResult()).isSameAs(proposal);
        assertThat(decision.outcome()).isNull();
        assertThat(decision.memories()).isEmpty();
        assertThat(decision.sceneBeats()).isEmpty();
        assertThat(decision.actionUnderstanding().steps()).isEmpty();
        // 内部旧 DTO 的缺省值不形成模型的另一份停止决定；领域根据 stop 生成最终 checkpoint。
        assertThat(decision.decisionCheckpoint().required()).isFalse();
        assertThat(decision.orderedResult().stop().reason()).isEqualTo(OrderedTurnResult.StopReason.DECISION_REQUIRED);
    }

    @Test
    void strictDecodingRejectsFormerParallelResultFieldsAndUnderstandingSteps() throws Exception {
        for (String obsolete : List.of("outcome", "memories", "sceneBeats", "decisionCheckpoint")) {
            ObjectNode json = mapper.valueToTree(draft(proposal()));
            json.putNull(obsolete);
            assertThatThrownBy(() -> mapper.treeToValue(json, TurnDirectorAi.DecisionDraft.class))
                    .isInstanceOf(UnrecognizedPropertyException.class).hasMessageContaining(obsolete);
        }
        ObjectNode json = mapper.valueToTree(draft(proposal()));
        ((ObjectNode) json.path("actionUnderstanding")).putArray("steps");
        assertThatThrownBy(() -> mapper.treeToValue(json, TurnDirectorAi.DecisionDraft.class))
                .isInstanceOf(UnrecognizedPropertyException.class).hasMessageContaining("steps");
    }

    @Test
    void modelStepsUseExplicitBindingAndCannotWriteServerRealmResult() throws Exception {
        ObjectNode json = mapper.valueToTree(draft(proposal()));
        var step = (ObjectNode) json.at("/orderedResult/steps/0");
        assertThat(step.has("realmProgressBinding")).isTrue();
        assertThat(step.has("cultivation")).isFalse();
        assertThat(step.has("realmResult")).isFalse();
        for (String field : List.of("cultivation", "realmResult")) {
            ObjectNode invalid = json.deepCopy();
            ((ObjectNode) invalid.at("/orderedResult/steps/0")).putNull(field);
            assertThatThrownBy(() -> mapper.treeToValue(invalid, TurnDirectorAi.DecisionDraft.class))
                    .isInstanceOf(UnrecognizedPropertyException.class).hasMessageContaining(field);
        }
    }

    private TurnDirectorAi.DecisionDraft draft(OrderedTurnResult.Proposal proposal) {
        return new TurnDirectorAi.DecisionDraft(new TurnDirectorAi.DurationDraft(TurnDirectorAi.DurationUnit.SCENE, 1, null),
                TurnDirectorAi.TimeResolution.COMPLETE, null, null, null, List.of(), List.of(), List.of(), List.of(),
                new TurnDirectorAi.AssetProcessDraft(List.of()),
                new TurnDirectorAi.UnderstandingDraft("报名试炼", List.of("不作长期承诺")), proposal);
    }

    private OrderedTurnResult.Proposal proposal() {
        return new OrderedTurnResult.Proposal(List.of(new OrderedTurnResult.Step("询问试炼报名条件",
                OrderedTurnResult.Resolution.SUCCEEDED, "报名要求先立长期誓约，尚未承诺", false, List.of(), List.of(),
                List.of(new OrderedTurnResult.Statement(null, "值守执事", "报名需要立下长期守约的誓言")))), stop());
    }

    private OrderedTurnResult.Stop stop() {
        return new OrderedTurnResult.Stop(OrderedTurnResult.StopReason.DECISION_REQUIRED, "是否接受长期誓约由玩家决定");
    }

    private DirectedNarrativeGenerator.Session session() {
        return generator.open(new DirectedNarrativeGenerator.InvocationContext("test-user", "test-request", "test-save", 1));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stub(TurnDirectorAi.DecisionDraft draft) {
        doAnswer(call -> {
            ModelTask task = ((ModelRouteRequest) call.getArgument(0)).task();
            return new ModelRoute(task, "test-request", new ModelRouteCandidate(model(),
                    ModelCredential.platform("test-credential"), ModelInvocationOptions.defaults()), null);
        }).when(router).route(any());
        var attempt = new ModelInvocationService.Attempt("TEST", "test-model", "PLATFORM", false,
                "SUCCESS", 5, 30, 12, 0, 30, "");
        when(invocations.invokeResolved(any(), any(), any(), any())).thenReturn((ModelInvocationService.Invocation)
                new ModelInvocationService.Invocation<>(draft, Result.<TurnDirectorAi.DecisionDraft>builder().content(draft).build(),
                        model(), CredentialSource.PLATFORM, false, List.of(attempt)));
    }

    private ModelDefinition model() {
        return new ModelDefinition("TEST", "test-model", "https://example.invalid", ModelAdapterType.OPENAI_COMPATIBLE,
                "test-model", Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE), 8_000, 2_000);
    }

    private StoryPromptContext context() {
        return new StoryPromptContext(new StoryPromptContext.SystemContext(List.of(), List.of()),
                new StoryPromptContext.ObjectiveContext(StoryWorldTime.opening(),
                        new StoryProjection.CoreState("宗门报名处", "平静", "引气", "问道"), List.of(), List.of(), List.of()),
                new StoryPromptContext.CognitionContext(List.of()), new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("你来到报名处。", null),
                new StoryPromptContext.ActionContext("去报名试炼，但先不要立下长期承诺"));
    }
}
