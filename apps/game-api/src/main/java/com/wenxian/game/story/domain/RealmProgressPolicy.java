package com.wenxian.game.story.domain;

import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.StoryPatch.CultivationPatch;
import com.wenxian.game.story.domain.StoryPatch.CultivationResolution;
import com.wenxian.game.story.domain.StoryPatch.OutcomeResolution;
import com.wenxian.game.story.domain.WorldPackage.RealmPhase;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 将模型的一枚修行进展意图裁决为服务端权威状态。
 *
 * <p>本策略不解析玩家中文、不让模型指定目标境界，也不计算修炼时长或突破概率。非法、缺项或不满足
 * 条件的修行意图只返回保持原状态的结果，不抛出会中止整轮剧情的异常。每轮最多增加一枚进展，且最多
 * 前进一个小阶段；进入新阶段时按当前游玩节奏重新固定门槛。</p>
 */
public final class RealmProgressPolicy {

    public CultivationPatch decide(StoryProjection before, StoryTurnDecision decision) {
        return decide(before, decision, decision == null || decision.outcome() == null
                ? null : decision.outcome().resolution());
    }

    /** 有序结果仅改变被检查的行动范围，进度、保底和跨境规则仍执行同一份策略。 */
    public CultivationPatch decide(StoryProjection before, StoryTurnDecision decision,
                                   OutcomeResolution actionOutcome) {
        if (before == null || decision == null || decision.cultivation() == null
                || before.cultivationSystem() == null || before.protagonistCultivation() == null) {
            return null;
        }
        ProtagonistCultivation current = before.protagonistCultivation();
        StoryTurnDecision.CultivationIntent intent = decision.cultivation();
        String note = present(intent.reason()) ? intent.reason().strip()
                : decision.outcome() == null ? "" : normalized(decision.outcome().summary());
        List<String> evidence = acceptedEvidence(before, intent.evidenceRefs());

        if (!intent.progressGained() && !intent.breakthroughAttempted()) {
            return unchanged(current, note, evidence, CultivationResolution.UNCHANGED,
                    "本轮没有形成修行进展或突破尝试");
        }
        if (current.phase() == RealmPhase.PERFECT && intent.breakthroughAttempted()
                && current.progressMarks() + 1 >= current.requiredProgressMarks()) {
            String nextStageId = directSuccessor(before.cultivationSystem(), current.currentStageId());
            if (!present(nextStageId)) {
                return unchanged(current, note, evidence, CultivationResolution.REJECTED,
                        "已经处于当前境界标尺终点，不能通过普通回合继续晋升");
            }
            return state(nextStageId, RealmPhase.EARLY, 0, threshold(before.playPreference().pace()),
                    true, "突破条件已满足，境界顺利前进", evidence, CultivationResolution.RANK_ADVANCED,
                    "圆满进度达到门槛且玩家明确尝试，进入下一大境界初期");
        }
        if (!present(intent.reason())) {
            return unchanged(current, note, evidence, CultivationResolution.REJECTED,
                    "修行进展缺少与本轮结果直接相关的原因");
        }
        OutcomeResolution outcome = actionOutcome;
        if (outcome == OutcomeResolution.FAILED || outcome == null) {
            return unchanged(current, note, evidence, CultivationResolution.REJECTED,
                    "本轮行动没有形成可提交的修行进展");
        }

        if (current.phase() != RealmPhase.PERFECT) {
            if (!intent.progressGained()) {
                return unchanged(current, note, evidence, CultivationResolution.REJECTED,
                        "当前尚未圆满，突破尝试不能改变境界");
            }
            int nextMarks = current.progressMarks() + 1;
            if (nextMarks < current.requiredProgressMarks()) {
                return state(current.currentStageId(), current.phase(), nextMarks,
                        current.requiredProgressMarks(), intent.breakthroughAttempted(), note, evidence,
                        CultivationResolution.PROGRESSED, "本轮接受一枚修行进展");
            }
            return state(current.currentStageId(), nextPhase(current.phase()), 0,
                    threshold(before.playPreference().pace()), intent.breakthroughAttempted(), note, evidence,
                    CultivationResolution.PHASE_ADVANCED, "本轮进展达到门槛，前进一个小阶段");
        }

        if (!intent.breakthroughAttempted()) {
            if (intent.progressGained()
                    && current.progressMarks() + 1 < current.requiredProgressMarks()) {
                return state(current.currentStageId(), current.phase(), current.progressMarks() + 1,
                        current.requiredProgressMarks(), false, note, evidence,
                        CultivationResolution.PROGRESSED, "本轮接受一枚圆满阶段修行进展");
            }
            return unchanged(current, note, evidence, CultivationResolution.REJECTED,
                    "圆满后的最后一步必须由玩家明确发起突破");
        }

        if (!intent.progressGained()) {
            return unchanged(current, note, evidence, CultivationResolution.REJECTED,
                    "本轮突破尝试没有形成足以跨境的进展");
        }
        if (current.progressMarks() + 1 < current.requiredProgressMarks()) {
            return state(current.currentStageId(), current.phase(), current.progressMarks() + 1,
                    current.requiredProgressMarks(), true, note, evidence,
                    CultivationResolution.PROGRESSED, "突破准备继续积累，但尚未达到门槛");
        }
        return unchanged(current, note, evidence, CultivationResolution.REJECTED,
                "圆满阶段的最后一步必须由玩家明确发起突破");
    }

