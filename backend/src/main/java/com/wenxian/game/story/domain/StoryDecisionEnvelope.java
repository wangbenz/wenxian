package com.wenxian.game.story.domain;

import java.util.List;

/**
 * 单回合的重大决定所有权契约。
 *
 * <p>它从行动理解结果派生，不判断胜负。剧情可以补充授权行为的过程、世界反应和自然收尾，
 * 但不得替玩家回答保留决定或发起下一项重大行动。</p>
 */
public record StoryDecisionEnvelope(
        List<String> authorizedMaterialActions,
        List<String> reservedDecisions,
        List<String> allowedConsequences,
        String requiredEndState
) {
    public StoryDecisionEnvelope {
        authorizedMaterialActions = safe(authorizedMaterialActions);
        reservedDecisions = safe(reservedDecisions);
        allowedConsequences = safe(allowedConsequences);
        requiredEndState = requiredEndState == null ? "" : requiredEndState.strip();
    }

    public static StoryDecisionEnvelope from(StoryActionContract contract) {
        if (contract == null || contract.agencyBoundary() == null) {
            return new StoryDecisionEnvelope(List.of(), List.of(), defaultConsequences(), "");
        }
        var boundary = contract.agencyBoundary();
        return new StoryDecisionEnvelope(boundary.authorizedActions(), boundary.unresolvedDecisions(),
                defaultConsequences(), boundary.requiredEndState());
    }

    private static List<String> defaultConsequences() {
        return List.of("成功", "失败", "部分成功", "受到直接影响", "消耗合理时间", "停留等待下一次决定");
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
