package com.wenxian.v2.ai.application;

import com.wenxian.v2.ai.domain.ActionIntent;
import com.wenxian.v2.ai.domain.IntentContext.EntityCandidate;
import com.wenxian.v2.ai.domain.TurnNarrativeContext;
import com.wenxian.v2.ai.domain.TurnProposal;
import com.wenxian.v2.ai.domain.TurnProposal.ConfirmedFactProposal;
import com.wenxian.v2.ai.domain.TurnProposal.DomainEffectProposal;
import com.wenxian.v2.ai.domain.TurnProposal.Generated;
import com.wenxian.v2.game.domain.WorldCalendar;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Repairs harmless model formatting drift before strict domain validation.
 * It never creates rewards or high-impact outcomes: unknown references remain
 * rejectable when they are central to the player's requested action.
 */
@Service
public class TurnProposalNormalizer {
    private static final List<String> FACT_DOMAINS = List.of(
            "CHARACTER", "LOCATION", "EVENT", "KNOWLEDGE", "WORLD");
    private static final List<String> EVENT_STAGES = List.of(
            "SEED", "SIGN", "BUILDUP", "ERUPTION", "TURN", "RESULT", "AFTERMATH");

    public Generated normalize(TurnNarrativeContext context, Generated generated) {
        TurnProposal proposal = generated.proposal();
        ActionIntent intent = normalizeIntent(context, proposal.intent());
        List<ConfirmedFactProposal> facts = normalizeFacts(context, proposal.confirmedFacts());
        List<DomainEffectProposal> effects = normalizeEffects(context, intent, proposal.domainEffects());
        TurnProposal normalized = new TurnProposal(intent, proposal.elapsedMinutes(), proposal.outcomeCode(),
                proposal.outcomeSummary(), proposal.narrativeText(), facts, effects,
                proposal.highImpact(), proposal.choices());
        return new Generated(normalized, generated.providerCode(), generated.modelCode());
    }

    private List<ConfirmedFactProposal> normalizeFacts(TurnNarrativeContext context,
                                                       List<ConfirmedFactProposal> proposals) {
        Map<String, String> characters = aliases(context.authority().characterCandidates());
        Map<String, String> locations = aliases(context.authority().locationCandidates());
        Map<String, String> items = aliases(context.authority().itemCandidates());
        Map<String, String> abilities = abilityAliases(context);
        Map<String, String> events = new LinkedHashMap<>();
        context.activeEvents().forEach(event -> {
            events.put(key(event.id()), event.id());
            events.putIfAbsent(key(event.title()), event.id());
        });
        List<ConfirmedFactProposal> result = new ArrayList<>();
        for (ConfirmedFactProposal proposal : proposals) {
            String domain = upper(proposal.domainType());
            String fact = proposal.fact() == null ? "" : proposal.fact().strip();
            if (!FACT_DOMAINS.contains(domain) || fact.length() < 2 || fact.length() > 300) continue;
            String entityId = switch (domain) {
                case "CHARACTER" -> characters.get(key(proposal.entityId()));
                case "LOCATION" -> locations.get(key(proposal.entityId()));
                case "EVENT" -> events.get(key(proposal.entityId()));
                case "KNOWLEDGE", "WORLD" -> canonicalFactScope(context, proposal.entityId(),
                        characters, locations, items, abilities, events);
                default -> null;
            };
            if (entityId == null) continue;
            result.add(new ConfirmedFactProposal(domain, entityId, fact));
            if (result.size() == 4) break;
        }
        return List.copyOf(result);
    }

    private String canonicalFactScope(TurnNarrativeContext context, String raw,
                                      Map<String, String>... aliases) {
        if (key(raw).equals(key(context.authority().saveId()))) return context.authority().saveId();
        for (Map<String, String> group : aliases) {
            String canonical = group.get(key(raw));
            if (canonical != null) return canonical;
        }
        // Knowledge and world facts belong to the save when the model does not name a durable entity.
        return context.authority().saveId();
    }

    private ActionIntent normalizeIntent(TurnNarrativeContext context, ActionIntent intent) {
        boolean itemCentral = List.of("USE_ITEM", "TRADE").contains(intent.actionType());
        boolean abilityCentral = List.of("USE_ABILITY", "COMBAT", "CULTIVATE").contains(intent.actionType());
        List<String> items = canonicalReferences(intent.referencedItemIds(), context.authority().itemCandidates(),
                itemCentral);
        List<String> abilities = canonicalReferences(intent.referencedAbilityIds(),
                abilityAliases(context), abilityCentral);
        Long durationMinutes = intent.durationMinutes();
        if ("WAIT".equals(intent.actionType()) && durationMinutes == null
                && intent.durationAmount() == null && intent.durationUnit() == null) {
            durationMinutes = WorldCalendar.durationUntilNamedPeriod(
                    context.authority().currentWorldMinute(), context.authority().actionText());
        }
        return new ActionIntent(intent.actionType(), intent.intentMode(), durationMinutes,
                intent.targetLocationId(), intent.targetCharacterId(), items, abilities,
                intent.targetRealmCode(), intent.declaresGuaranteedOutcome(), intent.ambiguities(),
                intent.summary(), intent.durationAmount(), intent.durationUnit());
    }

    private List<String> canonicalReferences(List<String> references, List<EntityCandidate> candidates,
                                             boolean preserveUnknown) {
        return canonicalReferences(references, aliases(candidates), preserveUnknown);
    }

