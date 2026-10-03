package com.wenxian.game.model.application;

/**
 * 模型任务单次请求的旁路观测端口。
 *
 * <p>观测只允许保存路由、用量和耗时等元数据，不得保存密钥、Prompt、模型正文或玩家邮箱。
 * 实现失败不能改变模型任务结果。</p>
 */
public interface ModelInvocationObserver {

    void observe(Observation observation);

    /** 不含玩家输入和模型正文的单次调用快照。 */
    record Observation(
            String taskCode,
            String requestId,
            String userId,
            int attemptNumber,
            String providerCode,
            String modelCode,
            String credentialSource,
            boolean fallbackUsed,
            String status,
            int inputTokens,
            int outputTokens,
            int cacheHitTokens,
            int cacheMissTokens,
            long latencyMs,
            String errorCategory
    ) {
    }
}
