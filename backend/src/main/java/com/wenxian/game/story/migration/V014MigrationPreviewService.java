package com.wenxian.game.story.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.MigrationIssue;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.PreviewReport;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.PreviewStatus;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.TargetCorrection;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.TargetEntity;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.TargetMemory;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.TargetSnapshot;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.TargetTurn;
import com.wenxian.game.story.migration.V014MigrationSourceReader.SourceCorrection;
import com.wenxian.game.story.migration.V014MigrationSourceReader.SourceSave;
import com.wenxian.game.story.migration.V014MigrationSourceReader.SourceSaveHeader;
import com.wenxian.game.story.migration.V014MigrationSourceReader.SourceTurn;
import com.wenxian.game.v014.model.V014State;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将 V0.14 旧命卷转换成统一 story 候选快照并生成差异报告。
 * 本服务只依赖旧表只读入口，不注入任何目标仓储，因此不会产生 INSERT、UPDATE 或 DELETE。
 */
@Service
@Transactional(readOnly = true)
public class V014MigrationPreviewService {
    static final int TARGET_SCHEMA_VERSION = 1;
    static final String TARGET_ENGINE = "LIGHT_NARRATIVE";
    static final int TARGET_ENGINE_VERSION = 1;

    private final V014MigrationSourceReader sources;
    private final ObjectMapper mapper;
    private final LegacyV014JsonNormalizer legacyJson;
    private final StoryMigrationSnapshotHasher snapshotHasher;

    public V014MigrationPreviewService(V014MigrationSourceReader sources, ObjectMapper mapper) {
        this.sources = sources;
        this.mapper = mapper;
        this.legacyJson = new LegacyV014JsonNormalizer(mapper);
        this.snapshotHasher = new StoryMigrationSnapshotHasher(mapper);
    }

    /** 读取并预演一份命卷；报告中的 target 仍是内存对象，不代表数据库已有对应记录。 */
    public PreviewReport preview(String sourceSaveId) {
        SourceSave source = sources.read(sourceSaveId);
        SourceSaveHeader header = source.header();
        String targetSaveId = targetSaveId(header.id());
        String beforeHash = hash(sourceDigest(source));
        List<MigrationIssue> issues = new ArrayList<>();

        V014State state;
        try {
            state = mapper.readValue(header.stateJson(), V014State.class);
        } catch (JsonProcessingException error) {
            issues.add(error("STATE_JSON_INVALID", "旧命卷核心状态无法解析：" + compact(error.getOriginalMessage())));
            return invalid(source, targetSaveId, beforeHash, issues);
        }

        validateHeaderAndState(source, state, issues);
        List<TargetTurn> targetTurns = previewTurns(source.turns(), issues);
        List<TargetCorrection> targetCorrections = previewCorrections(source.corrections(), issues);
        List<TargetEntity> entities = mapEntities(targetSaveId, state, issues).stream()
                .sorted(Comparator.comparing(TargetEntity::id)).toList();
        List<TargetMemory> memories = mapMemories(targetSaveId, state, source.turns(), issues).stream()
                .sorted(Comparator.comparing(TargetMemory::id)).toList();
        ObjectNode coreState = coreState(targetSaveId, state);
        String rollingSummary = source.conversation() == null ? "" : safe(source.conversation().rollingSummary());
        int summarizedThrough = source.conversation() == null ? 0 : source.conversation().summarizedThroughTurn();
        TargetSnapshot target = new TargetSnapshot(coreState, entities, memories, targetTurns,
                targetCorrections, rollingSummary, summarizedThrough);
        String afterHash = snapshotHasher.hash(target);
        PreviewStatus status = status(issues);

        Map<String, Integer> entityCounts = new LinkedHashMap<>();
        for (TargetEntity entity : entities) entityCounts.merge(entity.type(), 1, Integer::sum);
        return new PreviewReport(status, header.id(), targetSaveId, header.userId(), header.slotNo(),
                header.status(), header.stateSchemaVersion(), TARGET_SCHEMA_VERSION, header.revision(),
                state.turnNumber(), source.turns().size(), targetTurns.size(), Map.copyOf(entityCounts),
                memories.size(), source.conversation() != null, source.corrections().size(), beforeHash, afterHash,
                header.createdAt(), header.updatedAt(), List.copyOf(issues), target);
    }

