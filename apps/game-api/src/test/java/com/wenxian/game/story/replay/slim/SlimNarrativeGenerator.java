package com.wenxian.game.story.replay.slim;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 测试专用的精简两阶段候选，复用正式路由、模型调用与准备/提交端口，不注册 Spring Bean。
 * 每次方法只调用一次供应商，协议失败交回现有 prepare 的唯一修复预算；不写状态、不扣额度。
 */
public final class SlimNarrativeGenerator implements DirectedNarrativeGenerator {
    public static final String DIRECTOR_PROMPT = "prompts/experimental/slim-turn-director-system.txt";
    public static final String WRITER_PROMPT = "prompts/experimental/slim-narrative-writer-system.txt";
    private final ObjectMapper mapper;
    private final ModelTaskRouter router;
    private final ModelInvocationService invocations;
    private final String directorPrompt;
    private final String writerPrompt;

    private SlimNarrativeGenerator(ObjectMapper mapper, Components components) {
        this.mapper = Objects.requireNonNull(mapper);
        this.router = components.router();
        this.invocations = components.invocations();
        this.directorPrompt = resource(DIRECTOR_PROMPT);
        this.writerPrompt = resource(WRITER_PROMPT);
    }

    public static SlimNarrativeGenerator create(ObjectMapper mapper, ChatModelFactory factory, String baseUrl, String key) {
        return new SlimNarrativeGenerator(mapper, components(factory, baseUrl, key));
    }

    /** 正式双阶段对照使用完全相同的模型参数；只更换为正式 Prompt、DTO 和写作输入。 */
    public static DirectedNarrativeGenerator createBaseline(ObjectMapper mapper, ChatModelFactory factory,
                                                            String baseUrl, String key) {
        Components components = components(factory, baseUrl, key);
        return new LangChain4jDirectedNarrativeGenerator(components.router(), components.invocations(),
                new StoryAiServiceRegistry(new AiServiceRegistry(new PromptResourceLoader(), 8)), mapper);
    }

