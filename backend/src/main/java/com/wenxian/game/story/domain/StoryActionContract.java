package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;

import java.util.List;

/**
 * 玩家自然语言行动在生成剧情前形成的结构化事实契约。
 *
 * <p>它不决定剧情胜负，只把玩家提到的稳定实体、物品保管变化和人物前置状态显式化。
 * Java 只校验这些结构化前提是否与当前投影一致，不使用业务关键词或正则猜测玩家意图。</p>
 */
public record StoryActionContract(
        String intentSummary,
        boolean itemActionDetected,
        List<EntityReference> references,
        List<ItemTransition> itemTransitions,
        List<CharacterPrecondition> characterPreconditions,
        String stopBoundary,
        Conflict conflict
) {
    public StoryActionContract {
        intentSummary = text(intentSummary);
        references = safe(references);
        itemTransitions = safe(itemTransitions);
        characterPreconditions = safe(characterPreconditions);
        stopBoundary = text(stopBoundary);
        conflict = conflict == null ? new Conflict(false, "", "") : conflict;
    }

    /**
     * 玩家原话中的实体提及及其解析结果。
     *
     * <p>mention 保留玩家实际说法，canonicalName 是解析后的标准名称；EXISTING 必须绑定当前投影中的
     * 稳定 ID，NEW 明确表示本轮才出现，UNRESOLVED 表示玩家把尚未成立的对象当成了已有事实。
     * expectedCustody 表示玩家行动明确依赖的起始保管者，而不是模型为迎合当前事实抄写的结果；没有归属
     * 前提时可以为空。Java 只比较这些结构字段，不从玩家原句中提取业务关键词。</p>
     */
    public record EntityReference(
            String mention,
            ReferenceResolution resolution,
            String entityId,
            String entityType,
            String canonicalName,
            String role,
            CustodyValue expectedCustody
    ) {
        public EntityReference(String entityId, String entityType, String role) {
            this("", ReferenceResolution.EXISTING, entityId, entityType, "", role, null);
        }
    }

    public enum ReferenceResolution {
        EXISTING,
        NEW,
        UNRESOLVED
    }

    /** quantity 为空表示整件或数量未知；有值时表示玩家本轮明确处置的数量。 */
    public record ItemTransition(String itemId, CustodyValue expectedFrom,
                                 CustodyValue target, Integer quantity) {}

    /** 玩家行动明确依赖某个人物既有身份关系时，同时记录当前已确认关系名称。 */
    public record CharacterPrecondition(String characterId, LifecycleState requiredLifecycle,
                                        String requiredRelationship) {
        public CharacterPrecondition(String characterId, LifecycleState requiredLifecycle) {
            this(characterId, requiredLifecycle, null);
        }
    }

    /** 模型发现玩家输入建立在不成立的事实前提上时，必须直接说明冲突与可行修正。 */
    public record Conflict(boolean present, String reason, String correction) {
        public Conflict {
            reason = text(reason);
            correction = text(correction);
        }
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
