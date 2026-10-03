package com.wenxian.game.story.application;

import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.ai.DirectorProtocolException;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.ModelCall;
import com.wenxian.game.story.agent.ai.StoryCandidateGenerator;
import com.wenxian.game.story.agent.ai.StoryCandidateGenerator.Generated;
import com.wenxian.game.story.agent.ai.StoryCandidateProtocol;
import com.wenxian.game.story.diagnostic.StoryTurnDiagnosticService;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryCandidateCompiler;
import com.wenxian.game.story.domain.StoryCandidateCompiler.*;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryRecords;
import com.wenxian.game.story.domain.TrustedStoryEvent;
import com.wenxian.game.story.domain.TrustedStoryEventCompiler;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.reducer.StoryStateReducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 同次完整故事的事务外准备器：从同一前态生成正文、变化与持续记录，经一次领域归并后统一提交。
 * 必要格式、引用、资产和服务端结果对齐共用至多一次完整修复；网络、数据库或程序错误不进入修复。
 * 原始失败稿只用于本次修复，不进入玩家 API 或常规日志，不用额外模型和中文关键词判定叙事充分性。
 */
public final class StoryCandidatePreparation {
    private final StoryCandidateCompiler compiler = new StoryCandidateCompiler();
    private final TrustedStoryEventCompiler eventCompiler = new TrustedStoryEventCompiler();

