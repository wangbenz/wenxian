package com.wenxian.game.story.replay.slim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.ai.NarrativeEndState;
import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.worldpackage.domain.WorldContext;

import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 测试候选的单份写作输入，由同一冻结上下文和已预演结果确定构造，不加载数据库或重判剧情。
 * 保留过程、来源、能力限制、当前事实和历史承接；不传完整补丁或再次生成结果摘要。
 */
public record SlimWriterView(StoryPromptContext.ActionContext action, WorldContext worldContext,
                             JsonNode before, Selected selected,
                             OrderedTurnResult.Accepted acceptedResult, AssetProcess.Accepted assetProcess,
                             After after, TimeBoundary timeBoundary, Continuity continuity) {
    public static SlimWriterView from(ObjectMapper mapper, StoryPromptContext context,
                                      StoryPatch plan, NarrativeEndState end) {
        if (context == null || plan == null || plan.time() == null || end == null || end.acceptedResult() == null) {
            throw new IllegalArgumentException("精简写作视图缺少同轮已接受结果");
        }
        OrderedTurnResult.validateAccepted(end.acceptedResult());
        StoryWorldTime start = context.objective().worldTime();
        if (!end.worldTime().equals(start.advance(plan.time().duration()))) {
            throw new IllegalArgumentException("精简写作视图的实际时间与已接受计划不一致");
        }
        ObjectNode before = objective(mapper, context);
        before.remove(List.of("worldTime", "entityIndex", "sourceExcerpts"));
        var completeIds = Stream.concat(end.inventory().stream(), end.abilities().stream())
                .map(StoryProjection.EntityState::id).collect(Collectors.toSet());
        var remainingChanges = end.changedEntities().stream().filter(e -> !completeIds.contains(e.id())).toList();
        var narrative = context.narrative();
        TreeMap<Long, StoryPromptContext.PreviousTurn> turns = new TreeMap<>();
        if (narrative != null) {
            narrative.recentTurns().forEach(turn -> turns.put(turn.sequence(), turn));
            if (narrative.previousTurn() != null) turns.putIfAbsent(narrative.previousTurn().sequence(), narrative.previousTurn());
        }
        return new SlimWriterView(context.action(), context.worldContext(), before,
                new Selected(context.cognition(), context.memory(), context.objective().sourceExcerpts()),
                end.acceptedResult(), end.assetProcess(),
                new After(end.protagonist(), end.cultivation(), end.inventory(), end.abilities(), remainingChanges,
                        plan.worldFacts(), plan.knowledge()),
                new TimeBoundary(start, end.worldTime(), plan.time()),
                new Continuity(narrative == null ? "" : narrative.rollingSummary(), turns.values().stream()
                        .map(turn -> new PriorTurn(turn.sequence(), turn.playerAction(), turn.title(), turn.narrative(),
                                turn.acceptedResult(), turn.acceptedResult() == null ? turn.acceptedPatchSummary() : null)).toList()));
    }

    /** 与正式导演相同的世界资料去重，不让未选取的包正文绕过世界分区。 */
    static ObjectNode objective(ObjectMapper mapper, StoryPromptContext context) {
        ObjectNode objective = mapper.valueToTree(context.objective());
        if (context.worldContext() != null) {
            if (objective.get("creationContract") instanceof ObjectNode creation) creation.remove("worldPremise");
            if (objective.get("cultivation") instanceof ObjectNode cultivation) {
                cultivation.remove("summary");
                cultivation.path("stages").forEach(stage -> {
                    if (stage instanceof ObjectNode value) value.remove("meaning");
                });
            }
        }
        return objective;
    }

    public record Selected(StoryPromptContext.CognitionContext cognition, StoryPromptContext.MemoryContext memory,
                           List<StoryPromptContext.SourceExcerpt> sourceExcerpts) {}

    /** 完整可见行囊与功法为空即没有；其余实体变化与已接受事实/认知变化明确保留。 */
    public record After(StoryProjection.CoreState protagonist, ProtagonistCultivation cultivation,
                        List<StoryProjection.EntityState> inventory, List<StoryProjection.EntityState> abilities,
                        List<StoryProjection.EntityState> otherChangedEntities,
                        List<StoryPatch.WorldFactPatch> worldFactChanges,
                        List<StoryPatch.KnowledgePatch> cognitionChanges) {}

    public record TimeBoundary(StoryWorldTime start, StoryWorldTime end, StoryPatch.TimePassage passage) {}
    public record Continuity(String rollingSummary, List<PriorTurn> recentTurns) {}
    public record PriorTurn(long sequence, String playerAction, String title, String narrativeForContinuityOnly,
                            OrderedTurnResult.Accepted acceptedResult,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String legacyAcceptedPatchSummary) {}
}
