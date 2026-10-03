package com.wenxian.game.story.replay.playerintent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.model.infrastructure.ChatModelFactory;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator;
import com.wenxian.game.story.replay.slim.SlimNarrativeGenerator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 精简模型合同的隔离 H2 对照：两种真实类型化接口使用相同固定故事结果，经过正式准备、归并和提交。
 * 检查教学、能力用途、书册和下一轮重载；另验证候选违反余额、正文失败和提交中断时不留下半份经历。
 * 固定响应只证明接线与事务等价，不代表模型生成质量、真人体验或正式额度门禁已经通过。
 */
class SlimNarrativeReplayTest {
    private static final String ABILITY = "松息口诀";
    private static final String BOOK = "松息口诀抄本";
    private static final String TEACHING = "先松开肩背，再放慢呼气；只能辅助调息，不能用来攻伐。";
    private static final List<String> LEARNING = List.of(
            "听懂松肩、缓呼的练法，尚未独自验证。",
            "静坐时可独自松肩、缓呼；行走效果尚未验证。",
            "缓步可调息，快走容易乱；仍不能用于战斗。");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void baselineAndSlimPreserveTheSameLearningAndAssetsAcrossThreeCommittedTurns() throws Exception {
        Models baseline = new Models(false, false, false);
        Models slim = new Models(true, false, false);
        var original = sequence(baseline);
        var candidate = sequence(slim);
        assertEquals("COMMITTED_IN_H2", original.status());
        assertEquals("COMMITTED_IN_H2", candidate.status(), mapper.writeValueAsString(candidate));
        assertEquals(3, candidate.turns().size());
        assertEquals(3, slim.directors.size());
        assertEquals(3, slim.writers.size());
        assertEquals(entityFacts(original.finalAuthority()), entityFacts(candidate.finalAuthority()));
        String abilityId = entity(candidate.turns().getFirst().result().after(), ABILITY).get("id");
        String bookId = entity(candidate.turns().getFirst().result().after(), BOOK).get("id");
        JsonNode firstPatch = storedPatch(candidate.turns().getFirst().result().after(), candidate.turns().getFirst().requestId());
        JsonNode firstStoredResult = firstPatch.path("acceptedResult");
        String teacherName = candidate.turns().getFirst().result().after().tables().get("story_entity").stream()
                .filter(row -> "entity:case:merchant".equals(row.get("id"))).findFirst().orElseThrow().get("name");
        assertTeachingSource(firstStoredResult, teacherName);
        assertEquals(bookId, firstPatch.path("assetProcess").path("steps").get(0)
                .path("outputs").get(0).path("entityRef").asText());
        assertNotEquals("book", bookId, "提交的资产过程必须引用数据库正式实体，不能残留本轮 localRef");
        for (int i = 0; i < candidate.turns().size(); i++) {
            var turn = candidate.turns().get(i).result();
            assertEquals(abilityId, entity(turn.after(), ABILITY).get("id"));
            assertEquals(bookId, entity(turn.after(), BOOK).get("id"));
            assertEquals(LEARNING.get(i), entity(turn.after(), ABILITY).get("summary"));
            var ability = mapper.readTree(entity(turn.after(), ABILITY).get("attributes_json"));
            assertEquals(i == 0 ? "KNOWN" : "LEARNING", ability.path("_trusted").path("abilityState").asText());
            var book = mapper.readTree(entity(turn.after(), BOOK).get("attributes_json"));
            assertEquals(1, book.path("_trusted").path("quantity").path("value").asInt());
            assertEquals("本", book.path("quantityUnit").asText());
            assertEquals(turn.prepared().path("acceptedResult"), slim.writers.get(i).path("acceptedResult"));
            JsonNode storedResult = storedPatch(turn.after(), candidate.turns().get(i).requestId()).path("acceptedResult");
            assertEquals(turn.prepared().path("acceptedResult"), storedResult,
                    "准备和正文交接正确还不够，同一有序结果必须真实提交并重新读取");
            if (i > 0) {
                assertEquals(candidate.turns().get(i - 1).result().after(), turn.before());
                JsonNode recalled = find(slim.directors.get(i).path("objective").path("relatedEntities"), ABILITY);
                assertEquals(abilityId, recalled.path("id").asText());
                assertEquals(LEARNING.get(i - 1), recalled.path("summary").asText());
                JsonNode narrative = slim.directors.get(i).path("narrative");
                var previous = candidate.turns().get(i - 1);
                assertEquals(storedPatch(previous.result().after(), previous.requestId()).path("acceptedResult"),
                        narrative.path("previousTurn").path("acceptedResult"));
                JsonNode recalledTeaching = StreamSupport.stream(narrative.path("recentTurns").spliterator(), false)
                        .map(value -> value.path("acceptedResult")).filter(firstStoredResult::equals).findFirst().orElseThrow();
                assertTeachingSource(recalledTeaching, teacherName);
            }
        }
        JsonNode firstResult = candidate.turns().getFirst().result().prepared().path("acceptedResult");
        assertEquals(TEACHING, firstResult.path("steps").get(0).path("statements").get(0).path("statement").asText());
        for (JsonNode writer : slim.writers) {
            assertFalse(writer.has("acceptedPlan"));
            assertFalse(writer.toString().contains("directorActionUnderstanding"));
        }
        JsonNode originalState = mapper.readTree(original.finalAuthority().tables().get("story_save").getFirst().get("state_json"));
        JsonNode candidateState = mapper.readTree(candidate.finalAuthority().tables().get("story_save").getFirst().get("state_json"));
        assertEquals(originalState.path("trustedProjection").path("protagonistCultivation"),
                candidateState.path("trustedProjection").path("protagonistCultivation"));
        assertEquals(0, candidateState.path("trustedProjection").path("protagonistCultivation").path("progressMarks").asInt());
    }

