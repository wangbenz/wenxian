package com.wenxian.game.story.agent;

import com.wenxian.game.persistence.AiUsageStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AiUsageStoryModelInvocationRecorderTest {
    @Test
    void recordsProviderAttemptsWithoutSecretsOrRawProviderMessages() {
        CapturingUsageStore usage = new CapturingUsageStore(false);
        AiUsageStoryModelInvocationRecorder recorder = new AiUsageStoryModelInvocationRecorder(usage);
        StoryTurnAgent.InvocationContext invocation = new StoryTurnAgent.InvocationContext("save-1", 3);
        StoryModelRoute route = route();

        recorder.succeeded(invocation, "STORY_TURN", route,
                new StoryModelGateway.Result("private narrative", "response-1", "returned-model",
                        120, 340, 900));
        recorder.failed(invocation, "STORY_TURN_REPAIR", route, 450,
                new StoryModelFailureException(StoryModelFailureException.Kind.REQUEST_REJECTED,
                        "provider raw detail", null));

        assertEquals(2, usage.records.size());
        assertEquals("SUCCESS|STORY_TURN|returned-model|120|340|900|", usage.records.get(0));
        assertEquals("FAILED|STORY_TURN_REPAIR|configured-model|0|0|450|REQUEST_REJECTED",
                usage.records.get(1));
    }

    @Test
    void usagePersistenceFailureNeverInterruptsGenerationFlow() {
        AiUsageStoryModelInvocationRecorder recorder = new AiUsageStoryModelInvocationRecorder(
                new CapturingUsageStore(true));

        assertDoesNotThrow(() -> recorder.succeeded(new StoryTurnAgent.InvocationContext("save-1", 1),
                "STORY_TURN", route(), new StoryModelGateway.Result("{}", "response-1",
                        "returned-model", 1, 2, 3)));
        assertDoesNotThrow(() -> recorder.failed(new StoryTurnAgent.InvocationContext("save-1", 1),
                "STORY_TURN", route(), 4, new IllegalStateException("database detail")));
    }

    private StoryModelRoute route() {
        return new StoryModelRoute("https://example.invalid", "secret-key", "DEEPSEEK",
                "OPENAI_COMPATIBLE", "configured-model", "PUBLIC");
    }

    private static final class CapturingUsageStore extends AiUsageStore {
        private final List<String> records = new ArrayList<>();
        private final boolean fail;

        private CapturingUsageStore(boolean fail) {
            super(null, null);
            this.fail = fail;
        }

        @Override
        public void record(String saveId, Integer turnNumber, String operation, String provider,
                           String credentialSource, String model, String status, int promptTokens,
                           int completionTokens, int cacheHitTokens, int cacheMissTokens,
                           long latencyMs, String error) {
            if (fail) throw new IllegalStateException("usage table unavailable");
            records.add(String.join("|", status, operation, model, String.valueOf(promptTokens),
                    String.valueOf(completionTokens), String.valueOf(latencyMs), error));
        }
    }
}