    private static Components components(ChatModelFactory factory, String baseUrl, String key) {
        var definition = new ModelDefinition("DEEPSEEK", "SLIM_COMPARISON", baseUrl,
                ModelAdapterType.OPENAI_COMPATIBLE, "deepseek-v4-flash",
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL), 128_000, 8000);
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(CredentialSource.PLATFORM,
                List.of(new ModelRouteCandidate(definition, ModelCredential.platform(key),
                        new ModelInvocationOptions(request.task() == ModelTask.TURN_DIRECTOR ? 0.3 : 0.8,
                                Duration.ofSeconds(90), 0, request.task() == ModelTask.TURN_DIRECTOR ? 2200 : 3200, true))));
        var router = new ModelTaskRouter(policy);
        return new Components(router, new ModelInvocationService(router, new ChatModelRegistry(factory, 4)));
    }

    @Override
    public Session open(InvocationContext invocation) {
        Objects.requireNonNull(invocation, "精简候选调用缺少上下文");
        var director = router.route(request(ModelTask.TURN_DIRECTOR, invocation));
        var writer = router.route(request(ModelTask.NARRATIVE_WRITER, invocation));
        if (director.primary().credential().source() != writer.primary().credential().source()) {
            throw new IllegalStateException("同一回合不能混用密钥来源");
        }
        return new Session() {
            public CredentialSource credentialSource() { return director.primary().credential().source(); }

            public DirectedPlan plan(StoryPromptContext context) { return direct(payload(context)); }

            public DirectedPlan repair(StoryPromptContext context, StoryTurnDecision rejected, String problem) {
                Map<String, Object> input = payload(context);
                input.put("mode", "REPAIR_REJECTED_DECISION");
                input.put("rejectedDraft", SlimTurnProtocol.fromDecision(rejected));
                input.put("rejectedPhase", DirectorProtocolException.Phase.COMPILATION);
                input.put("validationProblem", SlimTurnProtocol.repairProblem(rejected, problem));
                return direct(input);
            }

            public DirectedPlan repairProtocol(StoryPromptContext context, DirectorProtocolException rejected) {
                Map<String, Object> input = payload(context);
                input.put("mode", "REPAIR_REJECTED_DECISION");
                input.put("rejectedDraft", rejected.rejectedDraft() instanceof StoryTurnDecision decision
                        ? SlimTurnProtocol.fromDecision(decision) : rejected.rejectedDraft());
                input.put("rejectedPhase", rejected.phase());
                input.put("validationProblem", rejected.rejectedDraft() instanceof StoryTurnDecision decision
                        ? SlimTurnProtocol.repairProblem(decision, rejected.getMessage())
                        : rejected.rejectedDraft() instanceof SlimTurnProtocol.Draft draft
                        ? SlimTurnProtocol.repairProblem(draft, rejected.getMessage()) : rejected.getMessage());
                return direct(input);
            }

            private DirectedPlan direct(Map<String, Object> input) {
                String contextJson = json(input);
                ModelInvocationService.Invocation<SlimTurnProtocol.Draft> result;
                try {
                    result = invocations.invokeResolved(request(ModelTask.TURN_DIRECTOR, invocation), director,
                            model -> ai(SlimTurnProtocol.DirectorAi.class, model, directorPrompt), service -> service.direct(contextJson));
                } catch (ModelInvocationService.ModelInvocationException failed) {
                    if (!decodingFailure(failed)) throw failed;
                    // 维持正式 typed 服务边界：失败时未暴露原件/usage，保留已知尝试，不从报错文本伪造响应。
                    throw new DirectorProtocolException(DirectorProtocolException.Phase.DECODING, null, calls(failed.attempts()),
                            "导演响应无法解析为精简协议，请依据同一行动重新整理结构", failed);
                }
                List<ModelCall> calls = calls(result.attempts());
                try {
                    return new DirectedPlan(SlimTurnProtocol.toDecision(result.content()), calls);
                } catch (IllegalArgumentException invalid) {
                    throw new DirectorProtocolException(DirectorProtocolException.Phase.ADAPTATION,
                            result.content(), calls, invalid.getMessage(), invalid);
                }
            }

            public RenderedNarrative render(StoryPromptContext context, StoryPatch acceptedPlan, NarrativeEndState endState) {
                String view = json(SlimWriterView.from(mapper, context, acceptedPlan, endState));
                var result = invocations.invokeResolved(request(ModelTask.NARRATIVE_WRITER, invocation), writer,
                        model -> ai(NarrativeWriterAi.class, model, writerPrompt), service -> service.write(view));
                List<ModelCall> calls = calls(result.attempts());
                NarrativeWriterAi.NarrativeDraft draft = result.content();
                try {
                    var choices = draft.choices() == null ? List.<StoryPatch.ChoiceDraft>of() : draft.choices().stream()
                            .filter(Objects::nonNull).map(value -> new StoryPatch.ChoiceDraft(value.title(), value.description())).toList();
                    return new RenderedNarrative(draft.title() == null || draft.title().isBlank() ? "此间经历" : draft.title(),
                            draft.narrative(), choices, draft.summary(), calls);
                } catch (IllegalArgumentException invalid) {
                    // 正文错误不回导演修复，也不能丢掉已经返回的供应商用量。
                    throw new ModelInvocationService.ModelInvocationException("SLIM_WRITER_OUTPUT_INVALID", result.attempts(), invalid);
                }
            }
        };
    }

    private Map<String, Object> payload(StoryPromptContext context) {
        Objects.requireNonNull(context, "精简导演缺少冻结上下文");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mode", "PLAN_CURRENT_ACTION");
        input.put("objective", SlimWriterView.objective(mapper, context));
        if (context.worldContext() != null) input.put("worldContext", context.worldContext());
        input.put("cognition", context.cognition());
        input.put("memory", context.memory());
        input.put("narrative", context.narrative());
        input.put("action", context.action());
        return input;
    }

    private static <T> T ai(Class<T> type, ChatModelRegistry.RegisteredChatModel model, String prompt) {
        return AiServices.builder(type).chatModel(model.model()).systemMessage(prompt).build();
    }

    private static ModelRouteRequest request(ModelTask task, InvocationContext invocation) {
        return new ModelRouteRequest(task, invocation.requestId(), invocation.userId(), false);
    }

    private static List<ModelCall> calls(List<ModelInvocationService.Attempt> attempts) {
        return attempts.stream().map(a -> new ModelCall(a.providerCode(), a.modelCode(), a.credentialSource(), a.fallback(),
                a.status(), a.latencyMs(), a.inputTokens(), a.outputTokens(), a.cacheHitTokens(), a.cacheMissTokens(), a.errorCategory())).toList();
    }

    private static boolean decodingFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof dev.langchain4j.service.output.OutputParsingException) return true;
        }
        return false;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("精简回放上下文无法编码", error); }
    }

    private static String resource(String path) {
        try (var stream = SlimNarrativeGenerator.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("测试 Prompt 缺失：" + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new IllegalStateException("测试 Prompt 无法读取：" + path, error); }
    }

    private record Components(ModelTaskRouter router, ModelInvocationService invocations) {}
}
