package com.wenxian.game.story.replay.records;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryReferenceAlias;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewTurn;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredTurn;
import com.wenxian.game.story.persistence.StoredTurnResultReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 隔离原型的持续故事记录：校验候选引用、派生阅读记忆并保存不可变来源。
 *
 * <p>本类不把声明解释为世界事实或状态命令，不证明中文经历与正文一致。候选形状错误可以进入
 * 宿主已有的一次修复；服务端引用映射缺失和损坏历史直接失败，不调用模型修复历史。</p>
 */
public final class StoryRecords {
    public static final int VERSION = 1;
    public static final int MAX_ENTRY_BYTES = 16 * 1024;
    public static final int MAX_TOTAL_BYTES = 32 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StoryRecords() {}

    /** 模型只提供经历或有来源的说法；不提供正式记录 ID、来源回合或权威时间。 */
    public record Entry(String kind, String text, String sourceRef, String sourceLabel, List<String> about) {
        public Entry { about = copyNullable(about); }
    }

    /** 已校验、尚待正式实体 ID 解析的候选；记忆仅为同一内容的阅读投影。 */
    public static final class Prepared {
        private final List<Entry> entries;
        private final Set<String> localRefs;

        private Prepared(List<Entry> entries, Set<String> localRefs) {
            this.entries = List.copyOf(entries);
            this.localRefs = Set.copyOf(localRefs);
        }

        public List<Entry> entries() { return entries; }

        public List<StoryPatch.MemoryDraft> memories() {
            return entries.stream().map(entry -> new StoryPatch.MemoryDraft(
                    "EVENT".equals(entry.kind()) ? "EVENT" : "CLUE",
                    "EVENT".equals(entry.kind()) ? "经历记录" : "来源记录：" + entry.sourceLabel(),
                    "EVENT".equals(entry.kind()) ? entry.text() : entry.sourceLabel() + "当时表示：" + entry.text(),
                    "NORMAL", List.of())).toList();
        }
    }

    /** 引用已解析的内容；只有 stamp 能为实际待写回合赋予记录身份。 */
    public record Value(String kind, String text, String sourceRef, String sourceLabel, List<String> about) {
        public Value { about = copyNullable(about); }
    }

    /** 回合来源覆盖整段经历，不表示每条声明都发生在本轮末尾。 */
    public record Envelope(int protocolVersion, String turnId, int turnNumber,
                           long worldTimeBefore, long worldTimeAfter, List<StampedValue> entries) {
        public Envelope { entries = copyNullable(entries); }
    }

    /** 正式记录 ID 由回合 ID 和记录位置派生，内容仍保留原文。 */
    public record StampedValue(String recordId, String kind, String text, String sourceRef,
                               String sourceLabel, List<String> about) {
        public StampedValue { about = copyNullable(about); }
    }

    /** 不同历史合同分别保留；旧正文不被反向补造成新记录。 */
    public record History(Envelope storyRecords, OrderedTurnResult.Accepted acceptedResult) {}

    /** 明确的候选协议错误，不包装数据库、网络和程序异常。 */
    public static final class ProtocolException extends IllegalArgumentException {
        public ProtocolException(String path, String code, String detail) {
            super(path + " [" + code + "] " + detail);
        }
    }

