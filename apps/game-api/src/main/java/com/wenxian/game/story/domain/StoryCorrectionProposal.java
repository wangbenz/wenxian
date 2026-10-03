package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryPatch.CoreStatePatch;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.KnowledgePatch;
import com.wenxian.game.story.domain.StoryPatch.WorldFactPatch;

import java.util.List;

/**
 * 修正解析 Agent 对玩家说明提出的结构化解释。
 *
 * <p>提案本身不是命卷事实。H5 必须先向玩家展示类型、目标、变化和确认文案；只有玩家确认同一提案、
 * 命卷 revision 仍一致且 Reducer 接受后，变化才能进入当前投影和修正审计。</p>
 */
public record StoryCorrectionProposal(
        CorrectionKind kind,
        String explanation,
        String confirmationPrompt,
        CoreStatePatch coreState,
        StoryPatch.CultivationPatch cultivation,
        List<EntityPatch> entities,
        List<WorldFactPatch> worldFacts,
        List<KnowledgePatch> knowledge
) {
    public StoryCorrectionProposal {
        entities = safe(entities);
        worldFacts = safe(worldFacts);
        knowledge = safe(knowledge);
    }

    /** 兼容结构化境界修正加入前的调用方和尚未失效的修正预览令牌。 */
    public StoryCorrectionProposal(CorrectionKind kind, String explanation, String confirmationPrompt,
                                   CoreStatePatch coreState, List<EntityPatch> entities,
                                   List<WorldFactPatch> worldFacts, List<KnowledgePatch> knowledge) {
        this(kind, explanation, confirmationPrompt, coreState, null, entities, worldFacts, knowledge);
    }

    public enum CorrectionKind {
        /** 地点、境界、状态、物品、能力或人物生命周期等客观事实。 */
        FACT,
        /** 某个人物知道、相信或误解什么，不改变客观世界。 */
        COGNITION,
        /** 玩家明确扩展或替换已经生效的世界设定。 */
        WORLD
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
