package com.wenxian.game.story.agent;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.UnresolvedModelServerException;
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
        ChatResponse response;
        try {
            response = model.chat(List.of(
                    SystemMessage.from(request.systemPrompt()),
                    UserMessage.from(request.userPrompt())
            ));
        } catch (RuntimeException error) {
            throw normalize(error);
        }
        long latency = (System.nanoTime() - started) / 1_000_000;
        TokenUsage usage = response.tokenUsage();
        int input = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int output = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        String text = response.aiMessage() == null ? "" : response.aiMessage().text();
        return new Result(text == null ? "" : text, response.id(),
                response.modelName() == null ? request.model() : response.modelName(), input, output, latency);
    }

    /** 将传输实现异常收敛为不含供应商细节的中性失败类型。 */
    RuntimeException normalize(RuntimeException error) {
        if (error instanceof StoryModelFailureException) return error;
        if (error instanceof AuthenticationException) {
            return failure(StoryModelFailureException.Kind.AUTHENTICATION, "model authentication rejected", error);
        }
        if (error instanceof ContentFilteredException) {
            return failure(StoryModelFailureException.Kind.CONTENT_FILTERED, "model content filtered", error);
        }
        if (error instanceof RateLimitException) {
            return failure(StoryModelFailureException.Kind.RATE_LIMITED, "model rate limited", error);
        }
        if (error instanceof InvalidRequestException || error instanceof ModelNotFoundException
                || error instanceof UnresolvedModelServerException) {
            return failure(StoryModelFailureException.Kind.REQUEST_REJECTED, "model request rejected", error);
        }
        if (error instanceof RetriableException || error instanceof LangChain4jException) {
            return failure(StoryModelFailureException.Kind.TEMPORARY_UNAVAILABLE,
                    "model temporarily unavailable", error);
        }
        return error;
    }

    private StoryModelFailureException failure(StoryModelFailureException.Kind kind, String diagnostic,
                                                RuntimeException cause) {
        return new StoryModelFailureException(kind, diagnostic, cause);
    }
}
