package com.wenxian.game.story.replay.single;

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
import com.wenxian.game.model.infrastructure.AiServiceRegistry;
import com.wenxian.game.model.infrastructure.ChatModelFactory;
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import com.wenxian.game.model.infrastructure.PromptResourceLoader;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator;
import com.wenxian.game.story.agent.ai.DirectorProtocolException;
import com.wenxian.game.story.agent.ai.LangChain4jDirectedNarrativeGenerator;
import com.wenxian.game.story.agent.ai.StoryAiServiceRegistry;
import com.wenxian.game.story.agent.ai.TurnDirectorAi;
import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryWorldTime;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同一固定草案分别经过正式类型化 Session.plan 和实验静态适配，比较确定性结果与拒绝原因。
 * 只用内存 ChatModel，不反射私有方法、不访问供应商，也不把适配通过当作领域接受或剧情质量证明。
 */
class SingleCandidateProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void preservesItemAbilitySeparationExistingIdsAndAssetReferencesLikeProduction() throws Exception {
        var draft = lesson();
        var calls = new AtomicInteger();
        StoryTurnDecision formal = formalPlan(draft, calls);
        StoryTurnDecision experimental = SingleCandidateProtocol.toDecision(draft);

        assertEquals(formal, experimental);
        assertEquals(1, calls.get(), "对照只调用一次离线导演，不经过修复或正文");
        assertEquals(List.of("ITEM", "ABILITY", "CHARACTER"), experimental.entities().stream()
                .map(StoryTurnDecision.EntityIntent::entityType).toList());
        var paper = experimental.entities().get(0);
        var ability = experimental.entities().get(1);
        var teacher = experimental.entities().get(2);
        assertEquals("", paper.existingEntityId());
        assertEquals("lesson-copy", paper.localRef());
        assertNull(paper.quantity(), "物品元数据不能成为另一份数量来源");
        assertNull(paper.custody());
        assertEquals("ability-existing", ability.existingEntityId());
        assertEquals(StoryPatch.AbilityState.LEARNING, ability.abilityState());
        assertEquals("已会静坐调息，刚学会缓步时放慢呼气；快跑尚未验证。", ability.summary());
        assertEquals("teacher-existing", teacher.existingEntityId());
        assertEquals("PRESENT", teacher.attributes().path("scenePresence").asText());
        assertEquals("指点者", teacher.relationship().label());
        var output = experimental.assetProcess().steps().getFirst().outputs().getFirst();
        assertEquals("lesson-copy", output.entityRef());
        assertEquals(new StoryPatch.QuantityValue(true, 1), output.amount());
        assertEquals("本", output.unit());
        assertEquals(StoryPatch.HolderType.PLAYER, output.custody().holderType());
        assertEquals(List.of(0), experimental.orderedResult().steps().getFirst().assetStepIndexes());
        assertEquals("teacher-existing", experimental.orderedResult().steps().getFirst()
                .statements().getFirst().sourceRef());
        assertEquals(new SemanticDuration(SemanticDuration.Unit.PHASE, 1, "午后"), experimental.duration());
    }

    @Test
    void preservesAllExistingStockAndNormalizesSceneTimeLikeProduction() throws Exception {
        var player = new TurnDirectorAi.CustodyDraft("PLAYER", null, null);
        var exchange = new TurnDirectorAi.AssetProcessDraft(List.of(new TurnDirectorAi.AssetStepDraft(
                AssetProcess.Kind.EXCHANGE, "把持有药草全部卖出，收取五十四文。",
                List.of(new TurnDirectorAi.FlowDraft("herbs-existing", "ALL", "株", player)),
                List.of(new TurnDirectorAi.FlowDraft("sale-coins", "54", "文", player)), List.of())));
        var draft = new TurnDirectorAi.DecisionDraft(
                new TurnDirectorAi.DurationDraft(TurnDirectorAi.DurationUnit.SCENE, 0, "不应带入场景时间"),
                TurnDirectorAi.TimeResolution.COMPLETE, "", new TurnDirectorAi.CoreStateDraft(" 药庄 ", " ", null),
                null, List.of(new TurnDirectorAi.ItemDraft(null, "sale-coins", "铜钱", "ACTIVE", "售药所得", List.of(), true)),
                List.of(), List.of(), List.of(), exchange,
                new TurnDirectorAi.UnderstandingDraft("按已接受条件出售药草", List.of()),
                ordered("出售已持有的全部药草", "全部药草交付，收取五十四文。", List.of()));
        var calls = new AtomicInteger();
        var experimental = SingleCandidateProtocol.toDecision(draft);

        assertEquals(formalPlan(draft, calls), experimental);
        assertEquals(1, calls.get());
        assertEquals(new SemanticDuration(SemanticDuration.Unit.SCENE, 1, ""), experimental.duration());
        assertEquals("药庄", experimental.coreState().location());
        assertNull(experimental.coreState().condition());
        var step = experimental.assetProcess().steps().getFirst();
        assertEquals("herbs-existing", step.inputs().getFirst().entityRef());
        assertTrue(step.inputs().getFirst().all());
        assertNull(step.inputs().getFirst().amount(), "ALL 保留为领域标记，不在适配时猜库存");
        assertEquals("sale-coins", step.outputs().getFirst().entityRef());
        assertEquals(new StoryPatch.QuantityValue(true, 54), step.outputs().getFirst().amount());
    }

    @Test
    void rejectsAbilityInOtherEntityGroupLikeProduction() throws Exception {
        var base = lesson();
        var wrongGroup = withChanges(base, base.itemChanges(), List.of(),
                base.abilityChanges(), base.assetProcess());
        assertSameAdaptationFailure(wrongGroup, "物品、功法与其他实体必须填写在各自变化分组");
    }

    @Test
    void rejectsMissingAssetStepsLikeProductionWithoutRepairingOrInferringThem() throws Exception {
        var base = lesson();
        var malformed = withChanges(base, base.itemChanges(), base.abilityChanges(), base.entities(),
                new TurnDirectorAi.AssetProcessDraft(null));
        assertSameAdaptationFailure(malformed, "assetProcess");
    }

    private void assertSameAdaptationFailure(TurnDirectorAi.DecisionDraft draft, String messagePart) {
        var calls = new AtomicInteger();
        var experimental = assertThrows(IllegalArgumentException.class,
                () -> SingleCandidateProtocol.toDecision(draft));
        var formal = assertThrows(DirectorProtocolException.class, () -> formalPlan(draft, calls));
        assertEquals(DirectorProtocolException.Phase.ADAPTATION, formal.phase());
        assertEquals(experimental.getClass(), formal.getCause().getClass());
        assertEquals(experimental.getMessage(), formal.getCause().getMessage());
        assertTrue(experimental.getMessage().contains(messagePart), experimental.getMessage());
        assertEquals(draft, formal.rejectedDraft());
        assertEquals(1, calls.get(), "Session.plan 只拒绝本候选，没有擅自调用修复");
        assertEquals(1, formal.modelCalls().size());
    }

    private StoryTurnDecision formalPlan(TurnDirectorAi.DecisionDraft draft, AtomicInteger calls) throws Exception {
        String raw = mapper.writeValueAsString(draft);
        ChatModelFactory factory = (definition, credential, options) -> new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                String context = request.messages().stream().filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast).reduce((first, last) -> last).orElseThrow().singleText();
                try {
                    assertEquals("PLAN_CURRENT_ACTION", mapper.readTree(context).path("mode").asText());
                } catch (java.io.IOException error) {
                    throw new AssertionError("正式导演请求必须保留可读的 JSON 上下文", error);
                }
                return ChatResponse.builder().aiMessage(AiMessage.from(raw)).build();
            }
        };
        var definition = new ModelDefinition("OFFLINE", "FIXED_DRAFT", "http://offline.invalid/v1",
                ModelAdapterType.OPENAI_COMPATIBLE, "fixed-draft", Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE),
                32000, 4000);
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(CredentialSource.PLATFORM,
                List.of(new ModelRouteCandidate(definition, ModelCredential.platform("offline-placeholder"),
                        new ModelInvocationOptions(0.0, Duration.ofSeconds(5), 0, 4000, false))));
        var router = new ModelTaskRouter(policy);
        var generator = new LangChain4jDirectedNarrativeGenerator(router,
                new ModelInvocationService(router, new ChatModelRegistry(factory, 2)),
                new StoryAiServiceRegistry(new AiServiceRegistry(new PromptResourceLoader(), 2)), mapper);
        return generator.open(new DirectedNarrativeGenerator.InvocationContext("offline-user", "offline-request", "offline-save", 2))
                .plan(context()).decision();
    }

    private TurnDirectorAi.DecisionDraft lesson() {
        var paper = new TurnDirectorAi.ItemDraft(null, "lesson-copy", "调息抄本", "ACTIVE", "授课时交付的抄本",
                List.of(new TurnDirectorAi.AttributeDraft("材质", "纸")), true);
        var ability = new TurnDirectorAi.EntityDraft("ability-existing", null, "ABILITY", "调息法", "ACTIVE",
                "已会静坐调息，刚学会缓步时放慢呼气；快跑尚未验证。", null, List.of(), null, null,
                "learning", null, null, true);
        var teacher = new TurnDirectorAi.EntityDraft("teacher-existing", null, "CHARACTER", "授课散修", "ACTIVE",
                "当面传授调息并交付抄本", "present", List.of(new TurnDirectorAi.AttributeDraft("identity", "授课散修")),
                null, null, "", new TurnDirectorAi.LifecycleDraft("ACTIVE", "本轮在场"),
                new TurnDirectorAi.RelationshipDraft("指点者", "本轮指点缓步调息"), true);
        var assets = new TurnDirectorAi.AssetProcessDraft(List.of(new TurnDirectorAi.AssetStepDraft(
                AssetProcess.Kind.ACQUIRE, "散修交付一本调息抄本", List.of(),
                List.of(new TurnDirectorAi.FlowDraft("lesson-copy", "1", "本",
                        new TurnDirectorAi.CustodyDraft("PLAYER", null, null))), List.of())));
        return new TurnDirectorAi.DecisionDraft(new TurnDirectorAi.DurationDraft(TurnDirectorAi.DurationUnit.PHASE, 1, " 午后 "),
                TurnDirectorAi.TimeResolution.COMPLETE, "", new TurnDirectorAi.CoreStateDraft("廊下", "平静", "炼气中期"),
                null, List.of(paper), List.of(ability), List.of(teacher), List.of(), assets,
                new TurnDirectorAi.UnderstandingDraft("收下抄本并学习缓步调息", List.of("不主动尝试突破")),
                ordered("收下抄本并按指点练习", "收到一本抄本，学习缓步调息的动作；尚未验证快跑。",
                        List.of(new OrderedTurnResult.Statement("teacher-existing", "授课散修", "先放慢呼气，再缓步行走。"))));
    }

    private OrderedTurnResult.Proposal ordered(String action, String result, List<OrderedTurnResult.Statement> statements) {
        return new OrderedTurnResult.Proposal(List.of(new OrderedTurnResult.Step(action,
                OrderedTurnResult.Resolution.SUCCEEDED, result, false, List.of(0), List.of(), statements)),
                new OrderedTurnResult.Stop(OrderedTurnResult.StopReason.ACTION_RESOLVED, "本轮行动到此结束"));
    }

    private TurnDirectorAi.DecisionDraft withChanges(TurnDirectorAi.DecisionDraft base, List<TurnDirectorAi.ItemDraft> items,
                                                     List<TurnDirectorAi.EntityDraft> abilities,
                                                     List<TurnDirectorAi.EntityDraft> entities,
                                                     TurnDirectorAi.AssetProcessDraft assets) {
        return new TurnDirectorAi.DecisionDraft(base.duration(), base.timeResolution(), base.interruptionReason(),
                base.coreState(), base.cultivation(), items, abilities, entities, base.worldFacts(), assets,
                base.actionUnderstanding(), base.orderedResult());
    }

    private StoryPromptContext context() {
        return new StoryPromptContext(new StoryPromptContext.SystemContext(List.of("已确认事实不可改写"), List.of()),
                new StoryPromptContext.ObjectiveContext(StoryWorldTime.opening(),
                        new CoreState("廊下", "平静", "炼气中期", "求学"), List.of(), List.of(), List.of()),
                new StoryPromptContext.CognitionContext(List.of()), new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("你来到廊下，请散修指点。", null),
                new StoryPromptContext.ActionContext("收下抄本并学习缓步调息，不主动突破。"));
    }
}
