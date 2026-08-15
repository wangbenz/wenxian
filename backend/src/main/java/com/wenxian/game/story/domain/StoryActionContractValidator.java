package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.CharacterPrecondition;
import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ItemTransition;
import com.wenxian.game.story.domain.StoryActionContract.ActionMode;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryProjection.EntityState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用权威投影校验模型已经结构化的行动前提。
 *
 * <p>本类不理解“赠送、取回、出售”等自然语言，也不判断剧情合理性；自然语言语义由行动契约 Agent
 * 提取，本类只比较稳定 ID、实体类型、人物生命周期和物品保管者。</p>
 */
public final class StoryActionContractValidator {

    public void validate(StoryProjection projection, StoryActionContract contract) {
        validate(projection, contract, java.util.Set.of());
    }

    /**
     * sourceRefs 是本轮上下文中真实提供给模型的来源目录；只允许 ESTABLISHED 引用指向这些来源。
     */
    public void validate(StoryProjection projection, StoryActionContract contract,
                         java.util.Set<String> sourceRefs) {
        if (projection == null) throw new IllegalArgumentException("行动契约缺少当前事实投影");
        if (contract == null) throw new IllegalArgumentException("行动契约不能为空");
        java.util.Set<String> availableSources = sourceRefs == null ? java.util.Set.of() : java.util.Set.copyOf(sourceRefs);
        if (contract.conflict().present()) {
            throw new StoryActionPreconditionException(contract.conflict().reason(),
                    contract.conflict().correction());
        }
        if (contract.actionMode() == ActionMode.AMEND_FACT
                || contract.actionScope() == StoryActionContract.ActionScope.CORRECT_FACT) {
            throw new StoryActionPreconditionException(
                    "这段输入是在修改已经落定的命卷事实，不属于普通行动。",
                    "请使用“修正命卷”预览改动，确认后再写入。");
        }
        boolean createsTrackedItem = contract.references().stream().anyMatch(this::createsTrackedItem);
        boolean changesExistingItem = contract.references().stream().anyMatch(value -> value != null
                && value.resolution() == ReferenceResolution.EXISTING
                && "ITEM".equalsIgnoreCase(value.entityType()));
        if (contract.itemActionDetected() && changesExistingItem
                && contract.itemTransitions().isEmpty() && !createsTrackedItem) {
            throw new IllegalArgumentException("行动契约识别到物品处置，但没有返回物品归属变化");
        }
        Map<String, EntityReference> referencesByKey = new LinkedHashMap<>();
        contract.references().forEach(reference -> {
            validateReference(projection, contract, reference, availableSources);
            String key = StoryReferenceAlias.normalizeKey(reference.referenceKey());
            if (key != null && referencesByKey.putIfAbsent(key, reference) != null) {
                throw new IllegalArgumentException("行动契约存在重复的回合内引用键：" + key);
            }
        });
        for (ItemTransition transition : contract.itemTransitions()) {
            validateItem(projection, contract, referencesByKey, transition);
        }
        for (CharacterPrecondition condition : contract.characterPreconditions()) {
            EntityState character = require(projection, condition.characterId(), "CHARACTER");
            if (condition.requiredLifecycle() != null
                    && character.lifecycle() != condition.requiredLifecycle()) {
                throw new StoryActionPreconditionException(
                        "当前人物“" + character.name() + "”的状态不满足这次行动。",
                        "请先确认人物当前状态，或改为调查其下落与状况。");
            }
            if (condition.requiredRelationship() != null && !condition.requiredRelationship().isBlank()) {
                String actual = character.relationship() == null || character.relationship().label() == null
                        ? "" : character.relationship().label().strip();
                if (!actual.equals(condition.requiredRelationship().strip())) {
                    throw new StoryActionPreconditionException(
                            "你与人物“" + character.name() + "”当前确认的关系是“"
                                    + (actual.isBlank() ? "尚未确认" : actual) + "”。",
                            "请按当前关系重新描述行动，或先通过剧情改变双方关系。");
                }
            }
        }
    }

