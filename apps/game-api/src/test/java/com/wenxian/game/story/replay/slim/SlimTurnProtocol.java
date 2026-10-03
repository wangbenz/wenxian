package com.wenxian.game.story.replay.slim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.agent.ai.DirectorAssetDraftAdapter;
import com.wenxian.game.story.agent.ai.TurnDirectorAi;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryPatch;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 仅供隔离回放使用的精简导演协议，不注册为正式模型服务。
 *
 * <p>把每步的资产过程展平为现有内部合同，确定性生成关联索引，不推断中文意图或补造理解摘要。
 * 身份、余额、保管、单位、境界及依赖仍由生产领域编译器基于冻结前态校验；本类转换成功不代表事实已接受。
 * 反向转换仅用于把本原型产生的领域候选交回同一种模型格式，不迁移旧历史、不替代持久化协议。</p>
 */
public final class SlimTurnProtocol {
    private static final DirectorAssetDraftAdapter ASSETS = new DirectorAssetDraftAdapter();
    private static final Set<String> RESERVED_ITEM_ATTRIBUTES = Set.of("quantity", "inPossession", "custody",
            "_trusted", "holderType", "holderRef", "balance", "quantityKnown", "quantityUnit");
    private static final Pattern ASSET_PATH = Pattern.compile("assetProcess\\.steps\\[(\\d+)\\]");

    private SlimTurnProtocol() {}

    public interface DirectorAi {
        @UserMessage("{{context}}")
        Result<Draft> direct(@V("context") String context);
    }

    public record Draft(TurnDirectorAi.DurationDraft duration, TurnDirectorAi.TimeResolution timeResolution,
                        String interruptionReason, TurnDirectorAi.CoreStateDraft coreState,
                        TurnDirectorAi.CultivationDraft cultivation, List<TurnDirectorAi.ItemDraft> itemChanges,
                        List<AbilityDraft> abilityChanges, List<TurnDirectorAi.EntityDraft> entities,
                        List<TurnDirectorAi.WorldFactDraft> worldFacts, ResultDraft orderedResult) {}

    public record ResultDraft(List<Step> steps, OrderedTurnResult.Stop stop) {}

    public record Step(String action, OrderedTurnResult.Resolution resolution, String result,
                       Boolean realmProgressBinding, List<OrderedTurnResult.Dependency> dependsOn,
                       List<OrderedTurnResult.Statement> statements, List<TurnDirectorAi.AssetStepDraft> assets) {}

    public record AbilityDraft(String existingEntityId, String localRef, String name, String status,
                               String summary, List<TurnDirectorAi.AttributeDraft> attributes,
                               String abilityState, Boolean visible) {}

