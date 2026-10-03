package com.wenxian.game.story.replay.records;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.model.application.ModelInvocationService;
import com.wenxian.game.model.application.ModelRoutePolicy;
import com.wenxian.game.model.application.ModelTaskRouter;
import com.wenxian.game.model.domain.*;
import com.wenxian.game.model.infrastructure.*;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.agent.ai.*;
import com.wenxian.game.story.domain.StoryPatch;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.output.OutputParsingException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 持续记录合同的测试专用生成器，一次类型化 AI Service 请求返回故事、状态命令和记录。
 *
 * <p>不注册生产 Bean、不调用 writer，也不提供自证清单。完整原响应在解码前捕获；仅三项已列明
 * 辅助字段允许在副本上降级，并显式返回诊断。坏状态或记录不会被删除后再解析成成功候选。
 * 格式、适配与领域拒绝仍由外层共用一次完整修复；网络失败不自动转成修复或追加供应商调用。</p>
 */
public final class RecordCandidateGenerator implements DirectedNarrativeGenerator {
    public static final String PROMPT = "prompts/experimental/record-candidate-system.txt";
    private final ObjectMapper mapper;
    private final ModelTaskRouter router;
    private final ModelInvocationService invocations;
    private final String prompt;

    private RecordCandidateGenerator(ObjectMapper mapper, ModelTaskRouter router, ModelInvocationService invocations) {
        this.mapper = Objects.requireNonNull(mapper);
        this.router = router;
        this.invocations = invocations;
        try (var stream = getClass().getClassLoader().getResourceAsStream(PROMPT)) {
            if (stream == null) throw new IllegalStateException("持续记录测试 Prompt 缺失");
            prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("持续记录测试 Prompt 读取失败", failure); }
    }

