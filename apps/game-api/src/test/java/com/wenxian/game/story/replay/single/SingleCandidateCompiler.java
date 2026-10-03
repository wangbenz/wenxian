package com.wenxian.game.story.replay.single;

import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryFactIntentCompiler;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryReferenceAlias;
import com.wenxian.game.story.domain.StorySemanticTimeResolver;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.domain.TrustedStoryEventCompiler;
import com.wenxian.game.story.domain.WorldPackage;
import com.wenxian.game.story.reducer.StoryStateReducer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单次完整候选实验的纯领域编译入口，复用正式事实、资产、时间和投影规则。
 *
 * <p>本类不解释正文，不调用模型，也不写数据库。实际末时间、境界结果和人物地点补全
 * 在同份候选完成领域编译后一起作为服务端计算信息返回，让测试宿主在本轮共同的一次
 * 修复预算内重写完整候选；是否需要先反馈再接受由宿主决定，不能在这里无限拒绝或偷偷追加调用。
 * 这些信息只说明确定性归并结果，不证明同次生成的中文故事与之相符。</p>
 */
public final class SingleCandidateCompiler {
    private final StoryFactIntentCompiler factIntents = new StoryFactIntentCompiler();
    private final StorySemanticTimeResolver semanticTimes = new StorySemanticTimeResolver();
    private final TrustedStoryEventCompiler eventCompiler = new TrustedStoryEventCompiler();

    /**
     * 从同一冻结前态编译并预演一份候选；失败不产生可提交的部分结果。
     *
     * <p>领域的 IllegalArgumentException 保持原有可修复边界；正式引用解析产生的
     * IllegalStateException 不转成模型错误。服务端生成 ID 及别名解析不改变故事含义，
     * 因而不列为需重写正文的变化。</p>
     */
    public Compilation compile(StoryProjection before, StoryTurnDecision decision,
                               SemanticDuration planned, StoryStateReducer reducer) {
        if (before == null || decision == null || reducer == null) {
            throw new IllegalArgumentException("单次候选编译缺少冻结前态、候选或归并器");
        }
        StoryFactIntentCompiler.Compilation compiled = factIntents.compileDetailed(before, decision);
        StoryPatch timed = semanticTimes.resolve(before, compiled.patch(), planned);
        StoryPatch plan = eventCompiler.compile(before, timed, planned).normalizedPatch();
        List<SceneLocationChange> sceneLocations = sceneLocationChanges(before, decision, timed, plan);
        StoryStateReducer.Applied applied = reducer.reduce(before, plan);
        AssetProcess.Accepted process = AssetProcess.resolveRefs(compiled.process(), applied.acceptedPatch().entities());
        OrderedTurnResult.Accepted acceptedResult = OrderedTurnResult.resolveRefs(
                compiled.acceptedResult(), applied.acceptedPatch().entities());
        return new Compilation(applied, process, acceptedResult, timeEffect(before, decision, applied),
                realmEffect(applied), sceneLocations);
    }

    /**
     * 已预演的候选与服务端补充信息；不包含正文接受结论，也不是额外一份状态写命令。
     * timeEffect 为空表示提案与正式末时间相同；realmEffect 为空表示没有修行状态补丁。
     * 场景地点列表只包含实际发生的补全。
     */
    public record Compilation(StoryStateReducer.Applied applied, AssetProcess.Accepted process,
                              OrderedTurnResult.Accepted acceptedResult, TimeEffect timeEffect, RealmEffect realmEffect,
                              List<SceneLocationChange> sceneLocationChanges) {
        public Compilation {
            sceneLocationChanges = List.copyOf(sceneLocationChanges);
        }
    }

    /**
     * 同一冻结起点下，提案时长与正式归并后的实际末时间差异。
     * 只复用正式世界时钟推进规则；时长写法不同但末时间相同时不产生反馈。
     */
    public record TimeEffect(SemanticDuration proposedDuration, SemanticDuration resolvedDuration,
                             StoryWorldTime proposedEndTime, StoryWorldTime actualEndTime) {}

