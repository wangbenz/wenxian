package com.wenxian.game.model.infrastructure;

import com.wenxian.game.model.domain.ModelTask;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 在 ChatModel 之上按任务复用 LangChain4j AI Service 代理。 */
@Component
public class AiServiceRegistry {
    private final PromptResourceLoader prompts;
    private final int maximumEntries;
    private final Map<Key, Object> cache = new LinkedHashMap<>(16, 0.75f, true);

    public AiServiceRegistry(PromptResourceLoader prompts,
                             @Value("${wenxian.model.ai-service-registry-maximum-entries:512}") int maximumEntries) {
        if (prompts == null) throw new IllegalArgumentException("Prompt 加载器不能为空");
        if (maximumEntries <= 0) throw new IllegalArgumentException("AI Service 缓存上限必须大于零");
        this.prompts = prompts;
        this.maximumEntries = maximumEntries;
    }

    public synchronized <T> T getOrCreate(ModelTask task,
                                          ChatModelRegistry.RegisteredChatModel registeredModel,
                                          Class<T> serviceType) {
        if (task == null || registeredModel == null || serviceType == null) {
            throw new IllegalArgumentException("AI Service 注册参数不能为空");
        }
        if (!serviceType.isInterface()) throw new IllegalArgumentException("AI Service 必须是接口");
        PromptResourceLoader.PromptResource prompt = prompts.load(task);
        Key key = new Key(task, prompt.version(), registeredModel.identity(), serviceType);
        Object existing = cache.get(key);
        if (existing != null) return serviceType.cast(existing);
        AiServices<T> builder = AiServices.builder(serviceType)
                .chatModel(registeredModel.model())
                .systemMessage(prompt.content());
        if (task == ModelTask.TURN_DIRECTOR) {
            builder.registerListener((AiServiceResponseReceivedListener)
                    event -> ModelResponseCapture.record(event.response()));
        }
        T service = builder.build();
        cache.put(key, service);
        evictEldest();
        return service;
    }

    public synchronized int size() {
        return cache.size();
    }

    private void evictEldest() {
        while (cache.size() > maximumEntries) {
            Key eldest = cache.keySet().iterator().next();
            cache.remove(eldest);
        }
    }

    private record Key(ModelTask task, String promptVersion, String modelIdentity, Class<?> serviceType) {}
}