    private void validateHeaderAndState(SourceSave source, V014State state, List<MigrationIssue> issues) {
        SourceSaveHeader header = source.header();
        if (header.stateSchemaVersion() != 14 || state.schemaVersion() != 14) {
            issues.add(error("SOURCE_SCHEMA_UNSUPPORTED", "只支持结构版本 14，当前表记录为 "
                    + header.stateSchemaVersion() + "，状态内容为 " + state.schemaVersion()));
        }
        if (!header.id().equals(state.saveId())) {
            issues.add(error("SAVE_ID_MISMATCH", "主表命卷 ID 与状态内容中的 saveId 不一致"));
        }
        if (state.world() == null || state.protagonist() == null) {
            issues.add(error("CORE_STATE_MISSING", "世界或主角核心资料缺失"));
        } else if (!safe(header.worldCode()).equals(safe(state.world().code()))) {
            issues.add(warning("WORLD_CODE_MISMATCH", "主表世界编码与状态内容不一致，以状态内容为候选值"));
        }
        if (state.turnNumber() < 0 || state.worldTimeMinutes() < 0) {
            issues.add(error("NEGATIVE_CORE_VALUE", "回合数或世界时间出现负数"));
        }

        int maximumTurn = source.turns().stream().mapToInt(SourceTurn::turnNumber).max().orElse(0);
        if (source.turns().size() != state.turnNumber() || maximumTurn != state.turnNumber()) {
            issues.add(warning("TURN_COUNT_MISMATCH", "状态回合为 " + state.turnNumber() + "，旧回合记录数为 "
                    + source.turns().size() + "，最大回合号为 " + maximumTurn));
        }
        if (!source.turns().isEmpty()) {
            SourceTurn last = source.turns().get(source.turns().size() - 1);
            if (last.worldTimeAfter() != state.worldTimeMinutes()) {
                issues.add(warning("WORLD_TIME_MISMATCH", "最后回合世界时间与当前状态不一致，以当前状态为权威快照"));
            }
        }
        if (source.conversation() == null) {
            issues.add(warning("CONVERSATION_MISSING", "旧命卷没有滚动摘要，将以空摘要迁移"));
        } else if (source.conversation().summarizedThroughTurn() > state.turnNumber()) {
            issues.add(warning("SUMMARY_AHEAD_OF_STATE", "滚动摘要覆盖回合超过当前命卷回合"));
        }
    }

    private List<TargetTurn> previewTurns(List<SourceTurn> turns, List<MigrationIssue> issues) {
        List<TargetTurn> targets = new ArrayList<>();
        int expected = 1;
        long previousTime = 0;
        for (SourceTurn turn : turns) {
            if (turn.turnNumber() != expected) {
                issues.add(warning("TURN_SEQUENCE_GAP", "回合序列在 " + expected + " 处不连续，实际为 "
                        + turn.turnNumber()));
                expected = turn.turnNumber();
            }
            if (turn.worldTimeBefore() < previousTime || turn.worldTimeAfter() < turn.worldTimeBefore()) {
                issues.add(error("TURN_TIME_INVALID", "第 " + turn.turnNumber() + " 回的世界时间发生倒退"));
            }
            JsonNode choices = parseJson(turn.choicesJson(), "TURN_CHOICES_INVALID", turn.turnNumber(), issues);
            JsonNode patch = normalizeTurnPatch(turn, issues);
            targets.add(new TargetTurn(turn.id(),
                    fallback(turn.requestId(), "legacy-request-" + turn.turnNumber()), turn.turnNumber(),
                    safe(turn.playerAction()), safe(turn.narrative()), choices, patch,
                    turn.worldTimeBefore(), turn.worldTimeAfter(), TARGET_ENGINE, TARGET_ENGINE_VERSION,
                    "legacy-v014", safe(turn.provider()), safe(turn.model()), safe(turn.credentialSource()),
                    turn.promptTokens(), turn.completionTokens(), 0, 0, turn.latencyMs(), "V0.14",
                    turn.createdAt()));
            previousTime = Math.max(previousTime, turn.worldTimeAfter());
            expected++;
        }
        return List.copyOf(targets);
    }

