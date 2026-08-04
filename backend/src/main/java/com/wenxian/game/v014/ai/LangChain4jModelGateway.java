package com.wenxian.game.v014.ai;

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

@Component
public class LangChain4jModelGateway implements V014ModelGateway {
    @Override
    public Result generate(Request request) {
        if (!"OPENAI_COMPATIBLE".equals(request.adapterType())) {
            throw new IllegalArgumentException("Unsupported model adapter: " + request.adapterType());
        }
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(request.baseUrl())
                .apiKey(request.apiKey())
                .modelName(request.model())
                .temperature(request.temperature())
                .responseFormat(ResponseFormat.JSON)
                // V0.14 keeps thinking disabled for every gameplay model, including Pro.
                // sendThinking(false) only controls whether earlier reasoning_content is sent
                // back; DeepSeek's actual switch is this top-level request parameter.
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
