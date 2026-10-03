package com.wenxian.game.story.agent.ai;

import com.wenxian.game.model.infrastructure.PromptResourceLoader;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 完整故事类型化 AI Service 的受控入口，固定使用正式故事合同及其资源 Prompt。
 *
 * <p>底层供应商模型仍由统一 ChatModelRegistry 有界复用。AI Service 绑定本次原稿捕获作用域，
 * 不跨调用缓存该代理，防止上一轮原文或辅助降级记录泄入其他玩家请求。资源缺失在启动时失败，
 * 不回退旧导演 Prompt，也不依赖测试供应商、密钥或固定模型参数。</p>
 */
@Component
public final class StoryCandidateAiServiceRegistry {
    private final PromptResourceLoader.PromptResource prompt;

    public StoryCandidateAiServiceRegistry(PromptResourceLoader prompts) {
        prompt = Objects.requireNonNull(prompts, "Prompt 加载器不能为空").loadStoryCandidate();
    }

    StoryCandidateProtocol.Ai create(ChatModel guardedModel, Consumer<ChatResponse> capture) {
        Objects.requireNonNull(guardedModel, "完整故事缺少已路由模型");
        Objects.requireNonNull(capture, "完整故事缺少本次响应捕获");
        return AiServices.builder(StoryCandidateProtocol.Ai.class).chatModel(guardedModel)
                .systemMessage(prompt.content())
                .registerListener((AiServiceResponseReceivedListener) event -> capture.accept(event.response())).build();
    }
}
