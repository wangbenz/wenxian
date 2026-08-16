package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.EntityAlias;

import java.util.ArrayList;
import java.util.List;

/**
 * 检查模型选择的既有人物 ID 是否在本轮文本证据中真正出现。
 *
 * <p>人物 ID 由模型返回并不等于已经证明身份。玩家只说“老人”“他”时，若当前行动、系统选项、
 * 上一回合或滚动摘要都没有出现该稳定人物的当前姓名或历史别名，就不能把唯一一个已有角色强行绑定
 * 给这个泛称。此时应让行动契约整理为带来源证据的 {@code ESTABLISHED} 场景人物，待人物发生物品保管、
 * 关系或生死等长期变化时再晋升。这里仅做精确身份依据检查，不按业务关键词或相似度猜人物。</p>
 */
public final class StoryCharacterReferenceEvidenceValidator {

    public void validate(StoryProjection projection, StoryActionContract contract,
                         List<EntityAlias> aliases, List<String> evidenceTexts) {
        if (projection == null || contract == null) return;
        String evidence = normalizedEvidence(evidenceTexts);
        for (EntityReference reference : contract.references()) {
            if (reference == null || reference.resolution() != ReferenceResolution.EXISTING
                    || !"CHARACTER".equalsIgnoreCase(reference.entityType())
                    || blank(reference.entityId())) {
                continue;
            }
            StoryProjection.EntityState entity = projection.entities().get(reference.entityId());
            if (entity == null || !"CHARACTER".equalsIgnoreCase(entity.entityType())) continue;
            List<String> identityNames = new ArrayList<>();
            identityNames.add(entity.name());
            if (aliases != null) {
                aliases.stream().filter(EntityAlias::active)
                        .filter(alias -> entity.id().equals(alias.entityId()))
                        .map(EntityAlias::aliasName)
                        .forEach(identityNames::add);
            }
            boolean supported = identityNames.stream().map(StoryEntityRepository::normalizeAlias)
                    .filter(value -> !value.isBlank())
                    .anyMatch(evidence::contains);
            if (!supported) {
                throw new IllegalArgumentException("既有人物引用缺少本轮文本身份依据："
                        + entity.name() + "；不得把场景泛称直接绑定为该人物");
            }
        }
    }

    private String normalizedEvidence(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(StoryEntityRepository::normalizeAlias)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