    private List<TargetCorrection> previewCorrections(List<SourceCorrection> corrections,
                                                      List<MigrationIssue> issues) {
        List<TargetCorrection> targets = new ArrayList<>();
        for (SourceCorrection correction : corrections) {
            JsonNode before = parseJson(correction.beforeJson(), "CORRECTION_BEFORE_INVALID", 0, issues);
            JsonNode after = parseJson(correction.afterJson(), "CORRECTION_AFTER_INVALID", 0, issues);
            targets.add(new TargetCorrection(correction.id(), correction.userId(), safe(correction.instruction()),
                    "OTHER", before, after, correction.createdAt()));
        }
        return List.copyOf(targets);
    }

    private List<TargetEntity> mapEntities(String targetSaveId, V014State state,
                                           List<MigrationIssue> issues) {
        List<TargetEntity> entities = new ArrayList<>();
        int index = 0;
        for (V014State.Item item : state.inventory()) {
            ObjectNode attributes = mapper.createObjectNode();
            attributes.put("sourceEntityId", safe(item.id()));
            attributes.put("itemType", safe(item.type()));
            attributes.put("source", safe(item.source()));
            attributes.put("quantity", Math.max(item.quantity(), 0));
            attributes.put("inPossession", Boolean.TRUE.equals(item.inPossession()));
            entities.add(new TargetEntity(entityId(targetSaveId, "ITEM", item.id(), index++),
                    "ITEM", fallbackName(item.name(), "未命名物品", issues), fallback(item.status(), "UNKNOWN"),
                    Boolean.TRUE.equals(item.inPossession()) && item.quantity() > 0,
                    fallback(item.description(), item.name()), attributes, 0, state.turnNumber()));
        }
        for (V014State.Ability ability : state.abilities()) {
            ObjectNode attributes = mapper.createObjectNode();
            attributes.put("sourceEntityId", safe(ability.id()));
            attributes.put("abilityType", safe(ability.type()));
            attributes.put("source", safe(ability.source()));
            entities.add(new TargetEntity(entityId(targetSaveId, "ABILITY", ability.id(), index++),
                    "ABILITY", fallbackName(ability.name(), "未命名能力", issues),
                    fallback(ability.status(), "UNKNOWN"), !terminal(ability.status()),
                    fallback(ability.description(), ability.name()), attributes, 0, state.turnNumber()));
        }
        for (V014State.Character character : state.characters()) {
            ObjectNode attributes = mapper.createObjectNode();
            attributes.put("sourceEntityId", safe(character.id()));
            attributes.put("tier", safe(character.tier()));
            attributes.put("identity", safe(character.identity()));
            attributes.put("relationship", safe(character.relationship()));
            attributes.put("lastKnownLocation", safe(character.lastKnownLocation()));
            String summary = String.join("；", nonBlank(character.identity(), character.relationship(),
                    character.lastKnownLocation()));
            entities.add(new TargetEntity(entityId(targetSaveId, "CHARACTER", character.id(), index++),
                    "CHARACTER", fallbackName(character.name(), "未命名人物", issues),
                    fallback(character.status(), "UNKNOWN"), Boolean.TRUE.equals(character.tracked()),
                    fallback(summary, character.name()), attributes, 0, state.turnNumber()));
        }
        if (!entities.isEmpty()) {
            issues.add(warning("ENTITY_FIRST_TURN_INFERRED", "旧快照未记录实体首次出现回合，候选值统一记为 0"));
        }
        return List.copyOf(entities);
    }

