package com.wenxian.game.story.agent;

import com.wenxian.game.story.domain.StoryPatch;

import java.util.List;

/**
 * 将一次玩家行动解释为剧情正文和结构化状态提案。
 *
 * <p>Agent 只负责提出变化，不能写数据库、扣除回合或把模型正文直接当作权威事实。调用方必须继续经过
 * StoryPatch 校验、Reducer 归并和事务提交。格式修复产生的所有模型调用都会保留在结果中，便于准确统计
 * Token 与延迟。</p>
 */
public interface StoryTurnAgent {
    Generated generate(StoryPromptContext context, StoryModelRoute route);

    /**
     * 携带本次回合的审计位置生成提案。
     *
     * <p>默认实现保持测试替身和非持久化调用兼容；真实模型 Agent 会使用该信息分别记录首次生成、格式修复等
     * 单次模型请求。审计位置不能进入 Prompt，也不能改变 Agent 的状态提案权限。</p>
     */
    default Generated generate(StoryPromptContext context, StoryModelRoute route, InvocationContext invocation) {
        return generate(context, route);
    }

    /** 单次业务回合在模型用量日志中的稳定位置。 */
    record InvocationContext(String saveId, int turnNumber) {
        public InvocationContext {
            if (saveId == null || saveId.isBlank()) throw new IllegalArgumentException("模型调用缺少命卷ID");
            if (turnNumber < 1) throw new IllegalArgumentException("模型调用回合号必须大于0");
        }
    }

    /** 一次业务推演及其全部模型尝试；最后一个结果是最终被解析的响应。 */
    record Generated(StoryPatch patch, List<StoryModelGateway.Result> modelResults) {
        public Generated {
            modelResults = modelResults == null ? List.of() : List.copyOf(modelResults);
            if (patch == null) throw new IllegalArgumentException("回合提案不能为空");
            if (modelResults.isEmpty()) throw new IllegalArgumentException("回合提案必须保留模型调用记录");
        }

        public StoryModelGateway.Result finalResult() {
            return modelResults.getLast();
        }

        public int inputTokens() {
            return modelResults.stream().mapToInt(StoryModelGateway.Result::inputTokens).sum();
        }

        public int outputTokens() {
            return modelResults.stream().mapToInt(StoryModelGateway.Result::outputTokens).sum();
        }

        public long latencyMs() {
            return modelResults.stream().mapToLong(StoryModelGateway.Result::latencyMs).sum();
        }
    }
}