    private List<String> canonicalReferences(List<String> references, Map<String, String> aliases,
                                             boolean preserveUnknown) {
        List<String> result = new ArrayList<>();
        for (String reference : references) {
            String canonical = aliases.get(key(reference));
            if (canonical != null) result.add(canonical);
            else if (preserveUnknown) result.add(reference);
        }
        return result.stream().distinct().toList();
    }

    private List<DomainEffectProposal> normalizeEffects(TurnNarrativeContext context, ActionIntent intent,
                                                         List<DomainEffectProposal> proposals) {
        Map<String, String> items = aliases(context.authority().itemCandidates());
        Map<String, String> definitions = aliases(context.authority().itemDefinitionCandidates());
        Map<String, String> abilities = abilityAliases(context);
        Map<String, String> currencies = aliases(context.authority().walletCandidates());
        Map<String, String> events = new LinkedHashMap<>();
        Map<String, String> eventStages = new LinkedHashMap<>();
        context.activeEvents().forEach(event -> {
            events.put(key(event.id()), event.id());
            events.putIfAbsent(key(event.title()), event.id());
            eventStages.put(event.id(), event.stage());
        });

        List<DomainEffectProposal> result = new ArrayList<>();
        for (DomainEffectProposal proposal : proposals) {
            String type = upper(proposal.effectType());
            String entityId = proposal.entityId();
            String definitionId = proposal.definitionId();
            String currencyCode = proposal.currencyCode();
            String value = proposal.value();
            switch (type) {
                case "ITEM_CONSUMED", "ITEM_TRANSFERRED_OUT" ->
                        entityId = canonicalOrOriginal(items, entityId);
                case "ITEM_ACQUIRED" -> definitionId = canonicalOrOriginal(definitions, definitionId);
                case "WALLET_CREDIT", "WALLET_DEBIT" ->
                        currencyCode = canonicalOrOriginal(currencies, currencyCode);
                case "ABILITY_USED" -> {
                    String canonical = abilities.get(key(entityId));
                    if (canonical == null && !List.of("USE_ABILITY", "COMBAT", "CULTIVATE")
                            .contains(intent.actionType())) continue;
                    if (canonical != null) entityId = canonical;
                    else if (intent.referencedAbilityIds().size() == 1
                            && abilities.containsValue(intent.referencedAbilityIds().getFirst())) {
                        entityId = intent.referencedAbilityIds().getFirst();
                    }
                }
                case "BODY_CONDITION_SET" -> {
                    entityId = context.authority().protagonist().id();
                    if (value == null || value.isBlank()) value = proposal.reason();
                }
                case "CULTIVATION_INSIGHT" -> {
                    if (!List.of("CULTIVATE", "INVESTIGATE").contains(intent.actionType())) continue;
                    entityId = context.authority().protagonist().id();
                    if (value == null || value.isBlank()) value = proposal.reason();
                }
                case "EVENT_ADVANCED" -> {
                    // Time passing is resolved by AutonomousWorldService. Letting the model also
                    // advance an event during WAIT/CULTIVATE can skip two stages in one turn.
                    if (List.of("WAIT", "CULTIVATE").contains(intent.actionType())) continue;
                    String canonical = events.get(key(entityId));
                    if (canonical == null) {
                        entityId = entityId;
                    } else {
                        entityId = canonical;
                        String next = nextStage(eventStages.get(canonical));
                        if (next != null) value = next;
                    }
                }
                default -> {
                    // Strict validation decides whether an unknown effect type is legal.
                }
            }
            result.add(new DomainEffectProposal(type, entityId, definitionId, currencyCode,
                    proposal.amount(), value, proposal.reason()));
        }
        return List.copyOf(result);
    }

    private Map<String, String> aliases(List<EntityCandidate> candidates) {
        Map<String, String> result = new LinkedHashMap<>();
        candidates.forEach(candidate -> {
            result.put(key(candidate.id()), candidate.id());
            String nameKey = key(candidate.name());
            if (!nameKey.isEmpty()) result.putIfAbsent(nameKey, candidate.id());
        });
        return result;
    }

    /**
     * The model can see both a learned ability instance and its learnable definition. If it copies
     * the definition id for an already learned ability, map it back to the owned instance instead
     * of treating that harmless identifier choice as an attempt to invent an ability.
     */
    private Map<String, String> abilityAliases(TurnNarrativeContext context) {
        Map<String, String> result = aliases(context.authority().abilityCandidates());
        Map<String, String> ownedByName = new LinkedHashMap<>();
        context.authority().abilityCandidates().forEach(ability ->
                ownedByName.putIfAbsent(key(ability.name()), ability.id()));
        context.authority().abilityDefinitionCandidates().forEach(definition -> {
            String ownedId = ownedByName.get(key(definition.name()));
            if (ownedId != null) result.put(key(definition.id()), ownedId);
        });
        return result;
    }

    private String canonicalOrOriginal(Map<String, String> aliases, String value) {
        return aliases.getOrDefault(key(value), value);
    }

    private String nextStage(String current) {
        int index = EVENT_STAGES.indexOf(upper(current));
        return index >= 0 && index + 1 < EVENT_STAGES.size() ? EVENT_STAGES.get(index + 1) : null;
    }

    private String key(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private String upper(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }
}
