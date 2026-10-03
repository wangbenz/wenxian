package com.wenxian.game.story.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 单回合有序结果及其只读交接记录，不是另一套可重放的状态命令。
 *
 * <p>模型只写一次子结果和带来源声明；编译器校验依赖并附加独立的境界裁决，派生整轮摘要和记忆。
 * 已接受记录与状态补丁一起提交。声明只证明当时有人这样说，不能自动进入世界事实或人物认知。</p>
 */
public final class OrderedTurnResult {
    public static final int VERSION = 2;
    public static final int MAX_STEPS = 16;
    public static final int MAX_STATEMENTS = 8;

    private OrderedTurnResult() {}

    public enum Resolution { SUCCEEDED, PARTIAL, FAILED, NOT_EXECUTED }
    public enum Expected { SUCCEEDED, PARTIAL, FAILED }
    public enum StopReason { ACTION_RESOLVED, DECISION_REQUIRED, BLOCKED }

    public record Proposal(List<Step> steps, Stop stop) {
        public Proposal { steps = copy(steps); }
    }

    /** 数组位置即步骤索引；依赖只允许引用前序步骤，空依赖表示独立发生。 */
    public record Step(String action, Resolution resolution, String result, Boolean realmProgressBinding,
                       List<Integer> assetStepIndexes, List<Dependency> dependsOn,
                       List<Statement> statements) {
        public Step {
            assetStepIndexes = copy(assetStepIndexes);
            dependsOn = copy(dependsOn);
            statements = copy(statements);
        }
    }

    public record Dependency(int stepIndex, Expected expected) {}

    /** sourceRef 可为空；临时说话者只保留标签，不为一次报价强建长期人物。 */
    public record Statement(String sourceRef, String sourceLabel, String statement) {}
    public record Stop(StopReason reason, String detail) {}

    public record Accepted(int protocolVersion, List<AcceptedStep> steps, Stop stop) {
        public Accepted { steps = copy(steps); }

        /** 粗粒度整体结果不替代逐步完成情况；未执行的条件分支不参与成功率统计。 */
        public StoryPatch.TurnOutcome outcome() {
            List<AcceptedStep> executed = steps.stream()
                    .filter(step -> step.resolution() != Resolution.NOT_EXECUTED).toList();
            StoryPatch.OutcomeResolution resolution = executed.isEmpty()
                    || executed.stream().allMatch(step -> step.resolution() == Resolution.FAILED)
                    ? StoryPatch.OutcomeResolution.FAILED
                    : executed.stream().allMatch(step -> step.resolution() == Resolution.SUCCEEDED)
                    && (stop.reason() == StopReason.ACTION_RESOLVED
                    || steps.stream().noneMatch(step -> step.resolution() == Resolution.NOT_EXECUTED))
                    ? StoryPatch.OutcomeResolution.SUCCEEDED : StoryPatch.OutcomeResolution.PARTIAL;
            return new StoryPatch.TurnOutcome(resolution, summary());
        }

        public String summary() {
            String results = steps.stream().map(step -> "第" + (step.stepIndex() + 1) + "步（"
                    + label(step.resolution()) + "）：" + step.action() + "；"
                    + step.result() + (step.realmResult() == null ? "" : "；境界结果：" + step.realmResult().result()))
                    .collect(Collectors.joining("\n"));
            return stop.detail() == null || stop.detail().isBlank() ? results : results + "\n停止说明：" + stop.detail();
        }

        /** 每轮一条确定性 EVENT 回顾；每条来源声明派生 CLUE，正文模型不再另写记忆。 */
        public List<StoryPatch.MemoryDraft> memories() {
            List<StoryPatch.MemoryDraft> memories = new ArrayList<>();
            memories.add(new StoryPatch.MemoryDraft("EVENT", "行动结果回顾", summary(), "NORMAL", List.of()));
            for (AcceptedStep step : steps) {
                for (Statement statement : step.statements()) {
                    memories.add(new StoryPatch.MemoryDraft("CLUE", "记录当轮第" + (step.stepIndex() + 1)
                            + "步的来源声明", "记录当轮第" + (step.stepIndex() + 1) + "步，"
                            + statement.sourceLabel() + "当时表示：" + statement.statement(), "NORMAL", List.of()));
                }
            }
            return List.copyOf(memories);
        }

