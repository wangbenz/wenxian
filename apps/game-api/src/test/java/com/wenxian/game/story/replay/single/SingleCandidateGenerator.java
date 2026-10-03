package com.wenxian.game.story.replay.single;

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
import dev.langchain4j.service.AiServices;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 测试专用的完整故事候选生成器，一次真实请求同时返回正文和原事实协议，不注册生产 Bean。
 * <p>保留现有事实映射、无默认输出截断、零传输重试；准备步骤掌握全轮唯一修复预算。
 * 旧 Session 方法明确拒绝使用，防止把本地取正文伪装成第二次供应商调用。</p>
 */
public final class SingleCandidateGenerator implements DirectedNarrativeGenerator {
    public static final String PROMPT = "prompts/experimental/single-candidate-system.txt";
    private final ObjectMapper mapper;
    private final ModelTaskRouter router;
    private final ModelInvocationService invocations;
    private final String prompt;

    private SingleCandidateGenerator(ObjectMapper mapper, ModelTaskRouter router, ModelInvocationService invocations) {
        this.mapper = Objects.requireNonNull(mapper);
        this.router = router;
        this.invocations = invocations;
        try (var stream = getClass().getClassLoader().getResourceAsStream(PROMPT)) {
            if (stream == null) throw new IllegalStateException("单候选测试 Prompt 缺失");
            prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new IllegalStateException("单候选测试 Prompt 读取失败", error); }
    }

    public static SingleCandidateGenerator create(ObjectMapper mapper, ChatModelFactory factory, String baseUrl, String key) {
        var definition = new ModelDefinition("DEEPSEEK", "SINGLE_CANDIDATE_REPLAY", baseUrl,
                ModelAdapterType.OPENAI_COMPATIBLE, "deepseek-v4-flash",
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL), 128_000, 8000);
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(CredentialSource.PLATFORM,
                List.of(new ModelRouteCandidate(definition, ModelCredential.platform(key),
                        new ModelInvocationOptions(0.3, Duration.ofSeconds(90), 0, null, true))));
        var router = new ModelTaskRouter(policy);
        return new SingleCandidateGenerator(mapper, router,
                new ModelInvocationService(router, new ChatModelRegistry(factory, 2)));
    }

    @Override
    public SingleSession open(InvocationContext invocation) {
        Objects.requireNonNull(invocation);
        var request = new ModelRouteRequest(ModelTask.TURN_DIRECTOR, invocation.requestId(), invocation.userId(), false);
        var route = router.route(request);
        return new SingleSession() {
            public CredentialSource credentialSource() { return route.primary().credential().source(); }

            public Generated generate(StoryPromptContext context, DirectorProtocolException rejected, Object serverAlignment) {
                Map<String, Object> input = payload(context);
                input.put("mode", rejected == null ? "GENERATE_COMPLETE_CANDIDATE" : "REPAIR_COMPLETE_CANDIDATE");
                if (rejected != null) {
                    input.put("rejectedDraft", rejected.rejectedDraft());
                    input.put("rejectedPhase", rejected.phase());
                    input.put("validationProblem", rejected.getMessage());
                }
                if (serverAlignment != null) input.put("serverAlignment", serverAlignment);
                ModelInvocationService.Invocation<SingleCandidateProtocol.Draft> result;
                try {
                    result = invocations.invokeResolved(request, route,
                            model -> AiServices.builder(SingleCandidateProtocol.Ai.class)
                                    .chatModel(model.model()).systemMessage(prompt)
                                    .registerListener((AiServiceResponseReceivedListener)
                                            event -> ModelResponseCapture.record(event.response())).build(),
                            ai -> ModelResponseCapture.invoke(() -> ai.generate(json(input))));
                } catch (ModelInvocationService.ModelInvocationException failed) {
                    ModelResponseCapture.DecodingFailure captured = decodingFailure(failed);
                    if (captured == null) throw failed;
                    String raw = captured.response() == null || captured.response().aiMessage() == null
                            ? null : captured.response().aiMessage().text();
                    throw new DirectorProtocolException(DirectorProtocolException.Phase.DECODING, raw,
                            calls(failed.attempts()), "完整候选类型解析失败；保留原行动，修复整份 decision 与 story", null);
                }
                List<ModelCall> calls = calls(result.attempts());
                try {
                    SingleCandidateProtocol.Draft draft = result.content();
                    if (draft == null || draft.story() == null || draft.story().narrative() == null
                            || draft.story().narrative().isBlank()) throw new IllegalArgumentException("完整候选缺少正文");
                    var decision = SingleCandidateProtocol.toDecision(draft.decision());
                    var story = draft.story();
                    var normalized = new NarrativeWriterAi.NarrativeDraft(
                            story.title() == null || story.title().isBlank() ? "此间经历" : story.title().strip(),
                            story.narrative().strip(), story.choices() == null ? List.of() : story.choices().stream()
                                    .filter(Objects::nonNull).toList(), story.summary() == null ? "" : story.summary());
                    return new Generated(draft, decision, normalized, calls);
                } catch (IllegalArgumentException invalid) {
                    throw new DirectorProtocolException(DirectorProtocolException.Phase.ADAPTATION,
                            result.content(), calls, invalid.getMessage(), invalid);
                }
            }
        };
    }

    /** 单候选端口只由隔离准备步骤调用，禁止进入生产双阶段 prepare。 */
    public interface SingleSession extends Session {
        Generated generate(StoryPromptContext context, DirectorProtocolException rejected, Object serverAlignment);
        default DirectedPlan plan(StoryPromptContext context) { throw unsupported(); }
        default DirectedPlan repair(StoryPromptContext context, StoryTurnDecision rejected, String problem) { throw unsupported(); }
        default RenderedNarrative render(StoryPromptContext context, StoryPatch plan, NarrativeEndState end) { throw unsupported(); }
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("单候选仅支持测试专用准备步骤，不能制造第二份调用记录");
        }
    }

    /** 同一供应商响应的原稿、等价事实映射与正文；调用摘要只归入整轮一次。 */
    public record Generated(SingleCandidateProtocol.Draft draft, StoryTurnDecision decision,
                            NarrativeWriterAi.NarrativeDraft story, List<ModelCall> calls) {}

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
        catch (JsonProcessingException error) { throw new IllegalStateException("单候选上下文编码失败", error); }
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
}
