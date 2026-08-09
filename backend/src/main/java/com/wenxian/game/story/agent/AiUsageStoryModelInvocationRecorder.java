package com.wenxian.game.story.agent;

import com.wenxian.game.persistence.AiUsageStore;
import org.springframework.stereotype.Component;

/**
 * 将可信叙事的每次模型请求写入现有用量事实表。
 *
 * <p>用量记录是旁路观测，不参与命卷事务。即使用量表短暂不可写，已完成的模型响应仍可继续进入 Reducer；
 * 失败详情只保存归一化类别或异常类名，不保存供应商正文。</p>
 */
@Component
public class AiUsageStoryModelInvocationRecorder implements StoryModelInvocationRecorder {
    private final AiUsageStore usage;

    public AiUsageStoryModelInvocationRecorder(AiUsageStore usage) {
        this.usage = usage;
    }

    @Override
    public void succeeded(StoryTurnAgent.InvocationContext invocation, String operation,
                          StoryModelRoute route, StoryModelGateway.Result result) {
        if (invocation == null || result == null) return;
        safelyRecord(invocation, operation, route, "SUCCESS", result.model(), result.inputTokens(),
                result.outputTokens(), result.latencyMs(), "");
    }

    @Override
    public void failed(StoryTurnAgent.InvocationContext invocation, String operation,
                       StoryModelRoute route, long latencyMs, RuntimeException failure) {
        if (invocation == null) return;
        safelyRecord(invocation, operation, route, "FAILED", route.model(), 0, 0, latencyMs,
                failureCategory(failure));
    }

    private void safelyRecord(StoryTurnAgent.InvocationContext invocation, String operation,
                              StoryModelRoute route, String status, String model,
                              int inputTokens, int outputTokens, long latencyMs, String error) {
        try {
            usage.record(invocation.saveId(), invocation.turnNumber(), operation, route.provider(),
                    route.credentialSource(), model, status, inputTokens, outputTokens,
                    0, 0, latencyMs, error);
        } catch (RuntimeException ignored) {
            // 观测数据不能中断已经完成或正在失败返回的玩家回合。
        }
    }

    private String failureCategory(RuntimeException failure) {
        if (failure instanceof StoryModelFailureException modelFailure) {
            return modelFailure.kind().name();
        }
        return failure == null ? "UNKNOWN" : failure.getClass().getSimpleName();
    }
}
