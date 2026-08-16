package com.wenxian.game.story.domain;

import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.EntityAlias;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 将行动理解模型返回的候选实体引用编译为当前命卷的稳定引用。
 *
 * <p>模型负责理解“玩家说的是谁或什么”，服务器负责稳定 ID、类型和标准名称。这里仅使用稳定 ID、
 * 当前名称和已经落库的精确别名，不使用业务关键词、模糊相似度或剧情专用规则猜测实体身份。</p>
 */
public final class StoryEntityReferenceCompiler {
    private final StoryActionContractCanonicalizer itemCanonicalizer = new StoryActionContractCanonicalizer();

    public StoryActionContract compile(StoryProjection projection, StoryActionContract contract,
                                       List<EntityAlias> aliases) {
        return compile(projection, contract, aliases, null);
    }

    /** 服务端已解析出明确持续时间时，未来推进范围不再依赖模型重复判断。 */
    public StoryActionContract compile(StoryProjection projection, StoryActionContract contract,
                                       List<EntityAlias> aliases, Long explicitDurationMinutes) {
        if (projection == null || contract == null) return contract;
        AliasIndex index = index(projection, aliases);
        List<EntityReference> compiled = contract.references().stream()
                .map(reference -> compileReference(projection, index, reference))
                .toList();
        Set<String> existingCharacters = compiled.stream()
                .filter(reference -> reference != null
                        && reference.resolution() == ReferenceResolution.EXISTING
                        && "CHARACTER".equalsIgnoreCase(reference.entityType())
                        && !blank(reference.entityId()))
                .map(EntityReference::entityId)
                .collect(Collectors.toUnmodifiableSet());
        // 人物前置条件只能引用本轮已经解析出的正式人物。来源人物和新人物尚无稳定 ID，
        // 其存在性由 sourceRef/referenceKey 负责，不能因为模型返回空 characterId 拒绝整轮。
        var characterPreconditions = contract.characterPreconditions().stream()
                .filter(condition -> condition != null && !blank(condition.characterId()))
                .filter(condition -> existingCharacters.contains(condition.characterId()))
                .toList();
        StoryActionContract.ActionScope scope = explicitDurationMinutes == null
                ? contract.actionScope() : StoryActionContract.ActionScope.ADVANCE_FUTURE;
        StoryActionContract normalized = compiled.equals(contract.references())
                && characterPreconditions.equals(contract.characterPreconditions())
                && scope == contract.actionScope()
                ? contract : new StoryActionContract(contract.intentSummary(), contract.actionMode(), scope,
                contract.itemActionDetected(), compiled, contract.itemTransitions(),
                characterPreconditions, contract.agencyBoundary(), contract.stopBoundary(),
                contract.conflict());
        return itemCanonicalizer.canonicalize(projection, normalized);
    }

    private EntityReference compileReference(StoryProjection projection, AliasIndex index,
                                             EntityReference reference) {
        if (reference == null) return null;
        if (reference.resolution() != ReferenceResolution.EXISTING) {
            if (reference.persistence() == StoryActionContract.ReferencePersistence.TRANSIENT) {
                return transientReference(reference);
            }
            return removeMeaninglessCustody(reference);
        }

        EntityState stable = entity(projection, reference.entityId());
        if (stable != null && sameType(stable, reference.entityType())) {
            return canonical(reference, stable);
        }

        EntityState resolved = uniqueAliasTarget(projection, index, reference);
        if (resolved != null && (blank(reference.entityType()) || sameType(resolved, reference.entityType()))) {
            return canonical(reference, resolved);
        }

        // 临时人物和场景对象没有稳定 ID 时可以正常参与本轮，不得仅因模型误写 EXISTING 阻塞普通问路或观察。
        if (reference.persistence() == StoryActionContract.ReferencePersistence.TRANSIENT) {
            return transientReference(reference);
        }

        // ID 指向其他类型时不能强行改写。例如功法名称误绑到玉简 ITEM ID，应交给结构修复创建或解析能力。
        return removeMeaninglessCustody(reference);
    }

    private EntityReference canonical(EntityReference reference, EntityState stable) {
        return new EntityReference(reference.mention(), reference.resolution(), stable.id(), stable.entityType(),
                stable.name(), reference.role(), "ITEM".equalsIgnoreCase(stable.entityType())
                ? reference.expectedCustody() : null, reference.evidenceRefs(), reference.referenceKey(),
                StoryActionContract.ReferencePersistence.DURABLE, reference.resultTarget());
    }

    private EntityReference transientReference(EntityReference reference) {
        return new EntityReference(reference.mention(), ReferenceResolution.NEW, null, reference.entityType(),
                reference.canonicalName(), reference.role(), null, List.of(), reference.referenceKey(),
                StoryActionContract.ReferencePersistence.TRANSIENT, reference.resultTarget());
    }

    /** 非物品没有保管者语义；清除模型误填字段属于结构归一，不改变剧情结果。 */
    private EntityReference removeMeaninglessCustody(EntityReference reference) {
        if (reference.expectedCustody() == null || "ITEM".equalsIgnoreCase(reference.entityType())) return reference;
        return new EntityReference(reference.mention(), reference.resolution(), reference.entityId(),
                reference.entityType(), reference.canonicalName(), reference.role(), null,
                reference.evidenceRefs(), reference.referenceKey(), reference.persistence(),
                reference.resultTarget());
    }

    private EntityState uniqueAliasTarget(StoryProjection projection, AliasIndex index,
                                          EntityReference reference) {
        Set<String> candidates = new LinkedHashSet<>();
        addCandidates(candidates, index, reference.canonicalName());
        addCandidates(candidates, index, reference.mention());
        if (candidates.size() != 1) return null;
        return projection.entities().get(candidates.iterator().next());
    }

    private void addCandidates(Set<String> candidates, AliasIndex index, String value) {
        String normalized = StoryEntityRepository.normalizeAlias(value);
        if (normalized.isBlank()) return;
        candidates.addAll(index.byName().getOrDefault(normalized, Set.of()));
    }

    private AliasIndex index(StoryProjection projection, List<EntityAlias> aliases) {
        Map<String, Set<String>> byName = new LinkedHashMap<>();
        projection.entities().values().forEach(entity -> {
            add(byName, entity.name(), entity.id());
            StoryCharacterIdentityNames.from(entity).forEach(name -> add(byName, name, entity.id()));
        });
        if (aliases != null) {
            aliases.stream().filter(EntityAlias::active)
                    .filter(alias -> projection.entities().containsKey(alias.entityId()))
                    .forEach(alias -> add(byName, alias.normalizedAlias(), alias.entityId()));
        }
        Map<String, Set<String>> immutable = new LinkedHashMap<>();
        byName.forEach((key, value) -> immutable.put(key, Set.copyOf(value)));
        return new AliasIndex(Map.copyOf(immutable));
    }

    private void add(Map<String, Set<String>> byName, String name, String entityId) {
        String normalized = StoryEntityRepository.normalizeAlias(name);
        if (normalized.isBlank() || blank(entityId)) return;
        byName.computeIfAbsent(normalized, ignored -> new LinkedHashSet<>()).add(entityId);
    }

    private EntityState entity(StoryProjection projection, String id) {
        return blank(id) ? null : projection.entities().get(id);
    }

    private boolean sameType(EntityState entity, String declaredType) {
        return blank(declaredType) || declaredType.strip().equalsIgnoreCase(entity.entityType());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record AliasIndex(Map<String, Set<String>> byName) {}
}
