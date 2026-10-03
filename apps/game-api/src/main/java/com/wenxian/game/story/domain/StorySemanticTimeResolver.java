package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryPatch.TimePassage;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;

/**
 * 将导演提议的时间收束为本轮唯一语义时长。
 *
 * <p>玩家明确授权的场景、时段、日、月、年由服务端保存为 {@link SemanticDuration}。完整执行时以玩家
 * 授权为准；中断或没有明确时长时使用模型给出的粗粒度语义。该服务不生成分钟、小时或其他兼容时间。</p>
 */
public final class StorySemanticTimeResolver {

    /** 为统一回合候选补齐唯一语义时间。 */
    public StoryPatch resolve(StoryProjection before, StoryPatch proposed, SemanticDuration planned) {
        if (before == null) throw new IllegalArgumentException("语义时间换算缺少当前投影");
        if (proposed == null) throw new IllegalArgumentException("语义时间换算缺少回合提案");
        TimePassage source = proposed.time();
        if (source == null || source.resolution() == null) return proposed;

        SemanticDuration actual = actualDuration(source, planned);
        TimePassage resolved = new TimePassage(actual, source.resolution(), source.interruptionReason());
        return new StoryPatch(proposed.title(), proposed.narrative(), resolved, proposed.coreState(),
                proposed.entities(), proposed.worldFacts(), proposed.knowledge(), proposed.memories(),
                proposed.decisionCheckpoint(), proposed.choices(), proposed.rollingSummary(), proposed.outcome(),
                proposed.cultivation());
    }

    private SemanticDuration actualDuration(TimePassage source, SemanticDuration planned) {
        if (source.resolution() == TimeResolution.COMPLETE && planned != null) return planned;
        if (source.duration() != null) return source.duration();
        // 中断结果缺少实际语义时长时，不能把玩家原计划误记为已经全部过去。
        return new SemanticDuration(SemanticDuration.Unit.SCENE, 1, "");
    }
}
