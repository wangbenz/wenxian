package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.CoreStatePatch;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.TrustedStoryEvent.EventType;

import java.util.ArrayList;
import java.util.List;

/**
 * 将通用 StoryPatch 收敛为类型化可信事件，并生成可交给旧 Reducer 的兼容补丁。
 *
 * <p>本编译器不会理解剧情或判断胜负。它只按实体类型移除无效可选字段、识别长期变化，
 * 让一次错误的无关字段不再迫使整章重写；缺少稳定引用、保管者或生命周期等重大字段仍由后续校验拒绝。</p>
 */
public final class TrustedStoryEventCompiler {

    public Compiled compile(StoryProjection before, StoryPatch proposed, SemanticDuration planned) {
        if (before == null || proposed == null) throw new IllegalArgumentException("可信事件编译缺少投影或提案");
        List<EntityPatch> normalizedEntities = proposed.entities().stream().map(this::normalizeEntity).toList();
        StoryPatch normalized = new StoryPatch(proposed.title(), proposed.narrative(), proposed.time(),
                proposed.coreState(), normalizedEntities, proposed.worldFacts(), proposed.knowledge(),
                proposed.memories(), proposed.decisionCheckpoint(), proposed.choices(), proposed.rollingSummary());
        List<TrustedStoryEvent> events = new ArrayList<>();
        events.add(new TrustedStoryEvent.ActionResolved(proposed.title(),
                proposed.time() != null && proposed.time().resolution() == TimeResolution.INTERRUPTED
                        ? TrustedStoryEvent.Resolution.INTERRUPTED : TrustedStoryEvent.Resolution.RESOLVED));
        for (EntityPatch patch : normalizedEntities) compileEntity(before, patch, events);
        compileCore(proposed.coreState(), events);
        compileTime(proposed, planned, events);
        proposed.worldFacts().forEach(fact -> events.add(new TrustedStoryEvent.WorldFactChanged(
                fact.operation() == WorldFactOperation.EXPAND
                        ? EventType.WORLD_FACT_ESTABLISHED : EventType.WORLD_FACT_REVISED,
                fact.factId(), fact.statement(), fact.reason())));
        return new Compiled(normalized, events);
    }

    private EntityPatch normalizeEntity(EntityPatch patch) {
        if (patch == null || patch.entityType() == null) return patch;
        String type = patch.entityType().strip().toUpperCase();
        return switch (type) {
            case "ITEM" -> copy(patch, patch.quantity(), patch.inPossession(), patch.custody(),
                    null, null, null);
            case "CHARACTER" -> copy(patch, null, null, null,
                    null, patch.lifecycle(), patch.relationship());
            case "ABILITY" -> copy(patch, null, null, null,
                    patch.abilityState(), null, null);
            case "LANDMARK" -> copy(patch, null, null, null,
                    null, null, null);
            default -> patch;
        };
    }

    private EntityPatch copy(EntityPatch patch, StoryPatch.QuantityValue quantity, Boolean inPossession,
                             CustodyValue custody, AbilityState abilityState,
                             StoryPatch.LifecycleValue lifecycle, StoryPatch.RelationshipValue relationship) {
        return new EntityPatch(patch.operation(), patch.entityId(), patch.entityType(), patch.name(), patch.status(),
                patch.summary(), patch.attributes(), quantity, inPossession, custody, abilityState, lifecycle,
                relationship, patch.nameChange(), patch.referenceKey());
    }

    private void compileEntity(StoryProjection before, EntityPatch patch, List<TrustedStoryEvent> events) {
        if (patch == null || patch.entityType() == null) return;
        String type = patch.entityType().strip().toUpperCase();
        switch (type) {
            case "ITEM" -> events.add(new TrustedStoryEvent.ItemChanged(itemEventType(before, patch),
                    patch.entityId(), patch.referenceKey(), patch.name(), patch.quantity(), patch.custody()));
            case "CHARACTER" -> compileCharacter(patch, events);
            case "ABILITY" -> events.add(new TrustedStoryEvent.AbilityChanged(abilityEventType(patch),
                    patch.entityId(), patch.referenceKey(), patch.name(), patch.abilityState()));
            case "LANDMARK" -> {
                if (patch.operation() == EntityOperation.CREATE) {
                    events.add(new TrustedStoryEvent.LandmarkEstablished(
                            patch.entityId(), patch.referenceKey(), patch.name()));
                }
            }
            default -> { }
        }
    }

