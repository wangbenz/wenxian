package com.wenxian.game.v014.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.api.GameApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

@Component
public class V014ProviderCredentialVerifier {
    public void verify(String baseUrl, String adapterType, String apiKey, String apiModelName) {
        if (!"OPENAI_COMPATIBLE".equals(adapterType)) {
            throw GameApiException.of(HttpStatus.BAD_REQUEST, "PROVIDER_ADAPTER_UNSUPPORTED", "CREDENTIAL",
                    "暂不支持这个推演服务", "当前版本还没有启用该供应商的连接适配器。",
                    List.of("选择其他推演服务"));
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", apiModelName,
                    "messages", List.of(Map.of("role", "user", "content", "只回复：可用")),
                    "temperature", 0,
                    "max_tokens", 8,
                    "stream", false
            );
            JsonNode response = RestClient.create(baseUrl).post().uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(body).retrieve().body(JsonNode.class);
            if (response == null || !response.path("choices").isArray()) {
                throw GameApiException.temporary("PERSONAL_KEY_VERIFY_UNAVAILABLE", "密钥暂时无法验证",
                        "推演服务没有返回有效验证结果，密钥尚未保存。", List.of("稍后重新验证"),
                        "credential verification returned no choices", null);
            }
        } catch (RestClientResponseException error) {
            throw switch (error.getStatusCode().value()) {
                case 401, 403 -> GameApiException.of(HttpStatus.BAD_REQUEST, "PERSONAL_KEY_INVALID", "CREDENTIAL",
                        "推演密钥无效", "密钥验证未通过，请检查所属服务、内容是否完整或是否已经失效。",
                        List.of("确认推演服务后重新复制完整密钥"));
                case 402 -> GameApiException.of(HttpStatus.BAD_REQUEST, "PERSONAL_KEY_BALANCE_EMPTY", "CREDENTIAL",
                        "密钥账户余额不足", "该密钥所属账户当前无法继续生成内容。",
                        List.of("为对应账户补充余额", "更换其他可用密钥"));
                case 429 -> GameApiException.of(HttpStatus.TOO_MANY_REQUESTS, "PERSONAL_KEY_RATE_LIMITED", "CREDENTIAL",
                        "密钥请求过于频繁", "推演服务暂时限制了该密钥的请求频率。",
                        List.of("稍后重新验证"));
                default -> GameApiException.temporary("PERSONAL_KEY_VERIFY_UNAVAILABLE", "密钥暂时无法验证",
                        "推演服务暂时没有完成验证，密钥尚未保存。", List.of("稍后重新验证"),
                        "credential verify status=" + error.getStatusCode().value(), error);
            };
        } catch (RestClientException error) {
            throw GameApiException.temporary("PERSONAL_KEY_VERIFY_UNAVAILABLE", "密钥暂时无法验证",
                    "当前无法连接所选推演服务，密钥尚未保存。", List.of("检查服务地址后重试", "稍后再试"),
                    error.getMessage(), error);
        }
    }
}
