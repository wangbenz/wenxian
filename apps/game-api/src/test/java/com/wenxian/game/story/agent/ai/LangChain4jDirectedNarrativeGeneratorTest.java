package com.wenxian.game.story.agent.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import dev.langchain4j.service.Result;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证类型化导演链只解析一次任务路由，并保持已预占密钥来源和旧用量汇总兼容。 */
class LangChain4jDirectedNarrativeGeneratorTest {
    private final ModelTaskRouter router = mock(ModelTaskRouter.class);
    private final ModelInvocationService invocations = mock(ModelInvocationService.class);
    private final StoryAiServiceRegistry services = mock(StoryAiServiceRegistry.class);
    private final LangChain4jDirectedNarrativeGenerator generator = new LangChain4jDirectedNarrativeGenerator(
            router, invocations, services, new ObjectMapper().findAndRegisterModules());

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void routesDirectorAndWriterOnceAndReturnsTypedResults() {
        TurnDirectorAi.DecisionDraft decision = decision();
        NarrativeWriterAi.NarrativeDraft narrative = new NarrativeWriterAi.NarrativeDraft(
                "石门回声", "你依照石纹缓缓运气，门后传来一声低沉回响。你停在原地，没有继续推门。",
                List.of(new NarrativeWriterAi.ChoiceDraft("查看石纹", "继续观察石门表面的纹路。")),
                "你在石门前尝试运气，门后传来回响。" );
        when(router.route(any())).thenAnswer(call -> route(((ModelRouteRequest) call.getArgument(0)).task()));
        when(invocations.invokeResolved(any(), any(), any(), any())).thenAnswer(call -> {
            ModelRouteRequest request = call.getArgument(0);
            return request.task() == ModelTask.TURN_DIRECTOR
                    ? invocation(decision, request.task(), 120, 36, 80, 40)
                    : invocation(narrative, request.task(), 240, 90, 160, 80);
        });

        DirectedNarrativeGenerator.InvocationContext invocation = new DirectedNarrativeGenerator.InvocationContext(
                "user-1", "request-1", "save-1", 4);
        DirectedNarrativeGenerator.Session session = generator.open(invocation);
        DirectedNarrativeGenerator.DirectedPlan planned = session.plan(context());
        DirectedNarrativeGenerator.DirectedPlan repaired = session.repair(context(), planned.decision(), "模拟结构整理");
        DirectedNarrativeGenerator.RenderedNarrative rendered = session.render(context(), acceptedPlan(),
                new NarrativeEndState(context().objective().worldTime(), context().objective().protagonist(),
                        null, List.of(), List.of(), List.of()));

        assertThat(planned.decision().orderedResult().steps()).singleElement().satisfies(step -> {
            assertThat(step.realmProgressBinding()).isTrue();
            assertThat(step.result()).isNotBlank();
        });
        assertThat(planned.decision().cultivation().progressGained()).isTrue();
        assertThat(planned.decision().cultivation().breakthroughAttempted()).isFalse();
        assertThat(planned.decision().cultivation().reason()).isEqualTo("已经形成一枚修行进展");
        assertThat(repaired.decision().orderedResult()).isEqualTo(planned.decision().orderedResult());
        assertThat(planned.modelCalls()).singleElement().satisfies(result -> {
            assertThat(result.inputTokens()).isEqualTo(120);
            assertThat(result.cacheHitTokens()).isEqualTo(80);
            assertThat(result.cacheMissTokens()).isEqualTo(40);
        });
        assertThat(rendered.title()).isEqualTo("石门回声");
        assertThat(rendered.choices()).extracting(StoryPatch.ChoiceDraft::title).containsExactly("查看石纹");
        assertThat(rendered.modelCalls()).singleElement().satisfies(result -> {
            assertThat(result.inputTokens()).isEqualTo(240);
            assertThat(result.outputTokens()).isEqualTo(90);
        });
        verify(router, org.mockito.Mockito.times(2)).route(any());
        verify(invocations, org.mockito.Mockito.times(3)).invokeResolved(any(), any(), any(), any());
    }

