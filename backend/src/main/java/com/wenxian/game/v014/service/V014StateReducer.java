package com.wenxian.game.v014.service;

import com.wenxian.game.engine.ActionDurationParser;
import com.wenxian.game.v014.model.AiTurnEnvelope;
import com.wenxian.game.v014.model.V014State;
import com.wenxian.game.v014.model.V014State.Ability;
import com.wenxian.game.v014.model.V014State.Character;
import com.wenxian.game.v014.model.V014State.Choice;
import com.wenxian.game.v014.model.V014State.Item;
import com.wenxian.game.v014.model.V014State.Memory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class V014StateReducer {
    public Applied apply(V014State before, String action, AiTurnEnvelope envelope) {
        long elapsed = safeElapsed(action, before.worldTimeMinutes(), envelope.elapsedMinutes());
        long afterTime = before.worldTimeMinutes() + elapsed;
        List<Item> items = new ArrayList<>(before.inventory());
        List<Ability> abilities = new ArrayList<>(before.abilities());
        List<Character> characters = new ArrayList<>(before.characters());
        List<Memory> memories = new ArrayList<>(before.memories());

        if (envelope.items() != null) envelope.items().forEach(patch -> applyItem(items, patch));
        if (envelope.abilities() != null) envelope.abilities().forEach(patch -> applyAbility(abilities, patch));
        if (envelope.characters() != null) envelope.characters().forEach(patch -> applyCharacter(characters, patch));
        if (envelope.memories() != null) {
            for (AiTurnEnvelope.MemoryDraft draft : envelope.memories().stream().limit(4).toList()) {
                if (present(draft.title()) && present(draft.summary())) {
                    memories.add(new Memory(id("memory"), draft.title().strip(), draft.summary().strip(),
                            afterTime, allowed(draft.importance(), List.of("MINOR", "NORMAL", "KEY"), "NORMAL")));
                }
            }
        }
        if (memories.size() > 80) memories = new ArrayList<>(memories.subList(memories.size() - 80, memories.size()));

        List<Choice> choices = choices(envelope.choices());
        String narrative = present(envelope.narrative()) ? envelope.narrative().strip() : "这一轮的命卷只留下了模糊痕迹。";
        V014State.Protagonist protagonist = before.protagonist();
        if (present(envelope.realm()) || present(envelope.lifeGoal())) {
            protagonist = new V014State.Protagonist(protagonist.name(), protagonist.race(), protagonist.gender(),
                    protagonist.origin(), text(envelope.realm(), protagonist.realm()),
                    text(envelope.lifeGoal(), protagonist.lifeGoal()), protagonist.storyTone());
        }
        V014State after = new V014State(14, before.saveId(), before.turnNumber() + 1, before.status(),
                before.world(), protagonist, afterTime,
                text(envelope.location(), before.currentLocation()), text(envelope.condition(), before.condition()),
                text(envelope.title(), "命卷新页"), narrative, choices, items, abilities, characters, memories);
        return new Applied(after, elapsed, envelope.rollingSummary() == null ? "" : envelope.rollingSummary().strip());
    }

    private long safeElapsed(String action, long current, Long modelValue) {
        Integer explicit = ActionDurationParser.parseMinutes(action, (int) Math.floorMod(current, Integer.MAX_VALUE));
        if (explicit != null) return explicit;
        long value = modelValue == null ? 60 : modelValue;
        return Math.max(1, Math.min(value, 100L * 360 * 1440));
    }

    private List<Choice> choices(List<AiTurnEnvelope.ChoiceDraft> drafts) {
        if (drafts == null) drafts = List.of();
        List<Choice> result = drafts.stream().filter(draft -> present(draft.title())).limit(5)
                .map(draft -> new Choice(id("choice"), draft.title().strip(), text(draft.description(), "沿此方向行动。"))).toList();
        if (!result.isEmpty()) return result;
        return List.of(
                new Choice(id("choice"), "继续探查", "从眼前留下的线索继续追索。"),
                new Choice(id("choice"), "与人交谈", "寻找知情者，确认局势与代价。"),
                new Choice(id("choice"), "暂避锋芒", "先整理所得，再决定下一步。")
        );
    }

    private void applyItem(List<Item> items, AiTurnEnvelope.EntityPatch patch) {
        if (!present(patch.name())) return;
        int index = indexOfItem(items, patch.name());
        String operation = operation(patch.operation());
        if (index < 0 && !"REMOVE".equals(operation)) {
            int quantity = patch.quantity() == null ? 1 : patch.quantity();
            String note = text(patch.status(), "仍在行囊中");
            boolean possessed = quantity > 0;
            items.add(new Item(id("item"), patch.name().strip(), text(patch.type(), "物品"),
                    text(patch.description(), "尚待了解。"), note,
                    text(patch.source(), "故事所得"), possessed ? Math.max(1, quantity) : Math.max(0, quantity), possessed));
        } else if (index >= 0) {
            Item old = items.get(index);
            String nextStatus = "REMOVE".equals(operation) ? text(patch.status(), "已经不再持有") : text(patch.status(), old.status());
            int quantity = Math.max(0, patch.quantity() == null ? old.quantity() : patch.quantity());
            boolean possessed = !"REMOVE".equals(operation) && quantity > 0;
            items.set(index, new Item(old.id(), old.name(), text(patch.type(), old.type()),
                    text(patch.description(), old.description()), nextStatus,
                    text(patch.source(), old.source()), quantity, possessed));
        }
    }

    private void applyAbility(List<Ability> abilities, AiTurnEnvelope.EntityPatch patch) {
        if (!present(patch.name())) return;
        int index = indexOfAbility(abilities, patch.name());
        String operation = operation(patch.operation());
        if (index < 0 && !"REMOVE".equals(operation)) {
            abilities.add(new Ability(id("ability"), patch.name().strip(), text(patch.type(), "能力"),
                    text(patch.description(), "尚待参悟。"), normalizedStatus(patch.status(), "MASTERED"),
                    text(patch.source(), "故事所得")));
        } else if (index >= 0) {
            Ability old = abilities.get(index);
            abilities.set(index, new Ability(old.id(), old.name(), text(patch.type(), old.type()),
                    text(patch.description(), old.description()),
                    "REMOVE".equals(operation) ? "LOST" : normalizedStatus(patch.status(), old.status()),
                    text(patch.source(), old.source())));
        }
    }

    private void applyCharacter(List<Character> characters, AiTurnEnvelope.CharacterPatch patch) {
        if (!present(patch.name()) || "TRANSIENT".equalsIgnoreCase(patch.tier())) return;
        int index = indexOfCharacter(characters, patch.name());
        String operation = operation(patch.operation());
        if (index < 0 && !"REMOVE".equals(operation)) {
            characters.add(new Character(id("character"), patch.name().strip(),
                    allowed(patch.tier(), List.of("KNOWN", "IMPORTANT"), "KNOWN"),
                    text(patch.identity(), "身份未明"), text(patch.relationship(), "初次相识"),
                    text(patch.status(), "近况未明"), text(patch.location(), "未知"), true));
        } else if (index >= 0) {
            Character old = characters.get(index);
            characters.set(index, new Character(old.id(), old.name(),
                    allowed(patch.tier(), List.of("KNOWN", "IMPORTANT"), old.tier()),
                    text(patch.identity(), old.identity()), text(patch.relationship(), old.relationship()),
                    text(patch.status(), old.status()),
                    text(patch.location(), old.lastKnownLocation()), !"REMOVE".equals(operation)));
        }
    }

    private int indexOfItem(List<Item> list, String name) { for (int i=0;i<list.size();i++) if (Boolean.TRUE.equals(list.get(i).inPossession()) && same(list.get(i).name(), name)) return i; return -1; }
    private int indexOfAbility(List<Ability> list, String name) { for (int i=0;i<list.size();i++) if (same(list.get(i).name(), name)) return i; return -1; }
    private int indexOfCharacter(List<Character> list, String name) { for (int i=0;i<list.size();i++) if (same(list.get(i).name(), name)) return i; return -1; }
    private boolean same(String left, String right) { return canonicalName(left).equalsIgnoreCase(canonicalName(right)); }
    private String canonicalName(String value) { return value.strip().replaceAll("[《》「」『』\\s]", ""); }
    private String operation(String value) { return allowed(value, List.of("ADD", "UPDATE", "REMOVE"), "UPDATE"); }
    private String normalizedStatus(String value, String fallback) { return present(value) ? value.strip().toUpperCase() : fallback; }
    private String allowed(String value, List<String> values, String fallback) { if (!present(value)) return fallback; String next=value.strip().toUpperCase(Locale.ROOT); return values.contains(next) ? next : fallback; }
    private String text(String value, String fallback) { return present(value) ? value.strip() : fallback; }
    private boolean present(String value) { return value != null && !value.isBlank(); }
    private String id(String prefix) { return prefix + "-" + UUID.randomUUID(); }

    public record Applied(V014State state, long elapsedMinutes, String rollingSummary) {}
}
