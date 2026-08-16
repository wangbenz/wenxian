package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;

/**
 * 在严格事实校验前清理模型提案中与实体类型无关的可选字段。
 *
 * <p>本类不补造缺失数据，也不修改正文、时间、结果或实体身份。它只移除模型常见的跨类型字段串位，
 * 例如地点误带人物关系、人物误带物品数量。名称、稳定引用、保管者、生命周期等必要字段仍由
 * {@link StoryPatchValidator} 严格校验，不能通过归一化绕过。</p>
 */
public final class StoryPatchNormalizer {

    /** 返回一份可继续进行严格校验的提案；空提案原样返回并由校验器拒绝。 */
    public StoryPatch normalize(StoryPatch proposed) {
        if (proposed == null) return null;
        return new StoryPatch(proposed.title(), proposed.narrative(), proposed.time(), proposed.coreState(),
                proposed.entities().stream().map(this::normalizeEntity).toList(), proposed.worldFacts(),
                proposed.knowledge(), proposed.memories(), proposed.decisionCheckpoint(), proposed.choices(),
                proposed.rollingSummary(), proposed.outcome());
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
}
