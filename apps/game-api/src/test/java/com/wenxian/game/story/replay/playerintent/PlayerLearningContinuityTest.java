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
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定模型响应经过正式类型化服务、归并和 H2 事务，验证学习成果的用途、限制与学习状态连续读取。
 * 每轮由既有回放宿主重建服务和仓储，不使用模型网络，也不以正文反推能力状态或追加修行分数。
 */
class PlayerLearningContinuityTest {
    private static final String SOURCE_ID = "entity:case:merchant";
    private static final String ABILITY = "松息口诀";
    private static final String BOOK = "松息口诀抄本";
    private static final String TEACHING = "先松开肩背，再放慢呼气；此法只助调息，不能用作攻伐。";
    private static final String TAUGHT = "药庄掌柜所授的调息方法：松肩、放慢呼气。仅知练法，尚未在静坐或行走中验证效果。";
    private static final String PRACTICED = "通过静坐练习，已能以松肩、放慢呼气缓解胸口滞涩；行走时能否保持仍未验证，不是攻伐功法。";
    private static final String APPLIED = "静坐与缓步行走时可借松肩、放慢呼气调息；快跑或战斗中仍未验证，不能替代攻伐功法。";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void taughtTechniqueKeepsItsUsesLimitsAndIdentityThroughPracticeReloadAndApplication() throws Exception {
        Models models = new Models(false);
        var sequence = run(models);
        assertEquals("COMMITTED_IN_H2", sequence.status(), mapper.writeValueAsString(sequence));
        assertEquals(3, sequence.turns().size());
        assertEquals(3, models.directors.size(), "合法普通练习不应触发额外修复");
        assertEquals(3, models.writers.size());
        var taught = sequence.turns().get(0).result();
        var practiced = sequence.turns().get(1).result();
        var applied = sequence.turns().get(2).result();
        assertEquals(taught.after(), practiced.before());
        assertEquals(practiced.after(), applied.before());

        String abilityId = entity(taught.after(), ABILITY).get("id");
        String bookId = entity(taught.after(), BOOK).get("id");
        assertNotEquals("lesson-ability", abilityId);
        assertNotEquals("lesson-book", bookId);
        assertEquals(abilityId, models.abilityId, "后轮只使用重新装配上下文的正式能力ID");
        for (var turn : sequence.turns()) {
            var authority = turn.result().after();
            assertEquals(1, count(authority, ABILITY));
            assertEquals(1, count(authority, BOOK));
            assertEquals(abilityId, entity(authority, ABILITY).get("id"));
            assertEquals(bookId, entity(authority, BOOK).get("id"));
            JsonNode book = mapper.readTree(entity(authority, BOOK).get("attributes_json"));
            assertEquals(1, book.path("_trusted").path("quantity").path("value").asInt());
            assertEquals("PLAYER", book.path("_trusted").path("custody").path("holderType").asText());
            assertEquals("本", book.path("quantityUnit").asText());
        }
        assertEquals(TAUGHT, entity(taught.after(), ABILITY).get("summary"));
        assertEquals(PRACTICED, entity(practiced.after(), ABILITY).get("summary"));
        assertEquals(APPLIED, entity(applied.after(), ABILITY).get("summary"));
        assertEquals("KNOWN", storedAbilityState(taught.after()));
        assertEquals("LEARNING", storedAbilityState(practiced.after()));
        assertEquals("LEARNING", storedAbilityState(applied.after()));

        assertLearning(find(models.directors.get(1).path("objective").path("relatedEntities"), ABILITY), abilityId, TAUGHT, "KNOWN");
        assertLearning(find(models.directors.get(2).path("objective").path("relatedEntities"), ABILITY), abilityId, PRACTICED, "LEARNING");
        assertLearning(find(models.writers.get(1).path("endState").path("abilities"), ABILITY), abilityId, PRACTICED, "LEARNING");
        assertLearning(find(models.writers.get(2).path("endState").path("abilities"), ABILITY), abilityId, APPLIED, "LEARNING");
        assertEquals("ACTIVE", find(models.directors.get(2).path("objective").path("relatedEntities"), ABILITY).path("status").asText(),
                "本样本刻意保留自由status与学习状态不同，不能用ACTIVE替代LEARNING");

        JsonNode lesson = models.writers.getFirst().path("endState").path("acceptedResult");
        assertTrue(lesson.path("steps").get(0).path("result").asText().contains(TEACHING));
        assertEquals(TEACHING, lesson.path("steps").get(0).path("statements").get(0).path("statement").asText());
        assertEquals(SOURCE_ID, lesson.path("steps").get(0).path("statements").get(0).path("sourceRef").asText());
        assertEquals(lesson, models.directors.get(1).path("narrative").path("previousTurn").path("acceptedResult"));
        assertTrue(models.writers.get(2).path("endState").path("acceptedResult").path("steps").get(0)
                .path("result").asText().contains("停下调整"));
        assertTrue(models.writers.stream().allMatch(writer -> writer.path("acceptedPlan").path("cultivation").isNull()),
                "本批固定样本验证学习与应用，不要求每次练习都产生数值进展");
        JsonNode before = mapper.readTree(entitySave(taught.before()));
        JsonNode after = mapper.readTree(entitySave(sequence.finalAuthority()));
        assertEquals(before.path("trustedProjection").path("protagonistCultivation"),
                after.path("trustedProjection").path("protagonistCultivation"));
        assertEquals(before.path("trustedProjection").path("worldFacts"), after.path("trustedProjection").path("worldFacts"));
    }

