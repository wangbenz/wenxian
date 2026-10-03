package com.wenxian.game.story.replay.slim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.model.application.ModelInvocationService;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.infrastructure.ChatModelFactory;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.ai.DirectorProtocolException;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator;
import com.wenxian.game.story.agent.ai.NarrativeEndState;
import com.wenxian.game.story.domain.*;
import com.wenxian.game.story.worldpackage.domain.WorldContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 固定 ChatModel 响应走真实 AiServices，检查实际 schema、请求分区、失败用量和单次调用边界。 */
class SlimNarrativeGeneratorTest {
    private static final String TEACHING = "先松肩再缓缓呼气；只在静坐有效，走动时还未验证。";
    private static final String VALID = """
            {"duration":{"unit":"SCENE","value":1},"timeResolution":"COMPLETE",
             "cultivation":{"progressGained":false,"breakthroughAttempted":false,"reason":"本轮无数值进展","evidenceRefs":[]},
             "itemChanges":[],"abilityChanges":[],"entities":[],"worldFacts":[],
             "orderedResult":{"steps":[{"action":"听指点后停下","resolution":"SUCCEEDED","result":"记住了松肩缓呼气的方法",
              "realmProgressBinding":false,"dependsOn":[],"statements":[],"assets":[]}],"stop":{"reason":"ACTION_RESOLVED"}}}
            """;
    private static final String PROSE = """
            {"title":"","narrative":"你按指点松开肩背，随后停下。","choices":[],"summary":"你记住了掌柜的指点。"}
            """;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void actualTypedSchemaNestsAssetsAndKeepsAbilityFieldsNarrow() {
        Capture model = new Capture(VALID);
        var result = session(model).plan(context());
        assertNull(result.decision().actionUnderstanding());
        JsonObjectSchema schema = (JsonObjectSchema) model.requests.getFirst().parameters().responseFormat().jsonSchema().rootElement();
        assertFalse(schema.properties().containsKey("actionUnderstanding"));
        assertFalse(schema.properties().containsKey("assetProcess"));
        JsonObjectSchema step = (JsonObjectSchema) ((JsonArraySchema) ((JsonObjectSchema) schema.properties()
                .get("orderedResult")).properties().get("steps")).items();
        assertTrue(step.properties().containsKey("assets"));
        assertTrue(step.properties().containsKey("realmProgressBinding"));
        assertFalse(step.properties().containsKey("assetStepIndexes"));
        assertFalse(step.properties().containsKey("realmResult"));
        JsonObjectSchema ability = (JsonObjectSchema) ((JsonArraySchema) schema.properties().get("abilityChanges")).items();
        assertEquals(Set.of("existingEntityId", "localRef", "name", "status", "summary", "attributes", "abilityState", "visible"),
                ability.properties().keySet());
        assertTrue(model.requests.getFirst().messages().stream().filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast).anyMatch(message -> message.text().contains("slim-turn-director-v1")));
        assertEquals(31, result.modelCalls().getFirst().inputTokens());
        assertEquals(7, result.modelCalls().getFirst().outputTokens());
        assertEquals("DEEPSEEK", result.modelCalls().getFirst().providerCode());
    }

    @Test
    void adaptationRepairRetainsOriginalSlimDraftKnownUsageAndFrozenInput() throws Exception {
        ObjectNode invalid = (ObjectNode) mapper.readTree(VALID);
        invalid.putNull("itemChanges");
        Capture model = new Capture(invalid.toString(), VALID);
        var session = session(model);
        var error = assertThrows(DirectorProtocolException.class, () -> session.plan(context()));
        assertEquals(DirectorProtocolException.Phase.ADAPTATION, error.phase());
        assertInstanceOf(SlimTurnProtocol.Draft.class, error.rejectedDraft());
        assertEquals(31, error.modelCalls().getFirst().inputTokens());
        session.repairProtocol(context(), error);
        JsonNode first = input(model.requests.getFirst());
        JsonNode repaired = input(model.requests.getLast());
        for (String field : List.of("objective", "action", "worldContext", "cognition", "memory", "narrative"))
            assertEquals(first.path(field), repaired.path(field), field);
        assertEquals("REPAIR_REJECTED_DECISION", repaired.path("mode").asText());
        assertTrue(repaired.path("rejectedDraft").path("itemChanges").isNull());
        assertFalse(repaired.path("rejectedDraft").has("assetProcess"));
        assertFalse(repaired.path("rejectedDraft").has("actionUnderstanding"));
        assertEquals(2, model.requests.size(), "每次端口调用只发出一次请求，由prepare控制总修复预算");
    }

    @Test
    void compilationRepairReconstructsNestedAssetsInsteadOfLeakingInternalIndexes() throws Exception {
        ObjectNode valid = (ObjectNode) mapper.readTree(VALID);
        ((ObjectNode) valid.at("/orderedResult/steps/0")).withArray("assets").add(mapper.readTree("""
                {"kind":"CONSUME","summary":"用掉一粒药","inputs":[{"entityRef":"pill","amount":"1","unit":"粒"}],"outputs":[],"observations":[]}
                """));
        Capture model = new Capture(valid.toString(), VALID);
        var session = session(model);
        var first = session.plan(context());
        assertEquals(List.of(0), first.decision().orderedResult().steps().getFirst().assetStepIndexes());
        session.repair(context(), first.decision(), "assetProcess.steps[0].inputs[0] 已知药仅一粒，不能消费两粒");
        JsonNode repairInput = input(model.requests.getLast());
        assertTrue(repairInput.path("validationProblem").asText().startsWith("orderedResult.steps[0].assets[0].inputs[0]"));
        JsonNode repaired = repairInput.path("rejectedDraft");
        assertTrue(repaired.at("/orderedResult/steps/0/assets/0").isObject());
        assertFalse(repaired.at("/orderedResult/steps/0").has("assetStepIndexes"));
        assertFalse(repaired.has("assetProcess"));
    }

    @Test
    void decodingFailureKeepsAnAttemptWithoutInventingUnavailableRawContent() {
        Capture model = new Capture("{broken-json");
        var error = assertThrows(DirectorProtocolException.class, () -> session(model).plan(context()));
        assertEquals(DirectorProtocolException.Phase.DECODING, error.phase());
        assertNull(error.rejectedDraft(), "typed AiServices未暴露解析失败的原件，不能从异常文字补造");
        assertEquals(1, error.modelCalls().size());
        assertEquals("FAILED", error.modelCalls().getFirst().status());
        assertEquals(1, model.requests.size());
    }

    @Test
    void networkOrPermissionFailureDoesNotBecomeNarrativeRepair() {
        Capture model = new Capture(new IllegalStateException("offline simulated credential rejection"));
        var error = assertThrows(ModelInvocationService.ModelInvocationException.class, () -> session(model).plan(context()));
        assertEquals(1, error.attempts().size());
        assertEquals(1, model.requests.size());
    }

    @Test
    void writerSeesOneAcceptedResultAndFullProcessTeachingLimitsAndTimeWithoutThePatch() throws Exception {
        Capture model = new Capture(PROSE);
        var result = session(model).render(context(), plan(), end());
        assertEquals("此间经历", result.title(), "空标题本地降级，不另调模型");
        assertEquals(31, result.modelCalls().getFirst().inputTokens());
        JsonNode input = input(model.requests.getFirst());
        assertEquals(mapper.valueToTree(accepted()), input.path("acceptedResult"));
        assertEquals(mapper.valueToTree(process()), input.path("assetProcess"));
        assertTrue(input.path("after").path("inventory").isEmpty(), "同轮取得后用完，最终行囊为空但过程不可删");
        assertEquals(TEACHING, input.at("/acceptedResult/steps/0/statements/0/statement").asText());
        assertEquals("teacher", input.at("/acceptedResult/steps/0/statements/0/sourceRef").asText());
        assertEquals(TEACHING, input.at("/after/abilities/0/summary").asText());
        assertEquals("LEARNING", input.at("/after/abilities/0/abilityState").asText());
        assertTrue(input.at("/after/otherChangedEntities").isEmpty(), "已有完整能力记录不重复作为变化实体传送");
        assertEquals("传闻尚未证实", input.at("/selected/cognition/entries/0/summary").asText());
        assertEquals("门今日开放", input.at("/before/worldFacts/0/statement").asText());
        assertEquals("门已关闭", input.at("/after/worldFactChanges/0/statement").asText());
        assertEquals("UNTRUSTED_WORLD_BACKGROUND", input.path("worldContext").path("trustLevel").asText());
        assertEquals(input.path("timeBoundary").path("start"), input.path("timeBoundary").path("end"));
        assertEquals(1, input.at("/continuity/recentTurns").size());
        assertEquals(mapper.valueToTree(prior()), input.at("/continuity/recentTurns/0/acceptedResult"));
        assertFalse(input.at("/continuity/recentTurns/0").has("legacyAcceptedPatchSummary"));
        assertFalse(input.has("acceptedPlan"));
        assertFalse(input.has("directorActionUnderstanding"));
        assertFalse(input.has("narrativeContext"));
        assertFalse(input.path("before").has("entityIndex"));
        assertEquals(context().action().playerAction(), input.at("/action/playerAction").asText());
    }

    @Test
    void emptyWriterBodyFailsWithoutRepairAndRetainsTheCompletedCallUsage() {
        Capture model = new Capture("{\"title\":\"\",\"narrative\":\"  \",\"choices\":[],\"summary\":\"\"}");
        var error = assertThrows(ModelInvocationService.ModelInvocationException.class,
                () -> session(model).render(context(), plan(), end()));
        assertEquals("SLIM_WRITER_OUTPUT_INVALID", error.code());
        assertEquals(31, error.attempts().getFirst().inputTokens());
        assertEquals(7, error.attempts().getFirst().outputTokens());
        assertEquals(1, model.requests.size());
    }

    @Test
    void baselineAndCandidateUseTheSameFixedProviderOptions() throws Exception {
        Capture slim = new Capture(VALID, PROSE);
        var candidate = session(slim);
        candidate.plan(context()); candidate.render(context(), plan(), end());
        ObjectNode baselineDraft = (ObjectNode) mapper.readTree(VALID);
        baselineDraft.putObject("actionUnderstanding").put("purpose", "听指点").putArray("constraints");
        baselineDraft.putObject("assetProcess").putArray("steps");
        ((ObjectNode) baselineDraft.at("/orderedResult/steps/0")).remove("assets");
        ((ObjectNode) baselineDraft.at("/orderedResult/steps/0")).putArray("assetStepIndexes");
        Capture baseline = new Capture(baselineDraft.toString(), PROSE.replace("\"title\":\"\"", "\"title\":\"听指点\""));
        var production = SlimNarrativeGenerator.createBaseline(mapper, baseline.factory(), "http://127.0.0.1:26001/v1", "offline-placeholder")
                .open(invocation());
        production.plan(context()); production.render(context(), plan(), end());
        assertEquals(slim.options, baseline.options);
        assertEquals(slim.definitions, baseline.definitions);
        assertEquals(List.of(2200, 3200), slim.options.stream().map(ModelInvocationOptions::maximumOutputTokens).toList());
        assertEquals(List.of(0.3, 0.8), slim.options.stream().map(ModelInvocationOptions::temperature).toList());
        assertTrue(slim.options.stream().allMatch(o -> o.maximumRetries() == 0 && o.timeout().equals(Duration.ofSeconds(90))));
        assertTrue(slim.definitions.stream().allMatch(d -> "deepseek-v4-flash".equals(d.apiModelName())));
    }

    private DirectedNarrativeGenerator.Session session(Capture model) {
        return SlimNarrativeGenerator.create(mapper, model.factory(), "http://127.0.0.1:26001/v1", "offline-placeholder").open(invocation());
    }
    private DirectedNarrativeGenerator.InvocationContext invocation() { return new DirectedNarrativeGenerator.InvocationContext("u", "r", "s", 2); }
    private JsonNode input(ChatRequest request) throws IOException {
        return mapper.readTree(request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .reduce((a, b) -> b).orElseThrow().singleText());
    }
    private StoryPromptContext context() {
        var previous = new StoryPromptContext.PreviousTurn(1, "询问是否愿意指点", "遇见掌柜", "掌柜点头。", "不用重复此摘要", prior());
        var objective = new StoryPromptContext.ObjectiveContext(StoryWorldTime.opening(),
                new StoryProjection.CoreState("药庄", "平静", "炼气初期", "问道"),
                List.of(new StoryPromptContext.WorldFactView("gate", "门今日开放", 1)), List.of(), List.of());
        var world = new WorldContext(new WorldContext.Source("r2", "official", "XUANXU", 2, "hash", "compiled", "world-v1"),
                "fixed", List.of(new WorldContext.Material("lore:teacher", "LORE", mapper.createObjectNode().put("text", "师承有别"), List.of("selected"))),
                1, 0, 30, 100, List.of());
        return new StoryPromptContext(new StoryPromptContext.SystemContext(List.of(), List.of()), objective,
                new StoryPromptContext.CognitionContext(List.of(new StoryPromptContext.KnowledgeView("PLAYER", "rumor", "传闻尚未证实", List.of()))),
                new StoryPromptContext.MemoryContext(List.of(new StoryPromptContext.MemoryView("m1", "CLUE", "掌柜说法", "掌柜愿意指点", "NORMAL", List.of()))),
                new StoryPromptContext.NarrativeContext("你来到药庄。", previous, List.of(previous)),
                new StoryPromptContext.ActionContext("请掌柜指点，收下赠药后服用；若要额外付钱就先停下确认。"), world);
    }
    private OrderedTurnResult.Accepted prior() {
        return new OrderedTurnResult.Accepted(2, List.of(new OrderedTurnResult.AcceptedStep(0, "询问指点", OrderedTurnResult.Resolution.SUCCEEDED,
                "掌柜愿意指点", false, List.of(), List.of(), List.of())), new OrderedTurnResult.Stop(OrderedTurnResult.StopReason.ACTION_RESOLVED, "此事结束"));
    }
    private OrderedTurnResult.Accepted accepted() {
        return new OrderedTurnResult.Accepted(2, List.of(new OrderedTurnResult.AcceptedStep(0, "听指点并服用赠药", OrderedTurnResult.Resolution.SUCCEEDED,
                "掌柜说明：" + TEACHING + "你收下赠药后服用完。", false, List.of(0, 1), List.of(),
                List.of(new OrderedTurnResult.Statement("teacher", "掌柜", TEACHING)))),
                new OrderedTurnResult.Stop(OrderedTurnResult.StopReason.ACTION_RESOLVED, "本轮行动结束于以上已记录的结果。"));
    }
    private StoryPatch plan() {
        return new StoryPatch("", "", new StoryPatch.TimePassage(new SemanticDuration(SemanticDuration.Unit.SCENE, 1, ""),
                StoryPatch.TimeResolution.COMPLETE, null), null, List.of(),
                List.of(new StoryPatch.WorldFactPatch(StoryPatch.WorldFactOperation.TRANSFORM, "gate", "门已关闭", "店铺歇业")),
                List.of(), List.of(), accepted().checkpoint(), List.of(), "", accepted().outcome());
    }
    private NarrativeEndState end() {
        var ability = new StoryProjection.EntityState("ability-1", "ABILITY", "调息口诀", "ACTIVE", TEACHING,
                mapper.createObjectNode().put("source", "掌柜"), true, null, null, StoryPatch.AbilityState.LEARNING, null);
        return new NarrativeEndState(StoryWorldTime.opening(), context().objective().protagonist(), null,
                List.of(), List.of(ability), List.of(ability), process(), null, accepted());
    }
    private AssetProcess.Accepted process() {
        var zero = new StoryPatch.QuantityValue(true, 0);
        var one = new StoryPatch.QuantityValue(true, 1);
        var held = new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null);
        var gain = new AssetProcess.Change("pill-1", "赠药", one, false, "粒", zero, one, null, held, null);
        var spent = new AssetProcess.Change("pill-1", "赠药", one, false, "粒", one, zero, held, held, null);
        return new AssetProcess.Accepted(1, List.of(new AssetProcess.AcceptedStep("1", AssetProcess.Kind.ACQUIRE, "获得赠药", List.of(), List.of(gain), List.of()),
                new AssetProcess.AcceptedStep("2", AssetProcess.Kind.CONSUME, "服用完赠药", List.of(spent), List.of(), List.of())));
    }
    private static final class Capture {
        private final List<Object> responses;
        private final List<ChatRequest> requests = new ArrayList<>();
        private final List<ModelInvocationOptions> options = new ArrayList<>();
        private final List<ModelDefinition> definitions = new ArrayList<>();
        private Capture(Object... responses) { this.responses = List.of(responses); }
        private ChatModelFactory factory() {
            return (definition, credential, invocationOptions) -> {
                definitions.add(definition); options.add(invocationOptions);
                return new ChatModel() {
                    public Set<Capability> supportedCapabilities() { return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA); }
                    public ChatResponse doChat(ChatRequest request) {
                        int ordinal = requests.size(); requests.add(request);
                        Object response = responses.get(ordinal);
                        if (response instanceof RuntimeException error) throw error;
                        return ChatResponse.builder().aiMessage(AiMessage.from((String) response)).tokenUsage(new TokenUsage(31, 7)).build();
                    }
                };
            };
        }
    }
}