    /** 只改变模型表达位置；所有事实提案仍走生产 StoryFactIntentCompiler 和 Reducer。 */
    public static StoryTurnDecision toDecision(Draft draft) {
        require(draft != null && draft.duration() != null && draft.duration().unit() != null,
                "duration", "必须明确时间尺度");
        require(draft.timeResolution() != null, "timeResolution", "必须明确时间结果");
        ResultDraft result = draft.orderedResult();
        require(result != null && result.steps() != null && !result.steps().isEmpty()
                        && result.steps().size() <= OrderedTurnResult.MAX_STEPS && result.stop() != null,
                "orderedResult", "必须有1至16项有序步骤和停止原因");
        List<TurnDirectorAi.AssetStepDraft> assets = new ArrayList<>();
        List<OrderedTurnResult.Step> steps = new ArrayList<>();
        for (int i = 0; i < result.steps().size(); i++) {
            Step step = result.steps().get(i);
            String path = "orderedResult.steps[" + i + "]";
            require(step != null, path, "步骤不能包含空项");
            explicitList(step.assets(), path + ".assets");
            List<Integer> indexes = new ArrayList<>();
            for (TurnDirectorAi.AssetStepDraft asset : step.assets()) {
                require(assets.size() < 64, path + ".assets", "单轮资产步骤不能超过64项");
                indexes.add(assets.size());
                assets.add(asset);
            }
            steps.add(new OrderedTurnResult.Step(step.action(), step.resolution(), step.result(),
                    step.realmProgressBinding(), indexes, step.dependsOn(), step.statements()));
        }
        List<StoryTurnDecision.EntityIntent> entities = new ArrayList<>();
        for (var item : explicitList(draft.itemChanges(), "itemChanges")) {
            for (var attribute : optionalList(item.attributes(), "itemChanges.attributes")) {
                require(!RESERVED_ITEM_ATTRIBUTES.contains(text(attribute.key())),
                        "itemChanges.attributes." + text(attribute.key()), "不能用物品元数据写受保护资产字段");
            }
            entities.add(entity(new TurnDirectorAi.EntityDraft(item.existingEntityId(), item.localRef(), "ITEM",
                    item.name(), item.status(), item.summary(), null, item.attributes(), null, null,
                    null, null, null, item.visible())));
        }
        for (var ability : explicitList(draft.abilityChanges(), "abilityChanges")) {
            StoryPatch.AbilityState state = optionalEnum(StoryPatch.AbilityState.class, ability.abilityState());
            require(state != null, "abilityChanges.abilityState", "功法变化必须明确学习状态");
            entities.add(entity(new TurnDirectorAi.EntityDraft(ability.existingEntityId(), ability.localRef(), "ABILITY",
                    ability.name(), ability.status(), ability.summary(), null, ability.attributes(), null, null,
                    state.name(), null, null, ability.visible())));
        }
        for (var other : explicitList(draft.entities(), "entities")) {
            String type = text(other.entityType()).toUpperCase(Locale.ROOT);
            require(!"ITEM".equals(type) && !"ABILITY".equals(type), "entities", "物品与功法必须填写各自分组");
            entities.add(entity(other));
        }
        var unit = SemanticDuration.Unit.valueOf(draft.duration().unit().name());
        var core = draft.coreState();
        var cultivation = draft.cultivation();
        return new StoryTurnDecision(null,
                new SemanticDuration(unit, Math.max(1, draft.duration().value()),
                        unit == SemanticDuration.Unit.PHASE ? text(draft.duration().targetPhase()) : ""),
                StoryPatch.TimeResolution.valueOf(draft.timeResolution().name()), draft.interruptionReason(),
                core == null ? null : new StoryTurnDecision.CoreStateIntent(optional(core.location()),
                        optional(core.condition()), optional(core.realm())),
                cultivation == null ? null : new StoryTurnDecision.CultivationIntent(cultivation.progressGained(),
                        cultivation.breakthroughAttempted(), cultivation.reason(),
                        optionalList(cultivation.evidenceRefs(), "cultivation.evidenceRefs")), entities,
                optionalList(draft.worldFacts(), "worldFacts").stream().map(fact ->
                        new StoryTurnDecision.WorldFactIntent(fact.factId(), fact.statement(), fact.reason())).toList(),
                List.of(), null, List.of(), ASSETS.toDomain(new TurnDirectorAi.AssetProcessDraft(assets)), null,
                new OrderedTurnResult.Proposal(steps, result.stop()));
    }

