package com.wenxian.v2.ai.domain;

import java.util.List;

public record ActionAdjudication(
        Decision decision,
        String code,
        String reason,
        List<String> suggestions,
        boolean consumesTurn,
        boolean changesState
) {
    public ActionAdjudication {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public static ActionAdjudication accept() {
        return new ActionAdjudication(Decision.ACCEPT, "ACTION_ACCEPTED", "行动可以在当前事实下尝试。",
                List.of(), true, true);
    }

    public static ActionAdjudication reject(String code, String reason, String... suggestions) {
        return new ActionAdjudication(Decision.REJECT, code, reason, List.of(suggestions), false, false);
    }

    public static ActionAdjudication clarify(String reason, String... suggestions) {
        return new ActionAdjudication(Decision.CLARIFY, "ACTION_CLARIFICATION_REQUIRED", reason,
                List.of(suggestions), false, false);
    }

    public enum Decision {
        ACCEPT,
        REJECT,
        CLARIFY
    }
}
