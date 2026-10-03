package com.wenxian.game.model.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.config.SecretCipher;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.infrastructure.ChatModelFactory;
import com.wenxian.game.model.persistence.ModelValidationRepository;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 对当前模型配置执行低流量 CHAT 与 JSON_MODE 受控验证，并追加脱敏证据。
 *
 * <p>本用例复用 game-api 的 LangChain4j 模型工厂，不写故事、不扣推演点、不记录 Prompt、响应正文或密钥。
 * 无论成功失败都会留下与开始时配置版本绑定的记录，供三档发布事务判定。</p>
 */
@Service
public class ModelValidationApplicationService {
    private static final Set<ModelCapability> REQUIRED = Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE);
    private final ModelValidationRepository repository;
    private final ChatModelFactory models;
    private final ObjectMapper mapper;
    private final String masterKey;

    public ModelValidationApplicationService(ModelValidationRepository repository, ChatModelFactory models,
                                             ObjectMapper mapper,
                                             @Value("${game.ai-config-master-key:wenxian-local-config-key-change-me}")
                                             String masterKey) {
        this.repository = repository;
        this.models = models;
        this.mapper = mapper;
        this.masterKey = masterKey;
    }

    public Result validate(String providerCode, String modelCode, String checkedBy) {
        ModelValidationRepository.Target target = repository.findTarget(providerCode, modelCode)
                .orElseThrow(() -> new IllegalArgumentException("待验证模型不存在"));
        Instant startedAt = Instant.now();
        String runId = "model-validation-" + UUID.randomUUID();
        String actor = required(checkedBy, 64, "验证操作人不能为空");
        try {
            if (!target.model().capabilities().containsAll(REQUIRED)) {
                throw new ValidationFailure("CAPABILITY_NOT_DECLARED", "模型尚未同时登记 CHAT 与 JSON_MODE 能力");
            }
            if (target.platformSecretCiphertext() == null || target.platformSecretCiphertext().isBlank()) {
                throw new ValidationFailure("PLATFORM_KEY_MISSING", "供应商尚未配置平台密钥");
            }
            String secret = SecretCipher.decrypt(target.platformSecretCiphertext(), masterKey);
            validateChat(target.model(), secret);
            validateJson(target.model(), secret);
            int latency = latency(startedAt);
            ModelValidationRepository.ValidationRun run = run(runId, target, "PASSED",
                    "CHAT,JSON_MODE", latency, null, null, actor);
            repository.insertRun(run);
            return result(run);
        } catch (RuntimeException error) {
            int latency = latency(startedAt);
            String code = error instanceof ValidationFailure failure
                    ? failure.code() : safeCode(error);
            String summary = error instanceof ValidationFailure failure
                    ? failure.getMessage() : "受控验证未通过：" + error.getClass().getSimpleName();
            ModelValidationRepository.ValidationRun run = run(runId, target, "FAILED", "", latency,
                    code, summary, actor);
            repository.insertRun(run);
            return result(run);
        }
    }

    private void validateChat(ModelDefinition source, String secret) {
        LinkedHashSet<ModelCapability> capabilities = new LinkedHashSet<>();
        capabilities.add(ModelCapability.CHAT);
        if (source.capabilities().contains(ModelCapability.THINKING_CONTROL)) {
            capabilities.add(ModelCapability.THINKING_CONTROL);
        }
        ModelDefinition chatDefinition = definition(source, Set.copyOf(capabilities));
        String text = call(chatDefinition, secret, "只回复 PONG");
        if (text.isBlank()) throw new ValidationFailure("CHAT_EMPTY", "普通对话验证返回空内容");
    }

    private void validateJson(ModelDefinition source, String secret) {
        String text = call(source, secret, "只回复一个 JSON 对象：{\"ok\":true}");
        try {
            JsonNode value = mapper.readTree(text);
            if (!value.isObject() || !value.path("ok").asBoolean(false)) {
                throw new ValidationFailure("JSON_SHAPE_INVALID", "JSON 模式没有返回约定对象");
            }
        } catch (ValidationFailure failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new ValidationFailure("JSON_PARSE_FAILED", "JSON 模式返回内容无法解析");
        }
    }

    private String call(ModelDefinition definition, String secret, String prompt) {
        boolean disableThinking = definition.capabilities().contains(ModelCapability.THINKING_CONTROL);
        ModelInvocationOptions options = new ModelInvocationOptions(0, Duration.ofSeconds(30), 0,
                Math.min(64, definition.maximumOutputTokens()), disableThinking);
        var response = models.create(definition, ModelCredential.platform(secret), options)
                .chat(List.of(UserMessage.from(prompt)));
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) return "";
        return response.aiMessage().text().strip();
    }

    private ModelDefinition definition(ModelDefinition source, Set<ModelCapability> capabilities) {
        return new ModelDefinition(source.providerCode(), source.modelCode(), source.baseUrl(),
                source.adapterType(), source.apiModelName(), capabilities,
                source.contextWindow(), source.maximumOutputTokens());
    }

    private ModelValidationRepository.ValidationRun run(String id, ModelValidationRepository.Target target,
                                                        String status, String capabilities, Integer latency,
                                                        String errorCode, String errorSummary, String actor) {
        return new ModelValidationRepository.ValidationRun(id, target.model().providerCode(),
                target.model().modelCode(), target.providerConfigVersion(), target.modelConfigVersion(),
                status, capabilities, latency, errorCode, errorSummary, actor, Instant.now());
    }

    private Result result(ModelValidationRepository.ValidationRun run) {
        return new Result(run.id(), run.status(), run.capabilityCodes(), run.latencyMs(),
                run.errorCode(), run.errorSummary(), run.checkedAt());
    }

    private int latency(Instant startedAt) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0,
                Duration.between(startedAt, Instant.now()).toMillis()));
    }

    private String safeCode(RuntimeException error) {
        String name = error.getClass().getSimpleName().replaceAll("[^A-Za-z0-9]", "_");
        return (name.isBlank() ? "VALIDATION_FAILED" : name).toUpperCase(Locale.ROOT);
    }

    private String required(String value, int maximum, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        String normalized = value.strip();
        if (normalized.length() > maximum) throw new IllegalArgumentException(message);
        return normalized;
    }

    public record Result(String id, String status, String capabilityCodes, Integer latencyMs,
                         String errorCode, String errorSummary, Instant checkedAt) { }

    private static final class ValidationFailure extends RuntimeException {
        private final String code;

        private ValidationFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
