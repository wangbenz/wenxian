package com.wenxian.game.story.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.model.application.*;
import com.wenxian.game.model.domain.*;
import com.wenxian.game.model.infrastructure.ChatModelFactory;
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import com.wenxian.game.model.infrastructure.PromptResourceLoader;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.InvocationContext;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryRecords;
import com.wenxian.game.story.domain.StoryWorldTime;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 正式模型端口的离线验证：真实 AI Service/路由/授权/用量链，内存供应商，不调用数据库或网络。 */
class LangChain4jStoryCandidateGeneratorTest {
    private static final ServicePlanSelection PLAN = new ServicePlanSelection("frozen-plan", "STANDARD", 7);
    private static final ModelInvocationOptions OPTIONS = new ModelInvocationOptions(0.61, Duration.ofSeconds(47), 0, 777, true);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void freezesOnlyOneTaskAndReusesItsPlanRouteCredentialAndAccountingForExplicitRepair() throws Exception {
        Harness harness = new Harness(CredentialSource.PLATFORM, (call, request) -> candidate().toString());
        var session = harness.generator.open(invocation("user-1", "request-1"));
        assertEquals(0, harness.inputs.size(), "先冻结方案供额度预占，不提前请求模型");
        assertEquals(Optional.of(PLAN), session.servicePlanSelection());
        assertEquals(CredentialSource.PLATFORM, session.credentialSource());
        var first = session.generate(context("观察石门后停下", null), List.of(), null, null);
        var rejected = new DirectorProtocolException(DirectorProtocolException.Phase.COMPILATION,
                first.rawResponse(), List.of(), "测试同一原行动的完整修复", null);
        var repaired = session.generate(context("观察石门后停下", null), List.of(), rejected, null);

        verify(harness.plans, times(1)).freezeForUser("user-1");
        assertEquals(1, harness.routed.size());
        assertEquals(ModelTask.TURN_DIRECTOR, harness.routed.getFirst().task());
        assertSame(PLAN, harness.routed.getFirst().servicePlanSelection());
        assertEquals(2, harness.authorized.size());
        assertTrue(harness.authorized.stream().allMatch(request -> request == harness.routed.getFirst()));
        assertEquals(2, harness.inputs.size());
        assertEquals(2, harness.listenerRequests.get(), "包装不能重复调用原模型监听器");
        assertEquals(1, harness.createdModels.get(), "底层统一模型注册表仍复用同一路由模型");
        assertEquals("REPAIR_COMPLETE_CANDIDATE", harness.inputs.getLast().path("mode").asText());
        assertEquals(first.rawResponse(), harness.inputs.getLast().path("rejectedDraft").asText());
        assertEquals(1, first.calls().size());
        assertEquals(1, repaired.calls().size());
        assertEquals(11, first.calls().getFirst().inputTokens());
        assertEquals(12, repaired.calls().getFirst().inputTokens());
        assertEquals(3, repaired.calls().getFirst().cacheHitTokens());
        assertEquals(9, repaired.calls().getFirst().cacheMissTokens());
        assertTrue(harness.observed.stream().allMatch(value -> "TURN_DIRECTOR".equals(value.taskCode())
                && "PLATFORM".equals(value.credentialSource()) && "SUCCESS".equals(value.status())));
        assertEquals(2, harness.observed.size());
        assertNull(first.adapted().decision().outcome());
        assertNull(first.adapted().decision().orderedResult());
        assertNull(first.adapted().decision().actionUnderstanding());
        assertTrue(harness.requests.stream().allMatch(request -> request.parameters().maxOutputTokens() == 777
                && request.parameters().temperature() == 0.61), "保留正式冻结参数，不能硬改为实验配置");
        assertTrue(harness.requests.stream().allMatch(request -> request.parameters().responseFormat().jsonSchema() != null),
                "保留 delegate 的结构化输出能力");
    }

