package com.wenxian.game.story.agent.ai;

import com.wenxian.game.story.domain.StoryRecords;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.InvocationContext;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.ModelCall;
import com.wenxian.game.model.application.ServicePlanRuntimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.model.application.ModelInvocationService;
import com.wenxian.game.model.application.ModelTaskRouter;
import com.wenxian.game.model.domain.*;
import com.wenxian.game.model.infrastructure.*;
import com.wenxian.game.story.agent.StoryPromptContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.output.OutputParsingException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 正式同次故事生成：按冻结的 TURN_DIRECTOR 路由返回正文、状态变化与持续记录。
 *
 * <p>服务方案在 open 时选择一次，凭据来源、价格与模型参数在本回合保持一致。每次生成和唯一
 * 修复都经过 ModelInvocationService 的授权、供应商调用、观测和用量提取，不自行创建模型配置。
 * 原始响应在类型解码前捕获；只对列明辅助字段作独立降级，不以删除状态绕过失败。</p>
 *
 * <p>本类不调用 NARRATIVE_WRITER，不决定修复次数，不归并权威状态或提交数据库。
 * 网络与权限失败保持原边界；中文一致性不能由类型解析或服务端反馈自动证明。</p>
 */
@Component
public final class LangChain4jStoryCandidateGenerator implements StoryCandidateGenerator {
    public static final String PROMPT = "prompts/model/story-candidate-system.txt";
    private final ObjectMapper mapper;
    private final ModelTaskRouter router;
    private final ServicePlanRuntimeService servicePlans;
    private final ModelInvocationService invocations;
    private final StoryCandidateAiServiceRegistry services;

    @Autowired
    public LangChain4jStoryCandidateGenerator(ModelTaskRouter router, ServicePlanRuntimeService servicePlans,
                                               ModelInvocationService invocations,
                                               StoryCandidateAiServiceRegistry services, ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        this.router = Objects.requireNonNull(router);
        this.servicePlans = servicePlans;
        this.invocations = Objects.requireNonNull(invocations);
        this.services = Objects.requireNonNull(services);
    }

    /** 仅供不加载服务方案的离线测试使用，仍必须提供明确的路由和统一模型调用服务。 */
    public LangChain4jStoryCandidateGenerator(ModelTaskRouter router, ModelInvocationService invocations,
                                               StoryCandidateAiServiceRegistry services, ObjectMapper mapper) {
        this(router, null, invocations, services, mapper);
    }

    @Override
    public Session open(InvocationContext invocation) {
        Objects.requireNonNull(invocation);
        ServicePlanSelection selection = servicePlans == null ? null : servicePlans.freezeForUser(invocation.userId()).orElse(null);
        var request = new ModelRouteRequest(ModelTask.TURN_DIRECTOR, invocation.requestId(), invocation.userId(), false, selection);
        var route = router.route(request);
        return new Session() {
            public CredentialSource credentialSource() { return route.primary().credential().source(); }
            public Optional<ServicePlanSelection> servicePlanSelection() { return Optional.ofNullable(selection); }

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
                ModelInvocationService.Invocation<StoryCandidateProtocol.Draft> result;
                try {
                    result = invocations.invokeResolved(request, route,
                            model -> services.create(guarded(model.model(), boundary), response -> ModelResponseCapture.record(
                                    boundary.original == null ? response : boundary.original)),
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
                    var adapted = StoryCandidateProtocol.adapt(draft);
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
                boundary.inspection = StoryCandidateProtocol.inspect(responseText(original));
                if (!boundary.inspection.problems().isEmpty())
                    throw new OutputParsingException("持续记录候选未通过解析边界检查", null);
                if (boundary.inspection.downgrades().isEmpty()) return original;
                return original.toBuilder().aiMessage(AiMessage.from(boundary.inspection.normalizedJson())).build();
            }
        };
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
        ObjectNode narrative = mapper.valueToTree(context.narrative());
        if (narrative != null) {
            if (narrative.path("previousTurn") instanceof ObjectNode previous) previous.remove("storyRecords");
            narrative.path("recentTurns").forEach(turn -> {
                if (turn instanceof ObjectNode previous) previous.remove("storyRecords");
            });
        }
        input.put("narrative", narrative);
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
        private StoryCandidateProtocol.Inspection inspection;
    }
}
