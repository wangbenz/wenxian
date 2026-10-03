package com.wenxian.game.story.replay.records;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.agent.ai.DirectorAssetDraftAdapter;
import com.wenxian.game.story.agent.ai.NarrativeWriterAi;
import com.wenxian.game.story.agent.ai.TurnDirectorAi;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryPatch;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 持续记录候选的测试专用协议；完整故事、局部状态命令与可追溯记录分别表达。
 *
 * <p>保留基线 SingleCandidateProtocol 的实体、状态枚举和正式资产适配，移除普通剧情步骤图、
 * 整轮 outcome 及行动复述。这里不调用旧事实编译器，不从中文补实体说明、数量或修行效果。
 * 新实体必须自己声明名称与说明；未提出的变化保持原值，空资产过程仍经过正式 ITEM 保护。</p>
 *
 * <p>解析前只读检查完整 JSON 的独立形状和技术体积。仅标题、推荐选项、辅助摘要允许在副本上
 * 采用列明降级；状态、待决定问题、正文和记录均不截断、不强转、不丢弃。原稿由生成器另行保留。
 * 检查通过不代表中文内容充分、一致或已经提交。</p>
 */
public final class RecordCandidateProtocol {
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;
    public static final int MAX_JSON_DEPTH = 32;
    public static final int MAX_RECORDS_BYTES = 32 * 1024;
    public static final int MAX_RECORD_BYTES = 16 * 1024;
    private static final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_JSON_DEPTH).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final DirectorAssetDraftAdapter assets = new DirectorAssetDraftAdapter();
    private static final Set<String> PROTECTED_ASSETS = Set.of("quantity", "inPossession", "custody", "_trusted",
            "holderType", "holderRef", "balance", "quantityKnown", "quantityUnit");

    private RecordCandidateProtocol() {}

    public record Draft(Story story, Changes changes, List<StoryRecords.Entry> records) {}
    public record Story(String title, String narrative, String decision,
                        List<NarrativeWriterAi.ChoiceDraft> choices, String summary) {}
    /** 缺省变化列表仅表示无该类命令；整个 changes、时间及 records 不能省略。 */
    public record Changes(TurnDirectorAi.DurationDraft duration, TurnDirectorAi.TimeResolution timeResolution,
                          String interruptionReason, TurnDirectorAi.CoreStateDraft coreState, Cultivation cultivation,
                          List<TurnDirectorAi.ItemDraft> itemChanges, List<TurnDirectorAi.EntityDraft> abilityChanges,
                          List<TurnDirectorAi.EntityDraft> entities, List<TurnDirectorAi.WorldFactDraft> worldFacts,
                          TurnDirectorAi.AssetProcessDraft assetProcess) {}
    /** actionOutcome 只表示这次修行尝试，不表示整篇小说的结果；目标境界仍由原正式规则计算。 */
    public record Cultivation(boolean progressGained, boolean breakthroughAttempted, String reason,
                              List<String> evidenceRefs, StoryPatch.OutcomeResolution actionOutcome) {}
    public record Adapted(StoryTurnDecision decision, StoryPatch.OutcomeResolution actionOutcome) {}
    public record Downgrade(String field, String reason) {}
    /** normalizedJson 仅可能改列明辅助字段；problems 非空时禁止使用该副本继续接受状态。 */
    public record Inspection(String normalizedJson, List<String> problems, List<Downgrade> downgrades) {
        public Inspection { problems = List.copyOf(problems); downgrades = List.copyOf(downgrades); }
    }
    public interface Ai {
        @UserMessage("{{context}}")
        Result<Draft> generate(@V("context") String context);
    }

    /** 不借旧步骤或全局成功枚举完成适配；返回值仍须独立编译、归并和事务保存。 */
    public static Adapted adapt(Draft draft) {
        if (draft == null || draft.story() == null || draft.changes() == null || draft.records() == null)
            throw new IllegalArgumentException("story、changes、records 三个完整分区必须存在，空记录用 []");
        Story story = draft.story();
        if (text(story.narrative()).isBlank()) throw new IllegalArgumentException("story.narrative 必须是完整非空正文");
        if (story.decision() != null && story.decision().isBlank())
            throw new IllegalArgumentException("story.decision 无待决定问题用 null，不用空问题");
        safe(draft.records());
        Changes changes = draft.changes();
        if (changes.duration() == null || changes.duration().unit() == null || changes.timeResolution() == null)
            throw new IllegalArgumentException("changes.duration 和 timeResolution 必须明确给出");
        var duration = changes.duration();
        SemanticDuration time = new SemanticDuration(SemanticDuration.Unit.valueOf(duration.unit().name()), duration.value(),
                duration.unit() == TurnDirectorAi.DurationUnit.PHASE ? text(duration.targetPhase()) : "");
        StoryTurnDecision.CoreStateIntent core = changes.coreState() == null ? null
                : new StoryTurnDecision.CoreStateIntent(optional(changes.coreState().location()),
                optional(changes.coreState().condition()), optional(changes.coreState().realm()));
        Cultivation requested = changes.cultivation();
        StoryTurnDecision.CultivationIntent cultivation = requested == null ? null
                : new StoryTurnDecision.CultivationIntent(requested.progressGained(), requested.breakthroughAttempted(),
                requested.reason(), safe(requested.evidenceRefs()));
        if (requested != null && (requested.progressGained() || requested.breakthroughAttempted())
                && requested.actionOutcome() == null)
            throw new IllegalArgumentException("changes.cultivation.actionOutcome 必须明确给出局部修行结果");
        List<StoryTurnDecision.EntityIntent> entities = new ArrayList<>();
        appendItemChanges(entities, changes.itemChanges());
        appendChanges(entities, changes.abilityChanges(), "ABILITY");
        appendChanges(entities, changes.entities(), null);
        List<StoryTurnDecision.WorldFactIntent> worldFacts = safe(changes.worldFacts()).stream()
                .map(value -> new StoryTurnDecision.WorldFactIntent(value.factId(), value.statement(), value.reason())).toList();
        var process = assets.toDomain(changes.assetProcess() == null
                ? new TurnDirectorAi.AssetProcessDraft(List.of()) : changes.assetProcess());
        StoryTurnDecision decision = new StoryTurnDecision(null, time,
                StoryPatch.TimeResolution.valueOf(changes.timeResolution().name()), changes.interruptionReason(),
                core, cultivation, entities, worldFacts, List.of(), story.decision() == null ? null
                : new StoryPatch.DecisionCheckpoint(true, story.decision()), List.of(), process, null, null);
        return new Adapted(decision, requested == null ? null : requested.actionOutcome());
    }

    /** 在类型化解码前合并彼此独立的形状错误；坏状态字段只报告，不删除或转换成无变化。 */
    public static Inspection inspect(String raw) {
        List<String> problems = new ArrayList<>();
        List<Downgrade> downgrades = new ArrayList<>();
        if (raw == null || raw.isBlank()) return new Inspection(null, List.of("完整 JSON 响应不能为空"), List.of());
        if (bytes(raw) > MAX_RESPONSE_BYTES)
            return new Inspection(null, List.of("完整响应超过 256 KiB 技术上限，不截断候选"), List.of());
        JsonNode tree;
        try { tree = mapper.readTree(raw); }
        catch (IOException invalid) {
            return new Inspection(null, List.of("响应必须为单个完整 JSON，不能重复键、夹带文本或超过 32 层深度；原稿保留"), List.of());
        }
        if (!(tree instanceof ObjectNode root))
            return new Inspection(null, List.of("响应根必须是包含 story、changes、records 的对象"), List.of());
        for (String field : List.of("story", "changes", "records"))
            if (!root.hasNonNull(field)) problems.add(field + " 分区必须存在");
        ObjectNode normalized = root.deepCopy();
        if (normalized.path("story") instanceof ObjectNode story) {
            normalizeAuxiliary(story, downgrades);
            if (!story.has("decision")) problems.add("story.decision 必须明确存在；无待决定问题写 null");
            if (!story.path("narrative").isTextual() || story.path("narrative").asText().isBlank())
                problems.add("story.narrative 必须为非空完整正文，不能降级");
            if (story.hasNonNull("decision") && (!story.path("decision").isTextual() || story.path("decision").asText().isBlank()))
                problems.add("story.decision 必须为非空问题或 null，不能降级");
        }
        shape(normalized, mapper.constructType(Draft.class), "$", problems);
        JsonNode changes = root.path("changes");
        if (changes.isObject()) {
            if (!changes.path("duration").isObject()) problems.add("changes.duration 必须明确给出对象");
            else {
                JsonNode duration = changes.path("duration");
                if (!duration.hasNonNull("unit")) problems.add("changes.duration.unit 必须存在");
                if (!duration.path("value").isIntegralNumber() || !duration.path("value").canConvertToInt()
                        || duration.path("value").asInt() < 1) problems.add("changes.duration.value 必须为正整数");
            }
            if (!changes.hasNonNull("timeResolution")) problems.add("changes.timeResolution 必须存在");
            inspectEntityRequirements(changes, problems);
            if (changes.hasNonNull("assetProcess")
                    && problems.stream().noneMatch(problem -> problem.startsWith("$.changes.assetProcess"))) {
                try {
                    assets.validateShape(mapper.treeToValue(changes.get("assetProcess"), TurnDirectorAi.AssetProcessDraft.class));
                } catch (IOException invalid) {
                    problems.add("changes.assetProcess 含不可安全解码的资产字段");
                } catch (IllegalArgumentException invalid) {
                    problems.add("changes.assetProcess：" + invalid.getMessage());
                }
            }
            JsonNode cultivation = changes.path("cultivation");
            if (cultivation.isObject() && (cultivation.path("progressGained").asBoolean(false)
                    || cultivation.path("breakthroughAttempted").asBoolean(false)) && !cultivation.hasNonNull("actionOutcome"))
                problems.add("changes.cultivation.actionOutcome：有效修行意图必须有局部结果");
        }
        JsonNode records = root.path("records");
        if (records.isArray()) {
            if (bytes(records.toString()) > MAX_RECORDS_BYTES) problems.add("records 总体超过 32 KiB，必须完整修复，不能丢弃后项");
            for (int index = 0; index < records.size(); index++) {
                JsonNode entry = records.get(index);
                if (bytes(entry.toString()) > MAX_RECORD_BYTES) problems.add("records[" + index + "] 超过 16 KiB，不能截断原文");
                if (entry.isObject()) {
                    if (!entry.path("about").isArray()) problems.add("records[" + index + "].about 必须明确为关联引用数组，无关联用 []");
                    if (!Set.of("EVENT", "STATEMENT").contains(entry.path("kind").asText()))
                        problems.add("records[" + index + "].kind 必须为 EVENT 或 STATEMENT");
                    if (!entry.path("text").isTextual() || entry.path("text").asText().isBlank())
                        problems.add("records[" + index + "].text 必须是非空记录原文");
                }
            }
        }
        return new Inspection(normalized.toString(), problems.stream().distinct().toList(), downgrades);
    }

    /** 只有这三项辅助内容可以独立降级，未知顶层命令或状态字段不在此范围内。 */
    private static void normalizeAuxiliary(ObjectNode story, List<Downgrade> downgraded) {
        JsonNode title = story.path("title");
        if (!title.isTextual() || title.asText().isBlank()) {
            story.put("title", "此间经历"); downgraded.add(new Downgrade("story.title", "缺失或格式无效，使用中性标题"));
        }
        JsonNode choices = story.path("choices");
        boolean valid = choices.isArray();
        if (valid) for (JsonNode choice : choices) {
            if (!choice.isObject() || !choice.path("title").isTextual() || choice.path("title").asText().isBlank()
                    || !choice.path("description").isTextual() || choice.path("description").asText().isBlank()) { valid = false; break; }
            var fields = choice.fieldNames();
            while (fields.hasNext()) if (!Set.of("title", "description").contains(fields.next())) valid = false;
        }
        if (!valid) {
            story.putArray("choices"); downgraded.add(new Downgrade("story.choices", "缺失或格式无效，省略推荐选项，保留自由行动"));
        }
        if (!story.path("summary").isTextual()) {
            story.put("summary", ""); downgraded.add(new Downgrade("story.summary", "缺失或格式无效，省略辅助摘要"));
        }
    }

    /** 用冻结 record 类型逐字段只读检查，尽量同时报告不同分区的问题，不模拟领域接受。 */
    private static void shape(JsonNode value, JavaType type, String path, List<String> problems) {
        if (value == null || value.isNull()) {
            if (type.isPrimitive()) problems.add(path + " 不能为 null");
            return;
        }
        Class<?> raw = type.getRawClass();
        if (type.isCollectionLikeType()) {
            if (!value.isArray()) { problems.add(path + " 必须为数组"); return; }
            for (int i = 0; i < value.size(); i++) {
                if (value.get(i).isNull()) problems.add(path + "[" + i + "] 不能为空项");
                else shape(value.get(i), type.getContentType(), path + "[" + i + "]", problems);
            }
        } else if (raw.isRecord()) {
            if (!value.isObject()) { problems.add(path + " 必须为对象"); return; }
            Map<String, JavaType> fields = new LinkedHashMap<>();
            for (RecordComponent field : raw.getRecordComponents()) fields.put(field.getName(), mapper.constructType(field.getGenericType()));
            var names = value.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!fields.containsKey(name)) problems.add(path + "." + name + " 是未知字段，不能作为旧结果或新写命令接受");
                else shape(value.get(name), fields.get(name), path + "." + name, problems);
            }
        } else if (raw == String.class) {
            if (!value.isTextual()) problems.add(path + " 必须为字符串，不强转状态内容");
        } else if (raw == boolean.class || raw == Boolean.class) {
            if (!value.isBoolean()) problems.add(path + " 必须为布尔值");
        } else if (raw == int.class || raw == Integer.class) {
            if (!value.isIntegralNumber() || !value.canConvertToInt()) problems.add(path + " 必须为范围内整数");
        } else if (raw.isEnum()) {
            if (!value.isTextual() || Arrays.stream(raw.getEnumConstants()).noneMatch(item -> ((Enum<?>) item).name().equals(value.asText())))
                problems.add(path + " 必须使用该字段的协议枚举");
        } else throw new IllegalStateException("未覆盖的实验协议字段类型：" + raw.getName());
    }

    private static void inspectEntityRequirements(JsonNode changes, List<String> problems) {
        for (String group : List.of("itemChanges", "abilityChanges", "entities")) {
            JsonNode values = changes.path(group);
            if (!values.isArray()) continue;
            for (int i = 0; i < values.size(); i++) {
                JsonNode value = values.get(i);
                if (!value.isObject()) continue;
                String path = "changes." + group + "[" + i + "]";
                if (value.path("existingEntityId").asText("").isBlank()) {
                    if (!value.path("name").isTextual() || value.path("name").asText().isBlank()) problems.add(path + ".name：新实体必须有名称");
                    if (!value.path("summary").isTextual() || value.path("summary").asText().isBlank()) problems.add(path + ".summary：新实体必须有自身说明，不从整篇故事补造");
                }
                if ("itemChanges".equals(group) && value.path("attributes").isArray())
                    for (JsonNode attribute : value.path("attributes"))
                        if (PROTECTED_ASSETS.contains(attribute.path("key").asText(""))) problems.add(path + ".attributes：包含受保护资产字段");
            }
        }
    }

    private static void appendItemChanges(List<StoryTurnDecision.EntityIntent> target, List<TurnDirectorAi.ItemDraft> items) {
        for (var item : safe(items)) {
            requireNewSummary(item.existingEntityId(), item.name(), item.summary());
            for (var attribute : safe(item.attributes())) if (PROTECTED_ASSETS.contains(text(attribute.key())))
                throw new IllegalArgumentException("itemChanges.attributes 是受保护资产字段，不能另写余额、单位或保管关系");
            target.add(entity(new TurnDirectorAi.EntityDraft(item.existingEntityId(), item.localRef(), "ITEM", item.name(),
                    item.status(), item.summary(), null, item.attributes(), null, null, null, null, null, item.visible())));
        }
    }

    private static void appendChanges(List<StoryTurnDecision.EntityIntent> target, List<TurnDirectorAi.EntityDraft> changes, String expectedType) {
        for (var value : safe(changes)) {
            String type = text(value.entityType()).toUpperCase(Locale.ROOT);
            boolean asset = "ITEM".equals(type) || "ABILITY".equals(type);
            if (expectedType == null ? asset : !expectedType.equals(type))
                throw new IllegalArgumentException("物品、能力与其他实体必须位于各自变化分组");
            requireNewSummary(value.existingEntityId(), value.name(), value.summary());
            var intent = entity(value);
            if ("ABILITY".equals(expectedType) && intent.abilityState() == null)
                throw new IllegalArgumentException("abilityChanges 必须明确学习状态");
            target.add(intent);
        }
    }

    private static void requireNewSummary(String existingId, String name, String summary) {
        if (text(existingId).isBlank() && (text(name).isBlank() || text(summary).isBlank()))
            throw new IllegalArgumentException("新实体必须有 name 与 summary，不能用整轮结果补齐");
    }

    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static StoryTurnDecision.EntityIntent entity(TurnDirectorAi.EntityDraft value) {
        com.fasterxml.jackson.databind.node.ObjectNode attributes = null;
        if (value.attributes() != null && !value.attributes().isEmpty()) {
            attributes = mapper.createObjectNode();
            for (TurnDirectorAi.AttributeDraft attribute : value.attributes()) {
                if (attribute == null || text(attribute.key()).isBlank()) {
                    throw new IllegalArgumentException("attributes 不能含空项或空 key");
                } else {
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
        if (values == null) return List.of();
        if (values.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("数组不能包含空项");
        return List.copyOf(values);
    }

}
