package com.wenxian.game.story.replay.records;

import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.AssetProcessCompiler;
import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.RealmProgressPolicy;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryReferenceAlias;
import com.wenxian.game.story.domain.StorySemanticTimeResolver;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.domain.TrustedStoryEventCompiler;
import com.wenxian.game.story.reducer.StoryStateReducer;
import com.wenxian.game.story.replay.single.SingleCandidateCompiler.RealmEffect;
import com.wenxian.game.story.replay.single.SingleCandidateCompiler.SceneLocationChange;
import com.wenxian.game.story.replay.single.SingleCandidateCompiler.TimeEffect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 持续记录候选的独立测试编译器，不再要求普通故事步骤图或整轮成功枚举。
 *
 * <p>实体、局部引用与世界事实映射取自正式 StoryFactIntentCompiler，资产、晋升、时间和投影规则
 * 仍直接调用正式领域实现。这里不调用旧编译器的空 orderedResult 兼容分支，也不虚构步骤或
 * SUCCEEDED 填满旧合同。新实体必须自带事实说明，不能把整篇小说补成实体 summary。</p>
 *
 * <p>本类不调用模型或写库。返回值只是同一前态的完整预演；时间、人物地点、晋升或局部结果变化
 * 交给宿主统一反馈并重写完整候选，不能静默保留旧正文。记忆由调用方从记录派生后传入，
 * 只参与这一次归并；回合记录的局部引用由调用方使用返回映射和已分配实体 ID 解析。</p>
 */
public final class RecordCandidateCompiler {
    private final AssetProcessCompiler assets = new AssetProcessCompiler();
    private final RealmProgressPolicy realmProgress = new RealmProgressPolicy();
    private final StorySemanticTimeResolver semanticTimes = new StorySemanticTimeResolver();
    private final TrustedStoryEventCompiler eventCompiler = new TrustedStoryEventCompiler();

    /** 从同一冻结前态编译候选，不改变记录、正文或模型提出的开放经历。 */
    public Compilation compile(StoryProjection before, RecordCandidateProtocol.Adapted adapted,
                               String narrative, List<StoryPatch.MemoryDraft> memories,
                               SemanticDuration planned) {
        if (before == null || adapted == null || adapted.decision() == null) {
            throw new IllegalArgumentException("持续记录编译缺少前态或完整候选");
        }
        StoryTurnDecision decision = adapted.decision();
        if (decision.outcome() != null || decision.orderedResult() != null || !decision.sceneBeats().isEmpty()
                || !decision.memories().isEmpty()) {
            throw new IllegalArgumentException("持续记录合同不能混入旧整轮结果、故事步骤或第二份模型记忆");
        }
        if (decision.duration() == null || decision.timeResolution() == null) {
            throw new IllegalArgumentException("持续记录候选必须明确语义时间和完成状态");
        }
        if (decision.timeResolution() == StoryPatch.TimeResolution.INTERRUPTED
                && !present(decision.interruptionReason())) {
            throw new IllegalArgumentException("行动中断必须说明原因");
        }
        LocalAliases aliases = localAliases(decision.entities());
        List<StoryPatch.EntityPatch> metadata = entities(before, decision, aliases);
        // 即使没有资产变化也使用明确空过程；新物品不能借省略分区绕过取得与保管校验。
        AssetProcessCompiler.Result asset = assets.compile(before, metadata, decision.assetProcess(), aliases.requested());
        List<StoryPatch.WorldFactPatch> worldFacts = new ArrayList<>();
        for (StoryTurnDecision.WorldFactIntent fact : decision.worldFacts()) {
            if (fact == null || !present(fact.statement())) {
                throw new IllegalArgumentException("已提出的世界事实不能是空条目，不能静默删除变化");
            }
            worldFacts.add(worldFact(before, fact));
        }
        CultivationCompilation cultivation = cultivation(before, adapted);
        StoryTurnDecision.CoreStateIntent core = decision.coreState();
        StoryPatch.CoreStatePatch corePatch = core == null ? null : new StoryPatch.CoreStatePatch(
                blankToNull(core.location()), blankToNull(core.condition()),
                before.cultivationSystem() == null ? blankToNull(core.realm()) : null, null);
        StoryPatch proposal = new StoryPatch("本轮经历", required(narrative, "完整候选缺少正文"),
                new StoryPatch.TimePassage(decision.duration(), decision.timeResolution(), blankToNull(decision.interruptionReason())),
                corePatch, asset.entities(), worldFacts, List.of(), memories,
                decision.decisionCheckpoint(), List.of(), "", null, cultivation.patch());
        StoryPatch timed = semanticTimes.resolve(before, proposal, planned);
        StoryPatch normalized = eventCompiler.compile(before, timed, planned).normalizedPatch();
        List<SceneLocationChange> sceneLocations = sceneLocationChanges(before, timed, normalized, aliases);
        StoryStateReducer.Applied applied = new StoryStateReducer().reduce(before, normalized);
        AssetProcess.Accepted process = AssetProcess.resolveRefs(asset.process(), applied.acceptedPatch().entities());
        return new Compilation(applied, process, aliases.requested(), timeEffect(before, decision, applied),
                realmEffect(applied), sceneLocations, cultivation.localOutcomeEffect());
    }

