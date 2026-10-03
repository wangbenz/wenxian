package com.wenxian.game.model.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.application.ModelTokenUsageResolver;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 读取 OpenAI Compatible 响应中已经明确提供的缓存 Token 明细。 */
@Component
public class OpenAiTokenUsageResolver implements ModelTokenUsageResolver {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Optional<CacheUsage> resolve(Result<?> result) {
        if (result == null) return Optional.empty();
        Optional<CacheUsage> compatible = compatibleCacheUsage(result);
        if (compatible.isPresent()) return compatible;
        TokenUsage tokenUsage = result.tokenUsage();
        if (!(tokenUsage instanceof OpenAiTokenUsage usage)
                || usage.inputTokensDetails() == null
                || usage.inputTokensDetails().cachedTokens() == null) {
            return Optional.empty();
        }
        int input = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int hit = Math.min(input, Math.max(0, usage.inputTokensDetails().cachedTokens()));
        return Optional.of(new CacheUsage(hit, Math.max(0, input - hit)));
    }

    /**
     * DeepSeek 的兼容响应把缓存明细放在顶层 usage 扩展字段，LangChain4j 当前不会映射到
     * {@link OpenAiTokenUsage}。这里只读解析最终响应元数据，失败时退回通用用量，不影响剧情结果。
     */
    private Optional<CacheUsage> compatibleCacheUsage(Result<?> result) {
        try {
            if (result.finalResponse() == null
                    || !(result.finalResponse().metadata() instanceof OpenAiChatResponseMetadata metadata)
                    || metadata.rawHttpResponse() == null || metadata.rawHttpResponse().body() == null) {
                return Optional.empty();
            }
            JsonNode usage = JSON.readTree(metadata.rawHttpResponse().body()).path("usage");
            if (!usage.has("prompt_cache_hit_tokens") && !usage.has("prompt_cache_miss_tokens")) {
                return Optional.empty();
            }
            return Optional.of(new CacheUsage(
                    usage.path("prompt_cache_hit_tokens").asInt(0),
                    usage.path("prompt_cache_miss_tokens").asInt(0)));
        } catch (RuntimeException | java.io.IOException ignored) {
            return Optional.empty();
        }
    }
}