    private CultivationPatch unchanged(ProtagonistCultivation current, String note, List<String> evidence,
                                       CultivationResolution resolution, String reason) {
        return state(current.currentStageId(), current.phase(), current.progressMarks(),
                current.requiredProgressMarks(), false, note, evidence, resolution, reason);
    }

    private CultivationPatch state(String stageId, RealmPhase phase, int marks, int target,
                                   boolean breakthroughAttempted, String note, List<String> evidence,
                                   CultivationResolution resolution, String reason) {
        return new CultivationPatch(stageId, phase, marks, target, breakthroughAttempted,
                note, evidence, resolution, reason);
    }

    private RealmPhase nextPhase(RealmPhase phase) {
        return switch (phase) {
            case EARLY -> RealmPhase.MIDDLE;
            case MIDDLE -> RealmPhase.LATE;
            case LATE -> RealmPhase.PERFECT;
            case PERFECT -> throw new IllegalArgumentException("圆满阶段不能按普通小阶段前进");
        };
    }

    private String directSuccessor(CultivationSystem system, String currentStageId) {
        CultivationSystem.Stage current = system.stages().stream()
                .filter(stage -> stage.stageId().equals(currentStageId)).findFirst().orElse(null);
        if (current == null || current.allowedNextStageIds().size() != 1) return null;
        String next = current.allowedNextStageIds().getFirst();
        return system.stages().stream().anyMatch(stage -> stage.stageId().equals(next)) ? next : null;
    }

    /** 模型给出的依据只能回指当前权威投影或本轮玩家行动，伪造引用会被静默丢弃。 */
    private List<String> acceptedEvidence(StoryProjection before, List<String> requested) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add("PLAYER_ACTION");
        allowed.add("creation:origin");
        allowed.add("creation:opening");
        allowed.addAll(before.entities().keySet());
        before.worldFacts().values().stream().filter(StoryProjection.WorldFactState::active)
                .map(StoryProjection.WorldFactState::id).forEach(allowed::add);
        before.memories().stream().map(StoryProjection.MemoryState::id).forEach(allowed::add);
        before.creationContract().origin().establishedFacts().stream()
                .map(CreationContract.OriginFact::factId).forEach(allowed::add);
        before.creationContract().origin().openHooks().stream()
                .map(CreationContract.StoryHook::hookId).forEach(allowed::add);
        List<String> accepted = requested.stream().filter(allowed::contains).distinct().limit(8).toList();
        return accepted.isEmpty() ? List.of("PLAYER_ACTION") : accepted;
    }

    private int threshold(PlayPreference.Pace pace) {
        return switch (pace) {
            case FAST -> 1;
            case BALANCED -> 2;
            case RELAXED -> 3;
        };
    }

    private String normalized(String value) {
        return value == null ? "" : value.strip();
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