    @Test
    void teachingWithRealmProgressKeepsTheExperienceSourceAndIndependentRealmResultAfterReload() throws Exception {
        Models models = new Models(false, true);
        var sequence = run(models);
        assertEquals("COMMITTED_IN_H2", sequence.status(), mapper.writeValueAsString(sequence));
        assertEquals(3, models.directors.size(), "绑定进展并携带教师声明、资产的合法教学不应触发修复");
        assertEquals(3, models.writers.size());
        var first = sequence.turns().getFirst();
        JsonNode initial = mapper.readTree(entitySave(first.result().before()))
                .path("trustedProjection").path("protagonistCultivation");
        assertEquals("realm:xuanxu:human:l1", initial.path("currentStageId").asText());
        assertEquals("MIDDLE", initial.path("phase").asText());
        assertEquals(0, initial.path("progressMarks").asInt());
        assertEquals(2, initial.path("requiredProgressMarks").asInt());

        JsonNode accepted = models.writers.getFirst().path("endState").path("acceptedResult");
        JsonNode lesson = accepted.path("steps").get(0);
        assertEquals(2, accepted.path("protocolVersion").asInt());
        assertTrue(lesson.path("cultivation").asBoolean());
        assertEquals("掌柜讲解：" + TEACHING + "你记住练法，收好他赠送的一本抄本。", lesson.path("result").asText());
        assertEquals(TEACHING, lesson.path("statements").get(0).path("statement").asText());
        assertEquals(SOURCE_ID, lesson.path("statements").get(0).path("sourceRef").asText());
        assertEquals("药庄掌柜", lesson.path("statements").get(0).path("sourceLabel").asText());
        assertEquals(mapper.readTree("[0]"), lesson.path("assetStepIndexes"));
        assertEquals("PROGRESSED", lesson.path("realmResult").path("resolution").asText());
        assertTrue(lesson.path("realmResult").path("result").asText().contains("炼气中期，进度1/2"));

        var savedTurn = first.result().after().tables().get("story_turn").stream()
                .filter(row -> first.requestId().equals(row.get("request_id"))).findFirst().orElseThrow();
        assertEquals(accepted, mapper.readTree(savedTurn.get("state_patch_json")).path("acceptedResult"));
        assertEquals(accepted, first.result().prepared().path("acceptedResult"));
        assertEquals(accepted, models.directors.get(1).path("narrative").path("previousTurn").path("acceptedResult"));
        assertEquals(accepted, models.writers.get(1).path("narrativeContext").path("previousTurn").path("acceptedResult"));
        for (var turn : sequence.turns()) {
            JsonNode cultivation = mapper.readTree(entitySave(turn.result().after()))
                    .path("trustedProjection").path("protagonistCultivation");
            assertEquals("MIDDLE", cultivation.path("phase").asText());
            assertEquals(1, cultivation.path("progressMarks").asInt(), "后两轮普通练习与应用不强制追加进展");
            assertEquals(1, count(turn.result().after(), ABILITY));
            assertEquals(1, count(turn.result().after(), BOOK));
        }
        assertTrue(models.writers.stream().skip(1).allMatch(writer -> writer.path("acceptedPlan").path("cultivation").isNull()));
        assertTrue(models.writers.stream().skip(1).noneMatch(writer -> writer.path("endState")
                .path("acceptedResult").path("steps").get(0).hasNonNull("realmResult")));
        assertEquals(APPLIED, entity(sequence.finalAuthority(), ABILITY).get("summary"));
        assertEquals("LEARNING", storedAbilityState(sequence.finalAuthority()));
    }

