package com.wenxian.game.story.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;

/**
 * 将“本轮在场”这一临时提案标记转换为人物的最后确认地点。
 *
 * <p>本类不从人物姓名、正文关键词或关系名称猜测人物是否在场。模型只能在实体 attributes 中使用
 * {@code scenePresence=PRESENT|MENTIONED} 表达本轮结构化场景证据：PRESENT 表示人物确实在本轮场景
 * 出现，MENTIONED 表示只被提及。服务端仅在 PRESENT 且模型没有明确填写 lastKnownLocation 时，继承
 * 本轮结束地点；模型明确给出的其他地点永远优先。scenePresence 只服务本轮归一化，提交前会被移除。</p>
 */
public final class StorySceneLocationNormalizer {
    static final String SCENE_PRESENCE = "scenePresence";
    static final String LAST_KNOWN_LOCATION = "lastKnownLocation";
    static final String PRESENT = "PRESENT";

    /** 返回移除临时场景标记、并补全安全地点投影后的提案。 */
    public StoryPatch normalize(StoryProjection before, StoryPatch proposed) {
        if (before == null || proposed == null) {
            throw new IllegalArgumentException("场景地点归一化缺少投影或提案");
        }
        String finalLocation = finalLocation(before, proposed);
        return new StoryPatch(proposed.title(), proposed.narrative(), proposed.time(), proposed.coreState(),
                proposed.entities().stream().map(entity -> normalizeEntity(entity, finalLocation)).toList(),
                proposed.worldFacts(), proposed.knowledge(), proposed.memories(), proposed.decisionCheckpoint(),
                proposed.choices(), proposed.rollingSummary(), proposed.outcome(), proposed.cultivation());
    }

    private EntityPatch normalizeEntity(EntityPatch patch, String finalLocation) {
        if (patch == null || !"CHARACTER".equalsIgnoreCase(patch.entityType())) return patch;
        if (!(patch.attributes() instanceof ObjectNode source) || !source.has(SCENE_PRESENCE)) return patch;

        ObjectNode attributes = source.deepCopy();
        String presence = attributes.path(SCENE_PRESENCE).asText("").strip();
        attributes.remove(SCENE_PRESENCE);
        boolean canInherit = patch.operation() == EntityOperation.CREATE
                || patch.operation() == EntityOperation.UPDATE
                || patch.operation() == EntityOperation.RESTORE;
        if (canInherit && PRESENT.equalsIgnoreCase(presence) && present(finalLocation)
                && !present(attributes.path(LAST_KNOWN_LOCATION).asText(""))) {
            attributes.put(LAST_KNOWN_LOCATION, finalLocation);
        }
        JsonNode normalizedAttributes = attributes.isEmpty() ? null : attributes;
        return new EntityPatch(patch.operation(), patch.entityId(), patch.entityType(), patch.name(), patch.status(),
                patch.summary(), normalizedAttributes, patch.quantity(), patch.inPossession(), patch.custody(),
                patch.abilityState(), patch.lifecycle(), patch.relationship(), patch.nameChange(), patch.referenceKey());
    }

    private String finalLocation(StoryProjection before, StoryPatch proposed) {
        String proposedLocation = proposed.coreState() == null ? null : proposed.coreState().location();
        if (present(proposedLocation)) return proposedLocation.strip();
        String currentLocation = before.coreState() == null ? null : before.coreState().location();
        return present(currentLocation) ? currentLocation.strip() : "";
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