    /** 编译拒绝仍恢复为同一瘦草案；不把内部索引、版本或行动理解交给模型复抄。 */
    public static Draft fromDecision(StoryTurnDecision decision) {
        require(decision != null && decision.orderedResult() != null && decision.assetProcess() != null,
                "rejectedDecision", "精简协议修复需要本原型产生的完整候选");
        var process = ASSETS.fromDomain(decision.assetProcess());
        require(process.steps() != null, "rejectedDecision.assetProcess", "资产过程不能缺失");
        List<Step> steps = new ArrayList<>();
        int next = 0;
        for (var step : explicitList(decision.orderedResult().steps(), "rejectedDecision.orderedResult.steps")) {
            List<TurnDirectorAi.AssetStepDraft> owned = new ArrayList<>();
            for (var index : explicitList(step.assetStepIndexes(), "rejectedDecision.assetStepIndexes")) {
                require(index == next && index < process.steps().size(), "rejectedDecision.assetStepIndexes",
                        "不能猜测缺失、重复或乱序的资产归属");
                owned.add(process.steps().get(next++));
            }
            steps.add(new Step(step.action(), step.resolution(), step.result(), step.realmProgressBinding(),
                    step.dependsOn(), step.statements(), owned));
        }
        require(next == process.steps().size(), "rejectedDecision.assetProcess", "不能丢弃未关联的资产过程");
        List<TurnDirectorAi.ItemDraft> items = new ArrayList<>();
        List<AbilityDraft> abilities = new ArrayList<>();
        List<TurnDirectorAi.EntityDraft> others = new ArrayList<>();
        for (var entity : decision.entities()) {
            if ("ITEM".equalsIgnoreCase(entity.entityType())) {
                items.add(new TurnDirectorAi.ItemDraft(entity.existingEntityId(), entity.localRef(), entity.name(),
                        entity.status(), entity.summary(), modelAttributes(entity.attributes(), false), entity.visible()));
            } else if ("ABILITY".equalsIgnoreCase(entity.entityType())) {
                abilities.add(new AbilityDraft(entity.existingEntityId(), entity.localRef(), entity.name(),
                        entity.status(), entity.summary(), modelAttributes(entity.attributes(), false),
                        entity.abilityState() == null ? null : entity.abilityState().name(), entity.visible()));
            } else others.add(modelEntity(entity));
        }
        var duration = decision.duration();
        var core = decision.coreState();
        var cultivation = decision.cultivation();
        return new Draft(duration == null ? null : new TurnDirectorAi.DurationDraft(
                TurnDirectorAi.DurationUnit.valueOf(duration.unit().name()), duration.value(), duration.targetPhase()),
                decision.timeResolution() == null ? null : TurnDirectorAi.TimeResolution.valueOf(decision.timeResolution().name()),
                decision.interruptionReason(), core == null ? null : new TurnDirectorAi.CoreStateDraft(core.location(), core.condition(), core.realm()),
                cultivation == null ? null : new TurnDirectorAi.CultivationDraft(cultivation.progressGained(),
                        cultivation.breakthroughAttempted(), cultivation.reason(), cultivation.evidenceRefs()),
                items, abilities, others, decision.worldFacts().stream().map(fact ->
                        new TurnDirectorAi.WorldFactDraft(fact.factId(), fact.statement(), fact.reason())).toList(),
                new ResultDraft(steps, decision.orderedResult().stop()));
    }

    /** 只翻译已经确定的字段路径；保留原校验原因，不把内部错误改写成中文语义判断。 */
    public static String repairProblem(StoryTurnDecision decision, String problem) {
        Map<String, String> paths = new HashMap<>();
        if (decision != null && decision.orderedResult() != null && decision.orderedResult().steps() != null) {
            for (int i = 0; i < decision.orderedResult().steps().size(); i++) {
                var step = decision.orderedResult().steps().get(i);
                if (step == null || step.assetStepIndexes() == null) continue;
                for (int j = 0; j < step.assetStepIndexes().size(); j++) {
                    var index = step.assetStepIndexes().get(j);
                    if (index != null) paths.put(index.toString(), "orderedResult.steps[" + i + "].assets[" + j + "]");
                }
            }
        }
        return replacePaths(problem, paths);
    }

    /** 适配尚未成功时也可仅按原草案位置翻译路径；不再尝试解析有问题的金额或其他内容。 */
    public static String repairProblem(Draft draft, String problem) {
        Map<String, String> paths = new HashMap<>();
        if (draft != null && draft.orderedResult() != null && draft.orderedResult().steps() != null) {
            int index = 0;
            for (int i = 0; i < draft.orderedResult().steps().size(); i++) {
                var step = draft.orderedResult().steps().get(i);
                if (step == null || step.assets() == null) break;
                for (int j = 0; j < step.assets().size(); j++) {
                    paths.put(Integer.toString(index++), "orderedResult.steps[" + i + "].assets[" + j + "]");
                }
            }
        }
        return replacePaths(problem, paths);
    }

    private static String replacePaths(String problem, Map<String, String> paths) {
        if (problem == null) return null;
        return ASSET_PATH.matcher(problem).replaceAll(match -> Matcher.quoteReplacement(
                paths.getOrDefault(match.group(1), match.group())));
    }

