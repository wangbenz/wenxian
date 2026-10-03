package com.wenxian.game.story.agent.ai;

import com.wenxian.game.model.domain.ModelTask;
import com.wenxian.game.model.infrastructure.AiServiceRegistry;
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import org.springframework.stereotype.Component;

/**
 * 故事领域使用的 AI Service 门面。
 *
 * <p>它确保任务、Prompt 和接口类型保持唯一对应，剧情代码不直接操作通用注册表。</p>
 */
@Component
public class StoryAiServiceRegistry {
    private final AiServiceRegistry registry;

    public StoryAiServiceRegistry(AiServiceRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("AI Service 注册表不能为空");
        this.registry = registry;
    }

    public CharacterCreationAi characterCreation(ChatModelRegistry.RegisteredChatModel model) {
        return service(ModelTask.CHARACTER_CREATION, model, CharacterCreationAi.class);
    }

    public StoryCorrectionAi storyCorrection(ChatModelRegistry.RegisteredChatModel model) {
        return service(ModelTask.STORY_CORRECTION, model, StoryCorrectionAi.class);
    }

    public TurnDirectorAi turnDirector(ChatModelRegistry.RegisteredChatModel model) {
        return service(ModelTask.TURN_DIRECTOR, model, TurnDirectorAi.class);
    }

    public NarrativeWriterAi narrativeWriter(ChatModelRegistry.RegisteredChatModel model) {
        return service(ModelTask.NARRATIVE_WRITER, model, NarrativeWriterAi.class);
    }

    public ContextSummaryAi contextSummary(ChatModelRegistry.RegisteredChatModel model) {
        return service(ModelTask.CONTEXT_SUMMARY, model, ContextSummaryAi.class);
    }

    public FormatRepairAi formatRepair(ChatModelRegistry.RegisteredChatModel model) {
        return service(ModelTask.FORMAT_REPAIR, model, FormatRepairAi.class);
    }

    <T> T service(ModelTask task, ChatModelRegistry.RegisteredChatModel model, Class<T> serviceType) {
        Class<?> expected = StoryAiServiceContracts.type(task);
        if (!expected.equals(serviceType)) {
            throw new IllegalArgumentException(task + " 只能使用 " + expected.getSimpleName());
        }
        return registry.getOrCreate(task, model, serviceType);
    }
}
