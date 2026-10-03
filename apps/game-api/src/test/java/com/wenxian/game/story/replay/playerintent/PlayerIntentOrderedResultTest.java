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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用正式类型化服务和隔离 H2 验证本轮声明的来源、提交及后续召回。
 * 报价是离线协议样本，不是固定物价；正文故意省略数字，宿主不得据正文补造结构化声明。
 */
class PlayerIntentOrderedResultTest {
    private static final String SOURCE = "entity:case:merchant";
    private static final String NEW_SOURCE_REF = "new-quote-apprentice";
    private static final String NEW_SOURCE_NAME = "验药童子";
    private static final String QUOTE = "三株青叶草和两株紫根，本次共收五十四文铜钱，不附加其他条件。";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void scopedQuoteSurvivesCommitReloadAndActualNextRequestsWithoutBecomingWorldFact() throws Exception {
        Models models = new Models(false);
        var fixture = fixture();
        var plan = new PlayerIntentReplay.ContinuationPlan("offline_quote_sequence", fixture.caseId(),
                List.of(new PlayerIntentReplay.FollowupAction("先不卖，回想掌柜刚才的报价后停下。", null)));
        var result = new PlayerIntentReplay(mapper).runSequence(fixture, plan, generator(models));
        assertEquals("COMMITTED_IN_H2", result.status(), mapper.writeValueAsString(result));
        assertEquals(2, result.turns().size());
        assertEquals(2, models.directors.size());
        assertEquals(2, models.writers.size());
        var first = result.turns().getFirst();
        var second = result.turns().getLast();
        assertEquals(first.result().after(), second.result().before(), "后轮从上一轮提交的同一数据库状态重载");

        JsonNode stored = storedPatch(first.result().after(), first.requestId()).path("acceptedResult");
        assertTrue(stored.isObject());
        assertEquals(2, stored.path("protocolVersion").asInt());
        assertEquals(first.result().prepared().path("acceptedResult"), stored);
        assertEquals(stored, models.writers.getFirst().path("endState").path("acceptedResult"));
        JsonNode statement = stored.path("steps").get(0).path("statements").get(0);
        assertEquals(SOURCE, statement.path("sourceRef").asText());
        assertEquals("药庄掌柜", statement.path("sourceLabel").asText());
        assertEquals(QUOTE, statement.path("statement").asText());

        assertEquals(stored, models.directors.getLast().path("narrative").path("previousTurn").path("acceptedResult"));
        assertEquals(stored, models.writers.getLast().path("narrativeContext").path("previousTurn").path("acceptedResult"));
        assertTrue(models.directors.getFirst().path("narrative").path("previousTurn").path("acceptedResult").isNull(),
                "旧回合不从已有正文和摘要伪造有序结果");
        assertFalse(first.result().prepared().path("generation").path("patch").path("narrative").asText().contains("五十四"));
        assertFalse(first.result().prepared().path("generation").path("patch").path("rollingSummary").asText().contains("五十四"));
        assertEquals(first.result().before().tables().get("story_entity"), result.finalAuthority().tables().get("story_entity"),
                "只询价和回想，不出售、不收款、不为报价新增人物");
        assertEquals(worldFacts(first.result().before()), worldFacts(result.finalAuthority()));
        assertTrue(worldFacts(result.finalAuthority()).isEmpty(), "一次掌柜报价不能被升级为世界价格规则");
    }

