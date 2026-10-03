package com.wenxian.admin.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/**
 * 调用 game-api 的受控模型验证端口。
 *
 * <p>admin-api 只负责转发管理员身份和脱敏结果，不持有 LangChain4j 调用实现；内部令牌只存在于
 * 两个后端的环境配置，不进入浏览器、响应 DTO 或错误日志。</p>
 */
@Component
public class GameModelValidationClient {
    private final RestClient client;
    private final String token;

    public GameModelValidationClient(
            @Value("${wenxian.game-service.base-url:http://127.0.0.1:18080}") String baseUrl,
            @Value("${wenxian.game-service.model-validation-token:}") String token) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.token = token == null ? "" : token.strip();
    }

    public ValidationResult validate(String providerCode, String modelCode, String checkedBy) {
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型验证内部通道尚未配置");
        }
        try {
            ValidationResult result = client.post()
                    .uri("/api/internal/model-validation/{providerCode}/{modelCode}", providerCode, modelCode)
                    .header("X-Wenxian-Model-Validation-Token", token)
                    .body(Map.of("checkedBy", checkedBy))
                    .retrieve().body(ValidationResult.class);
            if (result == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "游戏后端没有返回验证结果");
            return result;
        } catch (RestClientResponseException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "游戏后端未能完成模型验证（HTTP " + error.getStatusCode().value() + "）");
        } catch (ResponseStatusException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "暂时无法连接游戏后端执行模型验证");
        }
    }

    public record ValidationResult(String id, String status, String capabilityCodes, Integer latencyMs,
                                   String errorCode, String errorSummary, Instant checkedAt) { }
}
