package com.wenxian.game.story.reducer;

import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryCorrectionValidator;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryPatch.TimePassage;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryProjection;

import java.util.List;

/**
 * 在玩家确认后，将中性修正提案归并到当前事实投影。
 *
 * <p>本类不保存数据库、不递增正式回合，也不重新调用模型。应用服务必须校验账号归属、expectedRevision
 * 和“玩家确认的是同一份提案”，再在单事务中保存投影与修正审计。</p>
 */
public final class StoryCorrectionReducer {
    private final StoryCorrectionValidator validator;
    private final StoryStateReducer stateReducer;

    public StoryCorrectionReducer() {
        this(new StoryCorrectionValidator(), new StoryStateReducer());
    }

    public StoryCorrectionReducer(StoryCorrectionValidator validator, StoryStateReducer stateReducer) {
        this.validator = validator;
        this.stateReducer = stateReducer;
    }

    /** 未收到玩家显式确认时拒绝应用，调用方只能返回预览。 */
    public Applied apply(StoryProjection before, StoryCorrectionProposal proposal, boolean playerConfirmed) {
        validator.validate(proposal);
        if (!playerConfirmed) throw new IllegalArgumentException("修正提案尚未获得玩家确认");

        StoryPatch synthetic = new StoryPatch("命卷修正", "本次修正不生成或替换历史剧情正文。",
                new TimePassage(0, TimeResolution.COMPLETE, null), proposal.coreState(), proposal.entities(),
                proposal.worldFacts(), proposal.knowledge(), List.of(),
                new StoryPatch.DecisionCheckpoint(false, null), List.of(), "");
        StoryStateReducer.Applied reduced = stateReducer.reduce(before, synthetic);
        if (reduced.projection().worldTimeMinutes() != before.worldTimeMinutes()) {
            throw new IllegalStateException("修正命卷不能推进世界时间");
        }
        if (reduced.projection().equals(before)) {
            throw new IllegalArgumentException("修正提案没有产生实际事实变化");
        }
        StoryCorrectionProposal accepted = new StoryCorrectionProposal(proposal.kind(), proposal.explanation(),
                proposal.confirmationPrompt(), reduced.acceptedPatch().coreState(),
                reduced.acceptedPatch().entities(), reduced.acceptedPatch().worldFacts(),
                reduced.acceptedPatch().knowledge());
        return new Applied(reduced.projection(), accepted, reduced.aliasChanges());
    }

    public record Applied(StoryProjection projection, StoryCorrectionProposal acceptedProposal,
                          List<StoryStateReducer.EntityAliasChange> aliasChanges) {
        public Applied {
            aliasChanges = aliasChanges == null ? List.of() : List.copyOf(aliasChanges);
        }
    }
}