    /** 已预演状态与只供修复反馈的差异；原局部引用映射不是另一份事实写命令。 */
    public record Compilation(StoryStateReducer.Applied applied, AssetProcess.Accepted process,
                              Map<String, String> requestedAliases, TimeEffect timeEffect,
                              RealmEffect realmEffect, List<SceneLocationChange> sceneLocationChanges,
                              LocalOutcomeEffect localOutcomeEffect) {
        public Compilation {
            requestedAliases = Collections.unmodifiableMap(new LinkedHashMap<>(requestedAliases));
            sceneLocationChanges = List.copyOf(sceneLocationChanges);
        }
    }

    /** 仅比较局部修行的结果枚举，不给整轮故事归类，也不比较自然语言措辞。 */
    public record LocalOutcomeEffect(StoryPatch.OutcomeResolution proposed, StoryPatch.OutcomeResolution actual) {}

    private List<StoryPatch.EntityPatch> entities(StoryProjection before, StoryTurnDecision decision,
                                                 LocalAliases aliases) {
        List<StoryPatch.EntityPatch> entities = new ArrayList<>();
        for (StoryTurnDecision.EntityIntent intent : decision.entities()) {
            if (intent == null) throw new IllegalArgumentException("实体变化不能包含空条目");
            boolean existing = present(intent.existingEntityId());
            StoryProjection.EntityState previous = existing ? before.entities().get(intent.existingEntityId()) : null;
            if (existing && previous == null) {
                throw new IllegalArgumentException("候选引用了不存在的实体：" + intent.existingEntityId());
            }
            String type = existing ? previous.entityType() : normalizedType(intent.entityType());
            if (!present(type)) throw new IllegalArgumentException("新增长期实体必须说明类型");
            if (existing && present(intent.entityType())
                    && !normalizedType(intent.entityType()).equals(normalizedType(previous.entityType()))) {
                throw new IllegalArgumentException("实体类型与稳定ID不一致：" + intent.existingEntityId());
            }
            if ("ITEM".equalsIgnoreCase(type) && (intent.quantity() != null || intent.custody() != null)) {
                throw new AssetProcess.ProtocolException("changes.items", "FINAL_ASSET_WRITE",
                        "物品数量与保管关系只能由资产过程计算，元数据不能另写末态");
            }
            StoryPatch.CustodyValue custody = custody(intent.custody(), aliases);
            StoryPatch.LifecycleValue lifecycle = intent.lifecycle();
            if (!existing && "CHARACTER".equalsIgnoreCase(type) && lifecycle == null) {
                lifecycle = new StoryPatch.LifecycleValue(StoryPatch.LifecycleState.ACTIVE, null);
            }
            entities.add(new StoryPatch.EntityPatch(operation(previous, intent.visible()),
                    existing ? intent.existingEntityId() : null, type,
                    existing ? null : required(intent.name(), "新增长期实体必须包含名称"),
                    existing ? blankToNull(intent.status()) : present(intent.status()) ? intent.status().strip() : "已确认",
                    existing ? blankToNull(intent.summary()) : required(intent.summary(), "新增长期实体必须明确自己的事实说明"),
                    intent.attributes(), intent.quantity(), custody == null ? null : custody.holderType() == StoryPatch.HolderType.PLAYER,
                    custody, intent.abilityState(), lifecycle, intent.relationship(), null,
                    existing ? "" : aliases.generated().get(intent)));
        }
        return entities;
    }