    private EventType itemEventType(StoryProjection before, EntityPatch patch) {
        if (patch.operation() == EntityOperation.CREATE) {
            return patch.custody() != null && patch.custody().holderType() == HolderType.PLAYER
                    ? EventType.ITEM_ACQUIRED : EventType.ITEM_TRANSFERRED;
        }
        EntityState previous = before.entities().get(patch.entityId());
        CustodyValue next = patch.custody();
        if (next != null && next.holderType() == HolderType.NONE) return EventType.ITEM_CONSUMED;
        if (patch.quantity() != null && patch.quantity().known() && patch.quantity().value() != null
                && patch.quantity().value() == 0) return EventType.ITEM_CONSUMED;
        if (next != null && previous != null && previous.custody() != null
                && previous.custody().holderType() == HolderType.PLAYER
                && next.holderType() != HolderType.PLAYER) return EventType.ITEM_LOST;
        return EventType.ITEM_TRANSFERRED;
    }

    private void compileCharacter(EntityPatch patch, List<TrustedStoryEvent> events) {
        if (patch.operation() == EntityOperation.CREATE) {
            events.add(new TrustedStoryEvent.CharacterChanged(EventType.CHARACTER_ESTABLISHED,
                    patch.entityId(), patch.referenceKey(), patch.name(),
                    patch.lifecycle() == null ? null : patch.lifecycle().state(),
                    patch.relationship() == null ? null : patch.relationship().label(),
                    patch.lifecycle() == null ? "" : patch.lifecycle().changeReason()));
        } else if (patch.lifecycle() != null || patch.status() != null) {
            events.add(new TrustedStoryEvent.CharacterChanged(EventType.CHARACTER_STATE_CHANGED,
                    patch.entityId(), patch.referenceKey(), patch.name(), patch.lifecycle() == null
                    ? null : patch.lifecycle().state(), null,
                    patch.lifecycle() == null ? "" : patch.lifecycle().changeReason()));
        }
        if (patch.relationship() != null) {
            events.add(new TrustedStoryEvent.CharacterChanged(EventType.RELATION_CHANGED,
                    patch.entityId(), patch.referenceKey(), patch.name(), null,
                    patch.relationship().label(), patch.relationship().changeReason()));
        }
    }

    private EventType abilityEventType(EntityPatch patch) {
        if (patch.operation() != EntityOperation.CREATE) return EventType.ABILITY_STATE_CHANGED;
        if (patch.abilityState() == null) return EventType.ABILITY_STATE_CHANGED;
        return switch (patch.abilityState()) {
            case KNOWN -> EventType.ABILITY_KNOWN;
            case LEARNING -> EventType.ABILITY_LEARNING;
            case MASTERED -> EventType.ABILITY_MASTERED;
            default -> EventType.ABILITY_STATE_CHANGED;
        };
    }

    private void compileCore(CoreStatePatch core, List<TrustedStoryEvent> events) {
        if (core != null && core.location() != null && !core.location().isBlank()) {
            events.add(new TrustedStoryEvent.LocationChanged(core.location().strip()));
        }
    }

    private void compileTime(StoryPatch proposed, SemanticDuration planned,
                             List<TrustedStoryEvent> events) {
        if (proposed.time() == null || proposed.time().elapsedMinutes() <= 0) return;
        SemanticDuration actual = planned == null || proposed.time().resolution() == TimeResolution.INTERRUPTED
                ? compatibilityDuration(proposed.time().elapsedMinutes()) : planned;
        events.add(new TrustedStoryEvent.TimeElapsed(planned, actual,
                proposed.time().resolution() == TimeResolution.INTERRUPTED
                        ? TrustedStoryEvent.Completion.INTERRUPTED : TrustedStoryEvent.Completion.COMPLETED,
                proposed.time().interruptionReason()));
    }

    private SemanticDuration compatibilityDuration(long minutes) {
        if (minutes % (360L * 1_440L) == 0) {
            return new SemanticDuration(SemanticDuration.Unit.YEAR,
                    Math.toIntExact(minutes / (360L * 1_440L)), "");
        }
        if (minutes % (30L * 1_440L) == 0) {
            return new SemanticDuration(SemanticDuration.Unit.MONTH,
                    Math.toIntExact(minutes / (30L * 1_440L)), "");
        }
        if (minutes % 1_440L == 0) {
            return new SemanticDuration(SemanticDuration.Unit.DAY, Math.toIntExact(minutes / 1_440L), "");
        }
        return new SemanticDuration(SemanticDuration.Unit.PHASE,
                Math.max(1, Math.toIntExact((minutes + 179L) / 180L)), "");
    }

    public record Compiled(StoryPatch normalizedPatch, List<TrustedStoryEvent> events) {
        public Compiled {
            if (normalizedPatch == null) throw new IllegalArgumentException("可信事件编译结果缺少兼容补丁");
            events = events == null ? List.of() : List.copyOf(events);
        }
    }
}
