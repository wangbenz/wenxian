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
        ActionMode actionMode,
        ActionScope actionScope,
        boolean itemActionDetected,
        List<EntityReference> references,
        List<ItemTransition> itemTransitions,
        List<CharacterPrecondition> characterPreconditions,
        PlayerAgencyBoundary agencyBoundary,
        String stopBoundary,
        Conflict conflict
) {
    public StoryActionContract {
        intentSummary = text(intentSummary);
        actionMode = actionMode == null ? ActionMode.ATTEMPT : actionMode;
        actionScope = actionScope == null ? ActionScope.DO_NOW : actionScope;
        references = safe(references);
        itemTransitions = safe(itemTransitions);
        characterPreconditions = safe(characterPreconditions);
        agencyBoundary = agencyBoundary == null
                ? new PlayerAgencyBoundary(intentSummary.isBlank() ? List.of() : List.of(intentSummary),
                        List.of(), List.of(), "")
                : agencyBoundary.authorizedActions().isEmpty() && !intentSummary.isBlank()
                ? new PlayerAgencyBoundary(List.of(intentSummary), agencyBoundary.prohibitedActions(),
                        agencyBoundary.unresolvedDecisions(), agencyBoundary.requiredEndState())
                : agencyBoundary;
        stopBoundary = text(stopBoundary);
        conflict = conflict == null ? new Conflict(false, "", "") : conflict;
    }

    /** 兼容行动时间/事实范围加入前的完整调用方。 */
    public StoryActionContract(String intentSummary, ActionMode actionMode, boolean itemActionDetected,
                               List<EntityReference> references, List<ItemTransition> itemTransitions,
                               List<CharacterPrecondition> characterPreconditions,
                               PlayerAgencyBoundary agencyBoundary, String stopBoundary, Conflict conflict) {
        this(intentSummary, actionMode, ActionScope.DO_NOW, itemActionDetected, references, itemTransitions,
                characterPreconditions, agencyBoundary, stopBoundary, conflict);
    }

    /** 兼容行动语义模式加入前的调用方；普通游戏行动默认视为尝试。 */
    public StoryActionContract(String intentSummary, boolean itemActionDetected,
                               List<EntityReference> references, List<ItemTransition> itemTransitions,
                               List<CharacterPrecondition> characterPreconditions,
                               PlayerAgencyBoundary agencyBoundary, String stopBoundary, Conflict conflict) {
        this(intentSummary, ActionMode.ATTEMPT, ActionScope.DO_NOW, itemActionDetected, references, itemTransitions,
                characterPreconditions, agencyBoundary, stopBoundary, conflict);
    }

    /** 兼容行动授权边界加入前的测试替身和调用方。 */
    public StoryActionContract(String intentSummary, boolean itemActionDetected,
                               List<EntityReference> references, List<ItemTransition> itemTransitions,
                               List<CharacterPrecondition> characterPreconditions, String stopBoundary,
                               Conflict conflict) {
        this(intentSummary, itemActionDetected, references, itemTransitions, characterPreconditions,
                null, stopBoundary, conflict);
    }

    /**
     * 玩家输入对事实的语义关系。QUERY/ATTEMPT 允许探索尚未确认的可能；ASSERT_FACT 依赖既有事实；
     * AMEND_FACT 必须进入显式命卷修正流程，不能伪装成普通剧情回合。
     */
    public enum ActionMode {
        QUERY,
        ATTEMPT,
        ASSERT_FACT,
        AMEND_FACT
    }

    /** 玩家输入作用于当前行动、未来推进、过去断言、世界修订或事实纠错中的哪一层。 */
    public enum ActionScope {
        DO_NOW,
        ADVANCE_FUTURE,
        ASSERT_PAST,
        REVISE_WORLD,
        CORRECT_FACT
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
            CustodyValue expectedCustody,
            List<String> evidenceRefs,
            String referenceKey,
            ReferencePersistence persistence,
            boolean resultTarget
    ) {
        public EntityReference {
            evidenceRefs = safe(evidenceRefs);
            referenceKey = text(referenceKey);
            persistence = persistence == null ? defaultPersistence(resolution) : persistence;
        }

        /** 兼容持久性和结果目标加入前的完整行动引用。 */
        public EntityReference(String mention, ReferenceResolution resolution, String entityId,
                               String entityType, String canonicalName, String role,
                               CustodyValue expectedCustody, List<String> evidenceRefs,
                               String referenceKey) {
            this(mention, resolution, entityId, entityType, canonicalName, role, expectedCustody,
                    evidenceRefs, referenceKey, null, false);
        }

        /** 兼容来源引用加入前的实体契约。 */
        public EntityReference(String mention, ReferenceResolution resolution, String entityId,
                               String entityType, String canonicalName, String role,
                               CustodyValue expectedCustody) {
            this(mention, resolution, entityId, entityType, canonicalName, role, expectedCustody,
                    List.of(), "", null, false);
        }

        /** 兼容回合内引用键加入前、已经提供来源证据的调用方。 */
        public EntityReference(String mention, ReferenceResolution resolution, String entityId,
                               String entityType, String canonicalName, String role,
                               CustodyValue expectedCustody, List<String> evidenceRefs) {
            this(mention, resolution, entityId, entityType, canonicalName, role, expectedCustody,
                    evidenceRefs, "", null, false);
        }

        public EntityReference(String entityId, String entityType, String role) {
            this("", ReferenceResolution.EXISTING, entityId, entityType, "", role, null, List.of(), "",
                    ReferencePersistence.DURABLE, false);
        }

        private static ReferencePersistence defaultPersistence(ReferenceResolution resolution) {
            return resolution == ReferenceResolution.EXISTING || resolution == ReferenceResolution.ESTABLISHED
                    ? ReferencePersistence.DURABLE : ReferencePersistence.PROMOTE_ON_CHANGE;
        }
    }

    public enum ReferenceResolution {
        EXISTING,
        ESTABLISHED,
        NEW,
        UNRESOLVED
    }

    /**
     * 实体提及是否需要跨回合保存。TRANSIENT 只参与本轮叙事；PROMOTE_ON_CHANGE 只有在本轮形成长期变化时建档。
     */
    public enum ReferencePersistence {
        DURABLE,
        TRANSIENT,
        PROMOTE_ON_CHANGE
    }

    /** quantity 为空表示整件或数量未知；有值时表示玩家本轮明确处置的数量。 */
    public record ItemTransition(String itemId, CustodyValue expectedFrom,
                                 CustodyValue target, Integer quantity, String targetReferenceKey) {
        public ItemTransition {
            targetReferenceKey = text(targetReferenceKey);
        }

        /** 兼容只涉及既有稳定实体的物品变化。 */
        public ItemTransition(String itemId, CustodyValue expectedFrom,
                              CustodyValue target, Integer quantity) {
            this(itemId, expectedFrom, target, quantity, "");
        }
    }

    /** 玩家行动明确依赖某个人物既有身份关系时，同时记录当前已确认关系名称。 */
    public record CharacterPrecondition(String characterId, LifecycleState requiredLifecycle,
                                        String requiredRelationship) {
        public CharacterPrecondition(String characterId, LifecycleState requiredLifecycle) {
            this(characterId, requiredLifecycle, null);
        }
    }

    /**
     * 玩家本轮授予主角的行动权限。
     *
     * <p>它由独立行动解析请求形成，供正文模型与独立审阅共同使用。字段保存语义陈述而不是业务枚举，
     * 因而能覆盖开放世界中的任意行动；Java 只负责完整传递，不使用关键词替玩家解释。</p>
     */
    public record PlayerAgencyBoundary(
            List<String> authorizedActions,
            List<String> prohibitedActions,
            List<String> unresolvedDecisions,
            String requiredEndState
    ) {
        public PlayerAgencyBoundary {
            authorizedActions = safe(authorizedActions);
            prohibitedActions = safe(prohibitedActions);
            unresolvedDecisions = safe(unresolvedDecisions);
            requiredEndState = text(requiredEndState);
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
