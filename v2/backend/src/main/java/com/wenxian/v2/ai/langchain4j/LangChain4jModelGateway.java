package com.wenxian.v2.ai.langchain4j;

import com.wenxian.v2.ai.application.ModelGateway;
import com.wenxian.v2.ai.domain.ModelContracts.ModelRequest;
import com.wenxian.v2.ai.domain.ModelContracts.ModelResult;
import com.wenxian.v2.ai.persistence.AiModelRouteRepository;
import com.wenxian.v2.ai.persistence.AiModelRouteRepository.ModelRoute;
import com.wenxian.v2.ai.persistence.AiUsageRepository;
import com.wenxian.v2.api.V2ApiException;
import com.wenxian.v2.config.AiProperties;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LangChain4jModelGateway implements ModelGateway {
    private final AiProperties properties;
    private final AiModelRouteRepository routes;
    private final AiUsageRepository usage;
    private final Map<String, ChatModel> platformModels = new ConcurrentHashMap<>();

    public LangChain4jModelGateway(AiProperties properties, AiModelRouteRepository routes,
                                   AiUsageRepository usage) {
        this.properties = properties;
        this.routes = routes;
        this.usage = usage;
    }

    @Override
    public ModelResult completeJson(ModelRequest request) {
        if (!properties.platformAvailable()) {
            throw new V2ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "SERVICE",
                    "当前推演服务尚未开放。", List.of("稍后重新进入"));
        }
        ModelRoute route = routes.require(request.taskType());
        long started = System.nanoTime();
        try {
            ChatResponse response = model(route, request).chat(
                    SystemMessage.from(request.systemPrompt()), UserMessage.from(request.userPrompt()));
            TokenUsage tokens = response.tokenUsage();
            ModelResult result = new ModelResult(response.aiMessage().text(), route.providerCode(),
                    response.modelName() == null ? route.modelCode() : response.modelName(), response.id(),
                    tokens == null || tokens.inputTokenCount() == null ? 0 : tokens.inputTokenCount(),
                    tokens == null || tokens.outputTokenCount() == null ? 0 : tokens.outputTokenCount(),
                    elapsedMillis(started));
            usage.success(request, result);
            return result;
        } catch (V2ApiException business) {
            throw business;
        } catch (RuntimeException error) {
            usage.failure(request, route.providerCode(), route.modelCode(), elapsedMillis(started),
                    error.getClass().getSimpleName());
            throw new V2ApiException(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_UNAVAILABLE", "SERVICE",
                    "本轮推演没有完成，命卷状态不会改变。", List.of("稍后使用同一行动重试"));
        }
    }

    private ChatModel model(ModelRoute route, ModelRequest request) {
        String key = route.providerCode() + ':' + route.modelCode() + ':' + request.maxOutputTokens()
                + ':' + request.temperature();
        return platformModels.computeIfAbsent(key, ignored -> OpenAiChatModel.builder()
                .baseUrl(route.baseUrl())
                .apiKey(properties.platformApiKey())
                .modelName(route.modelCode())
                .temperature(request.temperature())
                .maxTokens(request.maxOutputTokens())
                .responseFormat(ResponseFormat.JSON)
                .customParameters(Map.of("thinking", Map.of("type", "disabled")))
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .maxRetries(properties.maxRetries())
                .logRequests(false)
                .logResponses(false)
                .build());
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
