package com.wenxian.v2.ai.application;

import com.wenxian.v2.ai.domain.IntentContext;
import com.wenxian.v2.ai.domain.IntentContext.EntityCandidate;
import com.wenxian.v2.ai.domain.IntentContext.ProtagonistFacts;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.domain.RealmLadder;
import com.wenxian.v2.game.persistence.DomainEffectRepository;
import com.wenxian.v2.game.persistence.LocationRepository;
import org.springframework.stereotype.Service;

@Service
public class IntentContextAssembler {
    private final LocationRepository locations;
    private final DomainEffectRepository effects;

    public IntentContextAssembler(LocationRepository locations, DomainEffectRepository effects) {
        this.locations = locations;
        this.effects = effects;
    }

    public IntentContext assemble(String userId, String operationId, String actionText, SaveAggregate aggregate) {
        var protagonist = aggregate.protagonist();
        var state = aggregate.characterState();
        var nextRealm = RealmLadder.next(protagonist.race(), state.realmCode());
        var protagonistFacts = new ProtagonistFacts(protagonist.id(), protagonist.name(), protagonist.race().name(),
                state.realmCode(), state.realmDisplayName(),
                nextRealm == null ? null : nextRealm.code(), nextRealm == null ? null : nextRealm.displayName(),
                state.bodyCondition(), state.currentGoal());
        var characterCandidates = new java.util.ArrayList<EntityCandidate>();
        characterCandidates.add(new EntityCandidate(protagonist.id(), protagonist.name(), "PROTAGONIST",
                protagonist.status(), "境界=" + state.realmDisplayName() + "；位置=" + state.locationName()));
        aggregate.knownCharacters().forEach(character -> characterCandidates.add(new EntityCandidate(
                character.id(), character.name(), "NPC", character.status(),
                "种族=" + character.race().name() + "；身份=" + character.publicIdentity()
                        + "；境界=" + character.realmDisplayName() + "；位置=" + character.locationName()
                        + "；目标=" + character.currentGoal())));
        var locationCandidates = locations.knownLocations(aggregate.save().id(),
                        aggregate.characterState().locationId()).stream()
                .map(location -> new EntityCandidate(location.id(), location.name(), "LOCATION",
                        location.accessible() ? "ACCESSIBLE" : "KNOWN"))
                .toList();
        var itemCandidates = aggregate.inventory().stream()
                .map(item -> new EntityCandidate(item.instanceId(), item.name(), "ITEM", item.status(),
                        "数量=" + item.quantity() + "；状态=" + item.condition()))
                .toList();
        var abilityCandidates = aggregate.abilities().stream()
                .map(ability -> new EntityCandidate(ability.characterAbilityId(), ability.name(), ability.type(),
                        ability.status(), "掌握=" + ability.masteryStage() + "；稳定=" + ability.stability()))
                .toList();
        var itemDefinitionCandidates = effects.activeItemDefinitions().stream()
                .map(item -> new EntityCandidate(item.id(), item.name(), "ITEM_DEFINITION",
                        item.consumable() ? "CONSUMABLE" : "NON_CONSUMABLE",
                        "类型=" + item.itemType() + "；可堆叠=" + item.stackable()
                                + "；可交易=" + item.tradeable() + "；" + item.description()))
                .toList();
        var walletCandidates = aggregate.wallet().stream()
                .map(wallet -> new EntityCandidate(wallet.currencyCode(), wallet.currencyName(), "CURRENCY",
                        "AVAILABLE", "余额=" + wallet.balance()))
                .toList();
        var abilityDefinitionCandidates = effects.activeAbilityDefinitions(protagonist.race()).stream()
                .map(ability -> new EntityCandidate(ability.id(), ability.name(), "ABILITY_DEFINITION",
                        "LEARNABLE", "类型=" + ability.abilityType() + "；种族=" + ability.raceScope()
                                + "；" + ability.description()))
                .toList();
        return new IntentContext(userId, aggregate.save().id(), operationId, aggregate.save().stateVersion(),
                actionText, protagonistFacts, state.locationId(), state.locationName(), aggregate.worldMinute(),
                characterCandidates, locationCandidates, itemCandidates, abilityCandidates,
                itemDefinitionCandidates, walletCandidates, abilityDefinitionCandidates);
    }
}
