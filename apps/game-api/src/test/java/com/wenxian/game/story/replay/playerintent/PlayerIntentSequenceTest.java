package com.wenxian.game.story.replay.playerintent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.model.infrastructure.ChatModelFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 连续宿主的离线协议验证：使用正式类型化 AI Service 与进程内假模型，核对提交重载及实际模型输入。
 * 固定资产仅检验宿主不从正文改账；不以这些断言替代交易、炼制规则或真实模型能力验收。
 */
class PlayerIntentSequenceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private static final String DIRECTOR = """
            {
              "duration": {
                "unit": "DAY",
                "value": 1,
                "targetPhase": ""
              },
              "timeResolution": "COMPLETE",
              "interruptionReason": "",
              "coreState": null,
              "cultivation": null,
              "itemChanges": [],
              "abilityChanges": [],
              "entities": [],
              "worldFacts": [],
              "assetProcess": {
                "steps": []
              },
              "actionUnderstanding": {
                "purpose": "整理后休息",
                "constraints": [
                  "不买卖或消耗物品"
                ]
              },
              "orderedResult": {
                "steps": [
                  {
                    "action": "整理后休息",
                    "resolution": "SUCCEEDED",
                    "result": "整理行囊后在原处休息。",
                    "realmProgressBinding": false,
                    "assetStepIndexes": [],
                    "dependsOn": [],
                    "statements": []
                  }
                ],
                "stop": {
                  "reason": "ACTION_RESOLVED",
                  "detail": "整理行囊后在原处休息。"
                }
              }
            }
            """;
    // 故意污染表现层，验证宿主不会把正文中的 999 文写回数据库或下一轮 objective。
    private static final String WRITER = """
            {"title":"离线表现层反例","narrative":"你休息一日，手中竟有九百九十九文铜钱。",
             "choices":[{"title":"停下","description":"不再行动。"}],"summary":"表现层声称拥有999文，尚未改变权威库存。"}
            """;

    @Test
    void reopensFromPreviousCommitAndSendsStoredInventoryToActualWriterRequest() throws Exception {
        var fixture = fixture();
        var models = new OfflineModels(0);
        List<PlayerIntentReplay.SequenceTurn> checkpoints = new ArrayList<>();
        var result = new PlayerIntentReplay(mapper).runSequence(fixture, plan(fixture, 1),
                generator(models), checkpoints::add);
        assertEquals("COMMITTED_IN_H2", result.status(), mapper.writeValueAsString(result));
        assertEquals(2, result.turns().size());
        assertEquals(result.turns(), checkpoints);
        var first = result.turns().getFirst();
        var second = result.turns().getLast();
        assertEquals(first.result().after(), second.result().before(), "第二轮从同一 H2 已提交数据开始");
        assertEquals(second.result().after(), result.finalAuthority());
        assertEquals(PlayerIntentReplay.InputOrigin.REVIEWED_INITIAL, first.inputOrigin());
        assertEquals(PlayerIntentReplay.InputOrigin.ACCEPTANCE_FOLLOWUP, second.inputOrigin());
        assertNotEquals(first.requestId(), second.requestId());
        assertEquals(2, models.directorRequests.size());
        assertEquals(2, models.writerRequests.size());
        assertEquals("3", result.finalAuthority().tables().get("story_save").getFirst().get("revision"));
        assertEquals(2, result.finalAuthority().tables().get("story_turn").size());
        assertEquals(1, second.result().diagnostics().size(), "每轮诊断不混入上一轮条目");
        assertEquals(second.requestId(), second.result().stages().getFirst().path("invocation").path("requestId").asText());
        JsonNode savedState = mapper.readTree(second.result().before().tables().get("story_save").getFirst().get("state_json"));
        assertEquals(savedState.path("worldTime"), models.directorRequests.getLast().path("objective").path("worldTime"));
        assertEquals(savedState.path("worldTime"), models.writerRequests.getLast().path("beforeState").path("worldTime"));
        assertEquals(7, storedCoins(second.result().before()));
        assertEquals(7, inventoryCoins(models.directorRequests.getLast().path("objective").path("currentInventory").path("items")));
        assertEquals(7, inventoryCoins(models.writerRequests.getLast().path("beforeState").path("currentInventory").path("items")));
        assertEquals(7, inventoryCoins(models.writerRequests.getLast().path("endState").path("inventory")));
        assertEquals(second.result().stages().getLast().path("endState"), models.writerRequests.getLast().path("endState"));
        assertTrue(models.directorRequests.getLast().toString().contains("999"), "污染正文确已作为表现层前情进入下一轮");
        assertEquals(7, storedCoins(result.finalAuthority()), "宿主没有根据盘点正文补造资产");
        var row = result.finalAuthority().tables().get("story_turn").stream()
                .filter(value -> second.requestId().equals(value.get("request_id"))).findFirst().orElseThrow();
        assertEquals(second.action(), row.get("player_action"));
    }

    @Test
    void failureInSecondTurnKeepsFirstCommitAndStopsBeforeThirdAction() throws Exception {
        var fixture = fixture();
        var models = new OfflineModels(2);
        var result = new PlayerIntentReplay(mapper).runSequence(fixture, plan(fixture, 2), generator(models));
        assertEquals("STOPPED_ON_FAILURE", result.status());
        assertEquals(3, result.plannedTurns());
        assertEquals(2, result.turns().size());
        var first = result.turns().getFirst().result();
        var failed = result.turns().getLast().result();
        assertEquals("COMMITTED_IN_H2", first.status());
        assertEquals("FAILED", failed.status());
        assertTrue(failed.unchangedOnFailure());
        assertEquals(first.after(), failed.before());
        assertEquals(failed.before(), failed.after());
        assertEquals(first.after(), result.finalAuthority());
        assertEquals(1, result.finalAuthority().tables().get("story_turn").size());
        assertEquals(2, models.directorRequests.size());
        assertEquals(2, models.writerRequests.size());
    }

    @Test
    void choosesEachFollowupFromTheCommittedTurnAfterItsEvidenceIsSaved() throws Exception {
        var fixture = fixture();
        var models = new OfflineModels(0);
        List<String> order = new ArrayList<>();
        List<PlayerIntentReplay.SequenceTurn> observed = new ArrayList<>();
        List<String> actions = List.of("核对行囊后休息，不买卖或消耗物品。", "保持原地，安静休息，不作交易。");
        var result = new PlayerIntentReplay(mapper).runSequence(fixture, "chosen_sequence", 3,
                generator(models), turn -> order.add("saved-" + turn.ordinal()), turn -> {
                    assertEquals("saved-" + turn.ordinal(), order.getLast(), "先保存本轮证据，再允许选择行动");
                    assertEquals("COMMITTED_IN_H2", turn.result().status());
                    var saved = turn.result().after().tables().get("story_turn").stream()
                            .filter(row -> turn.requestId().equals(row.get("request_id"))).findFirst().orElseThrow();
                    assertEquals(turn.action(), saved.get("player_action"));
                    assertTrue(saved.get("narrative_text").contains("九百九十九文"), "回调可读取真实已提交正文，而不是仅有候选");
                    int coins = assertDoesNotThrow(() -> storedCoins(turn.result().after()));
                    assertEquals(7, coins, "同时交付权威库存，不从正文补账");
                    observed.add(turn);
                    order.add("chosen-" + turn.ordinal());
                    return new PlayerIntentReplay.FollowupAction(actions.get(turn.ordinal() - 1), null);
                });
        assertEquals("COMMITTED_IN_H2", result.status());
        assertEquals(List.of("saved-1", "chosen-1", "saved-2", "chosen-2", "saved-3"), order,
                "达到上限后不再询问下一条行动");
        assertEquals(result.turns().subList(0, 2), observed);
        assertEquals(3, result.turns().size());
        for (int index = 1; index < result.turns().size(); index++) {
            var previous = result.turns().get(index - 1);
            var current = result.turns().get(index);
            assertEquals(previous.result().after(), current.result().before());
            assertEquals(actions.get(index - 1), current.action());
            assertEquals(current.action(), models.directorRequests.get(index).path("action").path("playerAction").asText());
        }
        assertEquals(result.turns().getLast().result().after(), result.finalAuthority());
        assertEquals(3, models.directorRequests.size());
        assertEquals(3, models.writerRequests.size());
    }

    @Test
    void failedChosenTurnIsSavedAsEvidenceWithoutChoosingOrCallingAnotherTurn() throws Exception {
        var fixture = fixture();
        var models = new OfflineModels(2);
        List<PlayerIntentReplay.SequenceTurn> evidence = new ArrayList<>();
        AtomicInteger selections = new AtomicInteger();
        var result = new PlayerIntentReplay(mapper).runSequence(fixture, "chosen_failure", 5,
                generator(models), evidence::add, turn -> {
                    selections.incrementAndGet();
                    assertEquals("COMMITTED_IN_H2", turn.result().status());
                    return new PlayerIntentReplay.FollowupAction("留在原处休息，不作交易。", null);
                });
        assertEquals("STOPPED_ON_FAILURE", result.status());
        assertEquals(5, result.plannedTurns());
        assertEquals(2, result.turns().size());
        assertEquals(result.turns(), evidence);
        assertEquals(1, selections.get());
        assertTrue(result.turns().getLast().result().unchangedOnFailure());
        assertEquals(result.turns().getFirst().result().after(), result.finalAuthority());
        assertEquals(2, models.directorRequests.size());
        assertEquals(2, models.writerRequests.size());
    }

    @Test
    void nullFollowupEndsNormallyWithoutAnExtraModelCall() throws Exception {
        var fixture = fixture();
        var models = new OfflineModels(0);
        AtomicInteger selections = new AtomicInteger();
        var result = new PlayerIntentReplay(mapper).runSequence(fixture, "chosen_stop", 5,
                generator(models), ignored -> { }, turn -> {
                    selections.incrementAndGet();
                    return null;
                });
        assertEquals("COMMITTED_IN_H2", result.status());
        assertEquals(5, result.plannedTurns(), "记录批次上限，不把上限冒充已执行回合数");
        assertEquals(1, result.turns().size());
        assertEquals(1, selections.get());
        assertEquals(result.turns().getFirst().result().after(), result.finalAuthority());
        assertEquals(1, models.directorRequests.size());
        assertEquals(1, models.writerRequests.size());
    }

    @Test
    void evidenceCallbackFailureStopsBeforeFurtherModelInvocations() throws Exception {
        var fixture = fixture();
        var models = new OfflineModels(0);
        assertThrows(IllegalStateException.class, () -> new PlayerIntentReplay(mapper).runSequence(
                fixture, plan(fixture, 1), generator(models), turn -> {
                    throw new IllegalStateException("offline evidence sink failed");
                }));
        assertEquals(1, models.directorRequests.size());
        assertEquals(1, models.writerRequests.size());
    }

    @Test
    void eitherCallbackFailureStopsTheChosenSequenceBeforeAnyFurtherModelCall() throws Exception {
        var fixture = fixture();
        var evidenceFailure = new OfflineModels(0);
        AtomicInteger selections = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> new PlayerIntentReplay(mapper).runSequence(
                fixture, "chosen_evidence_failure", 5, generator(evidenceFailure), turn -> {
                    throw new IllegalStateException("offline evidence sink failed");
                }, turn -> {
                    selections.incrementAndGet();
                    return new PlayerIntentReplay.FollowupAction("休息。", null);
                }));
        assertEquals(0, selections.get());
        assertEquals(1, evidenceFailure.directorRequests.size());
        assertEquals(1, evidenceFailure.writerRequests.size());

        var selectionFailure = new OfflineModels(0);
        AtomicInteger saved = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> new PlayerIntentReplay(mapper).runSequence(
                fixture, "chosen_input_failure", 5, generator(selectionFailure), turn -> saved.incrementAndGet(), turn -> {
                    throw new IllegalStateException("offline input could not be read");
                }));
        assertEquals(1, saved.get());
        assertEquals(1, selectionFailure.directorRequests.size());
        assertEquals(1, selectionFailure.writerRequests.size());
    }

    @Test
    void validatesContinuationBoundAndInitialCaseIdentityBeforeAnyModelCall() throws Exception {
        var fixture = fixture();
        var models = new OfflineModels(0);
        assertThrows(IllegalArgumentException.class, () -> new PlayerIntentReplay(mapper).runSequence(fixture,
                new PlayerIntentReplay.ContinuationPlan("mismatch", "other_case", List.of()), generator(models)));
        assertThrows(IllegalArgumentException.class, () -> plan(fixture, 6));
        assertThrows(IllegalArgumentException.class, () -> new PlayerIntentReplay.FollowupAction(" ", null));
        for (int maxTurns : List.of(0, 7)) {
            assertThrows(IllegalArgumentException.class, () -> new PlayerIntentReplay(mapper).runSequence(
                    fixture, "chosen_invalid_limit", maxTurns, generator(models), ignored -> { }, ignored -> null));
        }
        assertEquals(0, models.directorRequests.size() + models.writerRequests.size());
    }

    @Test
    void separateAcceptancePlansReferenceUnmodifiedHistoricalFixtures() throws Exception {
        for (String name : List.of("C10_spend_sale_income", "C11_check_remaining_medicine")) {
            try (var stream = getClass().getClassLoader().getResourceAsStream(
                    "story/replay/player-intent-followups/" + name + ".json")) {
                assertNotNull(stream);
                var plan = mapper.readValue(stream, PlayerIntentReplay.ContinuationPlan.class);
                assertEquals(1, plan.followupActions().size());
                assertNull(plan.followupActions().getFirst().choiceId());
                try (var initial = getClass().getClassLoader().getResourceAsStream(
                        "story/replay/player-intent/" + plan.initialCaseId() + ".json")) {
                    assertNotNull(initial);
                    assertEquals(plan.initialCaseId(), mapper.readValue(initial, PlayerIntentReplay.CaseFixture.class).caseId());
                }
            }
        }
    }

    private PlayerIntentReplay.ContinuationPlan plan(PlayerIntentReplay.CaseFixture fixture, int count) {
        return new PlayerIntentReplay.ContinuationPlan("offline_sequence", fixture.caseId(),
                java.util.stream.IntStream.range(0, count).mapToObj(index ->
                        new PlayerIntentReplay.FollowupAction("整理后休息，不买卖或消耗物品。", null)).toList());
    }

    private PlayerIntentReplay.CaseFixture fixture() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("contracts/story-state/story-state-v2.json")) {
            assertNotNull(stream);
            ObjectNode state = (ObjectNode) mapper.readTree(stream);
            var trusted = state.path("trustedProjection");
            String scale = trusted.path("creationContract").path("realmBinding").path("scaleId").asText();
            ((ObjectNode) trusted.path("cultivationSystem")).put("systemId", scale);
            ((ObjectNode) trusted.path("protagonistCultivation")).put("systemId", scale);
            var coins = new PlayerIntentReplay.InitialEntity("offline-coins", "ITEM", "铜钱", "随身携带", true,
                    "离线已确认库存七文", mapper.readTree("""
                    {"_trusted":{"quantity":{"known":true,"value":7},"custody":{"holderType":"PLAYER","holderRef":null}}}
                    """), 0, 0);
            return new PlayerIntentReplay.CaseFixture("offline_initial", mapper.createObjectNode()
                    .put("kind", "OFFLINE_HOST_PROTOCOL"), state, "", List.of(coins), List.of(), List.of(),
                    "整理后休息，不买卖或消耗物品。", null);
        }
    }

    private int storedCoins(PlayerIntentReplay.Authority state) throws Exception {
        var coins = state.tables().get("story_entity").stream().filter(row -> "offline-coins".equals(row.get("id")))
                .findFirst().orElseThrow();
        return mapper.readTree(coins.get("attributes_json")).path("_trusted").path("quantity").path("value").asInt(-1);
    }

    private int inventoryCoins(JsonNode inventory) {
        for (JsonNode entity : inventory) {
            if ("offline-coins".equals(entity.path("id").asText())) return entity.path("quantity").path("value").asInt(-1);
        }
        fail("actual request lacks stored coin inventory: " + inventory);
        return -1;
    }

    private com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator generator(OfflineModels models) {
        return PlayerIntentReplay.generator(mapper, models.factory(), "http://127.0.0.1:26001/v1", "offline-placeholder");
    }

    private final class OfflineModels {
        private final List<JsonNode> directorRequests = new ArrayList<>();
        private final List<JsonNode> writerRequests = new ArrayList<>();
        private final int failWriterCall;
        private final AtomicInteger writerCalls = new AtomicInteger();

        private OfflineModels(int failWriterCall) { this.failWriterCall = failWriterCall; }

        private ChatModelFactory factory() {
            return (definition, credential, options) -> new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    boolean director = options.maximumOutputTokens() == 4000;
                    try {
                        UserMessage message = request.messages().stream().filter(UserMessage.class::isInstance)
                                .map(UserMessage.class::cast).reduce((before, next) -> next).orElseThrow();
                        JsonNode payload = mapper.readTree(message.singleText());
                        (director ? directorRequests : writerRequests).add(payload);
                    } catch (java.io.IOException error) {
                        throw new IllegalStateException("typed service did not send JSON payload", error);
                    }
                    if (!director && writerCalls.incrementAndGet() == failWriterCall)
                        throw new IllegalStateException("offline second writer failure");
                    return ChatResponse.builder().aiMessage(AiMessage.from(director ? DIRECTOR : WRITER)).build();
                }
            };
        }
    }
}