    private static StoryTurnDecision.EntityIntent entity(TurnDirectorAi.EntityDraft draft) {
        ObjectNode attributes = null;
        for (var attribute : optionalList(draft.attributes(), "attributes")) {
            require(!text(attribute.key()).isBlank(), "attributes.key", "属性名不能为空");
            if (attributes == null) attributes = JsonNodeFactory.instance.objectNode();
            attributes.put(text(attribute.key()), text(attribute.value()));
        }
        String presence = text(draft.scenePresence()).toUpperCase(Locale.ROOT);
        require(presence.isEmpty() || "PRESENT".equals(presence) || "MENTIONED".equals(presence),
                "scenePresence", "不支持的人物在场状态");
        if (!presence.isEmpty()) {
            if (attributes == null) attributes = JsonNodeFactory.instance.objectNode();
            attributes.put("scenePresence", presence);
        }
        var custody = draft.custody();
        var holder = custody == null ? null : optionalEnum(StoryPatch.HolderType.class, custody.holderType());
        var lifecycle = draft.lifecycle();
        var state = lifecycle == null ? null : optionalEnum(StoryPatch.LifecycleState.class, lifecycle.state());
        return new StoryTurnDecision.EntityIntent(draft.existingEntityId(), draft.localRef(), draft.entityType(), draft.name(),
                draft.status(), draft.summary(), attributes, draft.quantity() == null ? null
                : new StoryPatch.QuantityValue(draft.quantity().known(), draft.quantity().value()),
                holder == null ? null : new StoryTurnDecision.CustodyIntent(holder, custody.holderRef(), custody.localHolderRef()),
                optionalEnum(StoryPatch.AbilityState.class, draft.abilityState()), state == null ? null
                : new StoryPatch.LifecycleValue(state, lifecycle.changeReason()), draft.relationship() == null ? null
                : new StoryPatch.RelationshipValue(draft.relationship().label(), draft.relationship().changeReason()), draft.visible());
    }

    private static TurnDirectorAi.EntityDraft modelEntity(StoryTurnDecision.EntityIntent entity) {
        var custody = entity.custody();
        return new TurnDirectorAi.EntityDraft(entity.existingEntityId(), entity.localRef(), entity.entityType(), entity.name(),
                entity.status(), entity.summary(), entity.attributes() == null ? null : entity.attributes().path("scenePresence").asText(null),
                modelAttributes(entity.attributes(), true), entity.quantity() == null ? null
                : new TurnDirectorAi.QuantityDraft(entity.quantity().known(), entity.quantity().value()), custody == null ? null
                : new TurnDirectorAi.CustodyDraft(custody.holderType() == null ? null : custody.holderType().name(),
                        custody.holderRef(), custody.localHolderRef()), entity.abilityState() == null ? null : entity.abilityState().name(),
                entity.lifecycle() == null ? null : new TurnDirectorAi.LifecycleDraft(entity.lifecycle().state() == null
                        ? null : entity.lifecycle().state().name(), entity.lifecycle().changeReason()),
                entity.relationship() == null ? null : new TurnDirectorAi.RelationshipDraft(entity.relationship().label(),
                        entity.relationship().changeReason()), entity.visible());
    }

    private static List<TurnDirectorAi.AttributeDraft> modelAttributes(JsonNode attributes, boolean omitPresence) {
        if (attributes == null) return List.of();
        require(attributes.isObject(), "attributes", "不能把非对象属性静默丢弃");
        List<TurnDirectorAi.AttributeDraft> values = new ArrayList<>();
        attributes.fields().forEachRemaining(entry -> {
            if (!omitPresence || !"scenePresence".equals(entry.getKey())) {
                values.add(new TurnDirectorAi.AttributeDraft(entry.getKey(), entry.getValue().isTextual()
                        ? entry.getValue().asText() : entry.getValue().toString()));
            }
        });
        return values;
    }

    private static <E extends Enum<E>> E optionalEnum(Class<E> type, String raw) {
        String value = text(raw);
        return value.isEmpty() ? null : Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }

    private static <T> List<T> explicitList(List<T> list, String path) {
        require(list != null, path, "列表必须显式提供，无变化用空数组");
        for (int i = 0; i < list.size(); i++) require(list.get(i) != null, path + "[" + i + "]", "不能包含空项");
        return list;
    }

    private static <T> List<T> optionalList(List<T> list, String path) {
        return list == null ? List.of() : explicitList(list, path);
    }

    private static String text(String value) { return value == null ? "" : value.strip(); }
    private static String optional(String value) { return text(value).isBlank() ? null : text(value); }
    private static void require(boolean valid, String path, String message) {
        if (!valid) throw new IllegalArgumentException(path + ": " + message);
    }
}
