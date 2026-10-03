package com.wenxian.game.model.infrastructure;

import com.wenxian.game.model.application.ModelInvocationObserver;
import com.wenxian.game.persistence.AiUsageStore;
import org.springframework.stereotype.Component;

/** 将中性模型调用观测写入统一用量表。 */
@Component
public class AiUsageModelInvocationObserver implements ModelInvocationObserver {
    private final AiUsageStore usageStore;

    public AiUsageModelInvocationObserver(AiUsageStore usageStore) {
        this.usageStore = usageStore;
    }

    @Override
    public void observe(Observation observation) {
        usageStore.recordModelAttempt(observation.requestId(), observation.userId(), observation.attemptNumber(),
                observation.taskCode(), observation.providerCode(), observation.credentialSource(),
                observation.modelCode(), observation.fallbackUsed(), observation.status(),
                observation.inputTokens(), observation.outputTokens(), observation.cacheHitTokens(),
                observation.cacheMissTokens(), observation.latencyMs(), observation.errorCategory());
    }
}
