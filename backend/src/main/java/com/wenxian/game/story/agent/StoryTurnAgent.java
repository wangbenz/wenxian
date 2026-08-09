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