    @Test
    void newSpeakerLocalReferenceBecomesOnePersistedCharacterIdAcrossTheNextTurn() throws Exception {
        Models models = new Models(false, true);
        var base = fixture();
        var fixture = new PlayerIntentReplay.CaseFixture("offline_new_quote_source", base.sourceEvidence(),
                base.initialState(), base.initialSummary(), base.entities(), base.memories(), base.history(),
                "请掌柜介绍一位负责验药的药童，与他通名后问这批自有草药的价钱，暂不成交。", null);
        var plan = new PlayerIntentReplay.ContinuationPlan("offline_new_quote_source_sequence", fixture.caseId(),
                List.of(new PlayerIntentReplay.FollowupAction("先不卖，回想刚认识的验药童子的报价后停下。", null)));
        var result = new PlayerIntentReplay(mapper).runSequence(fixture, plan, generator(models));
        assertEquals("COMMITTED_IN_H2", result.status(), mapper.writeValueAsString(result));
        assertEquals(2, models.directors.size());
        assertEquals(2, models.writers.size());
        var first = result.turns().getFirst();
        var second = result.turns().getLast();
        assertEquals(first.result().after(), second.result().before());
        JsonNode proposed = first.result().stages().getFirst().path("result").path("decision");
        assertEquals(NEW_SOURCE_REF, proposed.path("orderedResult").path("steps").get(0)
                .path("statements").get(0).path("sourceRef").asText());
        assertTrue(first.result().before().tables().get("story_entity").stream()
                .noneMatch(row -> NEW_SOURCE_NAME.equals(row.get("name"))));
        var speakers = result.finalAuthority().tables().get("story_entity").stream()
                .filter(row -> NEW_SOURCE_NAME.equals(row.get("name"))).toList();
        assertEquals(1, speakers.size(), "重载后不能再次新建同一个来源人物");
        String storedId = speakers.getFirst().get("id");
        assertNotNull(storedId);
        assertNotEquals(NEW_SOURCE_REF, storedId);

        JsonNode accepted = storedPatch(first.result().after(), first.requestId()).path("acceptedResult");
        JsonNode statement = accepted.path("steps").get(0).path("statements").get(0);
        assertEquals(storedId, statement.path("sourceRef").asText());
        assertEquals(NEW_SOURCE_NAME, statement.path("sourceLabel").asText());
        assertEquals(QUOTE, statement.path("statement").asText());
        assertFalse(accepted.toString().contains(NEW_SOURCE_REF), "模型临时引用不能泄漏进已接受结果");
        assertEquals(accepted, first.result().prepared().path("acceptedResult"));
        assertEquals(accepted, models.writers.getFirst().path("endState").path("acceptedResult"));
        assertEquals(accepted, models.directors.getLast().path("narrative").path("previousTurn").path("acceptedResult"));
        assertEquals(accepted, models.writers.getLast().path("narrativeContext").path("previousTurn").path("acceptedResult"));
        assertEquals(worldFacts(first.result().before()), worldFacts(result.finalAuthority()));
    }

    @Test
    void writerFailureLeavesNoAcceptedResultOrPartialMemoriesInAuthority() throws Exception {
        Models models = new Models(true);
        var result = new PlayerIntentReplay(mapper).run(fixture(), generator(models));
        assertEquals("FAILED", result.status(), mapper.writeValueAsString(result));
        assertEquals(1, models.directors.size());
        assertEquals(1, models.writers.size());
        assertTrue(models.writers.getFirst().path("endState").path("acceptedResult").isObject(),
                "候选结果已交给正文，但尚未获得提交资格");
        assertTrue(result.unchangedOnFailure());
        assertEquals(result.before(), result.after());
        assertNoStoredAcceptedResult(result.after());
    }

    @Test
    void failureAfterActualTurnInsertRollsBackAcceptedResultSaveAndMemoriesTogether() throws Exception {
        Models models = new Models(false);
        var result = new PlayerIntentReplay(mapper).runWithFailureAfterTurnInsert(fixture(), generator(models));
        assertEquals("FAILED", result.status(), mapper.writeValueAsString(result));
        assertNotNull(result.prepared(), "准备及正文已完成，错误发生在正式提交事务内");
        assertTrue(result.prepared().path("acceptedResult").isObject());
        assertEquals("COMMIT_FAILED", result.diagnostics().getLast().get("current_stage"));
        assertTrue(result.unchangedOnFailure());
        assertEquals(result.before(), result.after(), "回合行已插入后出错，原命卷、实体、记忆和摘要仍整体回滚");
        assertNoStoredAcceptedResult(result.after());
    }

    private void assertNoStoredAcceptedResult(PlayerIntentReplay.Authority authority) throws IOException {
        for (Map<String, String> row : authority.tables().get("story_turn")) {
            assertFalse(mapper.readTree(row.get("state_patch_json")).hasNonNull("acceptedResult"));
        }
    }

    private JsonNode storedPatch(PlayerIntentReplay.Authority authority, String requestId) throws IOException {
        var row = authority.tables().get("story_turn").stream()
                .filter(value -> requestId.equals(value.get("request_id"))).findFirst().orElseThrow();
        return mapper.readTree(row.get("state_patch_json"));
    }

    private JsonNode worldFacts(PlayerIntentReplay.Authority authority) throws IOException {
        return mapper.readTree(authority.tables().get("story_save").getFirst().get("state_json"))
                .path("trustedProjection").path("worldFacts");
    }