    public static Prepared prepare(StoryProjection before, List<StoryTurnDecision.EntityIntent> entities,
                                   List<Entry> entries) {
        if (before == null || entities == null) throw new IllegalArgumentException("持续记录缺少冻结前态或实体提案");
        if (entries == null) throw error("records", "REQUIRED", "records 分区必须存在，允许空数组");
        checkBytes(entries);
        Map<String, StoryTurnDecision.EntityIntent> newEntities = new HashMap<>();
        for (StoryTurnDecision.EntityIntent entity : entities) {
            if (entity == null || present(entity.existingEntityId()) || !present(entity.localRef())) continue;
            String ref = entity.localRef();
            if ("PLAYER".equals(ref) || before.entities().containsKey(ref) || StoryReferenceAlias.isAlias(ref)
                    || newEntities.putIfAbsent(ref, entity) != null) {
                throw error("records", "AMBIGUOUS_REF", "本轮实体引用重复或与已有身份冲突：" + ref);
            }
        }
        List<Entry> accepted = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            String path = "records[" + i + "]";
            validateEntry(entry, path);
            for (String ref : entry.about()) requireReference(before, newEntities, ref, false, path + ".about");
            String label = entry.sourceLabel();
            if ("STATEMENT".equals(entry.kind()) && present(entry.sourceRef())) {
                label = requireReference(before, newEntities, entry.sourceRef(), true, path + ".sourceRef");
            }
            accepted.add(new Entry(entry.kind(), entry.text(), entry.sourceRef(), label, entry.about()));
        }
        checkBytes(accepted);
        return new Prepared(accepted, newEntities.keySet());
    }

    /** 正式归并器分配实体 ID 后解析所有本轮引用；缺映射是服务端接线故障。 */
    public static List<Value> resolve(Prepared prepared, Map<String, String> requestedAliases,
                                      List<StoryPatch.EntityPatch> acceptedEntities) {
        if (prepared == null || requestedAliases == null || acceptedEntities == null) {
            throw new IllegalStateException("持续记录缺少正式引用解析输入");
        }
        Map<String, String> ids = new HashMap<>();
        for (StoryPatch.EntityPatch entity : acceptedEntities) {
            if (!present(entity.referenceKey())) continue;
            String key = StoryReferenceAlias.normalizeKey(entity.referenceKey());
            if (!present(entity.entityId()) || ids.putIfAbsent(key, entity.entityId()) != null) {
                throw new IllegalStateException("持续记录的正式实体引用缺失或重复");
            }
        }
        List<Value> values = prepared.entries().stream().map(entry -> new Value(entry.kind(), entry.text(),
                resolvedReference(entry.sourceRef(), prepared.localRefs, requestedAliases, ids), entry.sourceLabel(),
                entry.about().stream().map(ref -> resolvedReference(ref, prepared.localRefs, requestedAliases, ids)).toList()))
                .toList();
        validateValues(values);
        return values;
    }

    /** 仅在实际回合插入边界赋予来源；没有独立提交，也不重放记录为状态变化。 */
    public static Envelope stamp(List<Value> values, NewTurn turn) {
        if (turn == null || !present(turn.id()) || turn.turnNumber() < 1) {
            throw new IllegalStateException("持续记录缺少真实来源回合");
        }
        validateTimes(turn.worldTimeBefore(), turn.worldTimeAfter());
        validateValues(values);
        List<StampedValue> stamped = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Value value = values.get(i);
            stamped.add(new StampedValue(recordId(turn.id(), i), value.kind(), value.text(),
                    value.sourceRef(), value.sourceLabel(), value.about()));
        }
        return new Envelope(VERSION, turn.id(), turn.turnNumber(), turn.worldTimeBefore(), turn.worldTimeAfter(), stamped);
    }

    /** 显式分派新记录与旧 v1/v2；损坏或未知版本拒绝读回，不用正文或摘要降级。 */
    public static History read(StoredTurn turn) {
        if (turn == null) throw new IllegalStateException("持续记录缺少来源回合");
        JsonNode patch = turn.statePatch();
        if (patch == null || patch.isNull()) return missingHistory(turn);
        if (!patch.isObject()) throw new IllegalStateException("已保存回合补丁结构损坏");
        boolean records = patch.has("storyRecords");
        boolean ordered = patch.has("acceptedResult");
        if (records && ordered) throw new IllegalStateException("同一回合不能同时保存两套故事结果");
        if (ordered) {
            if (RecordStoryTurnRepository.PROMPT_VERSION.equals(turn.promptVersion())) {
                throw new IllegalStateException("新记录回合不能以旧步骤结果替代 storyRecords");
            }
            JsonNode result = patch.get("acceptedResult");
            if (result == null || !result.isObject() || !integer(result.path("protocolVersion"))
                    || (result.path("protocolVersion").intValue() != 1 && result.path("protocolVersion").intValue() != 2)) {
                throw new IllegalStateException("旧步骤结果仅支持完整的 v1/v2 记录");
            }
            return new History(null, StoredTurnResultReader.read(patch));
        }
        if (!records) return missingHistory(turn);
        JsonNode raw = patch.get("storyRecords");
        validateEnvelopeShape(raw);
        try {
            Envelope result = MAPPER.treeToValue(raw, Envelope.class);
            if (result.protocolVersion() != VERSION || !turn.id().equals(result.turnId())
                    || result.turnNumber() != turn.turnNumber()
                    || result.worldTimeBefore() != turn.worldTimeBefore()
                    || result.worldTimeAfter() != turn.worldTimeAfter()) {
                throw new IllegalStateException("已保存持续记录版本或来源回合不匹配");
            }
            validateTimes(result.worldTimeBefore(), result.worldTimeAfter());
            List<Value> values = new ArrayList<>();
            for (int i = 0; i < result.entries().size(); i++) {
                StampedValue entry = result.entries().get(i);
                if (!recordId(turn.id(), i).equals(entry.recordId())) {
                    throw new IllegalStateException("已保存持续记录 ID 与来源回合或位置不匹配");
                }
                values.add(new Value(entry.kind(), entry.text(), entry.sourceRef(), entry.sourceLabel(), entry.about()));
            }
            validateValues(values);
            return new History(result, null);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalStateException("已保存持续记录结构损坏", error);
        }
    }

    private static History missingHistory(StoredTurn turn) {
        if (RecordStoryTurnRepository.PROMPT_VERSION.equals(turn.promptVersion())) {
            throw new IllegalStateException("新记录回合缺少 storyRecords 格式外壳");
        }
        return new History(null, null);
    }

    private static void validateEnvelopeShape(JsonNode raw) {
        if (raw == null || !raw.isObject() || !integer(raw.path("protocolVersion"))
                || raw.path("protocolVersion").intValue() != VERSION
                || !raw.path("turnId").isTextual() || !present(raw.path("turnId").textValue())
                || !integer(raw.path("turnNumber")) || raw.path("turnNumber").intValue() < 1
                || !number(raw.path("worldTimeBefore")) || !number(raw.path("worldTimeAfter"))
                || !raw.path("entries").isArray()) {
            throw new IllegalStateException("已保存持续记录版本、来源或格式外壳无效");
        }
        for (JsonNode entry : raw.path("entries")) {
            if (!entry.isObject() || !entry.path("recordId").isTextual() || !entry.path("kind").isTextual()
                    || !entry.path("text").isTextual() || !optionalText(entry.get("sourceRef"))
                    || !optionalText(entry.get("sourceLabel")) || !entry.path("about").isArray()) {
                throw new IllegalStateException("已保存持续记录字段缺失或类型错误");
            }
            for (JsonNode ref : entry.path("about")) {
                if (!ref.isTextual()) throw new IllegalStateException("已保存持续记录关联引用类型错误");
            }
        }
    }

    private static void validateValues(List<Value> values) {
        if (values == null) throw new IllegalStateException("持续记录缺少已接受内容，空记录应使用空数组");
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Value value = values.get(i);
            if (value == null) throw new IllegalStateException("持续记录不能包含空项");
            Entry entry = new Entry(value.kind(), value.text(), value.sourceRef(), value.sourceLabel(), value.about());
            validateEntry(entry, "records[" + i + "]");
            if ("STATEMENT".equals(value.kind()) && !present(value.sourceLabel())) {
                throw new IllegalStateException("已接受来源声明缺少已解析的来源名称");
            }
            if (StoryReferenceAlias.isAlias(value.sourceRef())
                    || value.about().stream().anyMatch(StoryReferenceAlias::isAlias)) {
                throw new IllegalStateException("已接受持续记录不能保留临时实体引用");
            }
            entries.add(entry);
        }
        checkBytes(entries);
    }

    private static void validateEntry(Entry entry, String path) {
        if (entry == null) throw error(path, "NULL_ENTRY", "记录不能是空项");
        if (!"EVENT".equals(entry.kind()) && !"STATEMENT".equals(entry.kind())) {
            throw error(path + ".kind", "KIND", "仅支持 EVENT 经历和 STATEMENT 来源说法");
        }
        if (!present(entry.text())) throw error(path + ".text", "TEXT", "记录内容不能为空");
        if (entry.about() == null || entry.about().stream().anyMatch(ref -> !present(ref))) {
            throw error(path + ".about", "ABOUT", "关联对象必须是明确引用数组，没有关联时使用空数组");
        }
        if ("EVENT".equals(entry.kind()) && (present(entry.sourceRef()) || present(entry.sourceLabel()))) {
            throw error(path, "EVENT_SOURCE", "EVENT 记录经历，不得携带说话者；人物说法使用 STATEMENT");
        }
        if ("STATEMENT".equals(entry.kind()) && !present(entry.sourceRef()) && !present(entry.sourceLabel())) {
            throw error(path, "SOURCE_REQUIRED", "声明必须有真实来源引用或临时来源名称");
        }
    }

    private static String requireReference(StoryProjection before, Map<String, StoryTurnDecision.EntityIntent> added,
                                           String ref, boolean speaker, String path) {
        if ("PLAYER".equals(ref)) return "主角";
        StoryProjection.EntityState existing = before.entities().get(ref);
        if (existing != null && (!speaker || "CHARACTER".equalsIgnoreCase(existing.entityType()))) {
            if (speaker && !present(existing.name())) throw new IllegalStateException("既有人物缺少权威名称");
            return existing.name();
        }
        StoryTurnDecision.EntityIntent proposed = added.get(ref);
        if (proposed != null && (!speaker || "CHARACTER".equalsIgnoreCase(proposed.entityType()))) {
            if (speaker && !present(proposed.name())) throw error(path, "SOURCE_NAME", "新增来源人物必须有名称");
            return proposed.name();
        }
        throw error(path, "SOURCE_REF", speaker ? "来源须指向主角、既有或本轮新增人物，临时来源用空引用和名称"
                : "关联对象须指向已有实体或本轮明确新增实体");
    }

    private static String resolvedReference(String ref, Set<String> localRefs,
                                            Map<String, String> requested, Map<String, String> ids) {
        if (!present(ref) || !localRefs.contains(ref)) return ref;
        String key = requested.get(ref);
        String id = key == null ? null : ids.get(key);
        if (!present(id) || StoryReferenceAlias.isAlias(id)) {
            throw new IllegalStateException("持续记录缺少本轮引用的正式实体 ID：" + ref);
        }
        return id;
    }

    private static void checkBytes(List<Entry> entries) {
        try {
            for (int i = 0; i < entries.size(); i++) {
                if (MAPPER.writeValueAsBytes(entries.get(i)).length > MAX_ENTRY_BYTES) {
                    throw error("records[" + i + "]", "ENTRY_BYTES", "单条记录 JSON 超出 16 KiB，不能截断原文");
                }
            }
            if (MAPPER.writeValueAsBytes(entries).length > MAX_TOTAL_BYTES) {
                throw error("records", "TOTAL_BYTES", "记录 JSON 总量超出 32 KiB，不能只保留前几条");
            }
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("持续记录无法序列化为 UTF-8 JSON", error);
        }
    }

    private static void validateTimes(long before, long after) {
        if (after < before) throw new IllegalStateException("持续记录来源时间不能倒退");
        StoryWorldTime.fromOrder(before);
        StoryWorldTime.fromOrder(after);
    }

    private static boolean integer(JsonNode node) { return number(node) && node.canConvertToInt(); }
    private static boolean number(JsonNode node) { return node.isIntegralNumber() && node.canConvertToLong(); }
    private static boolean optionalText(JsonNode node) { return node == null || node.isNull() || node.isTextual(); }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static String recordId(String turnId, int index) { return turnId + "#record-" + (index + 1); }
    private static ProtocolException error(String path, String code, String detail) { return new ProtocolException(path, code, detail); }
    private static <T> List<T> copyNullable(List<T> list) {
        return list == null ? null : Collections.unmodifiableList(new ArrayList<>(list));
    }
}
