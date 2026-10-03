package com.wenxian.game.model.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.config.SecretCipher;
import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.infrastructure.ChatModelFactory;
import com.wenxian.game.model.persistence.ModelValidationRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelValidationApplicationServiceTest {
    private static final String MASTER_KEY = "validation-master-key";

    @Test
    void validatesChatAndJsonThenPersistsVersionedEvidence() {
        AtomicReference<ModelValidationRepository.ValidationRun> saved = new AtomicReference<>();
        ModelValidationRepository repository = repository(model(Set.of(
                ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL)), saved);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ChatModel chat = mock(ChatModel.class);
        ChatModel json = mock(ChatModel.class);
        when(factory.create(any(), any(), any())).thenReturn(chat, json);
        when(chat.chat(any(java.util.List.class))).thenReturn(response("PONG"));
        when(json.chat(any(java.util.List.class))).thenReturn(response("{\"ok\":true}"));
        ModelValidationApplicationService service = new ModelValidationApplicationService(repository,
                factory, new ObjectMapper(), MASTER_KEY);

        var result = service.validate("TEST", "model-a", "admin-1");

        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.capabilityCodes()).isEqualTo("CHAT,JSON_MODE");
        assertThat(saved.get().providerConfigVersion()).isEqualTo(7);
        assertThat(saved.get().modelConfigVersion()).isEqualTo(11);
        assertThat(saved.get().errorSummary()).isNull();
    }

    @Test
    void missingDeclaredCapabilityProducesAStoredFailureWithoutCallingProvider() {
        AtomicReference<ModelValidationRepository.ValidationRun> saved = new AtomicReference<>();
        ModelValidationRepository repository = repository(model(Set.of(ModelCapability.CHAT)), saved);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ModelValidationApplicationService service = new ModelValidationApplicationService(repository,
                factory, new ObjectMapper(), MASTER_KEY);

        var result = service.validate("TEST", "model-a", "admin-1");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("CAPABILITY_NOT_DECLARED");
        assertThat(saved.get().capabilityCodes()).isEmpty();
        verify(factory, never()).create(any(), any(), any());
    }

    @Test
    void providerFailureIsStoredWithoutLeakingItsMessage() {
        AtomicReference<ModelValidationRepository.ValidationRun> saved = new AtomicReference<>();
        ModelValidationRepository repository = repository(model(Set.of(
                ModelCapability.CHAT, ModelCapability.JSON_MODE)), saved);
        ChatModelFactory factory = mock(ChatModelFactory.class);
        ChatModel chat = mock(ChatModel.class);
        when(factory.create(any(), any(), any())).thenReturn(chat);
        when(chat.chat(any(java.util.List.class)))
                .thenThrow(new IllegalStateException("provider said key=sk-do-not-return"));
        ModelValidationApplicationService service = new ModelValidationApplicationService(repository,
                factory, new ObjectMapper(), MASTER_KEY);

        var result = service.validate("TEST", "model-a", "admin-1");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.errorSummary()).doesNotContain("sk-do-not-return");
        assertThat(saved.get().errorSummary()).isEqualTo("受控验证未通过：IllegalStateException");
    }

    private ModelValidationRepository repository(ModelDefinition model,
                                                 AtomicReference<ModelValidationRepository.ValidationRun> saved) {
        return new ModelValidationRepository() {
            @Override
            public Optional<Target> findTarget(String providerCode, String modelCode) {
                return Optional.of(new Target(model, 7, 11,
                        SecretCipher.encrypt("sk-platform-validation-secret", MASTER_KEY)));
            }

            @Override
            public void insertRun(ValidationRun run) {
                saved.set(run);
            }
        };
    }

    private ModelDefinition model(Set<ModelCapability> capabilities) {
        return new ModelDefinition("TEST", "model-a", "https://example.invalid",
                ModelAdapterType.OPENAI_COMPATIBLE, "model-a", capabilities, 64_000, 8_000);
    }

    private ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