    private PlayerIntentReplay.CaseFixture fixture() throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "story/replay/player-intent/C10_sell_all_herbs_bound.json")) {
            assertNotNull(stream);
            var base = mapper.readValue(stream, PlayerIntentReplay.CaseFixture.class);
            return new PlayerIntentReplay.CaseFixture("offline_scoped_quote",
                    mapper.createObjectNode().put("kind", "OFFLINE_ACCEPTED_RESULT_REGRESSION"), base.initialState(),
                    base.initialSummary(), base.entities(), base.memories(), base.history(),
                    "先问掌柜，我这三株青叶草和两株紫根能卖多少铜钱，暂不成交。", null);
        }
    }

    private com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator generator(Models models) {
        return PlayerIntentReplay.generator(mapper, models.factory(), "http://127.0.0.1:26001/v1", "offline-placeholder");
    }

    private final class Models {
        private final boolean failWriter;
        private final boolean newSource;
        private final List<JsonNode> directors = new ArrayList<>();
        private final List<JsonNode> writers = new ArrayList<>();

        private Models(boolean failWriter) { this(failWriter, false); }
        private Models(boolean failWriter, boolean newSource) {
            this.failWriter = failWriter;
            this.newSource = newSource;
        }

        private ChatModelFactory factory() {
            return (definition, credential, options) -> new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    try {
                        String text = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                                .reduce((previous, next) -> next).orElseThrow().singleText();
                        JsonNode payload = mapper.readTree(text);
                        String response;
                        if (options.maximumOutputTokens() == 4000) {
                            assertEquals("PLAN_CURRENT_ACTION", payload.path("mode").asText(), "合法报价草案不应触发格式修复");
                            directors.add(payload);
                            response = director(directors.size());
                        } else {
                            writers.add(payload);
                            if (failWriter) throw new IllegalStateException("offline writer failure after accepted result");
                            response = """
                                    {"title":"问价之后","narrative":"你听清掌柜的答复，收好药材，暂未成交。",
                                     "choices":[],"summary":"你问价后保留药材，没有成交。"}
                                    """;
                            if (newSource) response = response.replace("掌柜", NEW_SOURCE_NAME);
                        }
                        return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
                    } catch (IOException error) { throw new IllegalStateException(error); }
                }
            };
        }

        private String director(int turn) throws IOException {
            ObjectNode response = (ObjectNode) mapper.readTree("""
                    {"duration":{"unit":"SCENE","value":1},"timeResolution":"COMPLETE",
                     "itemChanges":[],"abilityChanges":[],"entities":[],"worldFacts":[],
                     "assetProcess":{"steps":[]},
                     "actionUnderstanding":{"purpose":"了解当前报价，暂不成交","constraints":["不出售、不收款"]},
                     "orderedResult":{"steps":[{"action":"向掌柜问价","resolution":"SUCCEEDED",
                       "result":"掌柜已答复本批报价；尚未成交。","realmProgressBinding":false,
                       "assetStepIndexes":[],"dependsOn":[],"statements":[]}],
                       "stop":{"reason":"ACTION_RESOLVED","detail":"了解报价后停下，未成交"}}}
                    """);
            ObjectNode step = (ObjectNode) response.path("orderedResult").path("steps").get(0);
            if (turn == 1) {
                step.withArray("statements").addObject().put("sourceRef", newSource ? NEW_SOURCE_REF : SOURCE)
                        .put("sourceLabel", newSource ? NEW_SOURCE_NAME : "药庄掌柜").put("statement", QUOTE);
                if (newSource) {
                    step.put("action", "与验药童子通名并问价").put("result", "掌柜引见验药童子，对方通名后答复本批报价，尚未成交。");
                    ObjectNode speaker = response.withArray("entities").addObject();
                    speaker.put("localRef", NEW_SOURCE_REF).put("entityType", "CHARACTER").put("name", NEW_SOURCE_NAME)
                            .put("status", "ACTIVE").put("summary", "本轮经掌柜引见并报出本批药材价格")
                            .put("scenePresence", "PRESENT").put("visible", true);
                    speaker.putArray("attributes").addObject().put("key", "identity").put("value", "药庄验药童子");
                }
            }
            else step.put("action", "回想刚才报价后停下").put("result", "记住掌柜刚才的报价，仍未成交。");
            if (newSource && turn > 1) step.put("result", "记住验药童子刚才的报价，仍未成交。");
            return mapper.writeValueAsString(response);
        }
    }
}
