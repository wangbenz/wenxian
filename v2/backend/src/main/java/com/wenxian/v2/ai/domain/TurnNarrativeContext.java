package com.wenxian.v2.ai.domain;

import java.util.List;

public record TurnNarrativeContext(
        String worldCanon,
        String difficulty,
        IntentContext authority,
        List<RecentTurn> recentTurns,
        List<ConfirmedFact> confirmedFacts,
        List<ActiveEvent> activeEvents
) {
    public TurnNarrativeContext {
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
        confirmedFacts = confirmedFacts == null ? List.of() : List.copyOf(confirmedFacts);
        activeEvents = activeEvents == null ? List.of() : List.copyOf(activeEvents);
    }

    public record RecentTurn(int turnNumber, String playerAction, String outcomeSummary, String narrativeExcerpt) {
    }

    public record ConfirmedFact(String domainType, String entityId, String fact, long worldMinute) {
    }

    public record ActiveEvent(String id, String title, String stage, String locationName, String publicSummary) {
    }
}