    /**
     * 服务端算出的境界效果，供测试宿主给唯一修复传递明确规则反馈。
     * 不取模型对末态的自证；数字来自最终投影，result 来自正式编译器的境界结果。
     */
    public record RealmEffect(StoryPatch.CultivationResolution resolution, String stageId,
                              WorldPackage.RealmPhase phase, int progressMarks,
                              int requiredProgressMarks, String result) {
        public boolean advancesRealm() {
            return resolution == StoryPatch.CultivationResolution.PHASE_ADVANCED
                    || resolution == StoryPatch.CultivationResolution.RANK_ADVANCED;
        }
    }

    /**
     * 候选未填人物地点而正式归一化补入本轮结束地点的记录。
     * beforeLocation 是既有权威人物的最后地点；新人物为空。reference 对新人物保留本轮引用，
     * 对旧人物保留稳定 ID，不为反馈重新生成身份。补入值与旧权威地点相同时不列为变化。
     */
    public record SceneLocationChange(String reference, String name, String beforeLocation,
                                      String afterLocation) {}

    private TimeEffect timeEffect(StoryProjection before, StoryTurnDecision decision,
                                  StoryStateReducer.Applied applied) {
        StoryWorldTime proposedEnd = before.worldTime().advance(decision.duration());
        StoryWorldTime actualEnd = applied.projection().worldTime();
        if (Objects.equals(proposedEnd, actualEnd)) return null;
        return new TimeEffect(decision.duration(), applied.acceptedPatch().time().duration(),
                proposedEnd, actualEnd);
    }

    private RealmEffect realmEffect(StoryStateReducer.Applied applied) {
        StoryPatch.CultivationPatch patch = applied.acceptedPatch().cultivation();
        if (patch == null) return null;
        var cultivation = applied.projection().protagonistCultivation();
        if (cultivation == null) throw new IllegalStateException("已接受修行补丁缺少最终修行投影");
        return new RealmEffect(patch.resolution(), cultivation.currentStageId(), cultivation.phase(),
                cultivation.progressMarks(), cultivation.requiredProgressMarks(), patch.progressNote());
    }

    private List<SceneLocationChange> sceneLocationChanges(StoryProjection before, StoryTurnDecision decision,
                                                          StoryPatch proposed, StoryPatch normalized) {
        if (proposed.entities().size() != normalized.entities().size()) {
            throw new IllegalStateException("正式事件归一化意外改变实体列表结构");
        }
        Map<String, String> requestedReferences = requestedReferences(decision);
        List<SceneLocationChange> changes = new ArrayList<>();
        for (int index = 0; index < proposed.entities().size(); index++) {
            StoryPatch.EntityPatch source = proposed.entities().get(index);
            StoryPatch.EntityPatch target = normalized.entities().get(index);
            if (source == null || !"CHARACTER".equalsIgnoreCase(source.entityType())) continue;
            String proposedLocation = attribute(source, "lastKnownLocation");
            String normalizedLocation = attribute(target, "lastKnownLocation");
            if (Objects.equals(proposedLocation, normalizedLocation)) continue;
            StoryProjection.EntityState previous = source.entityId() == null
                    ? null : before.entities().get(source.entityId());
            String previousLocation = previous == null || previous.attributes() == null ? ""
                    : previous.attributes().path("lastKnownLocation").asText("").strip();
            if (Objects.equals(previousLocation, normalizedLocation)) continue;
            String reference = source.entityId() == null
                    ? requestedReferences.getOrDefault(source.referenceKey(), source.referenceKey()) : source.entityId();
            changes.add(new SceneLocationChange(reference,
                    previous == null ? source.name() : previous.name(), previousLocation, normalizedLocation));
        }
        return List.copyOf(changes);
    }

    /** 与正式编译器的新实体顺序对应，只还原反馈中的原始局部引用，不按名称匹配人物。 */
    private Map<String, String> requestedReferences(StoryTurnDecision decision) {
        Map<String, String> references = new HashMap<>();
        int newIndex = 0;
        for (StoryTurnDecision.EntityIntent entity : decision.entities()) {
            if (entity == null || (entity.existingEntityId() != null && !entity.existingEntityId().isBlank())) continue;
            String safe = StoryReferenceAlias.normalizeKey("new-" + (++newIndex));
            if (entity.localRef() != null && !entity.localRef().isBlank()) {
                references.put(safe, entity.localRef().strip());
            }
        }
        return references;
    }

    private String attribute(StoryPatch.EntityPatch entity, String key) {
        return entity == null || entity.attributes() == null ? "" : entity.attributes().path(key).asText("").strip();
    }
}
