package com.wenxian.v2.ai.langchain4j;

import com.wenxian.v2.ai.application.ModelGateway;
import com.wenxian.v2.ai.application.V2SecretCipher;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LangChain4jModelGateway implements ModelGateway {
    private static final Logger log = LoggerFactory.getLogger(LangChain4jModelGateway.class);

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
        ModelRoute route = routes.require(request.taskType(), request.userId());
        String apiKey = apiKey(route);
        long started = System.nanoTime();
        try {
            ChatResponse response = model(route, request, apiKey).chat(
                    SystemMessage.from(request.systemPrompt()), UserMessage.from(request.userPrompt()));
            TokenUsage tokens = response.tokenUsage();
            ModelResult result = new ModelResult(response.aiMessage().text(), route.providerCode(),
                    response.modelName() == null ? route.modelCode() : response.modelName(), response.id(),
                    tokens == null || tokens.inputTokenCount() == null ? 0 : tokens.inputTokenCount(),
                    tokens == null || tokens.outputTokenCount() == null ? 0 : tokens.outputTokenCount(),
                    elapsedMillis(started));
            usage.success(request, result, route.credentialSource());
            return result;
        } catch (V2ApiException business) {
            throw business;
        } catch (RuntimeException error) {
            Throwable root = rootCause(error);
            log.warn("V2 AI provider call failed: operation={}, provider={}, model={}, type={}, reason={}",
                    request.operationId(), route.providerCode(), route.modelCode(),
                    root.getClass().getSimpleName(), safeReason(root.getMessage()));
            usage.failure(request, route.providerCode(), route.modelCode(), elapsedMillis(started),
                    root.getClass().getSimpleName(), route.credentialSource());
            throw new V2ApiException(HttpStatus.BAD_GATEWAY, "AI_PROVIDER_UNAVAILABLE", "SERVICE",
                    "本轮推演没有完成，命卷状态不会改变。", List.of("稍后使用同一行动重试"));
        }
    }

    private String apiKey(ModelRoute route) {
        if ("USER".equals(route.credentialSource())) {
            if (!properties.credentialEncryptionAvailable()) {
                throw new V2ApiException(HttpStatus.SERVICE_UNAVAILABLE, "USER_AI_DECRYPTION_UNAVAILABLE", "SERVICE",
                        "自己的推演密钥暂时无法读取。", List.of("稍后重试", "切换回平台推演"));
            }
            try {
                return V2SecretCipher.decrypt(route.credentialCiphertext(), properties.credentialMasterKey());
            } catch (RuntimeException error) {
                throw new V2ApiException(HttpStatus.CONFLICT, "USER_AI_CREDENTIAL_UNAVAILABLE", "BUSINESS",
                        "自己的推演密钥已经失效，系统没有改用平台额度。", List.of("重新验证密钥", "切换回平台推演"));
            }
        }
        if (!properties.platformAvailable()) {
            throw new V2ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_NOT_CONFIGURED", "SERVICE",
                    "当前推演服务尚未开放。", List.of("稍后重新进入"));
        }
        return properties.platformApiKey();
    }

    private ChatModel model(ModelRoute route, ModelRequest request, String apiKey) {
        if ("USER".equals(route.credentialSource())) return buildModel(route, request, apiKey);
        String key = route.providerCode() + ':' + route.modelCode() + ':' + request.maxOutputTokens()
                + ':' + request.temperature();
        return platformModels.computeIfAbsent(key, ignored -> buildModel(route, request, apiKey));
    }

    private ChatModel buildModel(ModelRoute route, ModelRequest request, String apiKey) {
        return OpenAiChatModel.builder()
                .baseUrl(route.baseUrl())
                .apiKey(apiKey)
                .modelName(route.modelCode())
                .temperature(request.temperature())
                .maxTokens(request.maxOutputTokens())
                .responseFormat(ResponseFormat.JSON)
                .customParameters(Map.of("thinking", Map.of("type", "disabled")))
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .maxRetries(properties.maxRetries())
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private String safeReason(String message) {
        if (message == null || message.isBlank()) return "no provider message";
        String safe = message.replaceAll("sk-[A-Za-z0-9_-]+", "sk-***")
                .replaceAll("[\\r\\n\\t]+", " ").strip();
        return safe.length() <= 500 ? safe : safe.substring(0, 500) + "…";
    }
}