    private List<TargetMemory> mapMemories(String targetSaveId, V014State state, List<SourceTurn> turns,
                                           List<MigrationIssue> issues) {
        List<TargetMemory> memories = new ArrayList<>();
        int index = 0;
        boolean inferred = false;
        for (V014State.Memory memory : state.memories()) {
            int occurredTurn = inferTurn(memory.worldTimeMinutes(), turns);
            boolean approximate = turns.stream().noneMatch(turn -> turn.worldTimeAfter() == memory.worldTimeMinutes());
            inferred |= approximate;
            memories.add(new TargetMemory(memoryId(targetSaveId, memory.id(), index++), "EVENT",
                    normalizeImportance(memory.importance()), fallback(memory.title(), "未命名经历"),
                    fallback(memory.summary(), memory.title()), occurredTurn,
                    Math.max(memory.worldTimeMinutes(), 0), "ACTIVE"));
        }
        if (inferred) {
            issues.add(warning("MEMORY_TURN_INFERRED", "部分记忆没有原始发生回合，已按世界时间匹配最近回合"));
        }
        return List.copyOf(memories);
    }

    private ObjectNode coreState(String targetSaveId, V014State state) {
        ObjectNode core = mapper.createObjectNode();
        core.put("schemaVersion", TARGET_SCHEMA_VERSION);
        core.put("saveId", targetSaveId);
        core.put("turnNumber", state.turnNumber());
        core.put("status", safe(state.status()));
        core.set("world", mapper.valueToTree(state.world()));
        core.set("protagonist", mapper.valueToTree(state.protagonist()));
        core.put("worldTimeMinutes", state.worldTimeMinutes());
        core.put("currentLocation", safe(state.currentLocation()));
        core.put("condition", safe(state.condition()));
        core.put("sceneTitle", safe(state.sceneTitle()));
        core.put("narrative", safe(state.narrative()));
        core.set("choices", mapper.valueToTree(state.choices()));
        return core;
    }

    private PreviewReport invalid(SourceSave source, String targetSaveId, String beforeHash,
                                  List<MigrationIssue> issues) {
        SourceSaveHeader header = source.header();
        return new PreviewReport(PreviewStatus.INVALID, header.id(), targetSaveId, header.userId(),
                header.slotNo(), header.status(), header.stateSchemaVersion(), TARGET_SCHEMA_VERSION,
                header.revision(), 0, source.turns().size(), 0, Map.of(), 0,
                source.conversation() != null, source.corrections().size(), beforeHash, null,
                header.createdAt(), header.updatedAt(), List.copyOf(issues), null);
    }

    private JsonNode sourceDigest(SourceSave source) {
        ObjectNode digest = mapper.createObjectNode();
        SourceSaveHeader header = source.header();
        digest.put("id", header.id());
        digest.put("userId", header.userId());
        digest.put("slotNo", header.slotNo());
        digest.put("worldCode", header.worldCode());
        digest.put("stateSchemaVersion", header.stateSchemaVersion());
        digest.put("stateJson", header.stateJson());
        digest.put("revision", header.revision());
        digest.put("status", header.status());
        digest.put("createdAt", header.createdAt().toString());
        digest.put("updatedAt", header.updatedAt().toString());
        ArrayNode turns = digest.putArray("turns");
        source.turns().forEach(turn -> turns.add(mapper.valueToTree(turn)));
        if (source.conversation() != null) digest.set("conversation", mapper.valueToTree(source.conversation()));
        ArrayNode corrections = digest.putArray("corrections");
        source.corrections().forEach(correction -> corrections.add(mapper.valueToTree(correction)));
        return digest;
    }

    private JsonNode parseJson(String value, String code, int turn, List<MigrationIssue> issues) {
        try {
            return mapper.readTree(fallback(value, "{}"));
        } catch (JsonProcessingException error) {
            String context = turn > 0 ? "第 " + turn + " 回" : "修正记录";
            issues.add(error(code, context + "的 JSON 无法解析：" + compact(error.getOriginalMessage())));
            return mapper.createObjectNode();
        }
    }

