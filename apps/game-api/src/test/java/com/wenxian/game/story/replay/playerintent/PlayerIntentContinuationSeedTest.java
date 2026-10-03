package com.wenxian.game.story.replay.playerintent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator;
import com.wenxian.game.story.persistence.StoryMemoryRepository.StoredMemory;
import com.wenxian.game.story.replay.records.RecordStoryTurnRepository;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/** 仅验证隔离业务快照续玩；不恢复生产账号或额度，不调用模型。 */
class PlayerIntentContinuationSeedTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void keepsHistoricalRecordIdentityAndOriginalMemorySelectionOrder() throws Exception {
        var fixture = fixture(true);
        var result = new PlayerIntentReplay(mapper).preflight(fixture);
        assertEquals("VALID_UNTIL_DIRECTOR", result.status(), result.errorDetail());
        var savedTurn = result.loaded().tables().get("story_turn").getFirst();
        var source = fixture.history().getFirst();
        assertEquals(source.id(), savedTurn.get("id"));
        assertEquals(source.requestId(), savedTurn.get("request_id"));
        assertEquals(source.promptVersion(), savedTurn.get("prompt_version"));
        assertEquals(source.statePatch(), mapper.readTree(savedTurn.get("state_patch_json")));
        assertEquals(source.statePatch().at("/storyRecords/entries/0/recordId").asText(),
                mapper.readTree(savedTurn.get("state_patch_json")).at("/storyRecords/entries/0/recordId").asText());
        assertEquals("0", savedTurn.get("prompt_tokens"), "历史用量不计为本批新调用，保留已披露的 CONTEXT_ONLY 语义");
        assertEquals("CONTEXT_ONLY", savedTurn.get("model"));
        for (var memory : result.loaded().tables().get("story_memory")) {
            assertEquals(Timestamp.valueOf(fixture.seedMetadata().memoryCreatedAt().get(memory.get("id"))),
                    Timestamp.valueOf(memory.get("created_at")));
        }
        List<String> selected = StreamSupport.stream(result.directorContext().at("/memory/entries").spliterator(), false)
                .map(value -> value.path("id").asText()).toList();
        assertEquals(IntStream.range(0, 20).mapToObj(i -> "memory-%02d".formatted(i)).toList(), selected,
                "原最旧的 memory-20 应被窗口排除；不能按本批插入顺序改成排除 memory-00");
        assertEquals(fixture.initialSummary(), result.loaded().tables().get("story_conversation").getFirst().get("rolling_summary"));
        assertEquals("12", result.loaded().tables().get("story_conversation").getFirst().get("summarized_through_turn"));
        var item = result.loaded().tables().get("story_entity").getFirst();
        assertEquals("continuation-coins", item.get("id"));
        assertEquals(fixture.entities().getFirst().attributes(), mapper.readTree(item.get("attributes_json")));
        ObjectNode loadedState = (ObjectNode) mapper.readTree(result.loaded().tables().get("story_save").getFirst().get("state_json"));
        ObjectNode sourceState = fixture.initialState().deepCopy();
        loadedState.remove("saveId");
        sourceState.remove("saveId");
        assertEquals(sourceState, loadedState);
    }

    @Test
    void oldSyntheticFixtureWithoutMetadataStillInitializesNormally() throws Exception {
        var fixture = fixture(false);
        assertNull(fixture.seedMetadata());
        assertNull(fixture.history().getFirst().id());
        var result = new PlayerIntentReplay(mapper).preflight(fixture);
        assertEquals("VALID_UNTIL_DIRECTOR", result.status(), result.errorDetail());
        var turn = result.loaded().tables().get("story_turn").getFirst();
        assertTrue(turn.get("id").startsWith("seed-turn-"));
        assertTrue(turn.get("request_id").startsWith("seed-request-"));
        assertEquals("REVIEWED_CONTEXT_SEED", turn.get("prompt_version"));
        assertEquals(fixture.history().getFirst().statePatch(), mapper.readTree(turn.get("state_patch_json")));
    }

    @Test
    void invalidHistoricalSourcesFailDuringSeedWithoutEscapingRunOrOpeningGenerator() throws Exception {
        var base = fixture(true);
        var turn = base.history().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new PlayerIntentReplay.InitialTurn(turn.turnNumber(),
                turn.playerAction(), turn.narrative(), turn.choices(), turn.statePatch(), turn.worldTimeBefore(),
                turn.worldTimeAfter(), turn.id(), null, turn.promptVersion()));
        ObjectNode unknown = turn.statePatch().deepCopy();
        ((ObjectNode) unknown.get("storyRecords")).put("protocolVersion", 99);
        List<PlayerIntentReplay.InitialTurn> invalid = List.of(
                new PlayerIntentReplay.InitialTurn(turn.turnNumber(), turn.playerAction(), turn.narrative(),
                        turn.choices(), turn.statePatch(), turn.worldTimeBefore(), turn.worldTimeAfter()),
                new PlayerIntentReplay.InitialTurn(turn.turnNumber(), turn.playerAction(), turn.narrative(),
                        turn.choices(), turn.statePatch(), turn.worldTimeBefore(), turn.worldTimeAfter(),
                        "different-source-turn", turn.requestId(), turn.promptVersion()),
                new PlayerIntentReplay.InitialTurn(turn.turnNumber(), turn.playerAction(), turn.narrative(),
                        turn.choices(), unknown, turn.worldTimeBefore(), turn.worldTimeAfter(),
                        turn.id(), turn.requestId(), turn.promptVersion()));
        for (var source : invalid) {
            var input = copy(base, List.of(source), base.seedMetadata());
            assertSeedFailure(input);
            var preflight = new PlayerIntentReplay(mapper).preflight(input);
            assertEquals("INVALID", preflight.status());
            assertNotNull(preflight.loaded(), "种子失败仍返回回滚后的取证材料");
        }
    }

    @Test
    void incompleteOrInvalidMemoryTimestampMetadataCannotPartiallySeed() throws Exception {
        var base = fixture(true);
        List<Map<String, String>> invalid = new ArrayList<>();
        Map<String, String> missing = new LinkedHashMap<>(base.seedMetadata().memoryCreatedAt());
        missing.remove("memory-00");
        invalid.add(missing);
        Map<String, String> extra = new LinkedHashMap<>(base.seedMetadata().memoryCreatedAt());
        extra.put("unknown-memory", "2026-09-08 12:00:00.0");
        invalid.add(extra);
        Map<String, String> malformed = new LinkedHashMap<>(base.seedMetadata().memoryCreatedAt());
        malformed.put("memory-20", "not-a-JDBC-timestamp");
        invalid.add(malformed);
        for (var timestamps : invalid) assertSeedFailure(copy(base, base.history(), new PlayerIntentReplay.SeedMetadata(timestamps)));
    }

    private void assertSeedFailure(PlayerIntentReplay.CaseFixture fixture) {
        AtomicInteger opened = new AtomicInteger();
        DirectedNarrativeGenerator unused = invocation -> {
            opened.incrementAndGet();
            throw new AssertionError("无效种子不得开启模型会话");
        };
        var result = assertDoesNotThrow(() -> new PlayerIntentReplay(mapper).run(fixture, unused));
        assertEquals("FAILED", result.status());
        assertEquals(0, opened.get());
        assertTrue(result.unchangedOnFailure());
        assertEquals(result.before(), result.after());
        assertTrue(result.after().tables().values().stream().allMatch(List::isEmpty));
        assertEquals("SEED_VALIDATION", result.diagnostics().getFirst().get("current_stage"));
    }

    private PlayerIntentReplay.CaseFixture fixture(boolean sourced) throws Exception {
        ObjectNode state;
        try (var stream = getClass().getClassLoader().getResourceAsStream("contracts/story-state/story-state-v2.json")) {
            assertNotNull(stream);
            state = (ObjectNode) mapper.readTree(stream);
        }
        state.put("turnNumber", 12);
        state.putObject("worldTime").put("year", 1).put("day", 3).put("phase", "DUSK");
        var trusted = state.path("trustedProjection");
        String scale = trusted.path("creationContract").path("realmBinding").path("scaleId").asText();
        ((ObjectNode) trusted.path("cultivationSystem")).put("systemId", scale);
        ((ObjectNode) trusted.path("protagonistCultivation")).put("systemId", scale);
        ObjectNode patch = mapper.createObjectNode().put("title", "前次请教");
        if (sourced) {
            ObjectNode envelope = patch.putObject("storyRecords").put("protocolVersion", 1)
                    .put("turnId", "historical-turn-12").put("turnNumber", 12).put("worldTimeBefore", 11).put("worldTimeAfter", 18);
            envelope.putArray("entries").addObject().put("recordId", "historical-turn-12#record-1")
                    .put("kind", "EVENT").put("text", "完成此前约定的帮工，听过辨草指点，尚未独立验证。")
                    .putNull("sourceRef").putNull("sourceLabel").putArray("about");
        }
        var turn = sourced ? new PlayerIntentReplay.InitialTurn(12, "赴约帮工并请教", "原样保存的历史正文。",
                mapper.createArrayNode(), patch, 11, 18, "historical-turn-12", "historical-request-12", RecordStoryTurnRepository.PROMPT_VERSION)
                : new PlayerIntentReplay.InitialTurn(12, "赴约帮工并请教", "原样保存的历史正文。", mapper.createArrayNode(), patch, 11, 18);
        List<StoredMemory> memories = new ArrayList<>();
        Map<String, String> timestamps = new LinkedHashMap<>();
        for (int i = 0; i < 21; i++) {
            String id = "memory-%02d".formatted(i);
            memories.add(new StoredMemory(id, "CLUE", "来源记录", "第" + i + "条原始说法。", 12, 18, "NORMAL"));
            timestamps.put(id, "2026-09-08 12:00:%02d.123456".formatted(20 - i));
        }
        var coins = new PlayerIntentReplay.InitialEntity("continuation-coins", "ITEM", "铜钱", "随身携带", true,
                "已确认的七文铜钱", mapper.readTree("""
                {"_trusted":{"quantity":{"known":true,"value":7},"custody":{"holderType":"PLAYER","holderRef":null}}}
                """), 0, 12);
        return new PlayerIntentReplay.CaseFixture("continuation_seed", mapper.createObjectNode().put("kind", "SYNTHETIC_CONTINUATION"),
                state, "此前帮工已经结束，下一步由玩家决定。", List.of(coins), memories, List.of(turn),
                "回顾刚学的方法，暂不接受新安排。", null, sourced ? new PlayerIntentReplay.SeedMetadata(timestamps) : null);
    }

    private PlayerIntentReplay.CaseFixture copy(PlayerIntentReplay.CaseFixture base, List<PlayerIntentReplay.InitialTurn> turns,
                                                PlayerIntentReplay.SeedMetadata metadata) {
        return new PlayerIntentReplay.CaseFixture(base.caseId(), base.sourceEvidence(), base.initialState(), base.initialSummary(),
                base.entities(), base.memories(), turns, base.action(), base.choiceId(), metadata);
    }
}
