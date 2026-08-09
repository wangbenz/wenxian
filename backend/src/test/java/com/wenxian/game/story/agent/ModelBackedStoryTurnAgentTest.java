package com.wenxian.game.story.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.StoryPatchValidator;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelBackedStoryTurnAgentTest {
    private static final String VALID_PATCH = """
            {"title":"石门之后","narrative":"你运转照影诀，石门后的禁制逐层显现。",
             "time":{"elapsedMinutes":30,"resolution":"COMPLETE","interruptionReason":null},
             "coreState":{"location":null,"condition":null,"realm":null,"lifeGoal":null},
             "entities":[{"operation":"UPDATE","entityId":"ability-reflection","entityType":"ABILITY",
               "name":null,"status":"运转如常","summary":null,"attributes":null,"quantity":null,
               "inPossession":null,"abilityState":"MASTERED","lifecycle":null}],
             "worldFacts":[],"knowledge":[],"memories":[],
             "decisionCheckpoint":{"required":false,"prompt":null},
             "choices":[{"title":"继续观察","description":"寻找禁制薄弱处。"}],
             "rollingSummary":"你在石门前施展了照影诀。"}
            """;

    @Test
    void generatesValidatedPatchFromSeparatedContext() {
        StoryModelGateway gateway = request -> {
            assertEquals(0.7, request.temperature());
            assertTrue(request.systemPrompt().contains("第二人称“你”"));
            assertTrue(request.systemPrompt().contains("decisionCheckpoint.required=true"));
            assertTrue(request.userPrompt().contains("ability-reflection"));
            assertTrue(request.userPrompt().contains("施展照影诀"));
            assertFalse(request.userPrompt().contains("test-secret-key"));
            return result(VALID_PATCH, 101, 203, 800);
        };
        ModelBackedStoryTurnAgent agent = agent(gateway);

        StoryTurnAgent.Generated generated = agent.generate(context(), route());

        assertEquals("石门之后", generated.patch().title());
        assertEquals(TimeResolution.COMPLETE, generated.patch().time().resolution());
        assertEquals("ability-reflection", generated.patch().entities().getFirst().entityId());
        assertEquals(101, generated.inputTokens());
        assertEquals(203, generated.outputTokens());
        assertEquals(1, generated.modelResults().size());
    }

    @Test
    void retriesOnlyOnceWithCompactFormatRepairAndCountsBothCalls() {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            int attempt = calls.incrementAndGet();
            if (attempt == 1) return result("不是 JSON", 90, 10, 120);
            assertEquals(0.1, request.temperature());
            assertTrue(request.userPrompt().contains("只修复格式和契约字段"));
            assertTrue(request.userPrompt().contains("不是 JSON"));
            assertFalse(request.userPrompt().contains("ability-reflection"), "修复请求不应重复完整历史上下文");
            return result(VALID_PATCH, 40, 180, 300);
        };

        StoryTurnAgent.Generated generated = agent(gateway).generate(context(), route());

        assertEquals(2, generated.modelResults().size());
        assertEquals(130, generated.inputTokens());
        assertEquals(190, generated.outputTokens());
        assertEquals(420, generated.latencyMs());
    }

    @Test
    void rejectsTurnWithoutWritingWhenOriginalAndRepairAreInvalid() {
        AtomicInteger calls = new AtomicInteger();
        StoryModelGateway gateway = request -> {
            calls.incrementAndGet();
            return result("{}", 2, 2, 10);
        };

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> agent(gateway).generate(context(), route()));

        assertEquals(2, calls.get());
        assertEquals("推演结果未能形成可提交的状态提案", error.getMessage());
    }

    @Test
    void reportsInitialRepairAndFailedRequestsAsSeparateModelFacts() {
        AtomicInteger calls = new AtomicInteger();
        ArrayList<String> succeeded = new ArrayList<>();
        ArrayList<String> failed = new ArrayList<>();
        StoryModelInvocationRecorder recorder = new StoryModelInvocationRecorder() {
            @Override
            public void succeeded(StoryTurnAgent.InvocationContext invocation, String operation,
                                  StoryModelRoute route, StoryModelGateway.Result result) {
                assertEquals("save-observed", invocation.saveId());
                assertEquals(7, invocation.turnNumber());
                succeeded.add(operation);
            }

            @Override
            public void failed(StoryTurnAgent.InvocationContext invocation, String operation,
                               StoryModelRoute route, long latencyMs, RuntimeException failure) {
                failed.add(operation);
            }
        };
        StoryModelGateway repairedGateway = request -> calls.incrementAndGet() == 1
                ? result("not-json", 20, 5, 10)
                : result(VALID_PATCH, 30, 100, 20);
        ModelBackedStoryTurnAgent repairedAgent = new ModelBackedStoryTurnAgent(repairedGateway, recorder,
                new ObjectMapper().findAndRegisterModules(), new StoryPatchValidator());

        repairedAgent.generate(context(), route(), new StoryTurnAgent.InvocationContext("save-observed", 7));

        assertEquals(List.of("STORY_TURN", "STORY_TURN_REPAIR"), succeeded);
        assertTrue(failed.isEmpty());

        StoryModelGateway failedGateway = request -> {
            throw new StoryModelFailureException(StoryModelFailureException.Kind.RATE_LIMITED,
                    "internal diagnostic", null);
        };
        ModelBackedStoryTurnAgent failedAgent = new ModelBackedStoryTurnAgent(failedGateway, recorder,
                new ObjectMapper().findAndRegisterModules(), new StoryPatchValidator());

        assertThrows(StoryModelFailureException.class, () -> failedAgent.generate(context(), route(),
                new StoryTurnAgent.InvocationContext("save-observed", 7)));
        assertEquals(List.of("STORY_TURN"), failed);
    }

    private ModelBackedStoryTurnAgent agent(StoryModelGateway gateway) {
        return new ModelBackedStoryTurnAgent(gateway, new ObjectMapper().findAndRegisterModules());
    }

    private StoryPromptContext context() {
        return new StoryPromptContext(
                new StoryPromptContext.SystemContext(List.of("已确认事实不可静默改写"), List.of()),
                new StoryPromptContext.ObjectiveContext(120,
                        new CoreState("石门前", "平静", "引气", "问道"), List.of(),
                        List.of(new StoryPromptContext.EntityIndexEntry("ability-reflection", "ABILITY", "照影诀",
                                "运转如常", true, null, null,
                                com.wenxian.game.story.domain.StoryPatch.AbilityState.MASTERED, null)),
                        List.of()),
                new StoryPromptContext.CognitionContext(List.of()),
                new StoryPromptContext.MemoryContext(List.of()),
                new StoryPromptContext.NarrativeContext("你来到石门前。", null),
                new StoryPromptContext.ActionContext("施展照影诀查看石门后的禁制。"));
    }

    private StoryModelRoute route() {
        return new StoryModelRoute("https://example.invalid", "test-secret-key", "DEEPSEEK",
                "OPENAI_COMPATIBLE", "deepseek-v4-flash", "PUBLIC");
    }

    private StoryModelGateway.Result result(String text, int input, int output, long latency) {
        return new StoryModelGateway.Result(text, "response-1", "deepseek-v4-flash", input, output, latency);
    }
}
