package com.wenxian.game.story.agent.ai;

import com.wenxian.game.model.domain.ModelTask;

import java.util.Map;

/** 故事任务和 AI Service 接口的唯一映射，防止调用方随意混用 Prompt。 */
public final class StoryAiServiceContracts {
    private static final Map<ModelTask, Class<?>> TYPES = Map.of(
            ModelTask.CHARACTER_CREATION, CharacterCreationAi.class,
            ModelTask.STORY_CORRECTION, StoryCorrectionAi.class,
            ModelTask.TURN_DIRECTOR, TurnDirectorAi.class,
            ModelTask.NARRATIVE_WRITER, NarrativeWriterAi.class,
            ModelTask.CONTEXT_SUMMARY, ContextSummaryAi.class,
            ModelTask.FORMAT_REPAIR, FormatRepairAi.class
    );

    private StoryAiServiceContracts() {}

    public static Class<?> type(ModelTask task) {
        Class<?> type = TYPES.get(task);
        if (type == null) throw new IllegalArgumentException("未登记的故事 AI Service：" + task);
        return type;
    }
}
