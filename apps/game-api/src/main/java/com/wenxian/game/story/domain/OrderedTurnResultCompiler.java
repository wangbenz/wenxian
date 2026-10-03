package com.wenxian.game.story.domain;

import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.OrderedTurnResult.Accepted;
import com.wenxian.game.story.domain.OrderedTurnResult.AcceptedStep;
import com.wenxian.game.story.domain.OrderedTurnResult.ProtocolException;
import com.wenxian.game.story.domain.OrderedTurnResult.Resolution;
import com.wenxian.game.story.domain.OrderedTurnResult.Statement;
import com.wenxian.game.story.domain.OrderedTurnResult.Step;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编译有序结果、唯一修行子结果与声明来源，并由同一结果派生兼容摘要及记忆。
 *
 * <p>只校验明确声明的依赖、引用和资产归属，不解析中文来判断价格或行动授权。修行规则改变前提时
 * 拒绝整个候选，由外层既有的一次修复重新生成全部净变化；不能删除步骤后继续提交剩余补丁。</p>
 */
public final class OrderedTurnResultCompiler {
    private static final String ACTION_RESOLVED_DETAIL = "本轮行动结束于以上已记录的结果。";
    private final RealmProgressPolicy realmProgress = new RealmProgressPolicy();

    public record Compilation(Accepted acceptedResult, StoryPatch.CultivationPatch cultivation) {}

    public Compilation compile(StoryProjection before, StoryTurnDecision decision,
                               Map<String, String> localAliases) {
        if (before == null || decision == null) throw new IllegalArgumentException("有序结果编译缺少当前投影或导演草案");
        OrderedTurnResult.Proposal proposal = decision.orderedResult();
        int assetCount = decision.assetProcess() == null || decision.assetProcess().steps() == null
                ? 0 : decision.assetProcess().steps().size();
        int cultivationIndex = validateShape(proposal, assetCount, false, false);
        if (!decision.memories().isEmpty() || !decision.sceneBeats().isEmpty() || decision.outcome() != null) {
            throw error("orderedResult", "DUPLICATE_RESULT", "有序结果不能同时携带旧 outcome、sceneBeats 或模型记忆");
        }
        boolean effectiveCultivation = decision.cultivation() != null
                && (decision.cultivation().progressGained() || decision.cultivation().breakthroughAttempted());
        if (effectiveCultivation != (cultivationIndex >= 0)) {
            throw error("orderedResult.steps", "CULTIVATION_BINDING", "progressGained=" + (decision.cultivation() != null && decision.cultivation().progressGained())
                    + "，breakthroughAttempted=" + (decision.cultivation() != null && decision.cultivation().breakthroughAttempted())
                    + "，realmProgressBinding 步骤索引=" + cultivationIndex
                    + "。任一意图为 true 才恰好绑定一个已执行步骤；两者为 false/空时所有绑定均为 false。普通练习和教师声明仍完整保留，不要求为了通过而增加进展");
        }
        List<Resolution> requested = proposal.steps().stream().map(Step::resolution).toList();
        validateExecutedDependencies(proposal.steps(), requested);
        StoryPatch.OutcomeResolution cultivationOutcome = cultivationIndex < 0 ? null
                : StoryPatch.OutcomeResolution.valueOf(requested.get(cultivationIndex).name());
        StoryPatch.CultivationPatch cultivation = realmProgress.decide(before, decision, cultivationOutcome);
        if (cultivationIndex >= 0 && cultivation == null) {
            throw error(path(cultivationIndex), "CULTIVATION_UNAVAILABLE", "当前命卷没有可裁决的修行体系");
        }

        String cultivationText = null;
        if (cultivationIndex >= 0) {
            Resolution outcome = cultivationResolution(cultivation, decision.cultivation());
            cultivationText = cultivationText(before, cultivation);
            cultivation = new StoryPatch.CultivationPatch(cultivation.targetStageId(), cultivation.targetPhase(),
                    cultivation.targetProgressMarks(), cultivation.targetRequiredProgressMarks(),
                    cultivation.breakthroughAttempted(), cultivationText, cultivation.evidenceRefs(),
                    cultivation.resolution(), cultivation.reason(), StoryPatch.OutcomeResolution.valueOf(outcome.name()));
            if (requested.get(cultivationIndex) != outcome) {
                throw error(path(cultivationIndex) + ".resolution", "CULTIVATION_RESULT_CHANGED",
                        "原步骤索引=" + cultivationIndex + "；模型结果=" + requested.get(cultivationIndex)
                                + "；Java 裁决=" + outcome + "；规则=" + cultivation.resolution()
                                + "；境界结果=" + cultivationText + "；原因=" + cultivation.reason()
                                + "。必须按同一原行动重新生成完整候选的经历、声明、依赖、停止问题和净变化；"
                                + "不能只改枚举保留相反结果，也不能改变有效修行意图来绕过裁决");
            }
        } else {
            // 无有效修行意图不写一条由自由 reason 生成的进展备注，保留既有修行状态。
            cultivation = null;
        }
        List<AcceptedStep> accepted = new ArrayList<>();
        for (int i = 0; i < proposal.steps().size(); i++) {
            Step step = proposal.steps().get(i);
            List<Statement> statements = new ArrayList<>();
            for (int j = 0; j < step.statements().size(); j++) {
                statements.add(resolveSource(before, decision, localAliases, step.statements().get(j),
                        path(i) + ".statements[" + j + "]"));
            }
            accepted.add(new AcceptedStep(i, step.action().strip(), requested.get(i),
                    step.result().strip(), step.realmProgressBinding(),
                    step.assetStepIndexes(), step.dependsOn(), statements, step.realmProgressBinding()
                            ? new OrderedTurnResult.RealmResult(cultivation.resolution(), cultivationText) : null));
        }
        return new Compilation(new Accepted(OrderedTurnResult.VERSION, accepted,
                new OrderedTurnResult.Stop(proposal.stop().reason(),
                        proposal.stop().reason() == OrderedTurnResult.StopReason.ACTION_RESOLVED
                                ? ACTION_RESOLVED_DETAIL : text(proposal.stop().detail()))), cultivation);
    }