    @Test
    void failedPracticeWriterDoesNotPersistNewLearningStateOrRewrittenAbilitySummary() throws Exception {
        Models models = new Models(true);
        models.malformedPractice = true;
        var sequence = run(models);
        assertEquals("STOPPED_ON_FAILURE", sequence.status());
        assertEquals(2, sequence.turns().size());
        var taught = sequence.turns().getFirst().result();
        var failed = sequence.turns().getLast().result();
        assertEquals("COMMITTED_IN_H2", taught.status());
        assertEquals("FAILED", failed.status());
        assertTrue(failed.unchangedOnFailure());
        assertEquals(taught.after(), sequence.finalAuthority());
        assertEquals(TAUGHT, entity(sequence.finalAuthority(), ABILITY).get("summary"));
        assertEquals("KNOWN", storedAbilityState(sequence.finalAuthority()));
        assertEquals(1, count(sequence.finalAuthority(), BOOK));
        assertLearning(find(models.writers.getLast().path("endState").path("abilities"), ABILITY),
                models.abilityId, PRACTICED, "LEARNING");
        assertEquals(3, models.directors.size(), "第二轮仅修复一次，再进入失败的正文调用");
    }

    @Test
    void unrelatedDecodeErrorKeepsTheLearningProposalAvailableToRepairAndNextTurn() throws Exception {
        Models models = new Models(false);
        models.malformedPractice = true;
        var sequence = run(models);
        assertEquals("COMMITTED_IN_H2", sequence.status());
        assertEquals(4, models.directors.size(), "三轮仅额外使用既有的一次修复");
        assertEquals(3, models.writers.size());
        JsonNode repair = models.directors.get(2);
        assertEquals("REPAIR_REJECTED_DECISION", repair.path("mode").asText());
        assertEquals("DECODING", repair.path("rejectedPhase").asText());
        assertEquals(models.rejectedPracticeDraft, repair.path("rejectedDraft").asText());
        assertEquals(PRACTICED, mapper.readTree(repair.path("rejectedDraft").asText())
                .path("abilityChanges").get(0).path("summary").asText());
        var taught = sequence.turns().get(0).result();
        var practiced = sequence.turns().get(1).result();
        var applied = sequence.turns().get(2).result();
        assertEquals(taught.after(), practiced.before());
        assertEquals(practiced.after(), applied.before());
        String id = entity(taught.after(), ABILITY).get("id");
        assertEquals(id, entity(practiced.after(), ABILITY).get("id"));
        assertEquals(PRACTICED, entity(practiced.after(), ABILITY).get("summary"));
        assertLearning(find(models.directors.get(3).path("objective").path("relatedEntities"), ABILITY),
                id, PRACTICED, "LEARNING");
        assertLearning(find(models.writers.get(1).path("endState").path("abilities"), ABILITY),
                id, PRACTICED, "LEARNING");
        assertEquals(APPLIED, entity(sequence.finalAuthority(), ABILITY).get("summary"));
    }

    @Test
    void exhaustedDecodeRepairDoesNotApplyTheLearningProposalOrCallWriter() throws Exception {
        Models models = new Models(false);
        models.malformedPractice = true;
        models.failRepair = true;
        var sequence = run(models);
        assertEquals("STOPPED_ON_FAILURE", sequence.status());
        assertEquals(2, sequence.turns().size());
        assertEquals(3, models.directors.size());
        assertEquals(1, models.writers.size());
        assertTrue(sequence.turns().get(1).result().unchangedOnFailure());
        assertEquals(sequence.turns().getFirst().result().after(), sequence.finalAuthority());
        assertEquals(TAUGHT, entity(sequence.finalAuthority(), ABILITY).get("summary"));
        assertEquals("KNOWN", storedAbilityState(sequence.finalAuthority()));
    }