    private CultivationCompilation cultivation(StoryProjection before, RecordCandidateProtocol.Adapted adapted) {
        StoryTurnDecision decision = adapted.decision();
        StoryTurnDecision.CultivationIntent intent = decision.cultivation();
        if (intent == null || (!intent.progressGained() && !intent.breakthroughAttempted())) {
            return new CultivationCompilation(null, null);
        }
        if (adapted.actionOutcome() == null) throw new IllegalArgumentException("有效修行提案缺少局部 actionOutcome");
        StoryPatch.CultivationPatch proposed = realmProgress.decide(before, decision, adapted.actionOutcome());
        if (proposed == null) throw new IllegalArgumentException("当前命卷没有可裁决的主修体系");
        // 与正式 OrderedTurnResultCompiler 的局部映射一致，只调用晋升策略一次。
        StoryPatch.OutcomeResolution actual = switch (proposed.resolution()) {
            case RANK_ADVANCED -> StoryPatch.OutcomeResolution.SUCCEEDED;
            case PROGRESSED, PHASE_ADVANCED -> intent.breakthroughAttempted()
                    ? StoryPatch.OutcomeResolution.PARTIAL : StoryPatch.OutcomeResolution.SUCCEEDED;
            case REJECTED, UNCHANGED -> StoryPatch.OutcomeResolution.FAILED;
            case CORRECTED -> throw new IllegalArgumentException("普通回合不能提交命卷修正");
        };
        String canonical = cultivationText(before, proposed);
        StoryPatch.CultivationPatch patch = new StoryPatch.CultivationPatch(proposed.targetStageId(), proposed.targetPhase(),
                proposed.targetProgressMarks(), proposed.targetRequiredProgressMarks(), proposed.breakthroughAttempted(),
                canonical, proposed.evidenceRefs(), proposed.resolution(), proposed.reason(), actual);
        LocalOutcomeEffect effect = adapted.actionOutcome() == actual ? null : new LocalOutcomeEffect(adapted.actionOutcome(), actual);
        return new CultivationCompilation(patch, effect);
    }

    private record CultivationCompilation(StoryPatch.CultivationPatch patch, LocalOutcomeEffect localOutcomeEffect) {}

