package com.wenxian.game.engine;

import java.util.List;

/**
 * Result of intent understanding plus authoritative fact and conflict checks.
 */
public record ActionAdjudication(
        ActionIntent intent,
        ConflictVerdict verdict,
        AgentResult.ActionResolution actionResolution
) {
    public static ActionAdjudication accepted(ActionIntent intent, ConflictVerdict verdict) {
        return new ActionAdjudication(intent, verdict,
                new AgentResult.ActionResolution("ACCEPTED", "", "", List.of()));
    }

    public static ActionAdjudication rejected(ActionIntent intent, String title, String reason,
                                               List<String> suggestions) {
        return rejected(intent, "FACT_CONFLICT", title, reason, suggestions);
    }

    public static ActionAdjudication rejected(ActionIntent intent, String code, String title, String reason,
                                               List<String> suggestions) {
        return new ActionAdjudication(intent, ConflictVerdict.narrativeOnly(),
                new AgentResult.ActionResolution("REJECTED", code, title, reason, suggestions, false));
    }

    public static ActionAdjudication clarified(String title, String reason, List<String> suggestions) {
        return clarified("ACTION_UNCLEAR", title, reason, suggestions);
    }

    public static ActionAdjudication clarified(String code, String title, String reason, List<String> suggestions) {
        ActionIntent intent = new ActionIntent("OTHER", null, null, null, "UNKNOWN",
                List.of(), "MEDIUM", false, List.of());
        return clarified(intent, code, title, reason, suggestions);
    }

    public static ActionAdjudication clarified(ActionIntent intent, String code, String title, String reason,
                                                List<String> suggestions) {
        return new ActionAdjudication(intent, ConflictVerdict.narrativeOnly(),
                new AgentResult.ActionResolution("CLARIFY", code, title, reason, suggestions, false));
    }

    public boolean accepted() {
        return actionResolution != null && "ACCEPTED".equals(actionResolution.result());
    }
}
