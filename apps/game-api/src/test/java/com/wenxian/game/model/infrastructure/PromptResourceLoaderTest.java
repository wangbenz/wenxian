package com.wenxian.game.model.infrastructure;

import com.wenxian.game.model.domain.ModelTask;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptResourceLoaderTest {
    private final PromptResourceLoader loader = new PromptResourceLoader();

    @Test
    void everyStableTaskHasVersionedNonEmptyPrompt() {
        for (ModelTask task : ModelTask.values()) {
            var prompt = loader.load(task);
            assertThat(prompt.task()).isEqualTo(task);
            assertThat(prompt.path()).startsWith("prompts/model/");
            assertThat(prompt.version()).isNotBlank();
            assertThat(prompt.content()).isNotBlank().doesNotContain("# prompt-version:");
        }
    }

    @Test
    void storyPromptsKeepCharacterLocationAndPlayerDecisionBoundariesExplicit() {
        var director = loader.load(ModelTask.TURN_DIRECTOR);
        assertThat(director.version()).isEqualTo("turn-director-v16");
        assertThat(director.content())
                .contains("专用 scenePresence 字段", "PRESENT", "MENTIONED", "identity", "lastKnownLocation",
                        "orderedResult", "NOT_EXECUTED", "dependsOn", "statements", "ACTION_RESOLVED");

        var writer = loader.load(ModelTask.NARRATIVE_WRITER);
        assertThat(writer.version()).isEqualTo("narrative-writer-v10");
        assertThat(writer.content())
                .contains("必须保持同一不确定性", "不得写“已经决定”“已有打算”", "endState.acceptedResult",
                        "简单行动可以用少量自然段结束", "真实依赖")
                .doesNotContain("六至十个自然段", "七百至一千一百", "acceptedPlan.narrative");
    }
}
