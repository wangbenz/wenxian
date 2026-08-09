package com.wenxian.game.v014.ai;

import com.wenxian.game.story.agent.StoryModelGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LangChain4jModelGatewayAdapterTest {
    @Test
    void mapsLegacyRequestAndResponseWithoutChangingBehavior() {
        StoryModelGateway delegate = request -> {
            assertEquals("DEEPSEEK", request.provider());
            assertEquals("system", request.systemPrompt());
            assertEquals("user", request.userPrompt());
            return new StoryModelGateway.Result("{}", "response-1", "model-returned", 11, 7, 38);
        };
        LangChain4jModelGateway adapter = new LangChain4jModelGateway(delegate);

        V014ModelGateway.Result result = adapter.generate(new V014ModelGateway.Request(
                "https://example.invalid", "secret", "DEEPSEEK", "OPENAI_COMPATIBLE",
                "model-requested", "system", "user", 0.5));

        assertEquals("{}", result.text());
        assertEquals("response-1", result.responseId());
        assertEquals("model-returned", result.model());
        assertEquals(11, result.inputTokens());
        assertEquals(7, result.outputTokens());
        assertEquals(38, result.latencyMs());
    }
}
