package com.wenxian.game.model.infrastructure;

import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 首批 OpenAI Compatible 客户端工厂，不包含供应商业务判断。 */
@Component
public class OpenAiCompatibleChatModelFactory implements ChatModelFactory {
    @Override
    public ChatModel create(ModelDefinition definition, ModelCredential credential,
                            ModelInvocationOptions options) {
        if (definition.adapterType() != ModelAdapterType.OPENAI_COMPATIBLE) {
            throw new IllegalArgumentException("不支持的模型适配器：" + definition.adapterType());
        }
        if (options.disableThinking()
                && !definition.capabilities().contains(ModelCapability.THINKING_CONTROL)) {
            throw new IllegalArgumentException(definition.identity() + " 未验证思考模式控制能力");
        }
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(definition.baseUrl())
                .apiKey(credential.secret())
                .modelName(definition.apiModelName())
                .temperature(options.temperature())
                .timeout(options.timeout())
                .maxRetries(options.maximumRetries())
                .customParameters(thinkingParameters(definition, options))
                .logRequests(false)
                .logResponses(false);
        if (definition.capabilities().contains(ModelCapability.JSON_MODE)) {
            builder.responseFormat(ResponseFormat.JSON);
        }
        if (options.maximumOutputTokens() != null) {
            builder.maxTokens(Math.min(options.maximumOutputTokens(), definition.maximumOutputTokens()));
        }
        if (options.disableThinking()) {
            builder.sendThinking(false).returnThinking(false);
        }
        return builder.build();
    }

    /** DeepSeek 兼容接口必须收到显式 disabled；仅关闭 LangChain4j 的思考字段转发并不会关闭供应商推理。 */
    private Map<String, Object> thinkingParameters(ModelDefinition definition, ModelInvocationOptions options) {
        if (options.disableThinking() && "DEEPSEEK".equalsIgnoreCase(definition.providerCode())) {
            return Map.of("thinking", Map.of("type", "disabled"));
        }
        return Map.of();
    }
}