    /** 已保存结果只检查结构，不按现在的世界包或突破规则重新裁决过去。 */
    static void validateAccepted(Accepted accepted) {
        if (accepted == null || (accepted.protocolVersion() != 1 && accepted.protocolVersion() != OrderedTurnResult.VERSION)) {
            throw error("acceptedResult.protocolVersion", "UNSUPPORTED_VERSION", "已保存有序结果缺失或版本不受支持");
        }
        List<Step> steps = new ArrayList<>();
        int assetCount = 0;
        for (int i = 0; i < accepted.steps().size(); i++) {
            AcceptedStep step = accepted.steps().get(i);
            if (step == null || step.stepIndex() != i) throw error("acceptedResult.steps", "STEP_INDEX", "已保存结果的步骤索引必须连续");
            for (Statement statement : step.statements()) {
                if (statement != null && StoryReferenceAlias.isAlias(statement.sourceRef())) {
                    throw error("acceptedResult.steps[" + i + "].statements", "UNRESOLVED_SOURCE", "已保存声明不能携带临时引用");
                }
            }
            if (accepted.protocolVersion() == 1) {
                if (step.realmResult() != null) throw error("acceptedResult.steps", "LEGACY_REALM_RESULT", "v1 不能携带 v2 境界结果");
            } else {
                if (step.cultivation() != (step.realmResult() != null)) {
                    throw error("acceptedResult.steps", "REALM_RESULT_BINDING", "v2 绑定与独立境界结果必须一致");
                }
                if (step.realmResult() != null) {
                    var realm = step.realmResult();
                    if (realm.resolution() == null || realm.resolution() == StoryPatch.CultivationResolution.CORRECTED) {
                        throw error("acceptedResult.steps", "REALM_RESULT", "普通回合必须有有效境界裁决类型");
                    }
                    required(realm.result(), "acceptedResult.steps[" + i + "].realmResult.result", 1600, true);
                    boolean consistent = switch (realm.resolution()) {
                        case RANK_ADVANCED -> step.resolution() == Resolution.SUCCEEDED;
                        case PROGRESSED, PHASE_ADVANCED -> step.resolution() == Resolution.SUCCEEDED || step.resolution() == Resolution.PARTIAL;
                        case REJECTED, UNCHANGED -> step.resolution() == Resolution.FAILED;
                        case CORRECTED -> false;
                    };
                    if (!consistent) throw error("acceptedResult.steps", "REALM_RESULT_RESOLUTION", "步骤结果与境界裁决类型矛盾");
                }
            }
            steps.add(new Step(step.action(), step.resolution(), step.result(), step.cultivation(),
                    step.assetStepIndexes(), step.dependsOn(), step.statements()));
            assetCount += step.assetStepIndexes().size();
        }
        validateShape(new OrderedTurnResult.Proposal(steps, accepted.stop()), assetCount, true, accepted.protocolVersion() == 1);
        validateExecutedDependencies(steps, steps.stream().map(Step::resolution).toList());
    }

