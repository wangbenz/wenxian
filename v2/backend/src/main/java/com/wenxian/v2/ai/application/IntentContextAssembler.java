package com.wenxian.v2.ai.application;

import com.wenxian.v2.ai.domain.IntentContext;
import com.wenxian.v2.ai.domain.IntentContext.EntityCandidate;
import com.wenxian.v2.ai.domain.IntentContext.ProtagonistFacts;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.persistence.LocationRepository;
import org.springframework.stereotype.Service;

@Service
public class IntentContextAssembler {
    private final LocationRepository locations;

    public IntentContextAssembler(LocationRepository locations) {
        this.locations = locations;
    }

    public IntentContext assemble(String userId, String operationId, String actionText, SaveAggregate aggregate) {
        var protagonist = aggregate.protagonist();
        var state = aggregate.characterState();
        var protagonistFacts = new ProtagonistFacts(protagonist.id(), protagonist.name(), protagonist.race().name(),
                state.realmCode(), state.realmDisplayName(), state.bodyCondition(), state.currentGoal());
        var characterCandidates = java.util.List.of(
                new EntityCandidate(protagonist.id(), protagonist.name(), "CHARACTER", protagonist.status()));
        var locationCandidates = locations.knownLocations(aggregate.save().id()).stream()
                .map(location -> new EntityCandidate(location.id(), location.name(), "LOCATION",
                        location.accessible() ? "ACCESSIBLE" : "KNOWN"))
                .toList();
        var itemCandidates = aggregate.inventory().stream()
                .map(item -> new EntityCandidate(item.instanceId(), item.name(), "ITEM", item.status()))
                .toList();
        var abilityCandidates = aggregate.abilities().stream()
                .map(ability -> new EntityCandidate(ability.characterAbilityId(), ability.name(), ability.type(),
                        ability.status()))
                .toList();
        return new IntentContext(userId, aggregate.save().id(), operationId, aggregate.save().stateVersion(),
                actionText, protagonistFacts, state.locationId(), state.locationName(), aggregate.worldMinute(),
                characterCandidates, locationCandidates, itemCandidates, abilityCandidates);
    }
}