    @Test
    void personalCredentialSourceIsPreservedWithoutFreezingAPlatformPrice() throws Exception {
        Harness harness = new Harness(CredentialSource.USER, (call, request) -> candidate().toString());
        var session = harness.generator.open(invocation("personal-user", "personal-request"));
        assertEquals(CredentialSource.USER, session.credentialSource());
        assertTrue(session.servicePlanSelection().isEmpty());
        var generated = session.generate(context("在门外观察", null), List.of(), null, null);
        assertEquals("USER", generated.calls().getFirst().credentialSource());
        assertNull(harness.routed.getFirst().servicePlanSelection());
        assertFalse(harness.routed.getFirst().allowPlatformFallback());
        assertEquals("USER", harness.observed.getFirst().credentialSource());
        assertEquals(1, harness.authorized.size());
    }

    @Test
    void aTypedFailureKeepsItsRawUsageAndCannotLeakIntoAnotherSessionOrItsRepair() throws Exception {
        ObjectNode bad = candidate();
        ((ObjectNode) bad.path("changes")).putObject("coreState").put("condition", 9);
        String raw = bad.toString();
        Harness harness = new Harness(CredentialSource.PLATFORM, (call, request) -> call == 1 ? raw : candidate().toString());
        var first = harness.generator.open(invocation("user-1", "request-a"));
        DirectorProtocolException failure = assertThrows(DirectorProtocolException.class,
                () -> first.generate(context("第一位玩家观察", null), List.of(), null, null));
        assertEquals(DirectorProtocolException.Phase.DECODING, failure.phase());
        assertEquals(raw, failure.rejectedDraft());
        assertEquals(11, failure.modelCalls().getFirst().inputTokens());
        assertEquals(7, failure.modelCalls().getFirst().outputTokens());
        assertEquals(3, failure.modelCalls().getFirst().cacheHitTokens());
        assertFalse(failure.getMessage().contains("narrative"), "诊断不拼入原始模型全文");
        assertEquals(1, harness.inputs.size(), "生成器本身不启动协议修复");
        var second = harness.generator.open(invocation("user-2", "request-b"));
        var independent = second.generate(context("第二位玩家查看", null), List.of(), null, null);
        assertFalse(harness.inputs.get(1).has("rejectedDraft"));
        assertEquals(12, independent.calls().getFirst().inputTokens());
        var repaired = first.generate(context("第一位玩家观察", null), List.of(), failure, null);
        assertEquals(raw, harness.inputs.getLast().path("rejectedDraft").asText());
        assertEquals("第一位玩家观察", harness.inputs.getLast().path("action").path("playerAction").asText());
        assertEquals(13, repaired.calls().getFirst().inputTokens());
        assertEquals(List.of("request-a", "request-b", "request-a"), harness.authorized.stream().map(ModelRouteRequest::requestId).toList());
        assertTrue(harness.systemMessages.stream().noneMatch(value -> value.contains(raw)), "坏原稿只能作为修复数据，不能进入系统 Prompt");
    }

    @Test
    void auxiliaryDowngradesKeepOriginalAndCannotRemoveTheDecisionStateOrRecords() throws Exception {
        ObjectNode raw = candidate();
        ObjectNode story = (ObjectNode) raw.path("story");
        story.put("title", false).put("choices", "随便行动").putArray("summary").add("辅助摘要");
        Harness harness = new Harness(CredentialSource.PLATFORM, (call, request) -> raw.toString());
        var generated = harness.generator.open(invocation("user", "request")).generate(context("看门", null), List.of(), null, null);
        assertEquals(raw.toString(), generated.rawResponse());
        assertEquals("此间经历", generated.draft().story().title());
        assertEquals(List.of(), generated.draft().story().choices());
        assertEquals("", generated.draft().story().summary());
        assertEquals(List.of("story.title", "story.choices", "story.summary"),
                generated.downgrades().stream().map(StoryCandidateProtocol.Downgrade::field).toList());
        assertEquals("是否继续查看石纹？", generated.draft().story().decision());
        assertTrue(generated.adapted().decision().decisionCheckpoint().required());
        ObjectNode normalizedChanges = mapper.valueToTree(generated.draft().changes());
        assertEquals(raw.path("changes"), normalizedChanges.retain("duration", "timeResolution"));
        assertEquals(raw.path("records"), mapper.valueToTree(generated.draft().records()));
        assertEquals(1, harness.inputs.size());
    }

