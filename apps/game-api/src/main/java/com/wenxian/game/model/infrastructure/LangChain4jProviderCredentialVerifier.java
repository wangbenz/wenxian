package com.wenxian.game.model.infrastructure;

import com.wenxian.game.api.GameApiException;
import com.wenxian.game.model.application.ProviderCredentialVerifier;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** 使用统一 LangChain4j 客户端工厂验证玩家密钥，避免设置链路另造 HTTP 协议实现。 */
@Component
public class LangChain4jProviderCredentialVerifier implements ProviderCredentialVerifier {
    private final ChatModelFactory models;

    public LangChain4jProviderCredentialVerifier(ChatModelFactory models) {
        this.models = models;
    }

    @Override
    public void verify(ModelDefinition model, String apiKey) {
        try {
            boolean disableThinking = model.capabilities().contains(ModelCapability.THINKING_CONTROL);
            var options = new ModelInvocationOptions(0, Duration.ofSeconds(30), 0, 16, disableThinking);
            var response = models.create(model, ModelCredential.user(apiKey), options)
                    .chat(List.of(UserMessage.from("只回复一个 JSON 对象：{\"ok\":true}")));
            if (response == null || response.aiMessage() == null
                    || response.aiMessage().text() == null || response.aiMessage().text().isBlank()) {
                throw unavailable("credential verification returned empty response", null);
            }
        } catch (AuthenticationException error) {
            throw GameApiException.of(HttpStatus.BAD_REQUEST, "PERSONAL_KEY_INVALID", "CREDENTIAL",
                    "推演密钥无效", "密钥验证未通过，请检查所属服务、内容是否完整或是否已经失效。",
                    List.of("确认推演服务后重新复制完整密钥"));
        } catch (RateLimitException error) {
            throw GameApiException.of(HttpStatus.TOO_MANY_REQUESTS, "PERSONAL_KEY_RATE_LIMITED", "CREDENTIAL",
                    "密钥请求过于频繁", "推演服务暂时限制了该密钥的请求频率。", List.of("稍后重新验证"));
        } catch (RetriableException error) {
            throw unavailable("credential verification temporarily unavailable", error);
        } catch (GameApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("credential verification failed: " + error.getClass().getSimpleName(), error);
        }
    }

    private GameApiException unavailable(String diagnostic, RuntimeException cause) {
        return GameApiException.temporary("PERSONAL_KEY_VERIFY_UNAVAILABLE", "密钥暂时无法验证",
                "推演服务暂时没有完成验证，密钥尚未保存。", List.of("稍后重新验证"), diagnostic, cause);
    }
}