    @Test
    void rejectsMixedCredentialSourcesInsideOneTurn() {
        when(router.route(any())).thenAnswer(call -> {
            ModelRouteRequest request = call.getArgument(0);
            return route(request.task(), request.task() == ModelTask.TURN_DIRECTOR
                    ? CredentialSource.USER : CredentialSource.PLATFORM);
        });

        assertThatThrownBy(() -> generator.open(
                new DirectedNarrativeGenerator.InvocationContext("user-1", "request-1", "save-1", 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("同一回合的导演与正文任务不能混用平台和玩家密钥");
        verify(invocations, never()).invokeResolved(any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void ignoresBlankEnumNoiseOnUnrelatedOptionalEntityFields() {
        // 物品草案已不再暴露这些字段；其他实体仍需容忍无关可选枚举的空串噪声。
        TurnDirectorAi.EntityDraft location = new TurnDirectorAi.EntityDraft(
                "location-1", "", "LOCATION", "石门", "仍在原处", "石门没有变化", "", List.of(),
                null, new TurnDirectorAi.CustodyDraft("", "", ""), "",
                new TurnDirectorAi.LifecycleDraft("", ""), null, true);
        TurnDirectorAi.DecisionDraft decision = decisionDraft("观察石门后停下", null,
                List.of(), List.of(), List.of(location));
        when(router.route(any())).thenAnswer(call -> route(((ModelRouteRequest) call.getArgument(0)).task()));
        when(invocations.invokeResolved(any(), any(), any(), any()))
                .thenReturn((ModelInvocationService.Invocation) invocation(
                        decision, ModelTask.TURN_DIRECTOR, 120, 36, 80, 40));

        StoryTurnDecision.EntityIntent accepted = generator.open(new DirectedNarrativeGenerator.InvocationContext(
                        "user-1", "request-1", "save-1", 4))
                .plan(context()).decision().entities().getFirst();

        assertThat(accepted.custody()).isNull();
        assertThat(accepted.quantity()).isNull();
        assertThat(accepted.abilityState()).isNull();
        assertThat(accepted.lifecycle()).isNull();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void mapsTypedCharacterPresenceIntoTransientLocationEvidence() {
        TurnDirectorAi.EntityDraft character = new TurnDirectorAi.EntityDraft(
                "", "keeper", "CHARACTER", "周执事", "ACTIVE", "本轮当面通名", "present",
                List.of(new TurnDirectorAi.AttributeDraft("identity", "灵草会馆值守执事")),
                null, null, "", new TurnDirectorAi.LifecycleDraft("ACTIVE", "本轮在场"), null, true);
        TurnDirectorAi.DecisionDraft decision = decisionDraft("完成询问", null,
                List.of(), List.of(), List.of(character));
        when(router.route(any())).thenAnswer(call -> route(((ModelRouteRequest) call.getArgument(0)).task()));
        when(invocations.invokeResolved(any(), any(), any(), any()))
                .thenReturn((ModelInvocationService.Invocation) invocation(
                        decision, ModelTask.TURN_DIRECTOR, 120, 36, 80, 40));

        StoryTurnDecision.EntityIntent accepted = generator.open(new DirectedNarrativeGenerator.InvocationContext(
                        "user-1", "request-1", "save-1", 4))
                .plan(context()).decision().entities().getFirst();

        assertThat(accepted.attributes().path("identity").asText()).isEqualTo("灵草会馆值守执事");
        assertThat(accepted.attributes().path("scenePresence").asText()).isEqualTo("PRESENT");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void requiresExplicitAssetGroupsAndRejectsMissingLearningStateOrWrongGroup() {
        var ability = new TurnDirectorAi.EntityDraft("", "breathing", "ABILITY", "吐纳法", "学习中", "开始依口诀练习",
                null, List.of(), null, null, "LEARNING", null, null, true);
        when(router.route(any())).thenAnswer(call -> route(((ModelRouteRequest) call.getArgument(0)).task()));
        var missing = decisionDraft("开始依口诀练习", null, null, List.of(), List.of());
        var wrongGroup = decisionDraft("开始依口诀练习", null, List.of(), List.of(), List.of(ability));
        var noState = new TurnDirectorAi.EntityDraft("", "breathing", "ABILITY", "吐纳法", "学习中", "开始练习",
                null, List.of(), null, null, null, null, null, true);
        var missingState = decisionDraft("开始依口诀练习", null, List.of(), List.of(noState), List.of());
        var valid = decisionDraft("开始依口诀练习", null, List.of(), List.of(ability), List.of());
        when(invocations.invokeResolved(any(), any(), any(), any())).thenReturn(
                (ModelInvocationService.Invocation) invocation(missing, ModelTask.TURN_DIRECTOR, 1, 1, 0, 0),
                (ModelInvocationService.Invocation) invocation(wrongGroup, ModelTask.TURN_DIRECTOR, 1, 1, 0, 0),
                (ModelInvocationService.Invocation) invocation(missingState, ModelTask.TURN_DIRECTOR, 1, 1, 0, 0),
                (ModelInvocationService.Invocation) invocation(valid, ModelTask.TURN_DIRECTOR, 1, 1, 0, 0));
        var session = generator.open(new DirectedNarrativeGenerator.InvocationContext("u", "r", "s", 1));
        assertThatThrownBy(() -> session.plan(context())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("itemChanges");
        assertThatThrownBy(() -> session.plan(context())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("分组");
        assertThatThrownBy(() -> session.plan(context())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("学习状态");
        assertThat(session.plan(context()).decision().entities()).singleElement().satisfies(e -> {
            assertThat(e.entityType()).isEqualTo("ABILITY");
            assertThat(e.abilityState()).isEqualTo(StoryPatch.AbilityState.LEARNING);
        });
    }

    private TurnDirectorAi.DecisionDraft decision() {
        return decisionDraft("运气成功", new TurnDirectorAi.CultivationDraft(true, false,
                "已经形成一枚修行进展", List.of("PLAYER_ACTION")), List.of(), List.of(), List.of());
    }

    private TurnDirectorAi.DecisionDraft decisionDraft(String result, TurnDirectorAi.CultivationDraft cultivation,
                                                       List<TurnDirectorAi.ItemDraft> items,
                                                       List<TurnDirectorAi.EntityDraft> abilities,
                                                       List<TurnDirectorAi.EntityDraft> entities) {
        var ordered = new OrderedTurnResult.Proposal(List.of(new OrderedTurnResult.Step(
                "执行已授权行动", OrderedTurnResult.Resolution.SUCCEEDED, result, cultivation != null,
                List.of(), List.of(), List.of())), new OrderedTurnResult.Stop(
                OrderedTurnResult.StopReason.ACTION_RESOLVED, "完成后停下"));
        return new TurnDirectorAi.DecisionDraft(
                new TurnDirectorAi.DurationDraft(TurnDirectorAi.DurationUnit.SCENE, 1, "上午"),
                TurnDirectorAi.TimeResolution.COMPLETE, "", null, cultivation, items, abilities, entities, List.of(),
                new TurnDirectorAi.AssetProcessDraft(List.of()),
                new TurnDirectorAi.UnderstandingDraft("承接已授权行动", List.of()), ordered);
    }

    private StoryPatch acceptedPlan() {
        return new StoryPatch("", "", new StoryPatch.TimePassage(
                new SemanticDuration(SemanticDuration.Unit.SCENE, 1, ""),
                StoryPatch.TimeResolution.COMPLETE, null),
                null, List.of(), List.of(), List.of(), List.of(),
                new StoryPatch.DecisionCheckpoint(false, null), List.of(), "",
                new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.SUCCEEDED, "运气成功"));
    }

    private StoryPromptContext context() {
        return new StoryPromptContext(new StoryPromptContext.SystemContext(List.of("已确认事实不可改写"), List.of()),
                new StoryPromptContext.ObjectiveContext(com.wenxian.game.story.domain.StoryWorldTime.opening(),
                        new CoreState("石门前", "平静", "引气", "问道"), List.of(), List.of(), List.of()),
                new StoryPromptContext.CognitionContext(List.of()), new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("你来到石门前。", null),
                new StoryPromptContext.ActionContext("尝试运气感应石门后停下"));
    }

    private ModelRoute route(ModelTask task) {
        return route(task, CredentialSource.PLATFORM);
    }

    private ModelRoute route(ModelTask task, CredentialSource source) {
        ModelCredential credential = source == CredentialSource.USER
                ? ModelCredential.user("secret") : ModelCredential.platform("secret");
        ModelRouteCandidate candidate = new ModelRouteCandidate(model(task), credential,
                ModelInvocationOptions.defaults());
        return new ModelRoute(task, "request-1", candidate, null);
    }

    private ModelDefinition model(ModelTask task) {
        return new ModelDefinition("TEST", task.name().toLowerCase(), "https://example.invalid",
                ModelAdapterType.OPENAI_COMPATIBLE, task.name().toLowerCase(),
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL),
                64_000, 8_000);
    }

    private <T> ModelInvocationService.Invocation<T> invocation(T content, ModelTask task,
                                                                int input, int output, int hit, int miss) {
        ModelInvocationService.Attempt attempt = new ModelInvocationService.Attempt("TEST", task.name().toLowerCase(),
                "PLATFORM", false, "SUCCESS", 25, input, output, hit, miss, "");
        return new ModelInvocationService.Invocation<>(content, Result.<T>builder().content(content).build(), model(task),
                CredentialSource.PLATFORM, false, List.of(attempt));
    }
}
