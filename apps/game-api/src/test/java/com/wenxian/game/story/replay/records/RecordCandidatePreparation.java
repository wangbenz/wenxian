package com.wenxian.game.story.replay.records;

import com.wenxian.game.model.application.ModelInvocationService;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.ai.DirectorProtocolException;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.ModelCall;
import com.wenxian.game.story.application.PreparedStoryTurn;
import com.wenxian.game.story.application.StoryTurnProtocolException;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.TrustedStoryEvent;
import com.wenxian.game.story.domain.TrustedStoryEventCompiler;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.reducer.StoryStateReducer;
import com.wenxian.game.story.replay.records.RecordCandidateCompiler.Compilation;
import com.wenxian.game.story.replay.records.RecordCandidateCompiler.LocalOutcomeEffect;
import com.wenxian.game.story.replay.single.SingleCandidateCompiler.RealmEffect;
import com.wenxian.game.story.replay.single.SingleCandidateCompiler.SceneLocationChange;
import com.wenxian.game.story.replay.single.SingleCandidateCompiler.TimeEffect;
import com.wenxian.game.story.replay.records.RecordCandidateGenerator.Generated;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 持续记录候选的隔离准备流程：正文、变化、记录从同一前态预演，只整轮接受或整轮拒绝。
 * 每轮至多生成一次并完整修复一次；格式、引用及服务端对齐共用此预算，网络失败不转为修复。
 * 不调用独立正文模型，不裁决开放文本；记录先派生记忆，再随一次 Reducer 归并并解析新实体引用。
 * 本类不写数据库，宿主必须把返回记录和 PreparedStoryTurn 交给同一个正式提交事务。
 */
public final class RecordCandidatePreparation {
    private final RecordCandidateCompiler compiler = new RecordCandidateCompiler();
    private final TrustedStoryEventCompiler eventCompiler = new TrustedStoryEventCompiler();

    /** 事务宿主必须同时提交两部分，不能提前单独保存记录。 */
    public record Prepared(PreparedStoryTurn turn, List<StoryRecords.Value> records) {
        public Prepared { records = List.copyOf(records); }
    }

