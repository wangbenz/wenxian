package com.wenxian.game.story.agent.ai;

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
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import com.wenxian.game.model.infrastructure.OpenAiCompatibleChatModelFactory;
import com.wenxian.game.model.infrastructure.PromptResourceLoader;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用真实模型验证类型化导演和正文两个任务；不访问 RDS、不写玩家命卷。 */
class DirectedNarrativeRealModelTest {

    @Test
    void directsAndRendersOneBoundedTurnThroughTypedAiServices() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assumeTrue(key != null && !key.isBlank(), "set DEEPSEEK_API_KEY to run directed narrative smoke");
        ModelDefinition definition = new ModelDefinition("DEEPSEEK", "TEST_MODEL",
                value(System.getenv("DEEPSEEK_BASE_URL"), "https://api.deepseek.com"),
                ModelAdapterType.OPENAI_COMPATIBLE,
                value(System.getenv("DEEPSEEK_MODEL"), "deepseek-v4-flash"),
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL,
                        ModelCapability.CACHE_USAGE), 128_000, 8_000);
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(
                CredentialSource.PLATFORM, List.of(new ModelRouteCandidate(definition,
                ModelCredential.platform(key), options(request.task()))));
        ModelTaskRouter router = new ModelTaskRouter(policy);
        ChatModelRegistry models = new ChatModelRegistry(new OpenAiCompatibleChatModelFactory(), 4);
        ModelInvocationService invocations = new ModelInvocationService(router, models);
        StoryAiServiceRegistry services = new StoryAiServiceRegistry(
                new AiServiceRegistry(new PromptResourceLoader(), 8));
        LangChain4jDirectedNarrativeGenerator generator = new LangChain4jDirectedNarrativeGenerator(
                router, invocations, services, new ObjectMapper().findAndRegisterModules());
        DirectedNarrativeGenerator.InvocationContext invocation = new DirectedNarrativeGenerator.InvocationContext(
                "real-directed-user", "real-directed-request", "real-directed-save", 1);

        long started = System.nanoTime();
        DirectedNarrativeGenerator.Session session = generator.open(invocation);
        DirectedNarrativeGenerator.DirectedPlan directed = session.plan(context());
        StoryPatch acceptedPlan = new StoryPatch("", "",
                new StoryPatch.TimePassage(directed.decision().duration(),
                        directed.decision().timeResolution(), directed.decision().interruptionReason()),
                null, List.of(), List.of(), List.of(), List.of(), directed.decision().decisionCheckpoint(),
                List.of(), "", directed.decision().outcome());
        DirectedNarrativeGenerator.RenderedNarrative rendered = session.render(context(), acceptedPlan,
                new NarrativeEndState(context().objective().worldTime().advance(acceptedPlan.time().duration()),
                        context().objective().protagonist(), null, List.of(), List.of(), List.of()));

        assertThat(directed.decision().outcome()).isNotNull();
        assertThat(directed.decision().sceneBeats()).isNotEmpty();
        assertThat(rendered.title()).isNotBlank();
        assertThat(rendered.narrative()).contains("你").doesNotContain("```", "{\"title\"");
        assertThat(rendered.choices()).hasSizeBetween(2, 4);
        assertThat(directed.modelCalls()).isNotEmpty();
        assertThat(rendered.modelCalls()).isNotEmpty();
        System.out.printf("DIRECTED_AI_SERVICE latencyMs=%d directorTokens=%d/%d writerTokens=%d/%d chars=%d models=%d%n",
                (System.nanoTime() - started) / 1_000_000,
                directed.modelCalls().stream().mapToInt(value -> value.inputTokens()).sum(),
                directed.modelCalls().stream().mapToInt(value -> value.outputTokens()).sum(),
                rendered.modelCalls().stream().mapToInt(value -> value.inputTokens()).sum(),
                rendered.modelCalls().stream().mapToInt(value -> value.outputTokens()).sum(),
                rendered.narrative().length(), models.size());
    }

    private StoryPromptContext context() {
        return new StoryPromptContext(new StoryPromptContext.SystemContext(
                List.of("已确认事实不可静默改写", "玩家决定不可被代替"), List.of()),
                new StoryPromptContext.ObjectiveContext(com.wenxian.game.story.domain.StoryWorldTime.opening(),
                        new CoreState("青石山门前", "平静", "引气初境", "寻找适合自己的修行道路"),
                        List.of(), List.of(), List.of()), new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("你刚抵达青石山门，尚未决定是否进入。", null),
                new StoryPromptContext.ActionContext("站在原地仔细观察门上的旧纹，观察完成后停下，不进入山门"));
    }

    /** 与首批任务路由迁移脚本保持一致，避免无界测试掩盖生产配置的实际耗时。 */
    private ModelInvocationOptions options(com.wenxian.game.model.domain.ModelTask task) {
        int outputTokens = task == com.wenxian.game.model.domain.ModelTask.TURN_DIRECTOR ? 2200 : 3200;
        double temperature = task == com.wenxian.game.model.domain.ModelTask.TURN_DIRECTOR ? 0.3 : 0.8;
        return new ModelInvocationOptions(temperature, Duration.ofSeconds(90), 0, outputTokens, true);
    }

    private String value(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }
}