    @Test
    void independentBadFieldsAndLegacyCommandsAreReportedTogetherWithoutAutomaticRepair() throws Exception {
        ObjectNode raw = candidate();
        ((ObjectNode) raw.path("changes")).putObject("coreState").put("condition", true);
        raw.put("records", "没有记录");
        raw.putObject("orderedResult").putArray("steps");
        Harness harness = new Harness(CredentialSource.PLATFORM, (call, request) -> raw.toString());
        var session = harness.generator.open(invocation("user", "request"));
        var error = assertThrows(DirectorProtocolException.class,
                () -> session.generate(context("观察", null), List.of(), null, null));
        assertTrue(error.getMessage().contains("coreState.condition"));
        assertTrue(error.getMessage().contains("records"));
        assertTrue(error.getMessage().contains("orderedResult"));
        assertEquals(raw.toString(), error.rejectedDraft());
        assertEquals(1, error.modelCalls().size());
        assertEquals(1, harness.inputs.size());
    }

    @Test
    void subsequentTransportFailureDoesNotReuseEarlierRawOrBecomeProtocolRepair() throws Exception {
        Harness harness = new Harness(CredentialSource.PLATFORM, (call, request) -> {
            if (call == 2) throw new IllegalStateException("offline timeout", new SocketTimeoutException("offline"));
            return candidate().toString();
        });
        var session = harness.generator.open(invocation("user", "request"));
        session.generate(context("先观察", null), List.of(), null, null);
        var error = assertThrows(ModelInvocationService.ModelInvocationException.class,
                () -> session.generate(context("继续观察", null), List.of(), null, null));
        assertEquals(2, harness.inputs.size());
        assertEquals(1, error.attempts().size());
        assertEquals(0, error.attempts().getFirst().inputTokens());
        assertEquals(0, error.attempts().getFirst().outputTokens());
        assertEquals("FAILED", error.attempts().getFirst().status());
        assertFalse(harness.inputs.getLast().has("rejectedDraft"));
    }

    @Test
    void sendsNewHistoryOnceAndKeepsReadonlyOldResultWithoutMutatingContext() throws Exception {
        StoryRecords.Envelope records = new StoryRecords.Envelope(1, "turn-3", 3, 0, 1,
                List.of(new StoryRecords.StampedValue("turn-3-record-1", "STATEMENT", "只从外侧观察，别急着推门。", "npc-1", "守门人", List.of())));
        OrderedTurnResult.Accepted old = new OrderedTurnResult.Accepted(1,
                List.of(new OrderedTurnResult.AcceptedStep(0, "询问守门人", OrderedTurnResult.Resolution.SUCCEEDED,
                        "听到了提醒", false, List.of(), List.of(), List.of())),
                new OrderedTurnResult.Stop(OrderedTurnResult.StopReason.ACTION_RESOLVED, "询问完毕"));
        var prior = new StoryPromptContext.PreviousTurn(3, "请教如何查看", "门前", "守门人作了提醒。", "听到提醒", old, records);
        var context = context("照先前提醒查看", prior);
        Harness harness = new Harness(CredentialSource.PLATFORM, (call, request) -> candidate().toString());
        harness.generator.open(invocation("user", "request")).generate(context, List.of(records), null, null);
        JsonNode actual = harness.inputs.getFirst();
        assertEquals(mapper.readTree(mapper.writeValueAsString(List.of(records))), actual.path("recordHistory"));
        assertFalse(actual.path("narrative").path("previousTurn").has("storyRecords"));
        assertFalse(actual.path("narrative").path("recentTurns").get(0).has("storyRecords"));
        assertEquals(mapper.valueToTree(old), actual.path("narrative").path("previousTurn").path("acceptedResult"));
        assertSame(records, context.narrative().previousTurn().storyRecords());
        assertEquals(1, actual.path("recordHistory").size());
    }

    private ObjectNode candidate() throws IOException {
        return (ObjectNode) mapper.readTree("""
                {"story":{"title":"石门外","narrative":"你在门前查看石纹，没有推门，随后停了下来。",
                  "decision":"是否继续查看石纹？","choices":[],"summary":"在门外观察后停下。"},
                 "changes":{"duration":{"unit":"SCENE","value":1,"targetPhase":null},"timeResolution":"COMPLETE"},
                 "records":[]}
                """);
    }

    private InvocationContext invocation(String user, String request) { return new InvocationContext(user, request, "save-1", 4); }