    /** 返回可交给正式事务提交器的候选；此方法本身不写数据库、额度或生产诊断。 */
    public Prepared prepare(StoryProjection before, StoryPromptContext context,
                                     List<Entity> existingEntities,
                                     RecordCandidateGenerator.RecordSession session,
                                     List<StoryRecords.Envelope> recordHistory,
                                     Consumer<Map<String, Object>> observer) {
        if (before == null || context == null || context.action() == null || session == null) {
            throw new IllegalArgumentException("单次候选准备缺少冻结前态、行动上下文或生成会话");
        }
        SemanticDuration planned = context.action().semanticDuration();
        List<ModelCall> calls = new ArrayList<>();
        DirectorProtocolException rejected = null;
        ServerAlignment serverAlignment = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, Object> generation = generationEvent(attempt, context, rejected, serverAlignment);
            boolean generationObserved = false;
            try {
                Generated generated = session.generate(context, recordHistory, rejected, serverAlignment);
                if (generated == null || generated.calls() == null || generated.calls().isEmpty()) {
                    throw new IllegalStateException("单候选生成器违反完整候选及调用记录契约");
                }
                calls.addAll(generated.calls());
                if (generated.draft() == null || generated.adapted() == null || generated.draft().story() == null) {
                    throw new IllegalStateException("单候选生成器违反完整候选及调用记录契约");
                }
                generation.put("result", generated.draft());
                generation.put("rawResponse", generated.rawResponse());
                generation.put("downgrades", generated.downgrades());
                generation.put("modelCalls", generated.calls());
                observe(observer, generation);
                generationObserved = true;

                Compilation compiled;
                List<StoryRecords.Value> records;
                try {
                    StoryRecords.Prepared pending = StoryRecords.prepare(before, generated.adapted().decision().entities(), generated.draft().records());
                    compiled = compiler.compile(before, generated.adapted(), generated.draft().story().narrative(), pending.memories(), planned);
                    records = StoryRecords.resolve(pending, compiled.requestedAliases(), compiled.applied().acceptedPatch().entities());
                } catch (IllegalArgumentException invalid) {
                    throw new DirectorProtocolException(DirectorProtocolException.Phase.COMPILATION,
                            original(generated), List.of(), invalid.getMessage(), invalid);
                }
                ServerAlignment needed = alignment(compiled);
                if ((needed != null && (needed.timeEffect() != null || needed.localOutcomeEffect() != null)) || !covered(serverAlignment, needed)) {
                    if (attempt == 0) serverAlignment = needed;
                    throw new DirectorProtocolException(DirectorProtocolException.Phase.COMPILATION,
                            original(generated), List.of(), attempt == 0
                            ? (needed != null && needed.timeEffect() != null ? "RECORD_CANDIDATE_TIME_CHANGED；" : "")
                            + "RECORD_CANDIDATE_SERVER_ALIGNMENT：正式规则补充了时间、局部修行结果、境界晋升或人物地点；"
                            + "请根据 serverAlignment 和同一原行动重新生成完整 story、changes 与 records。"
                            + "该反馈只描述本草案按规则将得到的结果，尚未提交。"
                            : (needed != null && needed.timeEffect() != null
                            ? "RECORD_CANDIDATE_TIME_CHANGED：唯一修复仍未与正式末时间一致，"
                            : "RECORD_CANDIDATE_ALIGNMENT_CHANGED：唯一修复又产生未曾反馈的服务端结果，")
                            + "本轮停止，不能保留未知是否同步的正文或追加调用。", null);
                }

                PreparedStoryTurn prepared = compose(before, existingEntities, compiled, generated.draft().story(), planned, calls);
                Map<String, Object> accepted = new LinkedHashMap<>();
                accepted.put("phase", "record-accepted");
                accepted.put("acceptedPatch", prepared.applied().acceptedPatch());
                accepted.put("storyRecords", records);
                accepted.put("assetProcess", prepared.assetProcess());
                accepted.put("afterProjection", prepared.applied().projection());
                accepted.put("knownModelCalls", List.copyOf(calls));
                accepted.put("serverAlignment", serverAlignment);
                observe(observer, accepted);
                return new Prepared(prepared, records);
            } catch (DirectorProtocolException invalid) {
                calls.addAll(invalid.modelCalls());
                if (!generationObserved) {
                    generation.put("rejectedPhase", invalid.phase());
                    generation.put("rejectedDraft", invalid.rejectedDraft());
                    generation.put("problem", invalid.getMessage());
                    generation.put("modelCalls", invalid.modelCalls());
                    observe(observer, generation);
                }
                Map<String, Object> failure = failureEvent("record-rejected", invalid, calls);
                failure.put("rejectedPhase", invalid.phase());
                failure.put("rejectedDraft", invalid.rejectedDraft());
                failure.put("serverAlignment", serverAlignment);
                observe(observer, failure);
                if (attempt == 1) {
                    DirectorProtocolException exhausted = invalid.withModelCalls(calls);
                    if (rejected != null) exhausted.addSuppressed(rejected);
                    throw new StoryTurnProtocolException("RECORD_CANDIDATE_REPAIR",
                            "完整故事候选整理一次后仍无法接受，本轮未提交", exhausted);
                }
                rejected = invalid;
            } catch (ModelInvocationService.ModelInvocationException failed) {
                List<ModelCall> failedCalls = modelCalls(failed.attempts());
                calls.addAll(failedCalls);
                if (!generationObserved) {
                    generation.put("errorType", failed.getClass().getSimpleName());
                    generation.put("modelCalls", failedCalls);
                    observe(observer, generation);
                }
                observe(observer, failureEvent("record-failed", failed, calls));
                // 网络、路由等调用失败保留原异常；没有重新生成或伪装成协议修复。
                throw failed;
            } catch (RuntimeException failed) {
                observe(observer, failureEvent("record-failed", failed, calls));
                // 引用接线、观察器或其他程序错误不能交给模型修复。
                throw failed;
            }
        }
        throw new IllegalStateException("单次候选修复预算状态异常");
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
                                      Compilation compiled, RecordCandidateProtocol.Story story,
                                      SemanticDuration planned, List<ModelCall> calls) {
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
                new PreparedStoryTurn.Generation(accepted, calls), events, null,
                compiled.process(), null);
    }

    /** 优先保留逐字原稿，包括辅助字段的原始错误，不把降级后的 DTO 当供应商原文。 */
    private Object original(Generated generated) {
        return generated.rawResponse() == null ? generated.draft() : generated.rawResponse();
    }

    private Map<String, Object> generationEvent(int attempt, StoryPromptContext context,
                                                DirectorProtocolException rejected, ServerAlignment alignment) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("phase", attempt == 0 ? "record-generation" : "record-repair");
        event.put("context", context);
        if (rejected != null) {
            event.put("rejectedDraft", rejected.rejectedDraft());
            event.put("rejectedPhase", rejected.phase());
            event.put("problem", rejected.getMessage());
        }
        if (alignment != null) event.put("serverAlignment", alignment);
        return event;
    }

    private Map<String, Object> failureEvent(String phase, RuntimeException failure, List<ModelCall> calls) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("phase", phase);
        event.put("errorType", failure.getClass().getSimpleName());
        // 仅分类后的协议问题进入实验记录，任意供应商/程序异常消息不复制到取证字段。
        if (failure instanceof DirectorProtocolException protocol) event.put("problem", protocol.getMessage());
        event.put("knownModelCalls", List.copyOf(calls));
        return event;
    }

    private List<ModelCall> modelCalls(List<ModelInvocationService.Attempt> attempts) {
        return attempts.stream().map(attempt -> new ModelCall(attempt.providerCode(), attempt.modelCode(),
                attempt.credentialSource(), attempt.fallback(), attempt.status(), attempt.latencyMs(),
                attempt.inputTokens(), attempt.outputTokens(), attempt.cacheHitTokens(), attempt.cacheMissTokens(),
                attempt.errorCategory())).toList();
    }

    /** 观察器失败属于本地程序错误，不能误消耗唯一模型修复。记录可含私有草案，调用方只应保存到隔离证据目录。 */
    private void observe(Consumer<Map<String, Object>> observer, Map<String, Object> event) {
        if (observer == null) return;
        try {
            observer.accept(java.util.Collections.unmodifiableMap(event));
        } catch (RuntimeException failure) {
            throw new IllegalStateException("单次候选实验观察器失败", failure);
        }
    }
}
