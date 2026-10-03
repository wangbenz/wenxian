package com.wenxian.game.story.application;

import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.ModelCall;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryRecords;
import com.wenxian.game.story.domain.TrustedStoryEvent;
import com.wenxian.game.story.diagnostic.StoryTurnDiagnosticService;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.reducer.StoryStateReducer;

import java.util.List;

/**
 * 完整回合在事务外完成模型生成和确定性预演后的唯一提交载体。
 *
 * <p>该对象保存 Reducer 已接受的补丁、已校验并解析引用的故事记录、可信事件及模型用量摘要；
 * 未接受的模型原文和结构草案不能通过此对象越过事务提交边界。</p>
 */
public record PreparedStoryTurn(
        StoryProjection before,
        StoryStateReducer.Applied applied,
        List<Entity> existingEntities,
        Generation generation,
        List<TrustedStoryEvent> trustedEvents,
        StoryTurnDiagnosticService.Session diagnostic,
        AssetProcess.Accepted assetProcess,
        OrderedTurnResult.Accepted acceptedResult,
        List<StoryRecords.Value> storyRecords
) {
    /** 仅兼容既有固定样本和旧结果载体；正式新回合必须显式给出故事记录，允许空数组。 */
    public PreparedStoryTurn(StoryProjection before, StoryStateReducer.Applied applied,
                             List<Entity> existingEntities, Generation generation,
                             List<TrustedStoryEvent> trustedEvents, StoryTurnDiagnosticService.Session diagnostic,
                             AssetProcess.Accepted assetProcess, OrderedTurnResult.Accepted acceptedResult) {
        this(before, applied, existingEntities, generation, trustedEvents, diagnostic, assetProcess, acceptedResult, null);
    }
    /** 兼容旧固定样本；新故事回合使用带显式记录分区的完整构造。 */
    public PreparedStoryTurn(StoryProjection before, StoryStateReducer.Applied applied,
                             List<Entity> existingEntities, Generation generation,
                             List<TrustedStoryEvent> trustedEvents, StoryTurnDiagnosticService.Session diagnostic,
                             AssetProcess.Accepted assetProcess) {
        this(before, applied, existingEntities, generation, trustedEvents, diagnostic, assetProcess, null);
    }
    /** 兼容不含资产过程的既有测试与修正调用；新故事回合只用完整构造。 */
    public PreparedStoryTurn(StoryProjection before, StoryStateReducer.Applied applied,
                             List<Entity> existingEntities, Generation generation,
                             List<TrustedStoryEvent> trustedEvents, StoryTurnDiagnosticService.Session diagnostic) {
        this(before, applied, existingEntities, generation, trustedEvents, diagnostic, null);
    }
    public PreparedStoryTurn {
        if (before == null || applied == null || generation == null) {
            throw new IllegalArgumentException("完整回合准备结果不完整");
        }
        existingEntities = existingEntities == null ? List.of() : List.copyOf(existingEntities);
        trustedEvents = trustedEvents == null ? List.of() : List.copyOf(trustedEvents);
        if (storyRecords != null && acceptedResult != null) {
            throw new IllegalArgumentException("同一回合不能同时提交旧步骤结果与新故事记录");
        }
        storyRecords = storyRecords == null ? null : List.copyOf(storyRecords);
    }

    /** 已接受补丁和组成该回合的全部真实供应商调用。 */
    public record Generation(StoryPatch patch, List<ModelCall> modelCalls) {
        public Generation {
            if (patch == null) throw new IllegalArgumentException("完整回合缺少已接受补丁");
            modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
            if (modelCalls.isEmpty()) throw new IllegalArgumentException("完整回合必须保留模型调用摘要");
        }

        public ModelCall finalCall() {
            return modelCalls.getLast();
        }

        public int inputTokens() {
            return modelCalls.stream().mapToInt(ModelCall::inputTokens).sum();
        }

        public int outputTokens() {
            return modelCalls.stream().mapToInt(ModelCall::outputTokens).sum();
        }

        public int cacheHitTokens() {
            return modelCalls.stream().mapToInt(ModelCall::cacheHitTokens).sum();
        }

        public int cacheMissTokens() {
            return modelCalls.stream().mapToInt(ModelCall::cacheMissTokens).sum();
        }

        public long latencyMs() {
            return modelCalls.stream().mapToLong(ModelCall::latencyMs).sum();
        }
    }
}
