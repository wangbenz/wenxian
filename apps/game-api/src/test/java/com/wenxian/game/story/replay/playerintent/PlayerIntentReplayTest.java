package com.wenxian.game.story.replay.playerintent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.model.infrastructure.ChatModelFactory;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 只用仓库已有协议示例与进程内假模型核对宿主；这些断言材料不作为真实玩家抽样案例。 */
class PlayerIntentReplayTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private static final String DIRECTOR = """
            {
              "duration": {
                "unit": "SCENE",
                "value": 1,
                "targetPhase": ""
              },
              "timeResolution": "COMPLETE",
              "interruptionReason": "",
              "coreState": null,
              "cultivation": {
                "progressGained": false,
                "breakthroughAttempted": false,
                "reason": "没有修行行动",
                "evidenceRefs": []
              },
              "assetProcess": {
                "steps": []
              },
              "actionUnderstanding": {
                "purpose": "观察当前场景",
                "constraints": [
                  "观察后停下"
                ]
              },
              "itemChanges": [],
              "abilityChanges": [],
              "entities": [],
              "worldFacts": [],
              "orderedResult": {
                "steps": [
                  {
                    "action": "观察当前场景",
                    "resolution": "SUCCEEDED",
                    "result": "观察当前场景后停下。",
                    "realmProgressBinding": false,
                    "assetStepIndexes": [],
                    "dependsOn": [],
                    "statements": []
                  }
                ],
                "stop": {
                  "reason": "ACTION_RESOLVED",
                  "detail": "观察当前场景后停下。"
                }
              }
            }
            """;
    private static final String WRITER = """
            {"title":"静观","narrative":"沈砚望向远处的城门，稍作观察后停下。",
             "choices":[{"title":"继续观察","description":"留在原处留意周围。"}],"summary":"沈砚观察了城外的景象。"}
            """;

    @Test
    void formalTypedServicesPrepareAndCommitWithReloadedStateAndPrivateDiagnostics() throws Exception {
        FakeModels models = new FakeModels(false);
        var result = run(fixture("typed", "观察当前场景后停下。", null), models);
        assertEquals("COMMITTED_IN_H2", result.status(), mapper.writeValueAsString(result));
        assertEquals(1, models.directorCalls.get());
        assertEquals(1, models.writerCalls.get());
        assertEquals(2, result.stages().size());
        assertEquals("director", result.stages().getFirst().path("phase").asText());
        assertFalse(result.stages().getFirst().path("context").path("worldContext").isMissingNode());
        assertTrue(result.before().tables().get("story_turn").isEmpty());
        assertEquals(1, result.after().tables().get("story_turn").size());
        var saved = result.after().tables().get("story_save").getFirst();
        assertEquals("2", saved.get("revision"));
        assertEquals("1", saved.get("turn_number"));
        assertEquals(1, mapper.readTree(saved.get("state_json")).path("turnNumber").asInt());
        assertFalse(result.diagnostics().isEmpty());
        assertFalse(mapper.readTree(mapper.writeValueAsString(result)).path("prepared").isNull());
    }

    @Test
    void selectionUsesServerChoiceAndKeepsSubmittedActionSeparate() throws Exception {
        var base = fixture("choice", "本次请求携带的文本", "choice-protocol");
        ObjectNode state = base.initialState().deepCopy();
        ((ObjectNode) state.path("scene")).putArray("choices").addObject()
                .put("id", "choice-protocol").put("title", "观察").put("description", "留在原处。 ");
        var fixture = new PlayerIntentReplay.CaseFixture(base.caseId(), base.sourceEvidence(), state,
                base.initialSummary(), base.entities(), base.memories(), base.history(), base.action(), base.choiceId());
        FakeModels models = new FakeModels(false);
        var result = run(fixture, models);
        assertEquals("COMMITTED_IN_H2", result.status(), mapper.writeValueAsString(result));
        JsonNode action = result.stages().getFirst().path("context").path("action");
        assertEquals("观察：留在原处。", action.path("playerAction").asText());
        assertEquals("choice-protocol", action.path("selectedChoice").path("id").asText());
        assertEquals(base.action(), result.after().tables().get("story_turn").getFirst().get("player_action"));
    }

    @Test
    void missingChoiceFailsBeforeModelAndPreservesEveryPersistedTable() throws Exception {
        FakeModels models = new FakeModels(false);
        var result = run(fixture("unknown_choice", "观察当前场景后停下。", "absent-choice"), models);
        assertEquals("FAILED", result.status());
        assertTrue(result.unchangedOnFailure());
        assertEquals(result.before(), result.after());
        assertEquals(0, models.directorCalls.get() + models.writerCalls.get());
    }

    @Test
    void writerFailureNeverCommitsPreparedGrowthOrRetriesProvider() throws Exception {
        FakeModels models = new FakeModels(true);
        var result = run(fixture("writer_failure", "观察当前场景后停下。", null), models);
        assertEquals("FAILED", result.status());
        assertTrue(result.unchangedOnFailure());
        assertEquals(result.before(), result.after());
        assertEquals(1, models.directorCalls.get());
        assertEquals(1, models.writerCalls.get());
        assertEquals(2, result.stages().size());
        assertFalse(result.stages().getLast().has("result"));
        assertEquals("writer", result.stages().getLast().path("phase").asText());
    }

    @Test
    void reviewedEntitiesMemoriesAndHistoryAreLoadedWithoutReplacingTheirStableIds() throws Exception {
        var base = fixture("context_seed", "观察当前场景后停下。", null);
        ObjectNode state = base.initialState().deepCopy();
        state.put("turnNumber", 1);
        var item = new PlayerIntentReplay.InitialEntity("protocol-item", "ITEM", "协议物品", "随身", true,
                "离线协议记录", mapper.readTree("""
                {"_trusted":{"quantity":{"known":true,"value":1},"custody":{"holderType":"PLAYER","holderRef":null}}}
                """), 1, 1);
        var ability = new PlayerIntentReplay.InitialEntity("protocol-ability", "ABILITY", "协议能力", "已知", true,
                "离线协议记录", mapper.readTree("{\"_trusted\":{\"abilityState\":\"KNOWN\"}}"), 1, 1);
        var character = new PlayerIntentReplay.InitialEntity("protocol-character", "CHARACTER", "协议人物", "近况未知", true,
                "离线协议记录", mapper.readTree("{\"_trusted\":{\"lifecycle\":\"ACTIVE\"}}"), 1, 1);
        var memory = new StoryMemoryRepository.StoredMemory("protocol-memory", "EVENT", "前情标记",
                "已审阅前情协议标记", 1, 0, "HIGH");
        var turn = new PlayerIntentReplay.InitialTurn(1, "前次行动协议标记", "前次正文协议标记",
                mapper.createArrayNode(), mapper.createObjectNode(), 0, 0);
        var fixture = new PlayerIntentReplay.CaseFixture(base.caseId(), base.sourceEvidence(), state,
                "摘要协议标记", List.of(item, ability, character), List.of(memory), List.of(turn), base.action(), null);
        var result = run(fixture, new FakeModels(false));
        assertEquals("COMMITTED_IN_H2", result.status(), mapper.writeValueAsString(result));
        assertEquals(3, result.before().tables().get("story_entity").size());
        assertEquals("protocol-memory", result.before().tables().get("story_memory").getFirst().get("id"));
        assertTrue(result.after().tables().get("story_entity").stream().anyMatch(row -> "protocol-ability".equals(row.get("id"))));
        assertEquals(2, result.after().tables().get("story_turn").size());
        String context = mapper.writeValueAsString(result.stages().getFirst().path("context"));
        assertTrue(context.contains("protocol-ability"));
        assertTrue(context.contains("已审阅前情协议标记"));
        assertTrue(context.contains("前次行动协议标记"));
        assertTrue(context.contains("摘要协议标记"));
    }

    @Test
    void realEntryRejectsRemoteAndAmbiguousProxyUrlsOffline() {
        assertEquals("http://127.0.0.1:26001/v1", PlayerIntentReplayRealModelTest.requireLoopback("http://127.0.0.1:26001/v1"));
        for (String url : List.of("https://api.deepseek.com/v1", "http://localhost:26001/v1",
                "http://user@127.0.0.1:26001/v1", "http://127.0.0.1:26001/v1?next=remote",
                "http://127.0.0.1:26001/unreviewed", "http://127.0.0.1:80/v1"))
            assertThrows(IllegalArgumentException.class, () -> PlayerIntentReplayRealModelTest.requireLoopback(url));
    }

    @Test
    void preflightStopsAtDirectorAndReportsInvalidChoiceWithoutAChatModel() throws Exception {
        var host = new PlayerIntentReplay(mapper);
        var valid = host.preflight(fixture("preflight_valid", "观察当前场景后停下。", null));
        assertEquals("VALID_UNTIL_DIRECTOR", valid.status());
        assertNotNull(valid.directorContext());
        assertTrue(valid.loaded().tables().get("story_turn").isEmpty());
        var invalid = host.preflight(fixture("preflight_invalid", "观察当前场景后停下。", "absent-choice"));
        assertEquals("INVALID", invalid.status());
        assertFalse(invalid.errorDetail().isBlank());
        assertNull(invalid.directorContext());
    }

    @Test
    void genericPreflightValidityDoesNotGuaranteeOfficialCurrentStageMaterial() throws Exception {
        var host = new PlayerIntentReplay(mapper);
        var unbound = fixture("unbound_protocol", "观察当前场景后停下。", null);
        var first = host.preflight(unbound);
        assertEquals("VALID_UNTIL_DIRECTOR", first.status());
        JsonNode firstWorld = first.directorContext().path("worldContext");
        assertTrue(containsText(firstWorld.path("unavailableInputs"), "PROGRESSION_SYSTEM_NOT_BOUND_TO_PACKAGE"));
        assertTrue(containsText(firstWorld.path("unavailableInputs"), "CURRENT_STAGE_UNAVAILABLE"));
        assertEquals(0, stageMaterials(firstWorld).size());

        // 契约示例可以合法装载，但官方身份必须来自 realmBinding；不依赖名称、摘要或确认时间深度相等。
        var bound = boundFixture("bound_protocol");
        var second = host.preflight(bound);
        assertEquals("VALID_UNTIL_DIRECTOR", second.status());
        JsonNode secondWorld = second.directorContext().path("worldContext");
        assertFalse(containsText(secondWorld.path("unavailableInputs"), "PROGRESSION_SYSTEM_NOT_BOUND_TO_PACKAGE"));
        assertFalse(containsText(secondWorld.path("unavailableInputs"), "CURRENT_STAGE_UNAVAILABLE"));
        var material = stageMaterials(secondWorld).getFirst();
        String stageId = bound.initialState().path("trustedProjection").path("protagonistCultivation").path("currentStageId").asText();
        assertEquals(1, stageMaterials(secondWorld).size());
        assertEquals(stageId, material.path("ref").asText());
        assertEquals(stageId, material.path("data").path("stage").path("stageId").asText());
        assertFalse(material.path("data").path("stage").path("capabilityBoundary").asText().isBlank());
    }

    @Test
    void misplacedItemUsesOneRepairAndRepeatedFailureLeavesAuthorityUnchanged() throws Exception {
        // 技术反例仅保留实测错误的协议形状；不复制私人原件中的行动、人物、前情或模型正文。
        FakeModels models = new FakeModels(false, misplacedItemResponse());
        var result = run(boundFixture("wrong_asset_group"), models);
        assertEquals("FAILED", result.status());
        assertEquals("StoryTurnProtocolException", result.errorType());
        assertTrue(result.unchangedOnFailure());
        assertEquals(6, result.before().tables().size());
        assertEquals(result.before(), result.after());
        assertEquals(2, models.directorCalls.get(), "错误分组进入同一导演的一次修复，连续错误不能无限重试");
        assertEquals(0, models.writerCalls.get());
        assertEquals(2, result.stages().size());
        assertEquals("director", result.stages().getFirst().path("phase").asText());
        assertFalse(result.stages().getFirst().has("result"));
        assertNull(result.prepared());
        assertEquals("PREPARATION_FAILED", result.diagnostics().getLast().get("current_stage"));
        assertEquals("轻量导演结果整理后仍无法编译为可信事实", result.diagnostics().getLast().get("failure_detail"));
        JsonNode trace = (JsonNode) result.diagnostics().getLast().get("review_trace_json");
        assertEquals(2, trace.size());
        assertTrue(trace.get(0).path("problem").asText().contains("各自变化分组"));
        assertTrue(trace.get(1).path("problem").asText().contains("各自变化分组"));
        assertEquals("ADAPTATION", result.stages().getLast().path("rejectedPhase").asText());
        assertEquals("ITEM", result.stages().getLast().path("rejectedDraft").path("entities").get(0)
                .path("entityType").asText());
    }

    @Test
    void misplacedItemCanBeRepairedThroughTypedServicesWithoutLosingItsCallUsage() throws Exception {
        FakeModels models = new FakeModels(false, List.of(misplacedItemResponse(), DIRECTOR));
        var result = run(boundFixture("asset_group_repaired"), models);
        assertEquals("COMMITTED_IN_H2", result.status(), mapper.writeValueAsString(result));
        assertEquals(2, models.directorCalls.get());
        assertEquals(1, models.writerCalls.get());
        JsonNode repair = result.stages().get(1);
        assertEquals("ADAPTATION", repair.path("rejectedPhase").asText());
        assertEquals("ITEM", repair.path("rejectedDraft").path("entities").get(0).path("entityType").asText());
        JsonNode calls = result.prepared().path("generation").path("modelCalls");
        assertEquals(3, calls.size());
        assertEquals(31, calls.get(0).path("inputTokens").asInt());
        assertEquals(31, calls.get(1).path("inputTokens").asInt());
        assertEquals(19, calls.get(2).path("inputTokens").asInt());
        assertEquals("81", result.after().tables().get("story_turn").getFirst().get("prompt_tokens"));
        assertEquals(1, result.after().tables().get("story_turn").size());
        assertTrue(result.after().tables().get("story_entity").isEmpty(), "被拒绝草案中的物品不能泄漏到提交状态");
    }

    @Test
    void adaptationFailureAlsoReportsIndependentProcessErrorsWithinTheSameRepair() throws Exception {
        // 同一草案两个独立格式错误必须一起反馈；固定修复响应只验证协议预算，不证明模型语义。
        ObjectNode response = (ObjectNode) mapper.readTree(DIRECTOR);
        response.withArray("itemChanges").add(mapper.readTree("""
                {"localRef":"test-item","name":"协议物品",
                 "attributes":[{"key":"quantityUnit","value":"份"}],"visible":true}
                """));
        response.set("assetProcess", mapper.readTree("""
                {"steps":[{"kind":"CONSUME","summary":"格式反例",
                 "inputs":[{"entityRef":"test-item","amount":"ALL+1",
                            "custody":{"holderType":"PLAYER"}}],"outputs":[],"observations":[]}]}
                """));
        FakeModels models = new FakeModels(false, List.of(mapper.writeValueAsString(response), DIRECTOR));
        var result = run(boundFixture("combined_shape_repair"), models);
        assertEquals("COMMITTED_IN_H2", result.status(), mapper.writeValueAsString(result));
        String problem = result.stages().get(1).path("problem").asText();
        assertTrue(problem.contains("itemChanges"), problem);
        assertTrue(problem.contains("assetProcess.steps[0].inputs[0].amount"), problem);
        assertTrue(problem.contains("quantityUnit"), problem);
        assertEquals(2, models.directorCalls.get());
        assertEquals(1, models.writerCalls.get());
        assertEquals(3, result.prepared().path("generation").path("modelCalls").size());
    }

    @Test
    void oldItemStateFieldsCannotSilentlyPassThroughTheNarrowTypedContract() throws Exception {
        // 保留原 metadata 数量/保管反例；新版类型协议应在解码层拒绝，而不是丢弃字段后伪装接受。
        ObjectNode response = (ObjectNode) mapper.readTree(DIRECTOR);
        response.withArray("itemChanges").add(mapper.readTree("""
                {"localRef":"test-item","name":"协议物品","visible":true,
                 "quantity":{"known":true,"value":1},"custody":{"holderType":"PLAYER"}}
                """));
        FakeModels models = new FakeModels(false, List.of(mapper.writeValueAsString(response), DIRECTOR));
        var result = run(boundFixture("old_item_shape_repair"), models);
        assertEquals("COMMITTED_IN_H2", result.status(), mapper.writeValueAsString(result));
        assertEquals(2, models.directorCalls.get());
        assertEquals(1, models.writerCalls.get());
        assertEquals("DECODING", result.stages().get(1).path("rejectedPhase").asText());
        assertEquals(response, mapper.readTree(result.stages().get(1).path("rejectedDraft").asText()),
                "原样交回失败文本，不让旧字段绕过现有类型和资产协议");
        assertTrue(result.after().tables().get("story_entity").isEmpty(), "旧字段不能绕过资产过程写入物品状态");
        assertEquals(1, result.after().tables().get("story_turn").size());
    }

    @Test
    void protocolRepairFromMalformedJsonReceivesOriginalTextAndKnownUsageThenCommitsOnce() throws Exception {
        FakeModels models = new FakeModels(false, List.of("{malformed", DIRECTOR));
        var result = run(boundFixture("decode_repair"), models);
        assertEquals("COMMITTED_IN_H2", result.status(), mapper.writeValueAsString(result));
        assertEquals(2, models.directorCalls.get());
        assertEquals(1, models.writerCalls.get());
        assertEquals(1, result.after().tables().get("story_turn").size());
        JsonNode repair = result.stages().get(1);
        assertEquals("DECODING", repair.path("rejectedPhase").asText());
        assertEquals("{malformed", repair.path("rejectedDraft").asText(), "本次响应事件保留原文，不从异常文字伪造原件");
        JsonNode calls = result.prepared().path("generation").path("modelCalls");
        assertEquals(3, calls.size());
        assertEquals("FAILED", calls.get(0).path("status").asText());
        assertEquals(31, calls.get(0).path("inputTokens").asInt(), "已收到完整供应商响应时保留真实用量，解析失败仍算FAILED");
        assertEquals(17, calls.get(0).path("outputTokens").asInt());
        assertEquals(31, calls.get(1).path("inputTokens").asInt());
        assertEquals(19, calls.get(2).path("inputTokens").asInt());
    }

    private String misplacedItemResponse() throws Exception {
        ObjectNode response = (ObjectNode) mapper.readTree(DIRECTOR);
        response.withArray("entities").add(mapper.readTree("""
                {"existingEntityId":"","localRef":"protocol-item","entityType":"ITEM",
                 "name":"协议物品","status":"已确认","summary":"离线分组反例","scenePresence":"",
                 "attributes":[],"quantity":{"known":true,"value":1},
                 "custody":{"holderType":"PLAYER","holderRef":null,"localHolderRef":null},
                 "abilityState":null,"lifecycle":null,"relationship":null,"visible":true}
                """));
        return mapper.writeValueAsString(response);
    }

    private PlayerIntentReplay.CaseFixture boundFixture(String id) throws Exception {
        var base = fixture(id, "观察当前场景后停下。", null);
        ObjectNode state = base.initialState().deepCopy();
        var trusted = state.path("trustedProjection");
        String scaleId = trusted.path("creationContract").path("realmBinding").path("scaleId").asText();
        ((ObjectNode) trusted.path("cultivationSystem")).put("systemId", scaleId);
        ((ObjectNode) trusted.path("protagonistCultivation")).put("systemId", scaleId);
        return new PlayerIntentReplay.CaseFixture(base.caseId(), base.sourceEvidence(), state, base.initialSummary(),
                base.entities(), base.memories(), base.history(), base.action(), base.choiceId());
    }

    private boolean containsText(JsonNode array, String expected) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false).anyMatch(value -> expected.equals(value.asText()));
    }

    private List<JsonNode> stageMaterials(JsonNode world) {
        return java.util.stream.StreamSupport.stream(world.path("materials").spliterator(), false)
                .filter(value -> "PROGRESSION_STAGE".equals(value.path("kind").asText())).toList();
    }

    private PlayerIntentReplay.CaseFixture fixture(String id, String action, String choiceId) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("contracts/story-state/story-state-v2.json")) {
            assertNotNull(stream);
            return new PlayerIntentReplay.CaseFixture(id, mapper.createObjectNode().put("kind", "OFFLINE_PROTOCOL_FIXTURE"),
                    mapper.readTree(stream), "", List.of(), List.of(), List.of(), action, choiceId);
        }
    }

    private PlayerIntentReplay.ReplayResult run(PlayerIntentReplay.CaseFixture fixture, FakeModels models) {
        return new PlayerIntentReplay(mapper).run(fixture, PlayerIntentReplay.generator(mapper, models.factory(),
                "http://127.0.0.1:26001/v1", "offline-placeholder"));
    }

    private static final class FakeModels {
        private final AtomicInteger directorCalls = new AtomicInteger();
        private final AtomicInteger writerCalls = new AtomicInteger();
        private final boolean writerFails;
        private final List<String> directorResponses;
        private FakeModels(boolean writerFails) { this(writerFails, DIRECTOR); }
        private FakeModels(boolean writerFails, String directorResponse) {
            this(writerFails, List.of(directorResponse));
        }
        private FakeModels(boolean writerFails, List<String> directorResponses) {
            this.writerFails = writerFails;
            this.directorResponses = List.copyOf(directorResponses);
        }

        private ChatModelFactory factory() {
            return (definition, credential, options) -> {
                assertEquals(0, options.maximumRetries());
                boolean director = options.maximumOutputTokens() == 4000;
                return new ChatModel() {
                    @Override public ChatResponse doChat(ChatRequest request) {
                        assertTrue(request.messages().size() >= 2, "formal prompt and typed user payload must be present");
                        String response;
                        if (director) {
                            int call = directorCalls.incrementAndGet();
                            response = directorResponses.get(Math.min(call - 1, directorResponses.size() - 1));
                        }
                        else {
                            writerCalls.incrementAndGet();
                            if (writerFails) throw new IllegalStateException("offline writer failure");
                            response = WRITER;
                        }
                        return ChatResponse.builder().aiMessage(AiMessage.from(response))
                                .tokenUsage(director ? new TokenUsage(31, 17) : new TokenUsage(19, 11)).build();
                    }
                };
            };
        }
    }
}