    private static int validateShape(OrderedTurnResult.Proposal proposal, int assetCount, boolean accepted, boolean legacyAccepted) {
        if (proposal == null || proposal.steps().isEmpty() || proposal.steps().size() > OrderedTurnResult.MAX_STEPS) {
            throw error("orderedResult.steps", "STEP_COUNT", "有序结果必须有 1–16 个步骤");
        }
        if (proposal.stop() == null || proposal.stop().reason() == null) throw error("orderedResult.stop", "STOP_REQUIRED", "必须说明本轮停止边界");
        required(proposal.stop().detail(), "orderedResult.stop.detail", 600,
                proposal.stop().reason() != OrderedTurnResult.StopReason.ACTION_RESOLVED);
        int nextAsset = 0;
        int cultivationIndex = -1;
        int statementCount = 0;
        for (int i = 0; i < proposal.steps().size(); i++) {
            Step step = proposal.steps().get(i);
            if (step == null) throw error(path(i), "NULL_STEP", "步骤不能为空");
            required(step.action(), path(i) + ".action", 300, true);
            if (step.resolution() == null) throw error(path(i) + ".resolution", "RESOLUTION_REQUIRED", "必须提出本步结果");
            if (step.realmProgressBinding() == null) {
                throw error(path(i) + ".realmProgressBinding", "BINDING_REQUIRED", "必须明确填写境界裁决绑定；普通练习或没有数值意图用 false，不能使用旧 cultivation 步骤字段");
            }
            if (step.realmProgressBinding()) {
                if (cultivationIndex >= 0) throw error(path(i), "MULTIPLE_CULTIVATION", "每轮最多一个修行子结果");
                cultivationIndex = i;
                if (step.resolution() == Resolution.NOT_EXECUTED || (legacyAccepted && !step.statements().isEmpty())) {
                    throw error(path(i), "CULTIVATION_RESULT", "境界裁决绑定必须已执行；旧 v1 绑定记录不能携带声明");
                }
            }
            required(step.result(), path(i) + ".result", 1600, true);
            if (step.resolution() == Resolution.NOT_EXECUTED
                    && (!step.assetStepIndexes().isEmpty() || !step.statements().isEmpty())) {
                throw error(path(i), "UNEXECUTED_EFFECT", "未执行步骤不能产生资产变化或来源声明");
            }
            Set<Integer> dependencies = new HashSet<>();
            for (OrderedTurnResult.Dependency dependency : step.dependsOn()) {
                if (dependency == null || dependency.expected() == null || dependency.stepIndex() < 0
                        || dependency.stepIndex() >= i || !dependencies.add(dependency.stepIndex())) {
                    throw error(path(i) + ".dependsOn", "DEPENDENCY", "依赖必须是无重复的前序步骤及明确结果");
                }
            }
            for (Integer index : step.assetStepIndexes()) {
                if (index == null || index != nextAsset || index >= assetCount) {
                    throw error(path(i) + ".assetStepIndexes", "ASSET_ORDER", "当前应关联资产索引 " + nextAsset
                            + "，实际为 " + index + "，资产步骤总数 " + assetCount + "；索引从0开始，必须完整且按顺序恰好一次");
                }
                nextAsset++;
            }
            for (int j = 0; j < step.statements().size(); j++) {
                Statement statement = step.statements().get(j);
                String at = path(i) + ".statements[" + j + "]";
                if (statement == null) throw error(at, "NULL_STATEMENT", "来源声明不能为空");
                required(statement.sourceRef(), at + ".sourceRef", 160, false);
                required(statement.sourceLabel(), at + ".sourceLabel", 160, accepted || text(statement.sourceRef()).isEmpty());
                required(statement.statement(), at + ".statement", 1600, true);
                if (++statementCount > OrderedTurnResult.MAX_STATEMENTS) throw error(at, "STATEMENT_COUNT", "每轮最多八条重要来源声明");
            }
        }
        if (nextAsset != assetCount) throw error("orderedResult.steps", "ASSET_UNASSIGNED",
                "还未归属资产步骤索引 " + java.util.stream.IntStream.range(nextAsset, assetCount).boxed().toList()
                        + "（索引从0开始，资产步骤总数 " + assetCount + "）；每个资产步骤必须归属一个有序结果步骤");
        return cultivationIndex;
    }

