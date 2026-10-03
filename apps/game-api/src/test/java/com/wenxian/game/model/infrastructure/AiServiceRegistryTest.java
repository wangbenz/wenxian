package com.wenxian.game.model.infrastructure;

import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelTask;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceRegistryTest {
    @Test
    void reusesProxyForSameTaskPromptModelAndInterface() {
        AiServiceRegistry registry = new AiServiceRegistry(new PromptResourceLoader(), 4);
        var model = new ChatModelRegistry.RegisteredChatModel("safe-model-id", definition(), mock(ChatModel.class));

        TestAi first = registry.getOrCreate(ModelTask.NARRATIVE_WRITER, model, TestAi.class);
        TestAi repeated = registry.getOrCreate(ModelTask.NARRATIVE_WRITER, model, TestAi.class);

        assertThat(first).isSameAs(repeated);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void capturesBeforeTypedParsingAndIsolatesConcurrentCallsOnTheSameCachedDirector() throws Exception {
        AiServiceRegistry registry = new AiServiceRegistry(new PromptResourceLoader(), 4);
        ChatModel chatModel = mock(ChatModel.class);
        CountDownLatch entered = new CountDownLatch(2);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            String text = ((UserMessage) request.messages().getLast()).singleText();
            entered.countDown();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            return ChatResponse.builder().aiMessage(AiMessage.from(
                    text.startsWith("first") ? "invalid-private-first" : "invalid-private-second")).build();
        });
        var model = new ChatModelRegistry.RegisteredChatModel("director", definition(), chatModel);
        TestAi director = registry.getOrCreate(ModelTask.TURN_DIRECTOR, model, TestAi.class);
        assertThat(registry.getOrCreate(ModelTask.TURN_DIRECTOR, model, TestAi.class)).isSameAs(director);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> decodeFailure(director, "first"));
            var second = executor.submit(() -> decodeFailure(director, "second"));
            assertThat(first.get(10, TimeUnit.SECONDS).response().aiMessage().text())
                    .isEqualTo("invalid-private-first");
            assertThat(second.get(10, TimeUnit.SECONDS).response().aiMessage().text())
                    .isEqualTo("invalid-private-second");
        }
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void doesNotCaptureOtherTasksEvenWhenAnInvocationScopeExists() {
        AiServiceRegistry registry = new AiServiceRegistry(new PromptResourceLoader(), 4);
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("invalid-private-writer")).build());
        var model = new ChatModelRegistry.RegisteredChatModel("writer", definition(), chatModel);
        TestAi writer = registry.getOrCreate(ModelTask.NARRATIVE_WRITER, model, TestAi.class);

        assertThat(decodeFailure(writer, "write").response()).isNull();
        verify(chatModel).chat(any(ChatRequest.class));
    }

    private ModelResponseCapture.DecodingFailure decodeFailure(TestAi service, String input) {
        return catchThrowableOfType(() -> ModelResponseCapture.invoke(() -> service.invoke(input)),
                ModelResponseCapture.DecodingFailure.class);
    }

    private ModelDefinition definition() {
        return new ModelDefinition("TEST", "flash", "https://example.invalid",
                ModelAdapterType.OPENAI_COMPATIBLE, "flash",
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE), 64_000, 8_000);
    }

    interface TestAi {
        @dev.langchain4j.service.UserMessage("{{input}}")
        Result<Payload> invoke(@V("input") String input);
    }

    record Payload(String text) {}
}