    /** 不写库或扣额度；成功载体中即使没有经历也包含明确的空记录列表。 */
    public PreparedStoryTurn prepare(StoryProjection before, StoryPromptContext context,
                                     List<Entity> existingEntities, StoryCandidateGenerator.Session session,
                                     List<StoryRecords.Envelope> recordHistory,
                                     StoryTurnDiagnosticService.Session diagnostic) {
        if (before == null || context == null || context.action() == null || session == null)
            throw new IllegalArgumentException("完整候选缺少冻结前态、行动上下文或生成会话");
        SemanticDuration planned = context.action().semanticDuration();
        List<ModelCall> calls = new ArrayList<>();
        DirectorProtocolException rejected = null;
        ServerAlignment serverAlignment = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Generated generated = session.generate(context, recordHistory, rejected, serverAlignment);
                if (generated == null || generated.calls() == null || generated.calls().isEmpty())
                    throw new IllegalStateException("完整候选生成器缺少调用记录");
                calls.addAll(generated.calls());
                if (diagnostic != null) diagnostic.candidateAttempt(attempt + 1, generated.calls().size(),
                        generated.downgrades().stream().map(StoryCandidateProtocol.Downgrade::field).toList());
                if (generated.draft() == null || generated.adapted() == null || generated.draft().story() == null)
                    throw new IllegalStateException("完整候选生成器缺少正文或变化");
                Compilation compiled;
                List<StoryRecords.Value> records;
                try {
                    StoryRecords.Prepared pending = StoryRecords.prepare(before,
                            generated.adapted().decision().entities(), generated.draft().records());
                    compiled = compiler.compile(before, generated.adapted(), generated.draft().story().narrative(),
                            pending.memories(), planned);
                    records = StoryRecords.resolve(pending, compiled.requestedAliases(), compiled.applied().acceptedPatch().entities());
                } catch (IllegalArgumentException invalid) {
                    throw new DirectorProtocolException(DirectorProtocolException.Phase.COMPILATION,
                            original(generated), List.of(), invalid.getMessage(), invalid);
                }
                ServerAlignment needed = alignment(compiled);
                if ((needed != null && (needed.timeEffect() != null || needed.localOutcomeEffect() != null))
                        || !covered(serverAlignment, needed)) {
                    if (attempt == 0) serverAlignment = needed;
                    throw new DirectorProtocolException(DirectorProtocolException.Phase.COMPILATION,
                            original(generated), List.of(), attempt == 0
                            ? "STORY_CANDIDATE_SERVER_ALIGNMENT：领域规则补充了时间、局部修行结果、境界或人物地点；"
                              + "根据 serverAlignment 和同一行动修复完整 story、changes、records。反馈仅为预演，尚未提交。"
                            : "STORY_CANDIDATE_ALIGNMENT_CHANGED：唯一修复仍有结构化结果差异，本轮不提交。", null);
                }
                PreparedStoryTurn prepared = compose(before, existingEntities, compiled,
                        generated.draft().story(), planned, calls, records, diagnostic);
                if (diagnostic != null) {
                    diagnostic.proposal("STORY_CANDIDATE_ACCEPTED", prepared.applied().acceptedPatch());
                    diagnostic.reduced(prepared.applied());
                }
                return prepared;
            } catch (DirectorProtocolException invalid) {
                calls.addAll(invalid.modelCalls());
                if (diagnostic != null) diagnostic.rejected("STORY_CANDIDATE_" + invalid.phase(),
                        new IllegalArgumentException("候选在" + invalid.phase() + "阶段未通过必要协议校验"), true);
                if (attempt == 1) {
                    DirectorProtocolException exhausted = invalid.withModelCalls(calls);
                    if (rejected != null) exhausted.addSuppressed(rejected);
                    throw new StoryTurnProtocolException("STORY_CANDIDATE_REPAIR",
                            "完整故事整理一次后仍无法接受，本轮未提交", exhausted);
                }
                rejected = invalid;
            }
        }
        throw new IllegalStateException("完整候选修复预算状态异常");
    }

    /**
     * 已算出的条件式服务端反馈，不是玩家授权、模型确认字段或新的权威写入。
     * 无晋升或无地点补全的二稿可通过本检查；这不等于 Java 判断其开放剧情合理。
     */
    public record ServerAlignment(RealmEffect realmEffect, List<SceneLocationChange> sceneLocationChanges,
                                  TimeEffect timeEffect, LocalOutcomeEffect localOutcomeEffect) {
        public ServerAlignment { sceneLocationChanges = List.copyOf(sceneLocationChanges); }
    }

    private ServerAlignment alignment(Compilation compiled) {
        RealmEffect realm = compiled.realmEffect() != null && compiled.realmEffect().advancesRealm()
                ? compiled.realmEffect() : null;
        if (realm == null && compiled.sceneLocationChanges().isEmpty() && compiled.timeEffect() == null && compiled.localOutcomeEffect() == null) return null;
        return new ServerAlignment(realm, compiled.sceneLocationChanges(), compiled.timeEffect(), compiled.localOutcomeEffect());
    }

    private boolean covered(ServerAlignment supplied, ServerAlignment needed) {
        if (needed == null) return true;
        if (supplied == null) return false;
        if (needed.realmEffect() != null && !sameRealmEffect(needed.realmEffect(), supplied.realmEffect())) return false;
        return supplied.sceneLocationChanges().containsAll(needed.sceneLocationChanges());
    }

    /** 只比较服务端可判定的晋升结果，不把修行理由的中文措辞变化当作新结果。 */
    private boolean sameRealmEffect(RealmEffect actual, RealmEffect supplied) {
        return supplied != null && actual.resolution() == supplied.resolution()
                && Objects.equals(actual.stageId(), supplied.stageId()) && actual.phase() == supplied.phase()
                && actual.progressMarks() == supplied.progressMarks()
                && actual.requiredProgressMarks() == supplied.requiredProgressMarks();
    }

    private PreparedStoryTurn compose(StoryProjection before, List<Entity> existingEntities,
                                      Compilation compiled, StoryCandidateProtocol.Story story,
                                      SemanticDuration planned, List<ModelCall> calls,
                                      List<StoryRecords.Value> records, StoryTurnDiagnosticService.Session diagnostic) {
        StoryPatch plan = compiled.applied().acceptedPatch();
        List<StoryPatch.ChoiceDraft> choices = story.choices() == null ? List.of()
                : story.choices().stream().filter(Objects::nonNull)
                .map(choice -> new StoryPatch.ChoiceDraft(choice.title(), choice.description())).toList();
        StoryPatch accepted = new StoryPatch(story.title(), story.narrative(), plan.time(), plan.coreState(),
                plan.entities(), plan.worldFacts(), plan.knowledge(), plan.memories(), plan.decisionCheckpoint(),
                choices, story.summary(), plan.outcome(), plan.cultivation());
        StoryStateReducer.Applied applied = new StoryStateReducer.Applied(compiled.applied().projection(),
                accepted, compiled.applied().aliasChanges());
        List<TrustedStoryEvent> events = new ArrayList<>(eventCompiler.compile(before, accepted, planned).events());
        if (compiled.process() != null) {
            events.removeIf(event -> event instanceof TrustedStoryEvent.ItemChanged);
            compiled.process().steps().forEach(step -> events.add(new TrustedStoryEvent.AssetStepResolved(step)));
        }
        return new PreparedStoryTurn(before, applied, existingEntities,
                new PreparedStoryTurn.Generation(accepted, calls), events, diagnostic,
                compiled.process(), null, records);
    }

    /** 优先保留逐字原稿，包括辅助字段的原始错误，不把降级后的 DTO 当供应商原文。 */
    private Object original(Generated generated) {
        return generated.rawResponse() == null ? generated.draft() : generated.rawResponse();
    }

}