    @Test
    void slimCannotSpendMoreThanKnownInventoryAndHasOnlyOneRepair() throws Exception {
        Models models = new Models(true, true, false);
        var result = new PlayerIntentReplay(mapper).run(fixture(), generator(models));
        assertEquals("FAILED", result.status());
        assertTrue(result.unchangedOnFailure());
        assertEquals(result.before(), result.after());
        assertEquals(2, models.directors.size());
        assertEquals(0, models.writers.size());
        JsonNode repair = models.directors.getLast();
        assertTrue(repair.path("mode").asText().contains("REPAIR"));
        assertFalse(repair.toString().contains("assetStepIndexes"));
        assertFalse(repair.toString().contains("actionUnderstanding"));
    }

    @Test
    void writerFailurePreservesTheAlreadyCommittedTeachingAndLearningState() throws Exception {
        Models models = new Models(true, false, true);
        var result = sequence(models);
        assertEquals("STOPPED_ON_FAILURE", result.status());
        assertEquals(2, result.turns().size());
        assertTrue(result.turns().getLast().result().unchangedOnFailure());
        assertEquals(result.turns().getFirst().result().after(), result.finalAuthority());
        assertEquals(LEARNING.getFirst(), entity(result.finalAuthority(), ABILITY).get("summary"));
        assertEquals(1, result.finalAuthority().tables().get("story_entity").stream()
                .filter(row -> BOOK.equals(row.get("name"))).count());
    }

    @Test
    void failureAfterTurnInsertRollsBackTeachingBookAndMemoryTogether() throws Exception {
        Models models = new Models(true, false, false);
        var result = new PlayerIntentReplay(mapper).runWithFailureAfterTurnInsert(fixture(), generator(models));
        assertEquals("FAILED", result.status());
        assertTrue(result.unchangedOnFailure());
        assertEquals(result.before(), result.after());
        assertEquals(1, models.writers.size());
    }

    private PlayerIntentReplay.SequenceResult sequence(Models models) throws Exception {
        var fixture = fixture();
        return new PlayerIntentReplay(mapper).runSequence(fixture,
                new PlayerIntentReplay.ContinuationPlan("slim-offline-learning", fixture.caseId(), List.of(
                        new PlayerIntentReplay.FollowupAction("按刚学的方法静坐练习，确认用途和限制。", null),
                        new PlayerIntentReplay.FollowupAction("缓步与快走时检验调息，胸紧就停下，不硬撑。", null))), generator(models));
    }

