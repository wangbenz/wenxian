package com.wenxian.game.model.infrastructure;

import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatModelRegistryTest {
    @Test
    void reusesSameClientAndSeparatesRotatedCredential() {
        AtomicInteger creations = new AtomicInteger();
        ChatModelFactory factory = (definition, credential, options) -> {
            creations.incrementAndGet();
            return mock(ChatModel.class);
        };
        ChatModelRegistry registry = new ChatModelRegistry(factory, 8);
        ModelDefinition definition = definition("flash");
        ModelInvocationOptions options = ModelInvocationOptions.defaults();
        ModelCredential firstCredential = ModelCredential.platform("platform-secret-one");

        var first = registry.getOrCreate(definition, firstCredential, options);
        var repeated = registry.getOrCreate(definition, firstCredential, options);
        var rotated = registry.getOrCreate(definition, ModelCredential.platform("platform-secret-two"), options);

        assertThat(repeated).isSameAs(first);
        assertThat(rotated).isNotSameAs(first);
        assertThat(creations).hasValue(2);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void boundsCacheAndCanInvalidatePlayerCredential() {
        ChatModelFactory factory = (definition, credential, options) -> mock(ChatModel.class);
        ChatModelRegistry registry = new ChatModelRegistry(factory, 2);
        ModelCredential first = ModelCredential.user("user-secret-one");
        registry.getOrCreate(definition("one"), first, ModelInvocationOptions.defaults());
        registry.getOrCreate(definition("two"), ModelCredential.user("user-secret-two"),
                ModelInvocationOptions.defaults());
        registry.getOrCreate(definition("three"), ModelCredential.user("user-secret-three"),
                ModelInvocationOptions.defaults());

        assertThat(registry.size()).isEqualTo(2);
        registry.invalidateCredential(first);
        assertThat(registry.size()).isEqualTo(2); // first entry was already evicted as the least recently used.

        ModelCredential active = ModelCredential.user("user-secret-three");
        registry.invalidateCredential(active);
        assertThat(registry.size()).isEqualTo(1);
    }

    private ModelDefinition definition(String code) {
        return new ModelDefinition("TEST", code, "https://example.invalid", ModelAdapterType.OPENAI_COMPATIBLE,
                code, Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL),
                64_000, 8_000);
    }
}
