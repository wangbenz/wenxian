package com.wenxian.game.story.agent;

import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryActionContract;

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

    /**
     * 从已经完成的剧情草稿中提取需要长期保存的事实变化。
     *
     * <p>默认实现保持确定性测试替身兼容；生产 Agent 使用独立轻量模型完成投影。剧情草稿不是权威事实，
     * 只有本方法返回的提案继续通过审阅、Reducer 和事务提交后才会落库。</p>
     */
    default ProjectedFacts projectFacts(StoryPromptContext context, StoryModelRoute route,
                                        InvocationContext invocation, StoryPatch narrativeDraft) {
        return new ProjectedFacts(narrativeDraft, List.of());
    }

    /**
     * 在正文生成前把玩家自然语言解释为可校验的事实契约。默认结果不增加约束，供确定性测试替身兼容；
     * 真实模型 Agent 应独立调用轻量语义模型，并保留调用用量。
     */
    default ResolvedAction resolveAction(StoryPromptContext context, StoryModelRoute route,
                                         InvocationContext invocation) {
        return new ResolvedAction(new StoryActionContract("", false, List.of(), List.of(),
                List.of(), "", null), List.of());
    }

    /**
     * 行动契约出现结构性引用错误时执行一次轻量修复；真实的玩家事实冲突不得通过本方法绕过。
     * 修复结果仍须重新经过来源、稳定 ID 与保管关系校验。
     */
    default ResolvedAction repairActionContract(StoryPromptContext context, StoryModelRoute route,
                                                InvocationContext invocation,
                                                StoryActionContract rejected, String problem) {
        return new ResolvedAction(rejected, List.of());
    }

    /**
     * 当提案已经通过 JSON 校验、但被 Reducer 的通用事实边界拒绝时，生成一次紧凑的语义修复。
     *
     * <p>默认实现保持测试替身和其他 Agent 兼容；生产模型 Agent 可以覆盖该方法。调用方最多调用一次，
     * 修复结果仍须重新经过完整 Reducer，不能直接提交。</p>
     */
    default Generated repairRejected(StoryPromptContext context, StoryModelRoute route,
                                     InvocationContext invocation, StoryPatch rejected, String problem) {
        throw new IllegalArgumentException("当前故事 Agent 不支持修复被拒绝的状态提案");
    }

    /**
     * 当候选正文高度复用上一回内容时，不携带旧正文从当前行动重新生成。
     *
     * <p>默认退回一般语义修复以兼容测试替身；生产模型 Agent 应覆盖该方法，并避免再次携带
     * 被拒绝的正文。重新生成结果仍须通过完整审阅、契约和 Reducer 校验。</p>
     */
    default Generated regenerateFreshNarrative(StoryPromptContext context, StoryModelRoute route,
                                               InvocationContext invocation, StoryPatch rejected,
                                               String problem) {
        return repairRejected(context, route, invocation, rejected, problem);
    }

    /**
     * 在状态归并前独立复核正文是否执行了本轮行动、是否替玩家决定，以及是否违背当前客观事实。
     * 默认实现用于确定性测试替身；生产模型 Agent 应返回真实审阅及其模型用量。
     */
    default Review review(StoryPromptContext context, StoryModelRoute route,
                          InvocationContext invocation, StoryPatch proposed) {
        return new Review(true, "", List.of());
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

    record Review(boolean accepted, String problem, boolean requiresFreshNarrative,
                  List<StoryModelGateway.Result> modelResults) {
        public Review(boolean accepted, String problem, List<StoryModelGateway.Result> modelResults) {
            this(accepted, problem, false, modelResults);
        }

        public Review {
            problem = problem == null ? "" : problem.strip();
            modelResults = modelResults == null ? List.of() : List.copyOf(modelResults);
            if (!accepted && problem.isBlank()) throw new IllegalArgumentException("回合审阅拒绝时必须说明原因");
        }
    }

    record ResolvedAction(StoryActionContract contract, List<StoryModelGateway.Result> modelResults) {
        public ResolvedAction {
            if (contract == null) throw new IllegalArgumentException("行动事实契约不能为空");
            modelResults = modelResults == null ? List.of() : List.copyOf(modelResults);
        }
    }

    /** 一次独立事实投影及其模型调用；测试替身可返回空调用并直接沿用草稿。 */
    record ProjectedFacts(StoryPatch patch, List<StoryModelGateway.Result> modelResults) {
        public ProjectedFacts {
            if (patch == null) throw new IllegalArgumentException("事实投影不能为空");
            modelResults = modelResults == null ? List.of() : List.copyOf(modelResults);
        }
    }
}
