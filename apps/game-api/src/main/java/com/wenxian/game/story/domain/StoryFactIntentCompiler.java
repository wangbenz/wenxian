package com.wenxian.game.story.domain;

import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryProjection.EntityState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把轻量导演返回的语义事实意图编译为现有 StoryPatch。
 *
 * <p>本编译器只做可确定的协议转换：根据稳定 ID 推导新增或更新操作、为本轮新实体建立局部引用、从唯一
 * 保管关系推导是否在玩家行囊，并为普通新人物补齐 ACTIVE 生命周期。它不阅读中文关键词、不判断剧情胜负、
 * 不创造模型没有提出的物品、能力或关系。编译结果仍必须经过 StoryPatchValidator 和 Reducer。</p>
 */
public final class StoryFactIntentCompiler {
    private final RealmProgressPolicy realmProgress = new RealmProgressPolicy();
    private final AssetProcessCompiler assets = new AssetProcessCompiler();
    private final OrderedTurnResultCompiler orderedResults = new OrderedTurnResultCompiler();

    /** 资产过程与唯一状态补丁一起产生；过程仅作为已接受的计算证据交给正文和回合追溯。 */
    public record Compilation(StoryPatch patch, AssetProcess.Accepted process,
                              OrderedTurnResult.Accepted acceptedResult) {}

    public StoryPatch compile(StoryProjection before, StoryTurnDecision decision) {
        return compileDetailed(before, decision).patch();
    }

