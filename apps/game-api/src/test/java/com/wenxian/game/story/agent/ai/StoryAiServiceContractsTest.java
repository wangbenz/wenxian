package com.wenxian.game.story.agent.ai;

import com.wenxian.game.model.domain.ModelTask;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StoryAiServiceContractsTest {
    @Test
    void everyStableTaskMapsToOneAiServiceInterface() {
        for (ModelTask task : ModelTask.values()) {
            assertThat(StoryAiServiceContracts.type(task)).isNotNull().matches(Class::isInterface);
        }
    }
}
