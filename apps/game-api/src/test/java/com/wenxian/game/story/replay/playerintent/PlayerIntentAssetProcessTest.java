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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 正式类型化链路的资产交接验收；固定成交价只属于离线样本，不代表游戏定价规则。 */
class PlayerIntentAssetProcessTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void saleIncomeSurvivesReloadAndPaysForNextTurnWithTheSameAcceptedProcess() throws Exception {
        Models models = new Models(false);
        var result = run(models);
        assertEquals("COMMITTED_IN_H2", result.status(), mapper.writeValueAsString(result));
        var first = result.turns().getFirst().result();
        var second = result.turns().getLast().result();
        assertEquals(first.after(), second.before());
        assertEquals(3, quantity(first.before(), "青叶草"));
        assertEquals(2, quantity(first.before(), "紫根"));
        assertEquals(0, quantity(first.after(), "青叶草"));
        assertEquals(0, quantity(first.after(), "紫根"));
        assertEquals(50, quantity(first.after(), "铜钱"));
        assertEquals(38, quantity(second.after(), "铜钱"));
        assertEquals("文", mapper.readTree(entity(first.after(), "铜钱").get("attributes_json")).path("quantityUnit").asText());
        assertEquals("文", mapper.readTree(entity(second.after(), "铜钱").get("attributes_json")).path("quantityUnit").asText());
        assertEquals("文", models.writers.getLast().path("endState").path("assetProcess").path("steps").get(0)
                .path("inputs").get(0).path("unit").asText(), "后轮省略单位时继承已提交口径，不能遗失或另定单位");
        assertEquals(1, quantity(second.after(), "干粮"));
        assertEquals(entity(first.before(), "赤茎"), entity(second.after(), "赤茎"), "代管物品不能跟随出售");
        assertEquals(entity(first.after(), "铜钱").get("id"), models.coinId);
        assertNotEquals("sale-coins", models.coinId, "下一轮必须使用数据库分配的正式ID");
        assertEquals(2, models.directors);
        assertEquals(2, models.writers.size());
        for (int index = 0; index < result.turns().size(); index++) {
            var turn = result.turns().get(index);
            var row = turn.result().after().tables().get("story_turn").stream()
                    .filter(value -> turn.requestId().equals(value.get("request_id"))).findFirst().orElseThrow();
            JsonNode stored = mapper.readTree(row.get("state_patch_json"));
            JsonNode process = models.writers.get(index).path("endState").path("assetProcess");
            assertEquals(process, stored.path("assetProcess"), "正文交付与事务保存使用同一已接受过程");
            var ids = turn.result().after().tables().get("story_entity").stream().map(value -> value.get("id")).toList();
            process.findValuesAsText("entityRef").forEach(ref -> assertTrue(ids.contains(ref), "临时资产引用泄漏：" + ref));
            var events = stored.path("trustedEvents");
            var assetEvents = java.util.stream.StreamSupport.stream(events.spliterator(), false)
                    .filter(event -> "ASSET_PROCESS_STEP".equals(event.path("type").asText())).toList();
            assertEquals(1, assetEvents.size());
            assertEquals(process.path("steps").get(0), assetEvents.getFirst().path("step"));
            assertFalse(events.toString().contains("ITEM_CONSUMED"), "卖出不能从净余量被误记为消耗");
        }
    }

    @Test
    void secondWriterFailureLeavesSaleIncomeAndAllOtherCommittedFactsUntouched() throws Exception {
        Models models = new Models(true);
        var result = run(models);
        assertEquals("STOPPED_ON_FAILURE", result.status());
        var first = result.turns().getFirst().result();
        var failed = result.turns().getLast().result();
        assertEquals("COMMITTED_IN_H2", first.status());
        assertEquals("FAILED", failed.status());
        assertTrue(failed.unchangedOnFailure());
        assertEquals(first.after(), failed.before());
        assertEquals(first.after(), result.finalAuthority());
        assertEquals(50, quantity(result.finalAuthority(), "铜钱"));
        assertTrue(result.finalAuthority().tables().get("story_entity").stream().noneMatch(row -> "干粮".equals(row.get("name"))));
        assertTrue(java.util.stream.StreamSupport.stream(models.writers.getLast().path("endState").path("inventory").spliterator(), false)
                .anyMatch(item -> models.coinId.equals(item.path("id").asText()) && item.path("quantity").path("value").asInt() == 38));
        assertEquals(2, models.directors);
        assertEquals(2, models.writers.size());
    }

    @Test
    void receivingIntoHiddenEmptyStackRestoresItBeforeWriterAndPreservesCustodyAfterReload() throws Exception {
        Models models = new Models(false, true);
        var result = run(models);
        assertEquals("COMMITTED_IN_H2", result.status(), mapper.writeValueAsString(result));
        var first = result.turns().getFirst().result();
        var second = result.turns().getLast().result();
        assertEquals(0, quantity(first.before(), "铜钱"));
        assertFalse(Boolean.parseBoolean(entity(first.before(), "铜钱").get("visible")));
        assertEquals(first.after(), second.before());
        assertEquals("offline-hidden-coins", models.coinId);
        assertEquals(50, quantity(first.after(), "铜钱"));
        assertEquals(38, quantity(second.after(), "铜钱"));
        assertTrue(Boolean.parseBoolean(entity(second.after(), "铜钱").get("visible")));
        JsonNode attributes = mapper.readTree(entity(second.after(), "铜钱").get("attributes_json"));
        assertEquals("PLAYER", attributes.path("_trusted").path("custody").path("holderType").asText());
        assertEquals("文", attributes.path("quantityUnit").asText());
        assertTrue(java.util.stream.StreamSupport.stream(models.writers.getFirst().path("endState").path("inventory").spliterator(), false)
                .anyMatch(item -> models.coinId.equals(item.path("id").asText()) && item.path("quantity").path("value").asInt() == 50));
    }

    private PlayerIntentReplay.SequenceResult run(Models models) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("story/replay/player-intent/C10_sell_all_herbs_bound.json")) {
            assertNotNull(stream);
            var fixture = mapper.readValue(stream, PlayerIntentReplay.CaseFixture.class);
            if (models.restoreHidden) {
                var entities = new ArrayList<>(fixture.entities());
                entities.add(new PlayerIntentReplay.InitialEntity("offline-hidden-coins", "ITEM", "铜钱", "已用完", false,
                        "离线空旧栈", mapper.readTree("""
                        {"quantityUnit":"文","_trusted":{"quantity":{"known":true,"value":0},"custody":{"holderType":"NONE","holderRef":null}}}
                        """), 0, 1));
                fixture = new PlayerIntentReplay.CaseFixture("offline_restore_income", mapper.createObjectNode()
                        .put("kind", "OFFLINE_HIDDEN_STACK_REGRESSION"), fixture.initialState(), fixture.initialSummary(), entities,
                        fixture.memories(), fixture.history(), "把所有自有草药全部卖出，收下换来的铜钱。", null);
            }
            var plan = new PlayerIntentReplay.ContinuationPlan("offline_asset_exchange", fixture.caseId(),
                    List.of(new PlayerIntentReplay.FollowupAction("用刚收到的铜钱花十二文买一包干粮，然后停下。", null)));
            return new PlayerIntentReplay(mapper).runSequence(fixture, plan, PlayerIntentReplay.generator(
                    mapper, models.factory(), "http://127.0.0.1:26001/v1", "offline-placeholder"));
        }
    }

    private Map<String, String> entity(PlayerIntentReplay.Authority authority, String name) {
        return authority.tables().get("story_entity").stream().filter(row -> name.equals(row.get("name"))).findFirst().orElseThrow();
    }

    private int quantity(PlayerIntentReplay.Authority authority, String name) throws Exception {
        return mapper.readTree(entity(authority, name).get("attributes_json")).path("_trusted").path("quantity").path("value").asInt(-1);
    }
    private final class Models {
        private final boolean failSecondWriter;
        private final boolean restoreHidden;
        private final List<JsonNode> writers = new ArrayList<>();
        private int directors;
        private String coinId;
        private Models(boolean failSecondWriter) { this(failSecondWriter, false); }
        private Models(boolean failSecondWriter, boolean restoreHidden) {
            this.failSecondWriter = failSecondWriter; this.restoreHidden = restoreHidden;
        }
        private ChatModelFactory factory() {
            return (definition, credential, options) -> new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    try {
                        String text = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                                .reduce((previous, next) -> next).orElseThrow().singleText();
                        JsonNode payload = mapper.readTree(text);
                        if (options.maximumOutputTokens() == 4000) return ChatResponse.builder()
                                .aiMessage(AiMessage.from(director(payload, ++directors))).build();
                        writers.add(payload);
                        if (failSecondWriter && writers.size() == 2) throw new IllegalStateException("offline second writer failure");
                        return ChatResponse.builder().aiMessage(AiMessage.from("""
                                {"title":"交易已结","narrative":"你完成这笔交易，查验所得后停下。","choices":[],"summary":"完成交易并停下。"}
                                """)).build();
                    } catch (java.io.IOException error) { throw new IllegalStateException(error); }
                }
            };
        }
        private String director(JsonNode payload, int turn) throws java.io.IOException {
            assertTrue(turn <= 2, "合法资产过程不应触发导演修复");
            if (turn == 2) for (JsonNode item : payload.path("objective").path("currentInventory").path("items")) {
                if ("铜钱".equals(item.path("name").asText())) {
                    coinId = item.path("id").asText();
                    assertEquals(50, item.path("quantity").path("value").asInt());
                    assertEquals("文", item.path("quantityUnit").asText(), "下一轮导演索引必须携带已提交计数单位");
                }
            }
            if (turn == 2) assertNotNull(coinId, "必须从重新加载的真实导演上下文读取收入ID");
            if (turn == 1 && restoreHidden) assertTrue(java.util.stream.StreamSupport.stream(
                    payload.path("objective").path("currentInventory").path("historicalItems").spliterator(), false)
                    .anyMatch(item -> "offline-hidden-coins".equals(item.path("id").asText())));
            ObjectNode body = (ObjectNode) mapper.readTree("""
                    {"duration":{"unit":"SCENE","value":1},
                     "timeResolution":"COMPLETE","itemChanges":[],"abilityChanges":[],"entities":[],"worldFacts":[],
                     "actionUnderstanding":{"purpose":"完成指定交易","constraints":["不动他人财物"]},
                     "orderedResult":{"steps":[{"action":"完成指定交易","resolution":"SUCCEEDED",
                       "result":"双方结清交易后停下。","realmProgressBinding":false,"assetStepIndexes":[0],"dependsOn":[],"statements":[]}],
                       "stop":{"reason":"ACTION_RESOLVED","detail":"钱货结清后停下"}}}
                    """);
            String ref = turn == 1 ? (restoreHidden ? "offline-hidden-coins" : "sale-coins") : "bought-food";
            if (turn != 1 || !restoreHidden) body.withArray("itemChanges").addObject().put("localRef", ref)
                    .put("name", turn == 1 ? "铜钱" : "干粮").put("summary", "本轮交易所得").put("visible", true);
            List<ObjectNode> inputs = turn == 1 ? List.of(flow("entity:case:herba", 3, "份"), flow("entity:case:herbb", 2, "份")) : List.of(flow(coinId, 12, ""));
            body.set("assetProcess", mapper.valueToTree(Map.of("steps", List.of(Map.of(
                    "kind", "EXCHANGE", "summary", "双方钱货已结清", "inputs", inputs,
                    "outputs", List.of(flow(ref, turn == 1 ? 50 : 1, turn == 1 ? "文" : "包")), "observations", List.of())))));
            return mapper.writeValueAsString(body);
        }
    }
    private ObjectNode flow(String ref, int amount, String unit) {
        return mapper.valueToTree(Map.of("entityRef", ref, "amount", Integer.toString(amount), "unit", unit, "custody", Map.of("holderType", "PLAYER")));
    }
}
