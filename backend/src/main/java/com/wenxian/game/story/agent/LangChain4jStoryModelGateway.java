package com.wenxian.game.story.agent;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 基于 LangChain4j 的开放兼容模型网关。
 *
 * <p>首批只启用 OpenAI Compatible 适配器。所有故事类请求默认返回 JSON，并关闭供应商思考输出；
 * 后续增加模型时只扩展该适配层，不让供应商参数进入剧情、修正或状态归并代码。</p>
 */
@Component
public class LangChain4jStoryModelGateway implements StoryModelGateway {
    @Override
    public Result generate(Request request) {
        if (!"OPENAI_COMPATIBLE".equals(request.adapterType())) {
            throw new IllegalArgumentException("不支持的模型适配器：" + request.adapterType());
        }
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(request.baseUrl())
                .apiKey(request.apiKey())
                .modelName(request.model())
                .temperature(request.temperature())
                .responseFormat(ResponseFormat.JSON)
                .customParameters("DEEPSEEK".equals(request.provider())
                        ? Map.of("thinking", Map.of("type", "disabled")) : Map.of())
                .sendThinking(false)
                .returnThinking(false)
                .timeout(Duration.ofSeconds(90))
                .maxRetries(1)
                .logRequests(false)
                .logResponses(false)
                .build();
        long started = System.nanoTime();
        ChatResponse response = model.chat(List.of(
                SystemMessage.from(request.systemPrompt()),
                UserMessage.from(request.userPrompt())
        ));
        long latency = (System.nanoTime() - started) / 1_000_000;
        TokenUsage usage = response.tokenUsage();
        int input = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int output = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        String text = response.aiMessage() == null ? "" : response.aiMessage().text();
        return new Result(text == null ? "" : text, response.id(),
                response.modelName() == null ? request.model() : response.modelName(), input, output, latency);
    }
}
