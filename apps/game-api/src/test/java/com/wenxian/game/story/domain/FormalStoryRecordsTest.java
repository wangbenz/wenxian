package com.wenxian.game.story.domain;

import com.wenxian.game.story.persistence.StoryRecordHistoryReader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewTurn;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredTurn;
import com.wenxian.game.story.support.StoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 持续记录的离线合同测试；不调用模型，不访问数据库，不把原文断言当作叙事质量签收。 */
class FormalStoryRecordsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StoryProjection before = projection();

    @Test
    void preservesNineStatementsAndExactTextWithoutExtraWholeTurnOutcome() {
        List<StoryRecords.Entry> entries = IntStream.rangeClosed(1, 9).mapToObj(i ->
                new StoryRecords.Entry("STATEMENT", "  第" + i + "项指点\n适用缓步，快跑尚未验证。  ",
                        "npc-zhao", "模型错误的名称", List.of("npc-zhao"))).toList();
        StoryRecords.Prepared prepared = StoryRecords.prepare(before, List.of(), entries);
        assertEquals(9, prepared.entries().size());
        assertEquals(9, prepared.memories().size());
        for (int i = 0; i < 9; i++) {
            assertEquals(entries.get(i).text(), prepared.entries().get(i).text());
            assertEquals("赵先生", prepared.entries().get(i).sourceLabel());
            assertEquals("赵先生当时表示：" + entries.get(i).text(), prepared.memories().get(i).summary());
            assertEquals("CLUE", prepared.memories().get(i).memoryType());
            assertTrue(prepared.memories().get(i).evidenceRefs().isEmpty());
        }
        assertEquals("模型错误的名称", entries.getFirst().sourceLabel(), "保留原模型草案，不就地改写");
    }

    @Test
    void eventCannotImpersonateSpeakerAndUnknownOrNonCharacterSpeakerIsRejected() {
        List<StoryRecords.Entry> invalid = List.of(
                new StoryRecords.Entry("EVENT", "完成帮工", "npc-zhao", null, List.of()),
                new StoryRecords.Entry("EVENT", "已能御寒", null, "赵先生", List.of()),
                new StoryRecords.Entry("STATEMENT", "可以御寒", "missing-person", "赵先生", List.of()),
                new StoryRecords.Entry("STATEMENT", "可以御寒", "paper-one", null, List.of()),
                new StoryRecords.Entry("STATEMENT", "可以御寒", null, null, List.of()),
                new StoryRecords.Entry("EVENT", "完成帮工", null, null, List.of("missing-entity")));
        for (StoryRecords.Entry entry : invalid) {
            assertThrows(StoryRecords.ProtocolException.class, () -> StoryRecords.prepare(before, List.of(), List.of(entry)));
        }
        var transientSpeaker = StoryRecords.prepare(before, List.of(), List.of(
                new StoryRecords.Entry("STATEMENT", "下一日清晨可来问路。", null, "桥边过客", List.of())));
        assertNull(transientSpeaker.entries().getFirst().sourceRef());
        assertEquals("桥边过客", transientSpeaker.entries().getFirst().sourceLabel());
        String longLabel = "临时来源的描述".repeat(40);
        var longSource = StoryRecords.prepare(before, List.of(), List.of(
                new StoryRecords.Entry("STATEMENT", "此处曾有渡船。", null, longLabel, List.of())));
        assertEquals("来源记录", longSource.memories().getFirst().title(),
                "阅读标题不拼接任意长度的来源，避免合法记录溢出 story_memory.title");
        assertEquals(longLabel + "当时表示：此处曾有渡船。", longSource.memories().getFirst().summary());
        var player = StoryRecords.prepare(before, List.of(), List.of(
                new StoryRecords.Entry("STATEMENT", "我答应明天帮工。", "PLAYER", null, List.of("npc-zhao"))));
        assertEquals("主角", player.entries().getFirst().sourceLabel());
    }

    @Test
    void resolvesTeacherAndAbilityLocalRefsOnlyAfterReducerAssignedStableIds() {
        var teacher = entity("teacher-local", "CHARACTER", "周先生");
        var ability = entity("lesson-local", "ABILITY", "辨草入门");
        var prepared = StoryRecords.prepare(before, List.of(teacher, ability), List.of(
                new StoryRecords.Entry("STATEMENT", "细看叶背，再判断草种；尚不能只凭远观断定。", "teacher-local",
                        null, List.of("lesson-local", "npc-zhao")),
                new StoryRecords.Entry("EVENT", "听过示范，尚未独立验证。", null, null, List.of("lesson-local"))));
        assertEquals("周先生", prepared.entries().getFirst().sourceLabel());
        assertTrue(prepared.memories().stream().allMatch(memory -> memory.evidenceRefs().isEmpty()));
        var resolved = StoryRecords.resolve(prepared, Map.of("teacher-local", "new-1", "lesson-local", "new-2"),
                List.of(accepted("character-new", "CHARACTER", "new-1"), accepted("ability-new", "ABILITY", "new-2")));
        assertEquals("character-new", resolved.getFirst().sourceRef());
        assertEquals(List.of("ability-new", "npc-zhao"), resolved.getFirst().about());
        assertEquals(List.of("ability-new"), resolved.get(1).about());
        assertEquals("teacher-local", prepared.entries().getFirst().sourceRef());
        ObjectNode patch = mapper.createObjectNode();
        patch.set("storyRecords", mapper.valueToTree(stamp(resolved, turn(patch))));
        StoryRecordHistoryReader.History history = StoryRecordHistoryReader.read(stored(patch, StoryRecords.PROMPT_VERSION));
        assertNull(history.acceptedResult());
        assertEquals(mapper.valueToTree(stamp(resolved, turn(patch))), mapper.valueToTree(history.storyRecords()));
        assertEquals("character-new", history.storyRecords().entries().getFirst().sourceRef());
        assertEquals(prepared.entries().getFirst().text(), history.storyRecords().entries().getFirst().text());
    }

    @Test
    void missingServerReferenceMappingFailsInsteadOfDroppingRecordOrCallingItAWorldFact() {
        var ability = entity("lesson", "ABILITY", "辨草入门");
        var prepared = StoryRecords.prepare(before, List.of(ability), List.of(
                new StoryRecords.Entry("EVENT", "听过辨草的示范。", null, null, List.of("lesson"))));
        assertThrows(IllegalStateException.class, () -> StoryRecords.resolve(prepared, Map.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> StoryRecords.resolve(prepared, Map.of("lesson", "new-1"), List.of()));
        assertThrows(StoryRecords.ProtocolException.class, () -> StoryRecords.prepare(before, List.of(ability), List.of(
                new StoryRecords.Entry("STATEMENT", "我会说话", "lesson", null, List.of()))));
    }

    @Test
    void emptyNewRecordsKeepEnvelopeAndAreDistinctFromMissingOldHistory() {
        var prepared = StoryRecords.prepare(before, List.of(), List.of());
        assertTrue(prepared.memories().isEmpty());
        ObjectNode patch = mapper.createObjectNode();
        var envelope = stamp(StoryRecords.resolve(prepared, Map.of(), List.of()), turn(patch));
        patch.set("storyRecords", mapper.valueToTree(envelope));
        var read = StoryRecordHistoryReader.read(stored(patch, StoryRecords.PROMPT_VERSION));
        assertNotNull(read.storyRecords());
        assertTrue(read.storyRecords().entries().isEmpty());
        assertEquals(read, StoryRecordHistoryReader.read(stored(patch, "record-candidate-v1")),
                "已提交实验章节保留原身份和版本，只读导入同一新记录外壳");
        assertEquals("turn-record-test", read.storyRecords().turnId());
        assertEquals(2, read.storyRecords().turnNumber());
        var old = StoryRecordHistoryReader.read(stored(mapper.createObjectNode(), "old-prompt"));
        assertNull(old.storyRecords());
        assertNull(old.acceptedResult());
        assertThrows(IllegalStateException.class, () -> StoryRecordHistoryReader.read(stored(mapper.createObjectNode(),
                StoryRecords.PROMPT_VERSION)));
        assertThrows(IllegalStateException.class, () -> StoryRecordHistoryReader.read(stored(mapper.createObjectNode(),
                "record-candidate-v1")));
        assertThrows(StoryRecords.ProtocolException.class, () -> StoryRecords.prepare(before, List.of(), null));
    }

    @Test
    void readsLegacyV1AndV2ExactlyAndRejectsUnknownVersion() throws Exception {
        ObjectNode patch = legacy();
        for (int version : List.of(1, 2)) {
            ((ObjectNode) patch.get("acceptedResult")).put("protocolVersion", version);
            var history = StoryRecordHistoryReader.read(stored(patch, "legacy-director"));
            assertNull(history.storyRecords());
            assertEquals(version, history.acceptedResult().protocolVersion());
            assertEquals(patch.get("acceptedResult"), mapper.valueToTree(history.acceptedResult()));
        }
        ((ObjectNode) patch.get("acceptedResult")).put("protocolVersion", 99);
        assertThrows(IllegalStateException.class, () -> StoryRecordHistoryReader.read(stored(patch, "legacy-director")));
    }

    @Test
    void rejectsCoexistingFormatsIncludingNullLegacyField() {
        ObjectNode patch = savedRecords();
        patch.putNull("acceptedResult");
        assertThrows(IllegalStateException.class, () -> StoryRecordHistoryReader.read(stored(patch, StoryRecords.PROMPT_VERSION)));
    }

    @Test
    void rejectsUnknownOrDamagedEnvelopeAndMismatchedSourceWithoutSummaryFallback() {
        List<Consumer<ObjectNode>> mutations = List.of(
                node -> node.put("protocolVersion", 2),
                node -> node.put("turnId", "another-turn"),
                node -> node.put("turnNumber", 3),
                node -> node.put("worldTimeBefore", 0),
                node -> node.put("worldTimeAfter", 999),
                node -> node.put("worldTimeAfter", "8"),
                node -> node.remove("entries"),
                node -> ((ObjectNode) node.at("/entries/0")).put("recordId", "forged-record"),
                node -> ((ObjectNode) node.at("/entries/0")).put("text", 4),
                node -> ((ObjectNode) node.at("/entries/0")).put("sourceRef", "contract-ref:new-1"),
                node -> ((ObjectNode) node.at("/entries/0")).putNull("sourceLabel"));
        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode patch = savedRecords();
            mutation.accept((ObjectNode) patch.get("storyRecords"));
            assertThrows(IllegalStateException.class, () -> StoryRecordHistoryReader.read(stored(patch,
                    StoryRecords.PROMPT_VERSION)));
        }
    }

    @Test
    void measuresUtf8JsonBytesForBothEntryAndWholeArrayWithoutTruncation() throws Exception {
        StoryRecords.Entry empty = new StoryRecords.Entry("EVENT", "", null, null, List.of());
        int overhead = mapper.writeValueAsBytes(empty).length;
        String exact = "a".repeat(StoryRecords.MAX_ENTRY_BYTES - overhead);
        var atBoundary = new StoryRecords.Entry("EVENT", exact, null, null, List.of());
        assertEquals(StoryRecords.MAX_ENTRY_BYTES, mapper.writeValueAsBytes(atBoundary).length);
        assertEquals(exact, StoryRecords.prepare(before, List.of(), List.of(atBoundary)).entries().getFirst().text());
        assertThrows(StoryRecords.ProtocolException.class, () -> StoryRecords.prepare(before, List.of(), List.of(
                new StoryRecords.Entry("EVENT", exact + "a", null, null, List.of()))));
        String multiByte = "😀".repeat(4096);
        assertTrue(multiByte.length() < StoryRecords.MAX_ENTRY_BYTES);
        assertThrows(StoryRecords.ProtocolException.class, () -> StoryRecords.prepare(before, List.of(), List.of(
                new StoryRecords.Entry("EVENT", multiByte, null, null, List.of()))));
        var medium = new StoryRecords.Entry("EVENT", "修".repeat(4000), null, null, List.of());
        assertEquals(2, StoryRecords.prepare(before, List.of(), List.of(medium, medium)).entries().size());
        assertThrows(StoryRecords.ProtocolException.class,
                () -> StoryRecords.prepare(before, List.of(), List.of(medium, medium, medium)));
    }

    private ObjectNode savedRecords() {
        var prepared = StoryRecords.prepare(before, List.of(), List.of(new StoryRecords.Entry("STATEMENT",
                "缓步时可以尝试，快跑仍未验证。", "npc-zhao", null, List.of("paper-one"))));
        ObjectNode patch = mapper.createObjectNode();
        patch.set("storyRecords", mapper.valueToTree(stamp(
                StoryRecords.resolve(prepared, Map.of(), List.of()), turn(patch))));
        return patch;
    }

    private ObjectNode legacy() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"acceptedResult":{"protocolVersion":1,"steps":[{"stepIndex":0,
                "action":"问价","resolution":"SUCCEEDED","result":"问到价格，尚未成交。",
                "cultivation":false,"assetStepIndexes":[],"dependsOn":[],
                "statements":[{"sourceRef":null,"sourceLabel":"摊主","statement":"三文一份。"}]}],
                "stop":{"reason":"DECISION_REQUIRED","detail":"是否接受报价？"}}}
                """);
    }

    private StoryRecords.Envelope stamp(List<StoryRecords.Value> values, NewTurn turn) {
        return StoryRecords.stamp(values, turn.id(), turn.turnNumber(), turn.worldTimeBefore(), turn.worldTimeAfter());
    }

    private NewTurn turn(JsonNode patch) {
        long start = before.worldTime().toOrder();
        return new NewTurn("turn-record-test", "save-record-test", "request-record-test", 2,
                "请教辨草", "已提交的完整正文", mapper.createArrayNode(), patch, start, start + 7,
                "LIGHT_NARRATIVE", 1, 3, "unchanged-input-prompt", "DEEPSEEK", "test-model", "PLATFORM",
                17, 23, 3, 14, 99, "test-release", Instant.parse("2026-09-08T00:00:00Z"));
    }

    private StoredTurn stored(JsonNode patch, String promptVersion) {
        NewTurn turn = turn(patch);
        return new StoredTurn(turn.id(), turn.saveId(), turn.requestId(), turn.turnNumber(), turn.playerAction(),
                turn.narrative(), turn.choices(), patch, turn.worldTimeBefore(), turn.worldTimeAfter(),
                turn.engineCode(), turn.engineVersion(), turn.stateSchemaVersion(), promptVersion, turn.provider(),
                turn.model(), turn.credentialSource(), turn.promptTokens(), turn.completionTokens(), turn.cacheHitTokens(),
                turn.cacheMissTokens(), turn.latencyMs(), turn.releaseVersion(), turn.createdAt());
    }

    private StoryTurnDecision.EntityIntent entity(String localRef, String type, String name) {
        return new StoryTurnDecision.EntityIntent("", localRef, type, name, "KNOWN", "本轮明确引入", null,
                null, null, null, null, null, true);
    }

    private StoryPatch.EntityPatch accepted(String id, String type, String key) {
        return new StoryPatch.EntityPatch(StoryPatch.EntityOperation.CREATE, id, type, "正式实体", "KNOWN", "已确认",
                null, null, null, null, null, null, null, null, key);
    }

    private static StoryProjection projection() {
        var teacher = new StoryProjection.EntityState("npc-zhao", "CHARACTER", "赵先生", "ACTIVE", "曾经指点调息",
                null, true, null, null, null, null);
        var paper = new StoryProjection.EntityState("paper-one", "ITEM", "调息抄页", "ACTIVE", "已持有的抄页",
                null, true, new StoryPatch.QuantityValue(true, 1), true, null, null);
        return new StoryProjection(StoryWorldTime.opening(), new StoryProjection.CoreState("摊位", "平静", "炼气初期", "问道"),
                Map.of(teacher.id(), teacher, paper.id(), paper), Map.of(), Map.of(), List.of(),
                StoryTestFixtures.creationContract(), StoryTestFixtures.playPreference(), null, null);
    }
}