    /** 正式有序结果的境界文案规则；只解释结构化裁决，不改写普通经历或教师说法。 */
    private String cultivationText(StoryProjection before, StoryPatch.CultivationPatch patch) {
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
            case CORRECTED -> throw new IllegalArgumentException("普通回合不能生成修正结果");
        };
    }

    private StoryPatch.WorldFactPatch worldFact(StoryProjection before, StoryTurnDecision.WorldFactIntent intent) {
        boolean existing = present(intent.factId()) && before.worldFacts().containsKey(intent.factId())
                && before.worldFacts().get(intent.factId()).active();
        return new StoryPatch.WorldFactPatch(existing ? StoryPatch.WorldFactOperation.TRANSFORM : StoryPatch.WorldFactOperation.EXPAND,
                existing ? intent.factId() : null, intent.statement(), blankToNull(intent.reason()));
    }

    private StoryPatch.EntityOperation operation(StoryProjection.EntityState previous, Boolean visible) {
        if (previous == null) return StoryPatch.EntityOperation.CREATE;
        if (Boolean.FALSE.equals(visible)) return StoryPatch.EntityOperation.HIDE;
        if (Boolean.TRUE.equals(visible) && !previous.visible()) return StoryPatch.EntityOperation.RESTORE;
        return StoryPatch.EntityOperation.UPDATE;
    }

    private StoryPatch.CustodyValue custody(StoryTurnDecision.CustodyIntent intent, LocalAliases aliases) {
        if (intent == null) return null;
        if (intent.holderType() == null) throw new IllegalArgumentException("物品保管意图缺少保管者类型");
        String holderRef = switch (intent.holderType()) {
            case CHARACTER -> present(intent.localHolderRef())
                    ? StoryReferenceAlias.holderRef(required(aliases.requested().get(intent.localHolderRef().strip()),
                    "保管关系引用了本轮不存在的局部人物"))
                    : required(intent.holderRef(), "人物保管意图缺少稳定引用或本轮局部引用");
            case LOCATION -> required(intent.holderRef(), "地点保管意图缺少地点引用");
            case PLAYER, NONE, UNKNOWN -> null;
        };
        return new StoryPatch.CustodyValue(intent.holderType(), holderRef);
    }

    private LocalAliases localAliases(List<StoryTurnDecision.EntityIntent> intents) {
        Map<StoryTurnDecision.EntityIntent, String> generated = new IdentityHashMap<>();
        Map<String, String> requested = new LinkedHashMap<>();
        int newIndex = 0;
        for (StoryTurnDecision.EntityIntent intent : intents) {
            if (intent == null) throw new IllegalArgumentException("实体变化不能包含空条目");
            if (present(intent.existingEntityId())) continue;
            String safe = StoryReferenceAlias.normalizeKey("new-" + (++newIndex));
            generated.put(intent, safe);
            if (present(intent.localRef()) && requested.putIfAbsent(intent.localRef().strip(), safe) != null) {
                throw new IllegalArgumentException("回合内新实体引用重复，无法确定记录或保管关系");
            }
        }
        return new LocalAliases(generated, requested);
    }

    private record LocalAliases(Map<StoryTurnDecision.EntityIntent, String> generated, Map<String, String> requested) {}

    private TimeEffect timeEffect(StoryProjection before, StoryTurnDecision decision, StoryStateReducer.Applied applied) {
        StoryWorldTime proposedEnd = before.worldTime().advance(decision.duration());
        StoryWorldTime actualEnd = applied.projection().worldTime();
        return Objects.equals(proposedEnd, actualEnd) ? null
                : new TimeEffect(decision.duration(), applied.acceptedPatch().time().duration(), proposedEnd, actualEnd);
    }

    private RealmEffect realmEffect(StoryStateReducer.Applied applied) {
        StoryPatch.CultivationPatch patch = applied.acceptedPatch().cultivation();
        if (patch == null) return null;
        var cultivation = applied.projection().protagonistCultivation();
        if (cultivation == null) throw new IllegalStateException("已接受修行补丁缺少最终投影");
        return new RealmEffect(patch.resolution(), cultivation.currentStageId(), cultivation.phase(),
                cultivation.progressMarks(), cultivation.requiredProgressMarks(), patch.progressNote());
    }

    private List<SceneLocationChange> sceneLocationChanges(StoryProjection before, StoryPatch proposed,
                                                          StoryPatch normalized, LocalAliases aliases) {
        if (proposed.entities().size() != normalized.entities().size()) {
            throw new IllegalStateException("正式事件归一化意外改变实体列表结构");
        }
        Map<String, String> requestedBySafe = new LinkedHashMap<>();
        aliases.requested().forEach((requested, safe) -> requestedBySafe.put(safe, requested));
        List<SceneLocationChange> changes = new ArrayList<>();
        for (int index = 0; index < proposed.entities().size(); index++) {
            StoryPatch.EntityPatch source = proposed.entities().get(index);
            StoryPatch.EntityPatch target = normalized.entities().get(index);
            if (!"CHARACTER".equalsIgnoreCase(source.entityType())) continue;
            String proposedLocation = attribute(source, "lastKnownLocation");
            String normalizedLocation = attribute(target, "lastKnownLocation");
            if (Objects.equals(proposedLocation, normalizedLocation)) continue;
            StoryProjection.EntityState previous = source.entityId() == null ? null : before.entities().get(source.entityId());
            String previousLocation = previous == null || previous.attributes() == null ? ""
                    : previous.attributes().path("lastKnownLocation").asText("").strip();
            if (Objects.equals(previousLocation, normalizedLocation)) continue;
            String reference = source.entityId() == null ? requestedBySafe.getOrDefault(source.referenceKey(), source.referenceKey())
                    : source.entityId();
            changes.add(new SceneLocationChange(reference, previous == null ? source.name() : previous.name(),
                    previousLocation, normalizedLocation));
        }
        return List.copyOf(changes);
    }

    private String attribute(StoryPatch.EntityPatch entity, String key) {
        return entity.attributes() == null ? "" : entity.attributes().path(key).asText("").strip();
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private String normalizedType(String value) { return present(value) ? value.strip().toUpperCase(Locale.ROOT) : ""; }
    private String blankToNull(String value) { return present(value) ? value.strip() : null; }
    private String required(String value, String problem) {
        if (!present(value)) throw new IllegalArgumentException(problem);
        return value.strip();
    }
}