    private DirectedNarrativeGenerator generator(Models models) {
        return models.slim ? SlimNarrativeGenerator.create(mapper, models.factory(), "http://127.0.0.1:26001/v1", "offline")
                : SlimNarrativeGenerator.createBaseline(mapper, models.factory(), "http://127.0.0.1:26001/v1", "offline");
    }

    private PlayerIntentReplay.CaseFixture fixture() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("story/replay/player-intent/C10_sell_all_herbs_bound.json")) {
            var base = mapper.readValue(input, PlayerIntentReplay.CaseFixture.class);
            return new PlayerIntentReplay.CaseFixture("slim_offline_learning", mapper.createObjectNode().put("kind", "FIXED_RESPONSE_COMPARISON"),
                    base.initialState(), base.initialSummary(), base.entities(), base.memories(), base.history(),
                    "请掌柜传授一段调息口诀，问清用途和限制；若他愿意给抄本就收好。", null);
        }
    }

    private Map<String, String> entity(PlayerIntentReplay.Authority state, String name) {
        return state.tables().get("story_entity").stream().filter(row -> name.equals(row.get("name"))).findFirst().orElseThrow();
    }

    private JsonNode storedPatch(PlayerIntentReplay.Authority state, String requestId) throws IOException {
        var row = state.tables().get("story_turn").stream()
                .filter(value -> requestId.equals(value.get("request_id"))).findFirst().orElseThrow();
        return mapper.readTree(row.get("state_patch_json"));
    }

    private void assertTeachingSource(JsonNode result, String teacherName) {
        assertTrue(result.isObject(), "需要真实结构化回合记录，不能从摘要推断教学");
        JsonNode statement = result.path("steps").get(0).path("statements").get(0);
        assertEquals("entity:case:merchant", statement.path("sourceRef").asText());
        assertEquals(teacherName, statement.path("sourceLabel").asText());
        assertEquals(TEACHING, statement.path("statement").asText());
    }

    private List<JsonNode> entityFacts(PlayerIntentReplay.Authority state) {
        return state.tables().get("story_entity").stream().sorted(Comparator.comparing(row -> row.get("name")))
                .map(row -> {
                    ObjectNode facts = mapper.createObjectNode();
                    for (String field : List.of("entity_type", "name", "status", "visible", "summary", "attributes_json")) facts.put(field, row.get(field));
                    return (JsonNode) facts;
                }).toList();
    }

    private JsonNode find(JsonNode values, String name) {
        return StreamSupport.stream(values.spliterator(), false).filter(value -> name.equals(value.path("name").asText())).findFirst().orElseThrow();
    }

    private final class Models {
        private final boolean slim;
        private final boolean overspend;
        private final boolean failPracticeWriter;
        private final List<JsonNode> directors = new ArrayList<>();
        private final List<JsonNode> writers = new ArrayList<>();

        Models(boolean slim, boolean overspend, boolean failPracticeWriter) {
            this.slim = slim; this.overspend = overspend; this.failPracticeWriter = failPracticeWriter;
        }

        ChatModelFactory factory() {
            return (definition, credential, options) -> new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    try {
                        String text = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                                .reduce((previous, next) -> next).orElseThrow().singleText();
                        JsonNode payload = mapper.readTree(text);
                        ObjectNode response;
                        if (payload.has("mode")) {
                            directors.add(payload);
                            response = decision(payload, directors.size());
                            if (slim) response = slimResponse(response);
                        } else {
                            writers.add(payload);
                            if (failPracticeWriter && writers.size() == 2) throw new IllegalStateException("offline writer failure");
                            response = mapper.createObjectNode().put("title", "学以致用").put("narrative", writers.size() == 1
                                    ? "掌柜说：" + TEACHING + "你收下抄本，记住了练法。" : LEARNING.get(writers.size() - 1));
                            response.putArray("choices"); response.put("summary", "掌柜传授口诀并赠抄本，后续尝试保留已有用途与局限。");
                        }
                        return ChatResponse.builder().aiMessage(AiMessage.from(mapper.writeValueAsString(response))).build();
                    } catch (IOException error) { throw new IllegalStateException(error); }
                }
            };
        }

        private ObjectNode decision(JsonNode context, int turn) throws IOException {
            ObjectNode body = (ObjectNode) mapper.readTree("""
                    {"duration":{"unit":"SCENE","value":1},"timeResolution":"COMPLETE",
                    "cultivation":{"progressGained":false,"breakthroughAttempted":false,"reason":"仅学习或应用","evidenceRefs":[]},
                    "itemChanges":[],"abilityChanges":[],"entities":[],"worldFacts":[],"assetProcess":{"steps":[]},
                    "actionUnderstanding":{"purpose":"学习与运用已知口诀","constraints":["不硬撑"]},
                    "orderedResult":{"steps":[{"action":"学习或应用","resolution":"SUCCEEDED","result":"完成本次尝试",
                    "realmProgressBinding":false,"assetStepIndexes":[],"dependsOn":[],"statements":[]}],"stop":{"reason":"ACTION_RESOLVED"}}}
                    """);
            ObjectNode step = (ObjectNode) body.path("orderedResult").path("steps").get(0);
            if (overspend) {
                step.withArray("assetStepIndexes").add(0);
                ObjectNode asset = ((ObjectNode) body.path("assetProcess")).withArray("steps").addObject()
                        .put("kind", "CONSUME").put("summary", "消耗四株草药");
                asset.putArray("outputs"); asset.putArray("observations");
                asset.putArray("inputs").addObject().put("entityRef", "entity:case:herba").put("amount", "4").put("unit", "株")
                        .putObject("custody").put("holderType", "PLAYER");
                return body;
            }
            ObjectNode ability = body.withArray("abilityChanges").addObject().put("entityType", "ABILITY").put("status", "ACTIVE")
                    .put("abilityState", turn == 1 ? "KNOWN" : "LEARNING").put("visible", true).put("summary", LEARNING.get(turn - 1));
            step.put("result", LEARNING.get(turn - 1));
            if (turn == 1) {
                ability.put("localRef", "lesson").put("name", ABILITY);
                ability.putArray("attributes").addObject().put("key", "source").put("value", "药庄掌柜");
                body.withArray("itemChanges").addObject().put("localRef", "book").put("name", BOOK).put("status", "随身携带")
                        .put("summary", TEACHING).put("visible", true);
                step.withArray("statements").addObject().put("sourceRef", "entity:case:merchant").put("sourceLabel", "药庄掌柜").put("statement", TEACHING);
                step.withArray("assetStepIndexes").add(0);
                ObjectNode asset = ((ObjectNode) body.path("assetProcess")).withArray("steps").addObject().put("kind", "ACQUIRE").put("summary", "收下口诀抄本");
                asset.putArray("inputs"); asset.putArray("observations");
                asset.putArray("outputs").addObject().put("entityRef", "book").put("amount", "1").put("unit", "本")
                        .putObject("custody").put("holderType", "PLAYER");
            } else ability.put("existingEntityId", find(context.path("objective").path("relatedEntities"), ABILITY).path("id").asText());
            return body;
        }

        /** 测试输入的独立形状转换；两条接口都收到同一固定语义，期望余额与教学内容另外逐项断言。 */
        private ObjectNode slimResponse(ObjectNode baseline) {
            ObjectNode result = baseline.deepCopy();
            JsonNode assets = result.remove("assetProcess").path("steps");
            result.remove("actionUnderstanding");
            result.path("abilityChanges").forEach(value -> ((ObjectNode) value).remove("entityType"));
            result.path("orderedResult").path("steps").forEach(value -> {
                ObjectNode step = (ObjectNode) value;
                JsonNode indexes = step.remove("assetStepIndexes");
                var nested = step.putArray("assets");
                indexes.forEach(index -> nested.add(assets.get(index.asInt()).deepCopy()));
            });
            return result;
        }
    }
}
