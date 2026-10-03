package com.wenxian.game.story.replay.single;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.agent.ai.DirectorAssetDraftAdapter;
import com.wenxian.game.story.agent.ai.NarrativeWriterAi;
import com.wenxian.game.story.agent.ai.TurnDirectorAi;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryPatch;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

/**
 * 单次候选隔离实验的模型协议及确定性适配，仅存在于测试目录。
 *
 * <p>草案映射等价复制自基线 {@code ae0f32a} 的
 * {@code LangChain4jDirectedNarrativeGenerator.toDecision} 及其必需辅助方法，
 * 资产映射直接复用正式 {@link DirectorAssetDraftAdapter}。保留现有事实协议、分类拒绝、
 * 空值处理与枚举规则，避免把协议精简混入调用组织的比较。</p>
 *
 * <p>本类不补写末态声明、不从正文推断事实，也不执行领域编译、归并或事务。
 * 返回值仍是待检验提案；同次生成的正文不是事实正确性的证明。实验不会注册或切换生产 AI Service。</p>
 */
public final class SingleCandidateProtocol {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final DirectorAssetDraftAdapter assets = new DirectorAssetDraftAdapter();

    private SingleCandidateProtocol() {}

    /** 单次模型返回完整导演草案和正文；不新增正文末态自证或第二份事实清单。 */
    public record Draft(TurnDirectorAi.DecisionDraft decision, NarrativeWriterAi.NarrativeDraft story) {}

    /** 类型化实验入口；是否接受整份候选由调用它的测试宿主及正式领域链决定。 */
    public interface Ai {
        @UserMessage("{{context}}")
        Result<Draft> generate(@V("context") String context);
    }