    private void validateReference(StoryProjection projection, StoryActionContract contract,
                                   EntityReference reference, java.util.Set<String> availableSources) {
        if (reference == null) throw new IllegalArgumentException("行动契约实体引用不能为空");
        if (reference.resolution() == null) throw new IllegalArgumentException("行动契约实体引用缺少解析类型");
        if (blank(reference.mention())) throw new IllegalArgumentException("行动契约实体引用缺少玩家原始提及");
        if (blank(reference.entityType())) throw new IllegalArgumentException("行动契约实体引用缺少实体类型");
        if (blank(reference.canonicalName())) throw new IllegalArgumentException("行动契约实体引用缺少标准名称");
        if (reference.expectedCustody() != null && !"ITEM".equalsIgnoreCase(reference.entityType())) {
            throw new IllegalArgumentException("只有可转移物品可以声明保管关系：" + reference.canonicalName());
        }
        switch (reference.resolution()) {
            case EXISTING -> {
                EntityState existing = require(projection, reference.entityId(), reference.entityType());
                if (!existing.name().strip().equals(reference.canonicalName().strip())) {
                    throw new IllegalArgumentException("行动契约标准名称与稳定ID不一致：" + reference.entityId());
                }
                if (reference.expectedCustody() != null && "ITEM".equalsIgnoreCase(reference.entityType())) {
                    CustodyValue expected = normalized(reference.expectedCustody());
                    if (expected == null || !same(normalizedCustody(existing), expected)) {
                        throw new StoryActionPreconditionException(
                                "“" + existing.name() + "”当前" + holderDescription(projection, normalizedCustody(existing))
                                        + "，不满足本轮行动依赖的保管状态。",
                                "请按当前物品归属重新描述行动，或先调查物品实际去向。");
                    }
                }
            }
            case ESTABLISHED -> {
                if (!blank(reference.entityId())) {
                    throw new IllegalArgumentException("来源已确立但未晋升的引用不能伪造稳定实体ID");
                }
                if (reference.evidenceRefs().isEmpty()) {
                    throw new IllegalArgumentException("来源已确立的引用必须包含证据来源："
                            + reference.canonicalName());
                }
                if (reference.evidenceRefs().stream().noneMatch(availableSources::contains)) {
                    throw new StoryActionPreconditionException(
                            "当前命卷无法验证“" + reference.canonicalName() + "”的来源。",
                            "请刷新命卷后重试，或改为调查、寻找、询问等尝试性行动。");
                }
                if (reference.expectedCustody() != null) {
                    CustodyValue custody = normalized(reference.expectedCustody());
                    if (custody == null || custody.holderType() == HolderType.UNKNOWN) {
                        throw new IllegalArgumentException("来源已确立物品的保管前提不能写成未知");
                    }
                    validateHolderReference(projection, custody);
                }
            }
            case NEW -> {
                if (!blank(reference.entityId())) throw new IllegalArgumentException("新实体引用不能绑定已有稳定ID");
                if (reference.expectedCustody() != null) {
                    CustodyValue target = normalized(reference.expectedCustody());
                    if (target == null || target.holderType() == HolderType.UNKNOWN) {
                        throw new IllegalArgumentException("新实体的预期保管者不能未知");
                    }
                    validateHolderReference(projection, target);
                }
            }
            case UNRESOLVED -> {
                CustodyValue claimedCustody = normalized(reference.expectedCustody());
                if ("ITEM".equalsIgnoreCase(reference.entityType())
                        && claimedCustody != null && claimedCustody.holderType() == HolderType.PLAYER
                        && (contract.itemActionDetected() || contract.actionMode() == ActionMode.ASSERT_FACT)) {
                    throw new StoryActionPreconditionException(
                            "当前命卷中没有证据支持你已经持有“" + reference.mention().strip() + "”。",
                            "可以改为寻找、调查或尝试获得该物品，或先确认它当前由谁保管。");
                }
                if (contract.actionMode() == ActionMode.ASSERT_FACT && !contract.conflict().present()) {
                    throw new StoryActionPreconditionException(
                            "当前命卷中没有证据支持“" + reference.mention().strip() + "”已经成立。",
                            "可以改为寻找、调查、询问或尝试获得，而不是直接声明它已经属于你。");
                }
            }
        }
    }

