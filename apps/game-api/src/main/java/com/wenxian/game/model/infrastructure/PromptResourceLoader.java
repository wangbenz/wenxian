package com.wenxian.game.model.infrastructure;

import com.wenxian.game.model.domain.ModelTask;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** 从 classpath 读取按任务维护的 Prompt，Java 代码不再持有长篇 Prompt 常量。 */
@Component
public class PromptResourceLoader {
    private static final String VERSION_PREFIX = "# prompt-version:";
    private static final Map<ModelTask, String> PATHS = paths();

    public PromptResource load(ModelTask task) {
        String path = PATHS.get(task);
        if (path == null) throw new IllegalArgumentException("未配置任务 Prompt：" + task);
        return load(task, path);
    }

    /** 正式完整故事使用独立合同；旧导演 Prompt 只供历史离线对照，不能按同一个接口混用。 */
    public PromptResource loadStoryCandidate() {
        return load(ModelTask.TURN_DIRECTOR, "prompts/model/story-candidate-system.txt");
    }

    private PromptResource load(ModelTask task, String path) {
        try {
            String content = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).strip();
            String firstLine = content.lines().findFirst().orElse("").strip();
            if (!firstLine.startsWith(VERSION_PREFIX)) {
                throw new IllegalStateException("Prompt 缺少版本头：" + path);
            }
            String version = firstLine.substring(VERSION_PREFIX.length()).strip();
            if (version.isBlank()) throw new IllegalStateException("Prompt 版本不能为空：" + path);
            int lineBreak = content.indexOf('\n');
            if (lineBreak < 0) throw new IllegalStateException("Prompt 缺少正文：" + path);
            String body = content.substring(lineBreak + 1).strip();
            if (body.isBlank()) throw new IllegalStateException("Prompt 正文不能为空：" + path);
            return new PromptResource(task, path, version, body);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取任务 Prompt：" + path, error);
        }
    }

    private static Map<ModelTask, String> paths() {
        Map<ModelTask, String> paths = new EnumMap<>(ModelTask.class);
        paths.put(ModelTask.CHARACTER_CREATION, "prompts/model/character-creation-system.txt");
        paths.put(ModelTask.STORY_CORRECTION, "prompts/model/story-correction-system.txt");
        paths.put(ModelTask.TURN_DIRECTOR, "prompts/model/turn-director-system.txt");
        paths.put(ModelTask.NARRATIVE_WRITER, "prompts/model/narrative-writer-system.txt");
        paths.put(ModelTask.CONTEXT_SUMMARY, "prompts/model/context-summary-system.txt");
        paths.put(ModelTask.FORMAT_REPAIR, "prompts/model/format-repair-system.txt");
        return Map.copyOf(paths);
    }

    public record PromptResource(ModelTask task, String path, String version, String content) {}
}