    private static void validateExecutedDependencies(List<Step> steps, List<Resolution> resolutions) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).resolution() != Resolution.NOT_EXECUTED && !eligible(steps.get(i), resolutions)) {
                throw error(path(i) + ".dependsOn", "UNSATISFIED_DEPENDENCY", "前序结果条件未满足，不能宣称本步骤已执行");
            }
        }
    }

    private static boolean eligible(Step step, List<Resolution> resolutions) {
        return step.dependsOn().stream().allMatch(dependency ->
                dependency.expected().name().equals(resolutions.get(dependency.stepIndex()).name()));
    }

    private static Resolution cultivationResolution(StoryPatch.CultivationPatch patch,
                                                    StoryTurnDecision.CultivationIntent intent) {
        return switch (patch.resolution()) {
            case RANK_ADVANCED -> Resolution.SUCCEEDED;
            case PROGRESSED, PHASE_ADVANCED -> intent.breakthroughAttempted() ? Resolution.PARTIAL : Resolution.SUCCEEDED;
            case REJECTED, UNCHANGED -> Resolution.FAILED;
            case CORRECTED -> throw error("orderedResult.steps", "CORRECTION_FORBIDDEN", "普通回合不能提交命卷修正");
        };
    }

    private static String cultivationText(StoryProjection before, StoryPatch.CultivationPatch patch) {
        String stage = before.cultivationSystem().stages().stream()
                .filter(value -> value.stageId().equals(patch.targetStageId())).findFirst()
                .map(CultivationSystem.Stage::name).orElseThrow(() -> new IllegalStateException("修行裁决目标缺少标尺名称"));
        String phase = switch (patch.targetPhase()) {
            case EARLY -> "初期";
            case MIDDLE -> "中期";
            case LATE -> "后期";
            case PERFECT -> "圆满";
        };
        String state = stage + phase;
        return switch (patch.resolution()) {
            case RANK_ADVANCED -> "突破条件已满足，进入" + state + "。";
            case PHASE_ADVANCED -> "修行进展达到门槛，进入" + state + "。"
                    + (patch.breakthroughAttempted() ? "本次尝试尚未跨越大境界。" : "");
            case PROGRESSED -> "本轮修行获得一枚进展，现为" + state + "，进度"
                    + patch.targetProgressMarks() + "/" + patch.targetRequiredProgressMarks() + "。"
                    + (patch.breakthroughAttempted() ? "本次尝试尚未跨越大境界。" : "");
            case REJECTED, UNCHANGED -> patch.reason() + "；当前仍为" + state + "。";
            case CORRECTED -> throw error("orderedResult.steps", "CORRECTION_FORBIDDEN", "普通回合不能生成修正结果");
        };
    }

    private static Statement resolveSource(StoryProjection before, StoryTurnDecision decision,
                                           Map<String, String> aliases, Statement statement, String path) {
        String ref = text(statement.sourceRef());
        if (ref.isEmpty()) return new Statement(null, statement.sourceLabel().strip(), statement.statement().strip());
        if ("PLAYER".equals(ref)) return new Statement(ref, "主角", statement.statement().strip());
        StoryProjection.EntityState existing = before.entities().get(ref);
        if (existing != null && "CHARACTER".equals(existing.entityType())) {
            return new Statement(ref, existing.name(), statement.statement().strip());
        }
        String key = aliases.get(ref);
        StoryTurnDecision.EntityIntent source = decision.entities().stream()
                .filter(value -> value.existingEntityId().isBlank() && ref.equals(value.localRef())
                        && "CHARACTER".equalsIgnoreCase(value.entityType())).findFirst().orElse(null);
        if (key == null || source == null) throw error(path + ".sourceRef", "SOURCE_REF", "来源只能引用主角、已有稳定人物或本轮明确新增人物；临时来源应使用空引用和标签");
        return new Statement(StoryReferenceAlias.holderRef(key), source.name(), statement.statement().strip());
    }

    private static void required(String value, String path, int maximum, boolean required) {
        if (required && text(value).isEmpty() || value != null && value.length() > maximum) {
            throw error(path, "TEXT", "字段不能为空且不能超过 " + maximum + " 字符");
        }
    }

    private static String text(String value) { return value == null ? "" : value.strip(); }
    private static String path(int index) { return "orderedResult.steps[" + index + "]"; }
    private static ProtocolException error(String path, String code, String message) {
        return new ProtocolException(path, code, message);
    }
}