    private JsonNode normalizeTurnPatch(SourceTurn turn, List<MigrationIssue> issues) {
        LegacyV014JsonNormalizer.Result result = legacyJson.normalize(turn.statePatchJson());
        if (!result.valid()) {
            ObjectNode quarantine = mapper.createObjectNode();
            ObjectNode metadata = quarantine.putObject("_legacyMigration");
            metadata.put("status", "QUARANTINED");
            metadata.put("sourceEngine", "V014");
            metadata.put("sourceTable", "v014_game_turn");
            metadata.put("sourceTurnId", turn.id());
            metadata.put("sourceTurnNumber", turn.turnNumber());
            metadata.put("sourcePayloadSha256", hashText(turn.statePatchJson()));
            metadata.put("reasonCode", "AMBIGUOUS_JSON");
            issues.add(warning("TURN_PATCH_QUARANTINED", "第 " + turn.turnNumber()
                    + " 回的旧状态补丁无法确定性恢复，已保留原文定位和哈希；当前权威命卷不受影响"));
            return quarantine;
        }
        if (result.repaired()) {
            issues.add(warning("TURN_PATCH_REPAIRED", "第 " + turn.turnNumber()
                    + " 回的旧状态补丁已完成确定性格式修复："
                    + String.join(",", result.repairCodes())));
        }
        return result.value();
    }

    private int inferTurn(long worldTime, List<SourceTurn> turns) {
        int result = 0;
        for (SourceTurn turn : turns) {
            if (turn.worldTimeAfter() <= worldTime) result = turn.turnNumber();
            else if (turn.worldTimeBefore() <= worldTime) return turn.turnNumber();
            else break;
        }
        return result;
    }

    private String normalizeImportance(String value) {
        String normalized = safe(value).toUpperCase(Locale.ROOT);
        if ("KEY".equals(normalized)) return "KEY";
        if (List.of("IMPORTANT", "HIGH", "重要").contains(normalized)) return "IMPORTANT";
        return "NORMAL";
    }

    private PreviewStatus status(List<MigrationIssue> issues) {
        if (issues.stream().anyMatch(issue -> "ERROR".equals(issue.severity()))) return PreviewStatus.INVALID;
        return issues.isEmpty() ? PreviewStatus.READY : PreviewStatus.WARNING;
    }

    private MigrationIssue warning(String code, String message) {
        return new MigrationIssue("WARNING", code, message);
    }

    private MigrationIssue error(String code, String message) {
        return new MigrationIssue("ERROR", code, message);
    }

    private String targetSaveId(String sourceSaveId) {
        return "story-v014-" + hashText(sourceSaveId).substring(0, 32);
    }

    private String entityId(String saveId, String type, String sourceId, int index) {
        return "story-entity-" + hashText(saveId + "|" + type + "|" + safe(sourceId) + "|" + index)
                .substring(0, 32);
    }

    private String memoryId(String saveId, String sourceId, int index) {
        return "story-memory-" + hashText(saveId + "|" + safe(sourceId) + "|" + index).substring(0, 32);
    }

    private String hash(JsonNode value) {
        try {
            return hashBytes(mapper.writeValueAsBytes(value));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("迁移预演哈希无法生成", error);
        }
    }

    private String hashText(String value) {
        return hashBytes(safe(value).getBytes(StandardCharsets.UTF_8));
    }

    private String hashBytes(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境不支持 SHA-256", error);
        }
    }

    private String fallbackName(String value, String fallback, List<MigrationIssue> issues) {
        if (value == null || value.isBlank()) {
            issues.add(warning("ENTITY_NAME_MISSING", "存在名称为空的实体，候选报告已使用占位名称"));
            return fallback;
        }
        return value.strip();
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String compact(String value) {
        String compact = safe(value).replace('\n', ' ').replace('\r', ' ').strip();
        return compact.length() <= 180 ? compact : compact.substring(0, 180);
    }

    private boolean terminal(String value) {
        String normalized = safe(value).strip().toUpperCase(Locale.ROOT);
        return List.of("REMOVED", "FORGOTTEN", "LOST", "DELETED", "已遗忘", "已移除").contains(normalized);
    }

    private List<String> nonBlank(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) if (value != null && !value.isBlank()) result.add(value.strip());
        return result;
    }
}
