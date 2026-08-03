package com.wenxian.v2.game.application;

import com.wenxian.v2.ai.domain.ActionIntent;
import com.wenxian.v2.ai.domain.TurnNarrativeContext;
import com.wenxian.v2.ai.domain.TurnProposal.DomainEffectProposal;
import com.wenxian.v2.api.V2ApiException;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.persistence.DomainEffectRepository;
import com.wenxian.v2.game.persistence.DomainEffectRepository.ItemDefinition;
import com.wenxian.v2.game.persistence.TurnRepository.DomainChange;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DomainEffectService {
    private static final int MAX_EFFECTS = 8;
    private static final Set<String> TYPES = Set.of(
            "ITEM_ACQUIRED", "ITEM_CONSUMED", "ITEM_TRANSFERRED_OUT",
            "WALLET_CREDIT", "WALLET_DEBIT", "ABILITY_USED", "BODY_CONDITION_SET",
            "CULTIVATION_INSIGHT", "EVENT_ADVANCED");
    private static final List<String> EVENT_STAGES = List.of(
            "SEED", "SIGN", "BUILDUP", "ERUPTION", "TURN", "RESULT", "AFTERMATH");
    private static final Map<String, BigDecimal> CURRENCY_CAPS = Map.of(
            "WEN", new BigDecimal("10000"),
            "SILVER_LIANG", new BigDecimal("100"),
            "GOLD_LIANG", new BigDecimal("10"),
            "SPIRIT_STONE_LOW", new BigDecimal("20"),
            "SPIRIT_STONE_MID", new BigDecimal("2"),
            "SPIRIT_STONE_HIGH", new BigDecimal("1"));

    private final DomainEffectRepository effects;

    public DomainEffectService(DomainEffectRepository effects) {
        this.effects = effects;
    }

    public List<ValidatedEffect> validate(SaveAggregate aggregate, TurnNarrativeContext context,
                                          ActionIntent intent, List<DomainEffectProposal> proposals) {
        if (proposals == null || proposals.isEmpty()) return List.of();
        if (proposals.size() > MAX_EFFECTS) throw invalid("domainEffects数量");

        Map<String, BigDecimal> itemRemaining = new HashMap<>();
        aggregate.inventory().forEach(item -> itemRemaining.put(item.instanceId(), item.quantity()));
        Map<String, BigDecimal> walletRemaining = new HashMap<>();
        aggregate.wallet().forEach(wallet -> walletRemaining.put(wallet.currencyCode(), wallet.balance()));
        Map<String, ItemDefinition> definitions = new HashMap<>();
        effects.activeItemDefinitions().forEach(item -> definitions.put(item.id(), item));
        Map<String, String> eventStages = new HashMap<>();
        context.activeEvents().forEach(event -> eventStages.put(event.id(), event.stage()));
        Set<String> abilityIds = new HashSet<>();
        aggregate.abilities().stream().filter(ability -> "ACTIVE".equals(ability.status()))
                .forEach(ability -> abilityIds.add(ability.characterAbilityId()));
        Set<String> usedKeys = new HashSet<>();
        List<ValidatedEffect> validated = new ArrayList<>();

        for (DomainEffectProposal proposal : proposals) {
            String type = normalized(proposal.effectType());
            if (!TYPES.contains(type)) throw invalid("domainEffects.effectType");
            String entityId = stripped(proposal.entityId());
            String definitionId = stripped(proposal.definitionId());
            String currencyCode = normalized(proposal.currencyCode());
            String value = stripped(proposal.value());
            String reason = requireText(proposal.reason(), 2, 300, "domainEffects.reason");
            BigDecimal amount = proposal.amount();

            switch (type) {
                case "ITEM_ACQUIRED" -> {
                    ItemDefinition definition = definitions.get(definitionId);
                    requirePositive(amount, new BigDecimal("20"), "domainEffects.amount");
                    if (definition == null || "item-identity-token".equals(definitionId)) {
                        throw invalid("domainEffects.definitionId");
                    }
                    requireUnique(usedKeys, type + ":" + definitionId);
                    validated.add(new ValidatedEffect(type, null, definitionId, null, amount, null, reason));
                }
                case "ITEM_CONSUMED", "ITEM_TRANSFERRED_OUT" -> {
                    var item = aggregate.inventory().stream()
                            .filter(candidate -> candidate.instanceId().equals(entityId)).findFirst().orElse(null);
                    requirePositive(amount, new BigDecimal("100"), "domainEffects.amount");
                    ItemDefinition definition = item == null ? null : definitions.get(item.definitionId());
                    if (item == null || !"AVAILABLE".equals(item.status())
                            || ("ITEM_CONSUMED".equals(type) && (definition == null || !definition.consumable()))
                            || ("ITEM_TRANSFERRED_OUT".equals(type) && (definition == null || !definition.tradeable()))) {
                        throw invalid("domainEffects.entityId");
                    }
                    BigDecimal remaining = itemRemaining.get(entityId).subtract(amount);
                    if (remaining.signum() < 0) throw invalid("domainEffects.amount");
                    itemRemaining.put(entityId, remaining);
                    requireUnique(usedKeys, "ITEM_OUT:" + entityId);
                    if (!intent.referencedItemIds().contains(entityId)) throw invalid("intent.referencedItemIds");
                    validated.add(new ValidatedEffect(type, entityId, item.definitionId(), null,
                            amount, null, reason));
                }
                case "WALLET_CREDIT", "WALLET_DEBIT" -> {
                    BigDecimal cap = CURRENCY_CAPS.get(currencyCode);
                    requirePositive(amount, cap, "domainEffects.amount");
                    if (!walletRemaining.containsKey(currencyCode)) throw invalid("domainEffects.currencyCode");
                    BigDecimal signed = "WALLET_DEBIT".equals(type) ? amount.negate() : amount;
                    BigDecimal remaining = walletRemaining.get(currencyCode).add(signed);
                    if (remaining.signum() < 0) throw invalid("domainEffects.amount");
                    walletRemaining.put(currencyCode, remaining);
                    requireUnique(usedKeys, "WALLET:" + currencyCode);
                    validated.add(new ValidatedEffect(type, null, null, currencyCode, amount, null, reason));
                }
                case "ABILITY_USED" -> {
                    if (!abilityIds.contains(entityId) || !intent.referencedAbilityIds().contains(entityId)) {
                        throw invalid("domainEffects.entityId");
                    }
                    requireUnique(usedKeys, type + ":" + entityId);
                    validated.add(new ValidatedEffect(type, entityId, null, null, null, null, reason));
                }
                case "BODY_CONDITION_SET" -> {
                    if (!aggregate.protagonist().id().equals(entityId)) throw invalid("domainEffects.entityId");
                    value = requireText(value, 2, 120, "domainEffects.value");
                    requireUnique(usedKeys, type);
                    validated.add(new ValidatedEffect(type, entityId, null, null, null, value, reason));
                }
                case "CULTIVATION_INSIGHT" -> {
                    if (!aggregate.protagonist().id().equals(entityId)
                            || !("CULTIVATE".equals(intent.actionType()) || "INVESTIGATE".equals(intent.actionType()))) {
                        throw invalid("domainEffects.entityId");
                    }
                    value = requireText(value, 2, 500, "domainEffects.value");
                    requireUnique(usedKeys, type);
                    validated.add(new ValidatedEffect(type, entityId, null, null, null, value, reason));
                }
                case "EVENT_ADVANCED" -> {
                    String current = eventStages.get(entityId);
                    String target = normalized(value);
                    if (current == null || !target.equals(nextEventStage(current))) {
                        throw invalid("domainEffects.value");
                    }
                    requireUnique(usedKeys, type + ":" + entityId);
                    validated.add(new ValidatedEffect(type, entityId, null, null, null, target, reason));
                }
                default -> throw invalid("domainEffects.effectType");
            }
        }
        validateTradePairing(aggregate, intent, validated);
        return List.copyOf(validated);
    }

    public List<DomainChange> apply(String turnId, SaveAggregate aggregate, List<ValidatedEffect> validated,
                                    long worldMinute, long stateVersion, Instant now, int firstOrder) {
        List<DomainChange> changes = new ArrayList<>();
        int order = firstOrder;
        for (ValidatedEffect effect : validated) {
            switch (effect.type()) {
                case "ITEM_ACQUIRED" -> {
                    ItemDefinition definition = effects.itemDefinition(effect.definitionId());
                    if (definition == null) throw new IllegalStateException("Item definition changed before commit");
                    String itemId = effects.acquireItem(aggregate.save().id(), aggregate.protagonist().id(),
                            definition, effect.amount(), turnId, effect.reason(), worldMinute, now);
                    changes.add(change(turnId, aggregate.save().id(), order++, "ITEM", itemId,
                            effect.type(), "0", effect.amount().toPlainString(), effect.reason(), now));
                }
                case "ITEM_CONSUMED", "ITEM_TRANSFERRED_OUT" -> {
                    var item = effects.lockOwnedItem(aggregate.save().id(), aggregate.protagonist().id(), effect.entityId());
                    if (item == null) throw new IllegalStateException("Item changed before commit");
                    BigDecimal after = item.quantity().subtract(effect.amount());
                    effects.removeItemQuantity(aggregate.save().id(), aggregate.protagonist().id(), effect.entityId(),
                            effect.amount(), "ITEM_CONSUMED".equals(effect.type()) ? "CONSUMED" : "TRANSFERRED_OUT",
                            turnId, effect.reason(), worldMinute, now);
                    changes.add(change(turnId, aggregate.save().id(), order++, "ITEM", effect.entityId(),
                            effect.type(), item.quantity().toPlainString(), after.toPlainString(), effect.reason(), now));
                }
                case "WALLET_CREDIT", "WALLET_DEBIT" -> {
                    BigDecimal signed = "WALLET_DEBIT".equals(effect.type())
                            ? effect.amount().negate() : effect.amount();
                    var mutation = effects.mutateWallet(aggregate.save().id(), aggregate.protagonist().id(),
                            effect.currencyCode(), signed, turnId, effect.reason(), worldMinute, now);
                    changes.add(change(turnId, aggregate.save().id(), order++, "WALLET", mutation.walletId(),
                            effect.type(), mutation.before().toPlainString(), mutation.after().toPlainString(),
                            effect.reason(), now));
                }
                case "ABILITY_USED" -> {
                    effects.markAbilityUsed(aggregate.save().id(), aggregate.protagonist().id(), effect.entityId(),
                            worldMinute, now);
                    changes.add(change(turnId, aggregate.save().id(), order++, "ABILITY", effect.entityId(),
                            effect.type(), null, String.valueOf(worldMinute), effect.reason(), now));
                }
                case "BODY_CONDITION_SET" -> {
                    String before = effects.bodyCondition(aggregate.protagonist().id());
                    effects.setBodyCondition(aggregate.protagonist().id(), effect.value(), stateVersion, now);
                    changes.add(change(turnId, aggregate.save().id(), order++, "CHARACTER",
                            aggregate.protagonist().id(), effect.type(), before, effect.value(), effect.reason(), now));
                }
                case "CULTIVATION_INSIGHT" -> {
                    var before = effects.cultivation(aggregate.save().id(), aggregate.protagonist().id());
                    effects.setCultivationInsight(aggregate.save().id(), aggregate.protagonist().id(),
                            effect.value(), now);
                    changes.add(change(turnId, aggregate.save().id(), order++, "CULTIVATION",
                            aggregate.protagonist().id(), effect.type(), before == null ? null : before.insight(),
                            effect.value(), effect.reason(), now));
                }
                case "EVENT_ADVANCED" -> {
                    var before = effects.lockEvent(aggregate.save().id(), effect.entityId());
                    if (before == null) throw new IllegalStateException("Event changed before commit");
                    effects.advanceEvent(aggregate.save().id(), effect.entityId(), effect.value(),
                            effect.reason(), worldMinute, now);
                    changes.add(change(turnId, aggregate.save().id(), order++, "EVENT", effect.entityId(),
                            effect.type(), before.stage(), effect.value(), effect.reason(), now));
                }
                default -> throw new IllegalStateException("Unsupported validated effect");
            }
        }
        return List.copyOf(changes);
    }

    private void validateTradePairing(SaveAggregate aggregate, ActionIntent intent,
                                      List<ValidatedEffect> effects) {
        if (!"TRADE".equals(intent.actionType())) return;
        List<ValidatedEffect> acquired = matching(effects, "ITEM_ACQUIRED");
        List<ValidatedEffect> sold = matching(effects, "ITEM_TRANSFERRED_OUT");
        List<ValidatedEffect> debits = matching(effects, "WALLET_DEBIT");
        List<ValidatedEffect> credits = matching(effects, "WALLET_CREDIT");
        if ((!acquired.isEmpty() && debits.isEmpty() && sold.isEmpty())
                || (!sold.isEmpty() && credits.isEmpty() && acquired.isEmpty())) {
            throw invalid("domainEffects交易对价");
        }
        if (!acquired.isEmpty() && !debits.isEmpty()) {
            if (acquired.size() != 1 || debits.size() != 1) throw invalid("domainEffects复杂交易");
            validatePrice(aggregate, acquired.getFirst(), debits.getFirst());
        }
        if (!sold.isEmpty() && !credits.isEmpty()) {
            if (sold.size() != 1 || credits.size() != 1) throw invalid("domainEffects复杂交易");
            validatePrice(aggregate, sold.getFirst(), credits.getFirst());
        }
    }

    private List<ValidatedEffect> matching(List<ValidatedEffect> effects, String type) {
        return effects.stream().filter(effect -> type.equals(effect.type())).toList();
    }

    private void validatePrice(SaveAggregate aggregate, ValidatedEffect item, ValidatedEffect wallet) {
        var anchor = effects.priceAnchor(aggregate.characterState().locationId(), item.definitionId(),
                wallet.currencyCode());
        if (anchor == null) throw invalid("domainEffects缺少当地物价");
        BigDecimal minimum = anchor.minimum().multiply(item.amount());
        BigDecimal maximum = anchor.maximum().multiply(item.amount());
        if (wallet.amount().compareTo(minimum) < 0 || wallet.amount().compareTo(maximum) > 0) {
            throw invalid("domainEffects交易价格");
        }
    }

    private String nextEventStage(String current) {
        int index = EVENT_STAGES.indexOf(normalized(current));
        return index >= 0 && index + 1 < EVENT_STAGES.size() ? EVENT_STAGES.get(index + 1) : null;
    }

    private void requireUnique(Set<String> keys, String key) {
        if (!keys.add(key)) throw invalid("domainEffects重复变化");
    }

    private void requirePositive(BigDecimal amount, BigDecimal maximum, String field) {
        if (amount == null || amount.signum() <= 0 || maximum == null || amount.compareTo(maximum) > 0) {
            throw invalid(field);
        }
    }

    private String requireText(String value, int minimum, int maximum, String field) {
        String result = stripped(value);
        if (result.length() < minimum || result.length() > maximum) throw invalid(field);
        return result;
    }

    private String normalized(String value) {
        return stripped(value).toUpperCase(Locale.ROOT);
    }

    private String stripped(String value) {
        return value == null ? "" : value.strip();
    }

    private V2ApiException invalid(String field) {
        return new V2ApiException(HttpStatus.BAD_GATEWAY, "AI_DOMAIN_EFFECT_INVALID", "SERVICE",
                "本轮变化没有通过命卷一致性检查，已有状态不会改变。",
                List.of("稍后使用同一行动重试", "问题字段：" + field));
    }

    private DomainChange change(String turnId, String saveId, int order, String domainType,
                                String entityId, String changeType, String before, String after,
                                String reason, Instant now) {
        return new DomainChange("v2-change-" + UUID.randomUUID(), turnId, saveId, order,
                domainType, entityId, changeType, before, after, reason, now);
    }

    public record ValidatedEffect(String type, String entityId, String definitionId, String currencyCode,
                                  BigDecimal amount, String value, String reason) {
    }
}