    public static RecordCandidateGenerator create(ObjectMapper mapper, ChatModelFactory factory, String baseUrl, String key) {
        var definition = new ModelDefinition("DEEPSEEK", "RECORD_CANDIDATE_REPLAY", baseUrl,
                ModelAdapterType.OPENAI_COMPATIBLE, "deepseek-v4-flash",
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL), 128_000, 8000);
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(CredentialSource.PLATFORM,
                List.of(new ModelRouteCandidate(definition, ModelCredential.platform(key),
                        new ModelInvocationOptions(0.3, Duration.ofSeconds(90), 0, null, true))));
        var router = new ModelTaskRouter(policy);
        return new RecordCandidateGenerator(mapper, router,
                new ModelInvocationService(router, new ChatModelRegistry(factory, 2)));
    }

    @Override
    public RecordSession open(InvocationContext invocation) {
        Objects.requireNonNull(invocation);
        var request = new ModelRouteRequest(ModelTask.TURN_DIRECTOR, invocation.requestId(), invocation.userId(), false);
        var route = router.route(request);
        return new RecordSession() {
            public CredentialSource credentialSource() { return route.primary().credential().source(); }

            public Generated generate(StoryPromptContext context, List<StoryRecords.Envelope> history,
                                      DirectorProtocolException rejected, Object serverAlignment) {
                Map<String, Object> input = payload(context);
                input.put("recordHistory", history == null ? List.of() : List.copyOf(history));
                input.put("mode", rejected == null ? "GENERATE_COMPLETE_CANDIDATE" : "REPAIR_COMPLETE_CANDIDATE");
                if (rejected != null) {
                    input.put("rejectedDraft", rejected.rejectedDraft());
                    input.put("rejectedPhase", rejected.phase());
                    input.put("validationProblem", rejected.getMessage());
                }
                if (serverAlignment != null) input.put("serverAlignment", serverAlignment);
                Boundary boundary = new Boundary();
                ModelInvocationService.Invocation<RecordCandidateProtocol.Draft> result;
                try {
                    result = invocations.invokeResolved(request, route,
                            model -> AiServices.builder(RecordCandidateProtocol.Ai.class)
                                    .chatModel(guarded(model.model(), boundary)).systemMessage(prompt)
                                    .registerListener((AiServiceResponseReceivedListener) event -> ModelResponseCapture.record(
                                            boundary.original == null ? event.response() : boundary.original)).build(),
                            ai -> ModelResponseCapture.invoke(() -> ai.generate(json(input))));
                } catch (ModelInvocationService.ModelInvocationException failed) {
                    ModelResponseCapture.DecodingFailure captured = decodingFailure(failed);
                    if (captured == null) throw failed;
                    ChatResponse original = boundary.original == null ? captured.response() : boundary.original;
                    String raw = responseText(original);
                    String problem = boundary.inspection == null || boundary.inspection.problems().isEmpty()
                            ? "完整候选仍有类型解析错误；原文保留，请按原行动修复 story、changes、records 整份候选"
                            : String.join("\n", boundary.inspection.problems());
                    throw new DirectorProtocolException(DirectorProtocolException.Phase.DECODING, raw,
                            calls(failed.attempts()), problem, null);
                }
                List<ModelCall> calls = calls(result.attempts());
                try {
                    var draft = result.content();
                    var adapted = RecordCandidateProtocol.adapt(draft);
                    return new Generated(draft, adapted, calls, responseText(boundary.original),
                            boundary.inspection == null ? List.of() : boundary.inspection.downgrades());
                } catch (IllegalArgumentException invalid) {
                    // 已捕获完整响应时永远保留原稿，不把规范化副本冒充供应商原文。
                    throw new DirectorProtocolException(DirectorProtocolException.Phase.ADAPTATION,
                            responseText(boundary.original), calls, invalid.getMessage(), null);
                }
            }
        };
    }

    /** 包装只改变已声明的辅助内容；默认参数、能力及供应商监听器维持原模型边界，底层请求恰好一次。 */
    private ChatModel guarded(ChatModel delegate, Boundary boundary) {
        return new ChatModel() {
            @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
            @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
            @Override public ModelProvider provider() { return delegate.provider(); }
            @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
            @Override public ChatResponse doChat(ChatRequest request) {
                // wrapper.chat 已经合并原默认参数并执行同一份 listeners；不用 delegate.chat 再执行一遍。
                ChatResponse original = delegate.doChat(request);
                boundary.original = original;
                ModelResponseCapture.record(original);
                boundary.inspection = RecordCandidateProtocol.inspect(responseText(original));
                if (!boundary.inspection.problems().isEmpty())
                    throw new OutputParsingException("持续记录候选未通过解析边界检查", null);
                if (boundary.inspection.downgrades().isEmpty()) return original;
                return original.toBuilder().aiMessage(AiMessage.from(boundary.inspection.normalizedJson())).build();
            }
        };
    }

    /** 仅供隔离准备器使用；旧双阶段入口直接拒绝，不造虚假的第二次正文调用。 */
    public interface RecordSession extends Session {
        Generated generate(StoryPromptContext context, List<StoryRecords.Envelope> history,
                           DirectorProtocolException rejected, Object serverAlignment);
        default DirectedPlan plan(StoryPromptContext context) { throw unsupported(); }
        default DirectedPlan repair(StoryPromptContext context, StoryTurnDecision rejected, String problem) { throw unsupported(); }
        default RenderedNarrative render(StoryPromptContext context, StoryPatch plan, NarrativeEndState end) { throw unsupported(); }
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("持续记录候选只使用独立准备器，不能进入双阶段 writer");
        }
    }

    /** rawResponse 只进隔离取证与唯一修复，不进玩家 API；downgrades 不代表任何状态变更。 */
    public record Generated(RecordCandidateProtocol.Draft draft, RecordCandidateProtocol.Adapted adapted,
                            List<ModelCall> calls, String rawResponse, List<RecordCandidateProtocol.Downgrade> downgrades) {
        public Generated { calls = List.copyOf(calls); downgrades = List.copyOf(downgrades); }
        public Generated(RecordCandidateProtocol.Draft draft, RecordCandidateProtocol.Adapted adapted, List<ModelCall> calls) {
            this(draft, adapted, calls, null, List.of());
        }
    }

    private Map<String, Object> payload(StoryPromptContext context) {
        Objects.requireNonNull(context);
        ObjectNode objective = mapper.valueToTree(context.objective());
        if (context.worldContext() != null) {
            if (objective.get("creationContract") instanceof ObjectNode creation) creation.remove("worldPremise");
            if (objective.get("cultivation") instanceof ObjectNode cultivation) {
                cultivation.remove("summary");
                cultivation.path("stages").forEach(stage -> { if (stage instanceof ObjectNode item) item.remove("meaning"); });
            }
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("objective", objective);
        if (context.worldContext() != null) input.put("worldContext", context.worldContext());
        input.put("cognition", context.cognition());
        input.put("memory", context.memory());
        input.put("narrative", context.narrative());
        input.put("action", context.action());
        return input;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("持续记录上下文编码失败", error); }
    }
    private static String responseText(ChatResponse response) {
        return response == null || response.aiMessage() == null ? null : response.aiMessage().text();
    }
    private static List<ModelCall> calls(List<ModelInvocationService.Attempt> attempts) {
        return attempts.stream().map(a -> new ModelCall(a.providerCode(), a.modelCode(), a.credentialSource(), a.fallback(),
                a.status(), a.latencyMs(), a.inputTokens(), a.outputTokens(), a.cacheHitTokens(), a.cacheMissTokens(), a.errorCategory())).toList();
    }
    private static ModelResponseCapture.DecodingFailure decodingFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof ModelResponseCapture.DecodingFailure captured) return captured;
        return null;
    }
    private static final class Boundary {
        private ChatResponse original;
        private RecordCandidateProtocol.Inspection inspection;
    }
}
