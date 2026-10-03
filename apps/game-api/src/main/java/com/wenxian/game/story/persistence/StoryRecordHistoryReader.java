package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.StoryRecords;
import com.wenxian.game.story.domain.StoryRecords.Envelope;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredTurn;

import java.util.Set;

/**
 * 显式读取不可变章节的记录合同：旧步骤 v1/v2 只读，新故事记录 v1 校验真实来源。
 *
 * <p>旧无字段记录保持为空；已知新回合必须有记录外壳，空数组同样保留。冲突、损坏和未知版本
 * 停止依赖该历史的准备或修正预览，不改写原章节，也不从正文补造权威记录。</p>
 */
public final class StoryRecordHistoryReader {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RECORD_PROMPTS = Set.of("record-candidate-v1", StoryRecords.PROMPT_VERSION);

    private StoryRecordHistoryReader() {}

    public record History(Envelope storyRecords, OrderedTurnResult.Accepted acceptedResult) {}

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
            if (RECORD_PROMPTS.contains(turn.promptVersion() == null ? "" : turn.promptVersion())) {
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
            StoryRecords.validateStored(result, turn.id(), turn.turnNumber(),
                    turn.worldTimeBefore(), turn.worldTimeAfter());
            return new History(result, null);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalStateException("已保存持续记录结构损坏", error);
        }
    }

    private static History missingHistory(StoredTurn turn) {
        if (RECORD_PROMPTS.contains(turn.promptVersion() == null ? "" : turn.promptVersion())) {
            throw new IllegalStateException("新记录回合缺少 storyRecords 格式外壳");
        }
        return new History(null, null);
    }

    private static void validateEnvelopeShape(JsonNode raw) {
        if (raw == null || !raw.isObject() || !integer(raw.path("protocolVersion"))
                || raw.path("protocolVersion").intValue() != StoryRecords.VERSION
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

    private static boolean integer(JsonNode node) { return number(node) && node.canConvertToInt(); }
    private static boolean number(JsonNode node) { return node.isIntegralNumber() && node.canConvertToLong(); }
    private static boolean optionalText(JsonNode node) { return node == null || node.isNull() || node.isTextual(); }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