    private PlayerIntentReplay.SequenceResult run(Models models) throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream("story/replay/player-intent/C10_sell_all_herbs_bound.json")) {
            assertNotNull(stream);
            var base = mapper.readValue(stream, PlayerIntentReplay.CaseFixture.class);
            var fixture = new PlayerIntentReplay.CaseFixture("offline_learning_continuity",
                    mapper.createObjectNode().put("kind", "OFFLINE_LEARNING_CONTINUITY"), base.initialState(), base.initialSummary(),
                    base.entities(), base.memories(), base.history(), "请药庄掌柜讲一段调息口诀，问清用途和限制；若他愿意赠送抄本就收好。", null);
            var plan = new PlayerIntentReplay.ContinuationPlan("offline_learning_application", fixture.caseId(), List.of(
                    new PlayerIntentReplay.FollowupAction("按刚得到的口诀静坐练习，记下已经验证的用途和仍不确定的限制。", null),
                    new PlayerIntentReplay.FollowupAction("收好抄本，缓步行走时试着调息；胸口发紧就停下调整，不硬撑。", null)));
            return new PlayerIntentReplay(mapper).runSequence(fixture, plan, PlayerIntentReplay.generator(
                    mapper, models.factory(), "http://127.0.0.1:26001/v1", "offline-placeholder"));
        }
    }

    private void assertLearning(JsonNode ability, String id, String summary, String state) {
        assertEquals(id, ability.path("id").asText());
        assertEquals(summary, ability.path("summary").asText());
        assertEquals(state, ability.path("abilityState").asText());
        assertEquals("药庄掌柜", ability.path("attributes").path("source").asText());
        assertEquals("调息法门", ability.path("attributes").path("type").asText());
    }

    private JsonNode find(JsonNode list, String name) {
        return StreamSupport.stream(list.spliterator(), false).filter(value -> name.equals(value.path("name").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("正式模型上下文缺少：" + name));
    }

    private Map<String, String> entity(PlayerIntentReplay.Authority authority, String name) {
        return authority.tables().get("story_entity").stream().filter(row -> name.equals(row.get("name"))).findFirst().orElseThrow();
    }
    private long count(PlayerIntentReplay.Authority authority, String name) {
        return authority.tables().get("story_entity").stream().filter(row -> name.equals(row.get("name"))).count();
    }
    private String entitySave(PlayerIntentReplay.Authority authority) { return authority.tables().get("story_save").getFirst().get("state_json"); }
    private String storedAbilityState(PlayerIntentReplay.Authority authority) throws IOException {
        return mapper.readTree(entity(authority, ABILITY).get("attributes_json")).path("_trusted").path("abilityState").asText();
    }

    private final class Models {
        private final List<JsonNode> directors = new ArrayList<>();
        private final List<JsonNode> writers = new ArrayList<>();
        private final boolean failPracticeWriter;
        private final boolean teachingProgress;
        private boolean malformedPractice;
        private boolean failRepair;
        private String rejectedPracticeDraft;
        private String abilityId;
        private Models(boolean failPracticeWriter) { this(failPracticeWriter, false); }
        private Models(boolean failPracticeWriter, boolean teachingProgress) {
            this.failPracticeWriter = failPracticeWriter;
            this.teachingProgress = teachingProgress;
        }

        private ChatModelFactory factory() {
            return (definition, credential, options) -> new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    try {
                        String text = request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                                .reduce((prior, next) -> next).orElseThrow().singleText();
                        JsonNode payload = mapper.readTree(text);
                        String response;
                        if (payload.hasNonNull("mode")) {
                            directors.add(payload);
                            if ("REPAIR_REJECTED_DECISION".equals(payload.path("mode").asText())) {
                                assertEquals(rejectedPracticeDraft, payload.path("rejectedDraft").asText());
                                ObjectNode repaired = (ObjectNode) mapper.readTree(payload.path("rejectedDraft").asText());
                                ObjectNode character = (ObjectNode) repaired.path("entities").get(0);
                                character.putArray("attributes").addObject().put("key", "identity").put("value", "药庄掌柜");
                                response = failRepair ? "{still malformed" : mapper.writeValueAsString(repaired);
                            } else {
                                assertEquals("PLAN_CURRENT_ACTION", payload.path("mode").asText());
                                response = director(payload, writers.size() + 1);
                                if (malformedPractice && writers.size() == 1) {
                                    ObjectNode rejected = (ObjectNode) mapper.readTree(response);
                                    rejected.withArray("entities").addObject().put("existingEntityId", SOURCE_ID)
                                            .put("entityType", "CHARACTER").putObject("attributes").put("identity", "药庄掌柜");
                                    rejectedPracticeDraft = mapper.writeValueAsString(rejected);
                                    response = rejectedPracticeDraft;
                                }
                            }
                        } else {
                            writers.add(payload);
                            if (failPracticeWriter && writers.size() == 2) throw new IllegalStateException("offline learning writer failure");
                            ObjectNode prose = mapper.createObjectNode().put("title", "学以致用");
                            prose.put("narrative", writers.size() == 1 ? "掌柜讲道：" + TEACHING + "你记住练法，收好他赠送的抄本。"
                                    : writers.size() == 2 ? "你按口诀静坐练习，肩背放松后胸口滞涩减轻；行走时的效果还需要检验。"
                                    : "你缓步走了一段，胸口发紧便停下调整，依口诀放慢呼气，待气息平稳后结束尝试。");
                            prose.putArray("choices");
                            prose.put("summary", "掌柜曾教调息口诀并赠抄本；你保留抄本，按已知用途与限制尝试练习。");
                            response = mapper.writeValueAsString(prose);
                        }
                        return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
                    } catch (IOException error) { throw new IllegalStateException(error); }
                }
            };
        }

        private String director(JsonNode payload, int turn) throws IOException {
            assertTrue(turn <= 3);
            ObjectNode body = (ObjectNode) mapper.readTree("""
                    {"duration":{"unit":"SCENE","value":1},"timeResolution":"COMPLETE",
                     "cultivation":{"progressGained":false,"breakthroughAttempted":false,"reason":"本轮只确认学习和应用，不提出数值进展","evidenceRefs":[]},
                     "itemChanges":[],"abilityChanges":[],"entities":[],"worldFacts":[],"assetProcess":{"steps":[]},
                     "actionUnderstanding":{"purpose":"按现有能力学习并应用","constraints":["不把练习等同于必然晋升"]},
                     "orderedResult":{"steps":[{"action":"学习或应用口诀","resolution":"SUCCEEDED","result":"已完成本次尝试", "realmProgressBinding":false,
                       "assetStepIndexes":[],"dependsOn":[],"statements":[]}],"stop":{"reason":"ACTION_RESOLVED"}}}
                    """);
            ObjectNode ability = body.withArray("abilityChanges").addObject().put("entityType", "ABILITY")
                    .put("status", "ACTIVE").put("abilityState", turn == 1 ? "KNOWN" : "LEARNING").put("visible", true)
                    .put("summary", turn == 1 ? TAUGHT : turn == 2 ? PRACTICED : APPLIED);
            ObjectNode step = (ObjectNode) body.path("orderedResult").path("steps").get(0);
            if (turn == 1) {
                if (teachingProgress) {
                    ((ObjectNode) body.path("cultivation")).put("progressGained", true)
                            .put("reason", "听懂掌柜解释的调息要领，形成一枚修行进展");
                    step.put("realmProgressBinding", true);
                }
                ability.put("localRef", "lesson-ability").put("name", ABILITY);
                ability.putArray("attributes").addObject().put("key", "source").put("value", "药庄掌柜");
                ability.withArray("attributes").addObject().put("key", "type").put("value", "调息法门");
                body.withArray("itemChanges").addObject().put("localRef", "lesson-book").put("name", BOOK)
                        .put("status", "随身携带").put("summary", "口诀原文：" + TEACHING).put("visible", true);
                step.put("action", "听掌柜讲解用途与限制，记住口诀并收下抄本").put("result", "掌柜讲解：" + TEACHING + "你记住练法，收好他赠送的一本抄本。");
                step.withArray("assetStepIndexes").add(0);
                step.withArray("statements").addObject().put("sourceRef", SOURCE_ID).put("sourceLabel", "药庄掌柜").put("statement", TEACHING);
                ObjectNode asset = ((ObjectNode) body.path("assetProcess")).withArray("steps").addObject()
                        .put("kind", "ACQUIRE").put("summary", "收到掌柜赠送的一本口诀抄本");
                asset.putArray("inputs"); asset.putArray("observations");
                asset.putArray("outputs").addObject().put("entityRef", "lesson-book").put("amount", "1").put("unit", "本")
                        .putObject("custody").put("holderType", "PLAYER");
            } else {
                abilityId = find(payload.path("objective").path("relatedEntities"), ABILITY).path("id").asText();
                assertFalse(abilityId.isBlank());
                ability.put("existingEntityId", abilityId);
                step.put("action", turn == 2 ? "静坐练习，记录已验证的用途和限制" : "缓步行走时试用，胸紧就停下调整")
                        .put("result", turn == 2 ? "静坐时松肩、放慢呼气，胸口滞涩减轻；行走效果尚未验证。"
                                : "缓步行走时胸口发紧，你停下调整，依口诀恢复气息平稳；快跑和战斗尚未验证。");
            }
            return mapper.writeValueAsString(body);
        }
    }
}