    private StoryPromptContext context(String action, StoryPromptContext.PreviousTurn prior) {
        return new StoryPromptContext(new StoryPromptContext.SystemContext(List.of("已确认事实不可改写"), List.of()),
                new StoryPromptContext.ObjectiveContext(StoryWorldTime.opening(),
                        new StoryProjection.CoreState("石门前", "平静", "引气", "问道"), List.of(), List.of(), List.of()),
                new StoryPromptContext.CognitionContext(List.of()), new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("你来到石门前。", prior), new StoryPromptContext.ActionContext(action));
    }

    @FunctionalInterface
    private interface Script { String response(int call, JsonNode request) throws IOException; }

    private final class Harness {
        final ServicePlanRuntimeService plans = mock(ServicePlanRuntimeService.class);
        final List<ModelRouteRequest> routed = new ArrayList<>();
        final List<ModelRouteRequest> authorized = new ArrayList<>();
        final List<ModelInvocationObserver.Observation> observed = new ArrayList<>();
        final List<JsonNode> inputs = new ArrayList<>();
        final List<ChatRequest> requests = new ArrayList<>();
        final List<String> systemMessages = new ArrayList<>();
        final AtomicInteger listenerRequests = new AtomicInteger();
        final AtomicInteger createdModels = new AtomicInteger();
        final LangChain4jStoryCandidateGenerator generator;

        Harness(CredentialSource source, Script script) {
            when(plans.freezeForUser(anyString())).thenReturn(source == CredentialSource.PLATFORM ? Optional.of(PLAN) : Optional.empty());
            var model = new ModelDefinition("ROUTED_PROVIDER", "configured-story-model", "http://127.0.0.1:26001/v1",
                    ModelAdapterType.OPENAI_COMPATIBLE, "configured-api-model",
                    Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL), 64000, 8000);
            var credential = source == CredentialSource.PLATFORM ? ModelCredential.platform("offline-platform") : ModelCredential.user("offline-user");
            var router = new ModelTaskRouter(request -> {
                routed.add(request);
                return new ModelRoutePolicy.ResolvedCandidates(source, List.of(new ModelRouteCandidate(model, credential, OPTIONS)));
            });
            ChatModelFactory factory = (definition, selectedCredential, options) -> {
                assertEquals(model, definition);
                assertSame(credential, selectedCredential);
                assertEquals(OPTIONS, options);
                createdModels.incrementAndGet();
                return new ChatModel() {
                    public ChatRequestParameters defaultRequestParameters() {
                        return DefaultChatRequestParameters.builder().modelName(definition.apiModelName())
                                .temperature(options.temperature()).maxOutputTokens(options.maximumOutputTokens()).build();
                    }
                    public Set<Capability> supportedCapabilities() { return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA); }
                    public List<ChatModelListener> listeners() {
                        return List.of(new ChatModelListener() {
                            @Override public void onRequest(ChatModelRequestContext ignored) { listenerRequests.incrementAndGet(); }
                        });
                    }
                    public ChatResponse doChat(ChatRequest request) {
                        requests.add(request);
                        request.messages().stream().filter(SystemMessage.class::isInstance).map(SystemMessage.class::cast)
                                .map(SystemMessage::text).forEach(systemMessages::add);
                        String body = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                                .reduce((previous, next) -> next).orElseThrow().singleText();
                        try {
                            JsonNode input = mapper.readTree(body);
                            inputs.add(input);
                            int count = inputs.size();
                            String response = script.response(count, input);
                            return ChatResponse.builder().aiMessage(AiMessage.from(response)).tokenUsage(new TokenUsage(10 + count, 6 + count)).build();
                        } catch (IOException error) { throw new IllegalStateException(error); }
                    }
                };
            };
            var invocations = new ModelInvocationService(router, new ChatModelRegistry(factory, 4), List.of(observed::add),
                    List.of(result -> Optional.of(new ModelTokenUsageResolver.CacheUsage(3, result.tokenUsage().inputTokenCount() - 3))),
                    List.of((request, route) -> authorized.add(request)));
            generator = new LangChain4jStoryCandidateGenerator(router, plans, invocations,
                    new StoryCandidateAiServiceRegistry(new PromptResourceLoader()), mapper);
        }
    }
}