    public Compilation compileDetailed(StoryProjection before, StoryTurnDecision decision) {
        if (before == null) throw new IllegalArgumentException("事实意图编译缺少当前投影");
        if (decision == null) throw new IllegalArgumentException("事实意图编译缺少导演结果");
        LocalAliases localAliases = localAliases(decision.entities());
        OrderedTurnResultCompiler.Compilation ordered = decision.orderedResult() == null ? null
                : orderedResults.compile(before, decision, localAliases.requested());
        StoryPatch.TurnOutcome outcome = ordered == null ? decision.outcome() : ordered.acceptedResult().outcome();
        if (outcome == null || outcome.resolution() == null || !present(outcome.summary())) {
            throw new IllegalArgumentException("导演结果必须包含成功、部分成功或失败及直接结果");
        }
        if (decision.duration() == null || decision.timeResolution() == null) {
            throw new IllegalArgumentException("导演结果必须包含语义时间和完成状态");
        }
        if (decision.timeResolution() == StoryPatch.TimeResolution.INTERRUPTED
                && !present(decision.interruptionReason())) {
            throw new IllegalArgumentException("行动中断必须说明原因");
        }

        List<EntityPatch> entities = new ArrayList<>();
        for (StoryTurnDecision.EntityIntent intent : decision.entities()) {
            if (intent == null) continue;
            boolean existing = present(intent.existingEntityId());
            EntityState previous = existing ? before.entities().get(intent.existingEntityId()) : null;
            if (existing && previous == null) {
                throw new IllegalArgumentException("导演结果引用了不存在的实体：" + intent.existingEntityId());
            }
            String type = existing ? previous.entityType() : normalizedType(intent.entityType());
            if (!present(type)) throw new IllegalArgumentException("新增长期实体必须说明类型");
            if (existing && present(intent.entityType())
                    && !normalizedType(intent.entityType()).equals(normalizedType(previous.entityType()))) {
                throw new IllegalArgumentException("导演结果中的实体类型与稳定ID不一致：" + intent.existingEntityId());
            }

            String localRef = existing ? "" : localAliases.generatedFor(intent);
            EntityOperation operation = operation(previous, intent.visible());
            if (decision.assetProcess() != null && "ITEM".equals(type)
                    && (intent.quantity() != null || intent.custody() != null)) {
                throw new AssetProcess.ProtocolException("entities", "FINAL_ASSET_WRITE",
                        "新协议物品数量与保管关系只能由资产过程计算，不能在元数据另写末态");
            }
            CustodyValue custody = custody(intent.custody(), localAliases);
            Boolean inPossession = custody == null ? null : custody.holderType() == HolderType.PLAYER;
            StoryPatch.LifecycleValue lifecycle = intent.lifecycle();
            if (!existing && "CHARACTER".equals(type) && lifecycle == null) {
                lifecycle = new StoryPatch.LifecycleValue(StoryPatch.LifecycleState.ACTIVE, null);
            }
            String name = existing ? null : required(intent.name(), "新增长期实体必须包含名称");
            String status = existing ? blankToNull(intent.status())
                    : valueOr(intent.status(), "已确认");
            String summary = existing ? blankToNull(intent.summary())
                    : valueOr(intent.summary(), outcome.summary());

            entities.add(new EntityPatch(operation, existing ? intent.existingEntityId() : null, type,
                    name, status, summary, intent.attributes(), intent.quantity(), inPossession, custody,
                    intent.abilityState(), lifecycle, intent.relationship(), null, localRef));
        }

        AssetProcess.Accepted assetProcess = null;
        if (decision.assetProcess() != null) {
            AssetProcessCompiler.Result result = assets.compile(before, entities, decision.assetProcess(),
                    localAliases.requested());
            entities = result.entities();
            assetProcess = result.process();
        }

        List<StoryPatch.WorldFactPatch> worldFacts = decision.worldFacts().stream()
                .filter(value -> value != null && present(value.statement()))
                .map(value -> worldFact(before, value))
                .toList();
        List<StoryPatch.MemoryDraft> memories = ordered != null ? ordered.acceptedResult().memories() : decision.memories().stream()
                .filter(value -> value != null && present(value.title()) && present(value.summary()))
                .toList();
        StoryTurnDecision.CoreStateIntent core = decision.coreState();
        StoryPatch.CoreStatePatch corePatch = core == null ? null : new StoryPatch.CoreStatePatch(
                blankToNull(core.location()), blankToNull(core.condition()),
                before.cultivationSystem() == null ? blankToNull(core.realm()) : null, null);
        StoryPatch.CultivationPatch cultivation = ordered == null ? realmProgress.decide(before, decision) : ordered.cultivation();
        StoryPatch.TimePassage time = new StoryPatch.TimePassage(decision.duration(),
                decision.timeResolution(), blankToNull(decision.interruptionReason()));
        boolean guaranteedBreakthrough = ordered == null && cultivation != null
                && cultivation.resolution() == StoryPatch.CultivationResolution.RANK_ADVANCED;
        StoryPatch.TurnOutcome acceptedOutcome = guaranteedBreakthrough
                ? new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.SUCCEEDED,
                "突破条件已经满足，本轮进入下一大境界初期")
                : outcome;
        String directResult = acceptedOutcome.summary().strip();
        List<String> acceptedBeats = guaranteedBreakthrough
                ? List.of("突破条件达成", "进入下一大境界初期", "在新境界状态落定处停止")
                : decision.sceneBeats();
        String scenePlan = ordered != null || acceptedBeats.isEmpty() ? directResult
                : directResult + "\n场景节拍：" + String.join("；", acceptedBeats);
        return new Compilation(new StoryPatch("本轮结果", scenePlan, time, corePatch, entities, worldFacts, List.of(), memories,
                ordered == null ? decision.decisionCheckpoint() : ordered.acceptedResult().checkpoint(),
                List.of(), "", acceptedOutcome, cultivation), assetProcess,
                ordered == null ? null : ordered.acceptedResult());
    }

    private EntityOperation operation(EntityState previous, Boolean visible) {
        if (previous == null) return EntityOperation.CREATE;
        if (Boolean.FALSE.equals(visible)) return EntityOperation.HIDE;
        if (Boolean.TRUE.equals(visible) && !previous.visible()) return EntityOperation.RESTORE;
        return EntityOperation.UPDATE;
    }

    /**
     * 只有当前权威投影中的稳定 ID 才能修改旧事实。模型若为新发现擅自填写了语义化 ID，
     * 编译器会丢弃该伪 ID 并按新增事实处理；它不能因此取得修改任意既有事实的权限。
     */
    private StoryPatch.WorldFactPatch worldFact(
            StoryProjection before, StoryTurnDecision.WorldFactIntent intent) {
        boolean existing = present(intent.factId())
                && before.worldFacts().containsKey(intent.factId())
                && before.worldFacts().get(intent.factId()).active();
        return new StoryPatch.WorldFactPatch(
                existing ? StoryPatch.WorldFactOperation.TRANSFORM : StoryPatch.WorldFactOperation.EXPAND,
                existing ? intent.factId() : null,
                intent.statement(), blankToNull(intent.reason()));
    }

    private CustodyValue custody(StoryTurnDecision.CustodyIntent intent, LocalAliases localAliases) {
        if (intent == null) return null;
        if (intent.holderType() == null) throw new IllegalArgumentException("物品保管意图缺少保管者类型");
        String holderRef = switch (intent.holderType()) {
            case CHARACTER -> present(intent.localHolderRef())
                    ? StoryReferenceAlias.holderRef(localAliases.resolveRequested(intent.localHolderRef()))
                    : required(intent.holderRef(), "人物保管意图缺少稳定引用或本轮局部引用");
            case LOCATION -> required(intent.holderRef(), "地点保管意图缺少地点引用");
            case PLAYER, NONE, UNKNOWN -> null;
        };
        return new CustodyValue(intent.holderType(), holderRef);
    }

    /**
     * 模型只负责在单轮内表达“这个物品由刚出现的那个人保管”，不能负责生成技术引用键。
     * 服务端为每个新实体分配 ASCII 临时键，并把模型给出的任意语言别名映射到该键；这样中文名称、空格或
     * 供应商不同的命名习惯都不会阻断回合，同时 Reducer 仍只接收受控引用。
     */
    private LocalAliases localAliases(List<StoryTurnDecision.EntityIntent> intents) {
        Map<StoryTurnDecision.EntityIntent, String> generated = new IdentityHashMap<>();
        Map<String, String> requested = new HashMap<>();
        int newIndex = 0;
        for (StoryTurnDecision.EntityIntent intent : intents) {
            if (intent == null || present(intent.existingEntityId())) continue;
            String safe = StoryReferenceAlias.normalizeKey("new-" + (++newIndex));
            generated.put(intent, safe);
            if (!present(intent.localRef())) continue;
            String previous = requested.putIfAbsent(intent.localRef().strip(), safe);
            if (previous != null) {
                throw new IllegalArgumentException("回合内新实体引用重复，无法确定保管关系");
            }
        }
        return new LocalAliases(generated, requested);
    }

    private record LocalAliases(Map<StoryTurnDecision.EntityIntent, String> generated,
                                Map<String, String> requested) {
        private String generatedFor(StoryTurnDecision.EntityIntent intent) {
            String value = generated.get(intent);
            if (value == null) throw new IllegalArgumentException("新实体缺少服务端临时引用");
            return value;
        }

        private String resolveRequested(String requestedRef) {
            String value = requested.get(requestedRef.strip());
            if (value == null) throw new IllegalArgumentException("人物保管意图引用了本轮不存在的新人物");
            return value;
        }
    }

    private String normalizedType(String value) {
        return present(value) ? value.strip().toUpperCase(Locale.ROOT) : "";
    }

    private String required(String value, String problem) {
        if (!present(value)) throw new IllegalArgumentException(problem);
        return value.strip();
    }

    private String valueOr(String value, String fallback) {
        return present(value) ? value.strip() : fallback;
    }

    private static String blankToNull(String value) {
        return present(value) ? value.strip() : null;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
