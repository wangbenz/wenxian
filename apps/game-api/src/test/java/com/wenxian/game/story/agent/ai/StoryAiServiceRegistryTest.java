package com.wenxian.game.story.agent.ai;

import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelTask;
import com.wenxian.game.model.infrastructure.AiServiceRegistry;
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import com.wenxian.game.model.infrastructure.PromptResourceLoader;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class StoryAiServiceRegistryTest {
    @Test
    void buildsEveryTypedStoryServiceThroughItsOwnTask() {
        StoryAiServiceRegistry registry = registry();
        ChatModelRegistry.RegisteredChatModel model = model();

        assertThat(registry.characterCreation(model)).isInstanceOf(CharacterCreationAi.class);
        assertThat(registry.storyCorrection(model)).isInstanceOf(StoryCorrectionAi.class);
        assertThat(registry.turnDirector(model)).isInstanceOf(TurnDirectorAi.class);
        assertThat(registry.narrativeWriter(model)).isInstanceOf(NarrativeWriterAi.class);
        assertThat(registry.contextSummary(model)).isInstanceOf(ContextSummaryAi.class);
        assertThat(registry.formatRepair(model)).isInstanceOf(FormatRepairAi.class);
    }

    @Test
    void rejectsMismatchedTaskAndServiceInterface() {
        StoryAiServiceRegistry registry = registry();

        assertThatThrownBy(() -> registry.service(ModelTask.TURN_DIRECTOR, model(), NarrativeWriterAi.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TurnDirectorAi");
    }

    private StoryAiServiceRegistry registry() {
        return new StoryAiServiceRegistry(new AiServiceRegistry(new PromptResourceLoader(), 16));
    }

    private ChatModelRegistry.RegisteredChatModel model() {
        ModelDefinition definition = new ModelDefinition("TEST", "flash", "https://example.invalid",
                ModelAdapterType.OPENAI_COMPATIBLE, "flash",
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE), 64_000, 8_000);
        return new ChatModelRegistry.RegisteredChatModel("safe-model-id", definition, mock(ChatModel.class));
    }
}
