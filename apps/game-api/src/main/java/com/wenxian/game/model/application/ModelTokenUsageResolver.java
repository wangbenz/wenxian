package com.wenxian.game.model.application;

import dev.langchain4j.service.Result;

import java.util.Optional;

/** 将类型化 AI Service 的供应商扩展用量转换为中性缓存用量。 */
public interface ModelTokenUsageResolver {

    Optional<CacheUsage> resolve(Result<?> result);

    record CacheUsage(int hitTokens, int missTokens) {
        public CacheUsage {
            hitTokens = Math.max(0, hitTokens);
            missTokens = Math.max(0, missTokens);
        }
    }
}