    /** 等价执行正式导演适配；缺失或非法协议按原异常拒绝，不将候选当作已接受状态。 */
    public static StoryTurnDecision toDecision(TurnDirectorAi.DecisionDraft draft) {
        if (draft == null || draft.duration() == null) {
            throw new IllegalArgumentException("导演草案缺少时间尺度");
        }
        SemanticDuration.Unit durationUnit = SemanticDuration.Unit.valueOf(
                required(draft.duration().unit(), "时间尺度").name());
        String targetPhase = durationUnit == SemanticDuration.Unit.PHASE
                ? text(draft.duration().targetPhase()) : "";
        SemanticDuration duration = new SemanticDuration(durationUnit,
                Math.max(1, draft.duration().value()), targetPhase);
        StoryTurnDecision.CoreStateIntent core = draft.coreState() == null ? null
                : new StoryTurnDecision.CoreStateIntent(optional(draft.coreState().location()),
                optional(draft.coreState().condition()), optional(draft.coreState().realm()));
        StoryTurnDecision.CultivationIntent cultivation = draft.cultivation() == null ? null
                : new StoryTurnDecision.CultivationIntent(draft.cultivation().progressGained(),
                draft.cultivation().breakthroughAttempted(), draft.cultivation().reason(),
                safe(draft.cultivation().evidenceRefs()));
        List<StoryTurnDecision.EntityIntent> entities = new java.util.ArrayList<>();
        appendItemChanges(entities, draft.itemChanges());
        appendChanges(entities, draft.abilityChanges(), "ABILITY");
        appendChanges(entities, draft.entities(), null);
        List<StoryTurnDecision.WorldFactIntent> worldFacts = safe(draft.worldFacts()).stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> new StoryTurnDecision.WorldFactIntent(value.factId(), value.statement(), value.reason()))
                .toList();
        return new StoryTurnDecision(null, duration,
                StoryPatch.TimeResolution.valueOf(required(draft.timeResolution(), "时间结果").name()),
                draft.interruptionReason(), core, cultivation, entities, worldFacts, List.of(),
                null,
                List.of(), requiredAssetProcess(draft), requiredUnderstanding(draft), requiredOrderedResult(draft));
    }

    /** 模型必须明确提交有序结果与停止原因；不把缺失协议从旧摘要、节拍或人物元数据补造出来。 */
    private static OrderedTurnResult.Proposal requiredOrderedResult(TurnDirectorAi.DecisionDraft draft) {
        var result = draft.orderedResult();
        if (result == null || result.steps() == null || result.steps().isEmpty() || result.steps().size() > 16
                || result.stop() == null) {
            throw new IllegalArgumentException("orderedResult 必须有1至16项有序 steps 和明确 stop，不能用旧摘要代替");
        }
        return result;
    }

    private static com.wenxian.game.story.domain.AssetProcess.Proposal requiredAssetProcess(TurnDirectorAi.DecisionDraft draft) {
        return assets.toDomain(draft.assetProcess());
    }

    /** 物品在模型格式中只有元数据字段，固定类型与空数量由边界适配补齐，不能另写一份余额。 */
    private static void appendItemChanges(List<StoryTurnDecision.EntityIntent> target, List<TurnDirectorAi.ItemDraft> items) {
        if (items == null) throw new IllegalArgumentException("导演必须明确返回 itemChanges，无物品元数据变化用空数组");
        java.util.Set<String> protectedKeys = java.util.Set.of("quantity", "inPossession", "custody", "_trusted",
                "holderType", "holderRef", "balance", "quantityKnown", "quantityUnit");
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (item == null) throw new IllegalArgumentException("itemChanges[" + i + "] 不能包含空项");
            for (var attribute : safe(item.attributes())) {
                if (attribute != null && protectedKeys.contains(text(attribute.key()))) {
                    throw new IllegalArgumentException("itemChanges[" + i + "].attributes." + attribute.key()
                            + " 是受保护的资产字段，不能用普通元数据写入");
                }
            }
            target.add(entity(new TurnDirectorAi.EntityDraft(item.existingEntityId(), item.localRef(), "ITEM", item.name(),
                    item.status(), item.summary(), null, item.attributes(), null, null, null, null, null, item.visible())));
        }
    }

    /** 行动承接只供模型保持任务和审读；不替代原始行动，不作为新增授权或语义正确的证明。 */
    private static StoryTurnDecision.ActionUnderstanding requiredUnderstanding(TurnDirectorAi.DecisionDraft draft) {
        var understanding = draft.actionUnderstanding();
        if (understanding == null || text(understanding.purpose()).isBlank()
                || understanding.purpose().length() > 500
                || understanding.constraints() == null || understanding.constraints().size() > 16) {
            throw new IllegalArgumentException("actionUnderstanding 必须简短表达 purpose 与 constraints，步骤结果只放 orderedResult");
        }
        for (var constraint : understanding.constraints()) {
            if (constraint == null || constraint.isBlank() || constraint.length() > 500) {
                throw new IllegalArgumentException("行动限制必须是非空短句，无限制用空数组");
            }
        }
        return new StoryTurnDecision.ActionUnderstanding(understanding.purpose(), understanding.constraints(), List.of());
    }

    /** 三组变化必须显式返回；分类错误直接拒绝，不能把未填写解释成没有收获。 */
    private static void appendChanges(List<StoryTurnDecision.EntityIntent> target,
                               List<TurnDirectorAi.EntityDraft> changes, String expectedType) {
        if (changes == null) throw new IllegalArgumentException("导演必须明确返回 itemChanges、abilityChanges 和 entities，无变化用空数组");
        for (var value : changes) {
            if (value == null) throw new IllegalArgumentException("实体变化不能包含空项");
            String type = text(value.entityType()).toUpperCase(java.util.Locale.ROOT);
            boolean asset = "ITEM".equals(type) || "ABILITY".equals(type);
            if (expectedType == null ? asset : !expectedType.equals(type)) {
                throw new IllegalArgumentException("物品、功法与其他实体必须填写在各自变化分组");
            }
            var intent = entity(value);
            if ("ABILITY".equals(expectedType) && intent.abilityState() == null) {
                throw new IllegalArgumentException("功法变化必须明确学习状态");
            }
            target.add(intent);
        }
    }

    private static StoryTurnDecision.EntityIntent entity(TurnDirectorAi.EntityDraft value) {
        com.fasterxml.jackson.databind.node.ObjectNode attributes = null;
        if (value.attributes() != null && !value.attributes().isEmpty()) {
            attributes = mapper.createObjectNode();
            for (TurnDirectorAi.AttributeDraft attribute : value.attributes()) {
                if (attribute != null && !text(attribute.key()).isBlank()) {
                    attributes.put(attribute.key().strip(), text(attribute.value()));
                }
            }
            if (attributes.isEmpty()) attributes = null;
        }
        String scenePresence = optionalEntityScenePresence(value.scenePresence());
        if (!scenePresence.isBlank()) {
            if (attributes == null) attributes = mapper.createObjectNode();
            attributes.put("scenePresence", scenePresence);
        }
        StoryPatch.QuantityValue quantity = value.quantity() == null ? null
                : new StoryPatch.QuantityValue(value.quantity().known(), value.quantity().value());
        TurnDirectorAi.CustodyDraft custodyDraft = value.custody();
        StoryPatch.HolderType holderType = custodyDraft == null ? null
                : optionalEnum(StoryPatch.HolderType.class, custodyDraft.holderType());
        StoryTurnDecision.CustodyIntent custody = holderType == null ? null
                : new StoryTurnDecision.CustodyIntent(
                holderType, custodyDraft.holderRef(), custodyDraft.localHolderRef());
        TurnDirectorAi.LifecycleDraft lifecycleDraft = value.lifecycle();
        StoryPatch.LifecycleState lifecycleState = lifecycleDraft == null ? null
                : optionalEnum(StoryPatch.LifecycleState.class, lifecycleDraft.state());
        StoryPatch.LifecycleValue lifecycle = lifecycleState == null ? null
                : new StoryPatch.LifecycleValue(
                lifecycleState, lifecycleDraft.changeReason());
        TurnDirectorAi.RelationshipDraft relationshipDraft = value.relationship();
        StoryPatch.RelationshipValue relationship = relationshipDraft == null ? null
                : new StoryPatch.RelationshipValue(relationshipDraft.label(), relationshipDraft.changeReason());
        StoryPatch.AbilityState ability = optionalEnum(StoryPatch.AbilityState.class, value.abilityState());
        return new StoryTurnDecision.EntityIntent(value.existingEntityId(), value.localRef(), value.entityType(),
                value.name(), value.status(), value.summary(), attributes, quantity, custody, ability, lifecycle,
                relationship, value.visible());
    }

    /** 在场证据是人物地点归一化的类型化协议，不允许供应商返回任意新枚举。 */
    private static String optionalEntityScenePresence(String value) {
        String normalized = text(value).toUpperCase(java.util.Locale.ROOT);
        if (normalized.isBlank() || "PRESENT".equals(normalized) || "MENTIONED".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("不支持的人物在场状态：" + value);
    }

    /** 空串表示模型没有提出该可选事实；非空未知值仍立即拒绝，不能静默改写为另一领域状态。 */
    private static <E extends Enum<E>> E optionalEnum(Class<E> type, String value) {
        String normalized = text(value);
        if (normalized.isBlank()) return null;
        try {
            return Enum.valueOf(type, normalized.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("不支持的" + type.getSimpleName() + "：" + value, error);
        }
    }

    private static <T> T required(T value, String label) {
        if (value == null) throw new IllegalArgumentException(label + "不能为空");
        return value;
    }

    private static String optional(String value) {
        String normalized = text(value);
        return normalized.isBlank() ? null : normalized;
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

}