        public StoryPatch.DecisionCheckpoint checkpoint() {
            return new StoryPatch.DecisionCheckpoint(stop.reason() == StopReason.DECISION_REQUIRED,
                    stop.reason() == StopReason.DECISION_REQUIRED ? stop.detail() : null);
        }
    }

    public record AcceptedStep(int stepIndex, String action, Resolution resolution, String result,
                               boolean cultivation, List<Integer> assetStepIndexes,
                               List<Dependency> dependsOn, List<Statement> statements,
                               @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                               RealmResult realmResult) {
        /** 旧 v1 已保存步骤没有独立境界结果，不从旧正文补造教学。 */
        public AcceptedStep(int stepIndex, String action, Resolution resolution, String result,
                            boolean cultivation, List<Integer> assetStepIndexes,
                            List<Dependency> dependsOn, List<Statement> statements) {
            this(stepIndex, action, resolution, result, cultivation, assetStepIndexes, dependsOn, statements, null);
        }

        public AcceptedStep {
            assetStepIndexes = copy(assetStepIndexes);
            dependsOn = copy(dependsOn);
            statements = copy(statements);
        }
    }

    /** 只由已接受修行补丁派生的境界结果；不替代步骤经历或教师声明，也不是新的写状态命令。 */
    public record RealmResult(StoryPatch.CultivationResolution resolution, String result) {}

    /** 只捕获可修复的候选形状或依赖错误，不包装数据库、网络、权限或程序故障。 */
    public static final class ProtocolException extends IllegalArgumentException {
        private final String path;
        private final String code;

        public ProtocolException(String path, String code, String message) {
            super(path + " [" + code + "] " + message);
            this.path = path;
            this.code = code;
        }

        public String path() { return path; }
        public String code() { return code; }
    }

    /** Reducer 分配正式 ID 后解析声明来源；缺映射是服务端接线错误，不能再次调用模型修复。 */
    public static Accepted resolveRefs(Accepted accepted, List<StoryPatch.EntityPatch> entities) {
        if (accepted == null) return null;
        Map<String, String> ids = new HashMap<>();
        for (StoryPatch.EntityPatch entity : entities) {
            if (entity.referenceKey() == null || entity.referenceKey().isBlank()) continue;
            String previous = ids.putIfAbsent(entity.referenceKey(), entity.entityId());
            if (previous != null && !previous.equals(entity.entityId())) {
                throw new IllegalStateException("有序结果的来源引用映射重复");
            }
        }
        return new Accepted(accepted.protocolVersion(), accepted.steps().stream().map(step ->
                new AcceptedStep(step.stepIndex(), step.action(), step.resolution(), step.result(),
                        step.cultivation(), step.assetStepIndexes(), step.dependsOn(), step.statements().stream()
                        .map(statement -> {
                            String ref = statement.sourceRef();
                            if (StoryReferenceAlias.isAlias(ref)) {
                                ref = ids.get(StoryReferenceAlias.keyOf(ref));
                                if (ref == null || ref.isBlank()) throw new IllegalStateException("有序结果缺少正式声明来源引用");
                            }
                            return new Statement(ref, statement.sourceLabel(), statement.statement());
                        }).toList(), step.realmResult())).toList(), accepted.stop());
    }

    /** 新回合的损坏结构或未知版本必须拒绝读取，不能用旧摘要降级继续写入。 */
    public static void validateAccepted(Accepted accepted) {
        try {
            OrderedTurnResultCompiler.validateAccepted(accepted);
        } catch (ProtocolException rejected) {
            throw new IllegalStateException("已保存有序结果不受支持或结构损坏：" + rejected.getMessage(), rejected);
        }
    }

    private static String label(Resolution resolution) {
        return switch (resolution) {
            case SUCCEEDED -> "完成";
            case PARTIAL -> "部分完成";
            case FAILED -> "未达成";
            case NOT_EXECUTED -> "未执行";
        };
    }

    // 候选中的空项留给专用协议校验，避免 List.copyOf 提前抛出未分类的 NullPointerException。
    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
