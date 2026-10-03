package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryCorrectionProposal.CorrectionKind;
import com.wenxian.game.story.domain.StoryPatch.TimePassage;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;

import java.util.List;

/**
 * 校验修正提案的类型边界和通用结构。
 *
 * <p>本校验器不从玩家文本提取地点、境界或物品名称，也不判断世界观内容是否“合理”。它只防止一种
 * 修正同时偷改多个事实层级，并确保世界修订不会伪装成普通剧情事件。</p>
 */
public final class StoryCorrectionValidator {
    private final StoryPatchValidator patchValidator;

    public StoryCorrectionValidator() {
        this(new StoryPatchValidator());
    }

    public StoryCorrectionValidator(StoryPatchValidator patchValidator) {
        this.patchValidator = patchValidator;
    }

    /** 校验失败时不得生成可确认预览，更不能落库。 */
    public void validate(StoryCorrectionProposal proposal) {
        require(proposal != null, "修正提案不能为空");
        require(proposal.kind() != null, "修正提案必须明确类型");
        require(present(proposal.explanation()), "修正提案必须解释识别到的变化");
        require(present(proposal.confirmationPrompt()), "修正提案必须提供玩家确认文案");

        StoryPatch structure = new StoryPatch("命卷修正预览", "本次只展示修正提案，不生成新剧情。",
                new TimePassage(new SemanticDuration(SemanticDuration.Unit.SCENE, 1, ""),
                        TimeResolution.COMPLETE, null), proposal.coreState(), proposal.entities(),
                proposal.worldFacts(), proposal.knowledge(), List.of(),
                new StoryPatch.DecisionCheckpoint(false, null), List.of(), "", null,
                proposal.cultivation());
        patchValidator.validate(structure);
        if (proposal.cultivation() != null) {
            require(proposal.cultivation().resolution() == StoryPatch.CultivationResolution.CORRECTED,
                    "命卷修正中的境界变化必须使用CORRECTED结果");
        }

        switch (proposal.kind()) {
            case FACT -> validateFact(proposal);
            case COGNITION -> validateCognition(proposal);
            case WORLD -> validateWorld(proposal);
        }
    }

    private void validateFact(StoryCorrectionProposal proposal) {
        require(proposal.coreState() != null || proposal.cultivation() != null || !proposal.entities().isEmpty(),
                "事实修正必须包含核心状态、修行阶段或实体变化");
        require(proposal.worldFacts().isEmpty(), "事实修正不能同时改写世界设定");
        require(proposal.knowledge().isEmpty(), "事实修正不能同时改写人物认知");
    }

    private void validateCognition(StoryCorrectionProposal proposal) {
        require(!proposal.knowledge().isEmpty(), "认知修正必须包含人物认知变化");
        require(proposal.coreState() == null && proposal.cultivation() == null
                        && proposal.entities().isEmpty() && proposal.worldFacts().isEmpty(),
                "认知修正不能同时改写客观事实");
    }

    private void validateWorld(StoryCorrectionProposal proposal) {
        require(!proposal.worldFacts().isEmpty(), "世界修订必须包含世界事实变化");
        require(proposal.coreState() == null && proposal.cultivation() == null
                        && proposal.entities().isEmpty() && proposal.knowledge().isEmpty(),
                "世界修订不能同时夹带角色或认知变化");
        proposal.worldFacts().forEach(value -> require(value.operation() == WorldFactOperation.EXPAND
                        || value.operation() == WorldFactOperation.REVISE,
                "剧情内自然转变不能通过世界修订入口提交"));
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