    private boolean createsTrackedItem(EntityReference reference) {
        return reference != null && reference.resolution() == ReferenceResolution.NEW
                && "ITEM".equalsIgnoreCase(reference.entityType()) && reference.expectedCustody() != null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void validateItem(StoryProjection projection, StoryActionContract contract,
                              Map<String, EntityReference> referencesByKey,
                              ItemTransition transition) {
        if (transition == null) throw new IllegalArgumentException("物品归属变化不能为空");
        EntityState item = require(projection, transition.itemId(), "ITEM");
        CustodyValue actual = normalizedCustody(item);
        CustodyValue expected = normalized(transition.expectedFrom());
        CustodyValue target = normalized(transition.target());
        if (expected == null || target == null) {
            throw new IllegalArgumentException("物品归属变化必须同时声明原保管者和目标保管者");
        }
        if (!same(actual, expected)) {
            throw new StoryActionPreconditionException(
                    "“" + item.name() + "”当前" + holderDescription(projection, actual)
                            + "，并非" + holderDescription(projection, expected) + "。",
                    "请按当前物品归属重新描述行动，或先调查物品实际去向。");
        }
        validateTransitionTarget(projection, contract, referencesByKey, transition, target);
        if (transition.quantity() != null && transition.quantity() < 1) {
            throw new IllegalArgumentException("本轮处置的物品数量必须大于0");
        }
        if (transition.quantity() != null && item.quantity() != null && item.quantity().known()
                && item.quantity().value() != null && transition.quantity() > item.quantity().value()) {
            throw new StoryActionPreconditionException(
                    "“" + item.name() + "”当前数量不足，不能按本次描述处置。",
                    "请减少数量，或先确认实际持有数量。");
        }
    }

    /**
     * 人物保管者必须来自玩家本轮实际引用。既有人物使用稳定 ID；来源人物或本轮新人物使用回合内引用键，
     * 禁止模型把目标人物静默绑定为投影中的另一个人物。
     */
    private void validateTransitionTarget(StoryProjection projection, StoryActionContract contract,
                                          Map<String, EntityReference> referencesByKey,
                                          ItemTransition transition, CustodyValue target) {
        if (target.holderType() != HolderType.CHARACTER) {
            validateHolderReference(projection, target);
            return;
        }
        String aliasKey = StoryReferenceAlias.keyOf(target.holderRef());
        String declaredKey = StoryReferenceAlias.normalizeKey(transition.targetReferenceKey());
        if (aliasKey != null) {
            if (declaredKey == null || !aliasKey.equals(declaredKey)) {
                throw new IllegalArgumentException("物品人物目标的回合内引用键不一致");
            }
            EntityReference reference = referencesByKey.get(aliasKey);
            if (reference == null || !"CHARACTER".equalsIgnoreCase(reference.entityType())) {
                throw new IllegalArgumentException("物品人物目标没有对应的行动实体引用：" + aliasKey);
            }
            if (reference.resolution() != ReferenceResolution.ESTABLISHED
                    && reference.resolution() != ReferenceResolution.NEW) {
                throw new IllegalArgumentException("回合内人物引用只能指向来源人物或本轮新人物");
            }
            return;
        }

        validateHolderReference(projection, target);
        boolean explicitlyReferenced = contract.references().stream().anyMatch(reference -> reference != null
                && reference.resolution() == ReferenceResolution.EXISTING
                && "CHARACTER".equalsIgnoreCase(reference.entityType())
                && target.holderRef().equals(reference.entityId()));
        if (!explicitlyReferenced) {
            throw new IllegalArgumentException("物品目标人物没有出现在玩家本轮的稳定实体引用中");
        }
        if (declaredKey != null) {
            EntityReference reference = referencesByKey.get(declaredKey);
            if (reference == null || !target.holderRef().equals(reference.entityId())) {
                throw new IllegalArgumentException("物品目标人物与声明的实体引用不一致");
            }
        }
    }

    private EntityState require(StoryProjection projection, String entityId, String expectedType) {
        if (entityId == null || entityId.isBlank()) throw new IllegalArgumentException("行动契约缺少稳定实体ID");
        EntityState entity = projection.entities().get(entityId);
        if (entity == null) {
            throw new StoryActionPreconditionException("当前命卷中没有找到这项已确认记录。",
                    "请刷新命卷后重试，或先在剧情中确认对应对象。");
        }
        if (expectedType == null || expectedType.isBlank()
                || !expectedType.equalsIgnoreCase(entity.entityType())) {
            throw new IllegalArgumentException("行动契约实体类型与稳定ID不一致：" + entityId);
        }
        return entity;
    }

    private void validateHolderReference(StoryProjection projection, CustodyValue custody) {
        if (custody.holderType() == HolderType.CHARACTER) {
            require(projection, custody.holderRef(), "CHARACTER");
        }
        if ((custody.holderType() == HolderType.CHARACTER || custody.holderType() == HolderType.LOCATION)
                && (custody.holderRef() == null || custody.holderRef().isBlank())) {
            throw new IllegalArgumentException("人物或地点保管关系必须带稳定引用");
        }
    }

    public static CustodyValue normalizedCustody(EntityState item) {
        CustodyValue explicit = normalized(item.custody());
        if (explicit != null) return explicit;
        if (Boolean.TRUE.equals(item.inPossession())) return new CustodyValue(HolderType.PLAYER, null);
        return new CustodyValue(HolderType.UNKNOWN, null);
    }

    private static CustodyValue normalized(CustodyValue value) {
        if (value == null || value.holderType() == null) return null;
        String ref = value.holderType() == HolderType.CHARACTER || value.holderType() == HolderType.LOCATION
                ? value.holderRef() == null || value.holderRef().isBlank() ? null : value.holderRef().strip()
                : null;
        return new CustodyValue(value.holderType(), ref);
    }

    private boolean same(CustodyValue left, CustodyValue right) {
        return left.holderType() == right.holderType()
                && java.util.Objects.equals(left.holderRef(), right.holderRef());
    }

    private String holderDescription(StoryProjection projection, CustodyValue value) {
        return switch (value.holderType()) {
            case PLAYER -> "仍由你保管";
            case CHARACTER -> "由人物“" + displayName(projection, value.holderRef()) + "”保管";
            case LOCATION -> "留在地点“" + value.holderRef() + "”";
            case NONE -> "已经消耗或不再存在";
            case UNKNOWN -> "去向尚未确认";
        };
    }

    private String displayName(StoryProjection projection, String entityId) {
        EntityState entity = projection.entities().get(entityId);
        return entity == null || entity.name() == null || entity.name().isBlank() ? "未知人物" : entity.name();
    }
}
