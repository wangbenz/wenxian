package com.wenxian.game.story.replay.playerintent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.model.infrastructure.ChatModelFactory;
import com.wenxian.game.story.replay.single.SingleCandidateGenerator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单次完整故事候选的隔离 H2 验收，使用内存 ChatModel 经过真实类型化接口、正式领域规则及提交器。
 *
 * <p>连续回合只从上轮提交结果重载；固定的完整候选同时提供经历、能力和正文，不能调用独立 writer。
 * 覆盖唯一修复的用量、服务端结果对齐与事务失败边界，不验证供应商生成质量、中文语义或正式额度门禁。
 * C10 只复用已泛化的实体和世界前态，追加学习行动及圆满前态均为本测试显式构造。</p>
 */
class SingleCandidateReplayTest {
    private static final String ABILITY = "松息口诀";
    private static final String BOOK = "松息口诀抄本";
    private static final String TEACHING = "先松开肩背，再放慢呼气；只能辅助调息，不能用来攻伐。";
    private static final List<String> LEARNING = List.of(
            "听懂松肩、缓呼的练法，尚未独自验证。",
            "静坐时可独自松肩、缓呼；行走效果尚未验证。",
            "缓步可调息，快走容易乱；仍不能用于战斗。");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void threeLearningTurnsKeepOneAbilityAndTheSameStoryAcrossCommitAndNextActualRequest() throws Exception {
        Models models = new Models((request, call) -> json(lesson(request, call)));
        var fixture = learningFixture();
        var replay = new PlayerIntentReplay(mapper).runSequence(fixture,
                new PlayerIntentReplay.ContinuationPlan("single-learning-sequence", fixture.caseId(), List.of(
                        new PlayerIntentReplay.FollowupAction("按刚学的方法静坐练习，确认用途和限制。", null),
                        new PlayerIntentReplay.FollowupAction("缓步与快走时检验调息，胸紧就停下，不硬撑。", null))),
                generator(models));

        assertEquals("COMMITTED_IN_H2", replay.status(), () -> jsonUnchecked(replay));
        assertEquals(3, replay.turns().size());
        assertEquals(3, models.requests.size(), "普通回合各调用一次内存模型，没有独立正文调用");
        String abilityId = entity(replay.turns().getFirst().result().after(), ABILITY).get("id");
        String bookId = entity(replay.turns().getFirst().result().after(), BOOK).get("id");
        for (int index = 0; index < 3; index++) {
            var turn = replay.turns().get(index);
            var result = turn.result();
            JsonNode original = mapper.readTree(models.responses.get(index));
            JsonNode story = original.path("story");
            var row = storedTurn(result.after(), turn.requestId());
            var patch = mapper.readTree(row.get("state_patch_json"));
            var ability = entity(result.after(), ABILITY);
            assertEquals(abilityId, ability.get("id"));
            assertEquals(bookId, entity(result.after(), BOOK).get("id"));
            assertEquals(LEARNING.get(index), ability.get("summary"));
            assertEquals(index == 0 ? "KNOWN" : "LEARNING", mapper.readTree(ability.get("attributes_json"))
                    .path("_trusted").path("abilityState").asText());
            assertEquals(story.path("narrative").asText(), row.get("narrative_text"));
            assertEquals(story.path("narrative"), result.prepared().path("acceptedPatch").path("narrative"));
            assertEquals(story.path("summary"), result.prepared().path("acceptedPatch").path("rollingSummary"));
            assertEquals(result.prepared().path("acceptedResult"), patch.path("acceptedResult"));
            assertEquals(LEARNING.get(index), patch.path("acceptedResult").path("steps").get(0).path("result").asText());
            assertGenerationUsage(result, 1, 21 + index, 11 + index);
            assertEquals(Integer.toString(21 + index), row.get("prompt_tokens"));
            assertEquals(Integer.toString(11 + index), row.get("completion_tokens"));
            assertEquals("GENERATE_COMPLETE_CANDIDATE", models.requests.get(index).path("mode").asText());
            assertEquals(List.of("single-generation", "single-accepted"), phases(result));
            if (index > 0) {
                var previous = replay.turns().get(index - 1);
                assertEquals(previous.result().after(), result.before(), "下一轮前态必须来自上轮真实提交");
                JsonNode actual = models.requests.get(index);
                JsonNode recalledAbility = find(actual.path("objective").path("relatedEntities"), ABILITY);
                assertEquals(abilityId, recalledAbility.path("id").asText());
                assertEquals(LEARNING.get(index - 1), recalledAbility.path("summary").asText());
                JsonNode previousTurn = actual.path("narrative").path("previousTurn");
                assertEquals(storedTurn(previous.result().after(), previous.requestId()).get("narrative_text"),
                        previousTurn.path("narrative").asText());
                assertEquals(previous.result().prepared().path("acceptedResult"), previousTurn.path("acceptedResult"));
                JsonNode teaching = StreamSupport.stream(actual.path("narrative").path("recentTurns").spliterator(), false)
                        .map(value -> value.path("acceptedResult"))
                        .filter(value -> value.equals(replay.turns().getFirst().result().prepared().path("acceptedResult")))
                        .findFirst().orElseThrow();
                assertTeaching(teaching);
            }
        }
        assertEquals(1, replay.finalAuthority().tables().get("story_entity").stream()
                .filter(row -> ABILITY.equals(row.get("name"))).count());
        assertEquals(0, state(replay.finalAuthority()).path("trustedProjection")
                .path("protagonistCultivation").path("progressMarks").asInt());
    }

    @Test
    void paperAndTeachingSaveAsSeparateItemAndAbilityFromOneCompleteCandidate() throws Exception {
        Models models = new Models((request, call) -> json(lesson(request, 1)));
        var result = run(learningFixture(), models);
        assertCommitted(result);
        Map<String, String> ability = entity(result.after(), ABILITY);
        Map<String, String> book = entity(result.after(), BOOK);
        assertEquals("ABILITY", ability.get("entity_type"));
        assertEquals("ITEM", book.get("entity_type"));
        assertNotEquals(ability.get("id"), book.get("id"));
        JsonNode bookAttributes = mapper.readTree(book.get("attributes_json"));
        assertEquals(1, bookAttributes.path("_trusted").path("quantity").path("value").asInt());
        assertEquals("本", bookAttributes.path("quantityUnit").asText());
        JsonNode abilityAttributes = mapper.readTree(ability.get("attributes_json"));
        assertFalse(abilityAttributes.path("_trusted").hasNonNull("quantity"));
        assertEquals("KNOWN", abilityAttributes.path("_trusted").path("abilityState").asText());
        assertTeaching(result.prepared().path("acceptedResult"));
        JsonNode stored = newPatch(result);
        assertEquals(book.get("id"), stored.path("assetProcess").path("steps").get(0)
                .path("outputs").get(0).path("entityRef").asText());
        assertNotEquals("book", book.get("id"));
        assertGenerationUsage(result, 1, 21, 11);
    }

    @Test
    void twoOverspendingCandidatesExhaustTheOnlyRepairAndKeepAllOriginalTables() throws Exception {
        Models models = new Models((request, call) -> json(overspend()));
        var result = run(fixture("尝试用四株青叶草配药；不足就停下。"), models);
        assertUnchanged(result);
        assertEquals(2, models.requests.size());
        JsonNode repair = models.requests.getLast();
        assertEquals("REPAIR_COMPLETE_CANDIDATE", repair.path("mode").asText());
        assertEquals("COMPILATION", repair.path("rejectedPhase").asText());
        assertEquals(mapper.readTree(models.responses.getFirst()).path("story"), repair.path("rejectedDraft").path("story"));
        assertEquals("4", repair.path("rejectedDraft").path("decision").path("assetProcess").path("steps").get(0)
                .path("inputs").get(0).path("amount").asText());
        assertEquals(2, result.stages().stream().filter(stage -> "single-rejected".equals(stage.path("phase").asText())).count());
        assertEquals(3, mapper.readTree(entity(result.after(), "青叶草").get("attributes_json"))
                .path("_trusted").path("quantity").path("value").asInt());
    }

    @Test
    void typedDecodeFailurePreservesRawCandidateAndKnownUsageThenAcceptsOneWholeRewrite() throws Exception {
        Models models = new Models((request, call) -> {
            ObjectNode candidate = lesson(request, 1);
            if (call == 1) ((ObjectNode) candidate.path("decision").path("abilityChanges").get(0))
                    .putObject("attributes").put("source", "错把属性数组写成对象");
            return json(candidate);
        });
        var result = run(learningFixture(), models);
        assertCommitted(result);
        assertEquals(2, models.requests.size());
        JsonNode repair = models.requests.getLast();
        assertEquals("DECODING", repair.path("rejectedPhase").asText());
        assertTrue(repair.path("rejectedDraft").isTextual());
        assertEquals(models.responses.getFirst(), repair.path("rejectedDraft").asText());
        JsonNode rejected = result.stages().stream().filter(stage -> "single-rejected".equals(stage.path("phase").asText()))
                .findFirst().orElseThrow();
        assertEquals(models.responses.getFirst(), rejected.path("rejectedDraft").asText());
        assertEquals(21, rejected.path("knownModelCalls").get(0).path("inputTokens").asInt());
        assertEquals(11, rejected.path("knownModelCalls").get(0).path("outputTokens").asInt());
        assertGenerationUsage(result, 2, 43, 23);
        assertEquals("43", newTurn(result).get("prompt_tokens"));
        assertEquals("23", newTurn(result).get("completion_tokens"));
        assertEquals(mapper.readTree(models.responses.getLast()).path("story").path("narrative").asText(),
                newTurn(result).get("narrative_text"));
        assertEquals(List.of("single-generation", "single-rejected", "single-repair", "single-accepted"), phases(result));
    }

    @Test
    void serverRankAdvanceMustBeReturnedToTheSingleRewriteBeforeItsStoryCanCommit() throws Exception {
        Models models = new Models((request, call) -> {
            ObjectNode candidate = rankCandidate(call == 1
                ? "你运转灵力尝试冲关，停下来察看结果。"
                : "灵力运行终于稳定下来，你已进入筑基初期，收束气息后停下。");
            if (call == 2) ((ObjectNode) candidate.path("decision").path("cultivation"))
                    .put("reason", "本轮运转既有圆满积累，完成冲关后收功");
            return json(candidate);
        });
        var result = run(rankFixture(), models);
        assertCommitted(result);
        assertEquals(2, models.requests.size(), "服务端补充晋升时不能静默保留首稿正文");
        JsonNode repair = models.requests.getLast();
        JsonNode alignment = repair.path("serverAlignment").path("realmEffect");
        assertEquals("RANK_ADVANCED", alignment.path("resolution").asText());
        assertEquals("realm:xuanxu:human:l2", alignment.path("stageId").asText());
        assertEquals("EARLY", alignment.path("phase").asText());
        assertTrue(repair.path("validationProblem").asText().contains("SINGLE_CANDIDATE_SERVER_ALIGNMENT"));
        assertEquals(mapper.readTree(models.responses.getFirst()).path("story"), repair.path("rejectedDraft").path("story"));
        assertEquals(mapper.readTree(models.responses.getFirst()).path("decision").path("orderedResult").path("steps").get(0).path("result"),
                repair.path("rejectedDraft").path("decision").path("orderedResult").path("steps").get(0).path("result"));
        JsonNode cultivation = state(result.after()).path("trustedProjection").path("protagonistCultivation");
        assertEquals("realm:xuanxu:human:l2", cultivation.path("currentStageId").asText());
        assertEquals("EARLY", cultivation.path("phase").asText());
        assertEquals(0, cultivation.path("progressMarks").asInt());
        assertEquals("RANK_ADVANCED", newPatch(result).path("acceptedResult").path("steps").get(0)
                .path("realmResult").path("resolution").asText());
        assertEquals(mapper.readTree(models.responses.getLast()).path("story").path("narrative").asText(),
                newTurn(result).get("narrative_text"));
        assertGenerationUsage(result, 2, 43, 23);
    }

    @Test
    void decodingRepairThatNewlyTriggersRankAdvanceCannotUseAnUnprovidedAlignmentOrThirdCall() throws Exception {
        Models models = new Models((request, call) -> {
            if (call == 1) {
                ObjectNode malformed = complete(baseDecision(), "你停下来检查修行基础。");
                malformed.withObject("story").putObject("choices").put("title", "不是选项数组");
                return json(malformed);
            }
            return json(rankCandidate("你完成冲关，停下来查看修为。"));
        });
        var result = run(rankFixture(), models);
        assertUnchanged(result);
        assertEquals(2, models.requests.size());
        assertEquals("DECODING", models.requests.getLast().path("rejectedPhase").asText());
        assertFalse(models.requests.getLast().has("serverAlignment"));
        JsonNode last = result.stages().getLast();
        assertEquals("single-rejected", last.path("phase").asText());
        assertTrue(last.path("problem").asText().contains("SINGLE_CANDIDATE_ALIGNMENT_CHANGED"));
        assertEquals("realm:xuanxu:human:l1", state(result.after()).path("trustedProjection")
                .path("protagonistCultivation").path("currentStageId").asText());
    }

    @Test
    void bothCandidatesWhoseCompletedTimeIsOverriddenStopWithoutChangingClockOrRequestingAThird() throws Exception {
        Models models = new Models((request, call) -> {
            ObjectNode decision = baseDecision();
            if (call == 2) decision.withObject("duration").put("unit", "DAY");
            return json(complete(decision, call == 1 ? "你暂时收功停下。" : "你练了一日后收功停下。"));
        });
        var result = run(fixture("闭关五年，潜心参悟已有功法。"), models);
        assertUnchanged(result);
        assertEquals(2, models.requests.size());
        JsonNode planned = models.requests.getFirst().path("action").path("semanticDuration");
        assertEquals("YEAR", planned.path("unit").asText());
        assertEquals(5, planned.path("value").asInt());
        assertTrue(models.requests.getLast().path("validationProblem").asText().contains("SINGLE_CANDIDATE_TIME_CHANGED"));
        assertTrue(result.stages().getLast().path("problem").asText().contains("SINGLE_CANDIDATE_TIME_CHANGED"));
        assertEquals(state(result.before()).path("worldTime"), state(result.after()).path("worldTime"));
    }

    @Test
    void transportFailureGetsNoAutomaticProtocolRepairAndNoPartialSave() throws Exception {
        Models models = new Models((request, call) -> {
            throw new IllegalStateException("offline transport boundary", new SocketTimeoutException("offline timeout"));
        });
        var result = run(learningFixture(), models);
        assertUnchanged(result);
        assertEquals(1, models.requests.size());
        assertEquals(0, models.responses.size());
        assertEquals("ModelInvocationException", result.errorType());
        assertFalse(phases(result).contains("single-repair"));
        assertEquals("single-failed", result.stages().getLast().path("phase").asText());
    }

    @Test
    void oneRepairReceivesClockAndRealmTogetherInsteadOfDiscoveringTheRealmAfterFixingTime() throws Exception {
        var base = rankFixture();
        var fixture = new PlayerIntentReplay.CaseFixture("single_time_rank", base.sourceEvidence(), base.initialState(),
                base.initialSummary(), base.entities(), base.memories(), base.history(),
                "闭关五年，以已有圆满积累尝试突破，收功后停下。", null);
        Models models = new Models((request, call) -> {
            ObjectNode candidate = rankCandidate(call == 1 ? "你在闭关中完成冲关。" : "五年闭关结束，你收功时已是筑基初期。");
            if (call == 2) ((ObjectNode) candidate.path("decision").path("duration")).put("unit", "YEAR").put("value", 5);
            return json(candidate);
        });
        var result = run(fixture, models);
        assertCommitted(result);
        assertEquals(2, models.requests.size());
        JsonNode feedback = models.requests.getLast().path("serverAlignment");
        assertEquals("YEAR", feedback.path("timeEffect").path("resolvedDuration").path("unit").asText());
        assertEquals(5, feedback.path("timeEffect").path("resolvedDuration").path("value").asInt());
        assertEquals("RANK_ADVANCED", feedback.path("realmEffect").path("resolution").asText());
        assertEquals("realm:xuanxu:human:l2", state(result.after()).path("trustedProjection")
                .path("protagonistCultivation").path("currentStageId").asText());
    }

    @Test
    void equivalentSceneDurationsDoNotSpendTheOnlyRepair() throws Exception {
        Models models = new Models((request, call) -> {
            ObjectNode decision = baseDecision();
            decision.withObject("duration").put("value", 2);
            return json(complete(decision, "你依照刚才的指点再次调匀呼吸，随即收功。"));
        });
        var result = run(fixture("再次调匀呼吸，随即收功。"), models);
        assertCommitted(result);
        assertEquals(1, models.requests.size(), "同一末时间不能因 duration 写法不同占用修复");
        assertEquals(state(result.before()).path("worldTime"), state(result.after()).path("worldTime"));
    }

    @Test
    void failureAfterTurnInsertRollsBackTheWholeCandidateIncludingBookAbilityAndMemories() throws Exception {
        Models models = new Models((request, call) -> json(lesson(request, 1)));
        var result = new PlayerIntentReplay(mapper).runWithFailureAfterTurnInsert(learningFixture(), generator(models));
        assertUnchanged(result);
        assertNotNull(result.prepared(), "必须实际完成准备并进入提交故障点，不能以提前失败冒充回滚");
        assertEquals("single-accepted", result.stages().getLast().path("phase").asText());
        assertEquals(1, models.requests.size());
        assertTrue(result.after().tables().get("story_entity").stream()
                .noneMatch(row -> BOOK.equals(row.get("name")) || ABILITY.equals(row.get("name"))));
        assertGenerationUsage(result, 1, 21, 11);
    }

    private SingleCandidateGenerator generator(Models models) {
        return SingleCandidateGenerator.create(mapper, models.factory(), "http://127.0.0.1:26001/v1", "offline");
    }

    private PlayerIntentReplay.ReplayResult run(PlayerIntentReplay.CaseFixture fixture, Models models) {
        return new PlayerIntentReplay(mapper).run(fixture, generator(models));
    }

    private PlayerIntentReplay.CaseFixture learningFixture() throws IOException {
        return fixture("请掌柜传授一段调息口诀，问清用途和限制；若他愿意给抄本就收好。");
    }

    private PlayerIntentReplay.CaseFixture fixture(String action) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream("story/replay/player-intent/C10_sell_all_herbs_bound.json")) {
            var base = mapper.readValue(input, PlayerIntentReplay.CaseFixture.class);
            return new PlayerIntentReplay.CaseFixture("single_offline_candidate",
                    mapper.createObjectNode().put("kind", "SYNTHETIC_FIXED_RESPONSE_TEST"),
                    base.initialState(), base.initialSummary(), base.entities(), base.memories(), base.history(), action, null);
        }
    }

    private PlayerIntentReplay.CaseFixture rankFixture() throws IOException {
        var base = fixture("以已积累的修为尝试突破，收功后停下察看结果。");
        ObjectNode state = base.initialState().deepCopy();
        ((ObjectNode) state.path("trustedProjection").path("protagonistCultivation"))
                .put("phase", "PERFECT").put("progressMarks", 1).put("requiredProgressMarks", 2)
                .put("progressNote", "本测试显式给定已有圆满积累");
        return new PlayerIntentReplay.CaseFixture("single_offline_rank",
                mapper.createObjectNode().put("kind", "SYNTHETIC_PERFECT_REALM_PRESTATE"), state,
                base.initialSummary(), base.entities(), base.memories(), base.history(), base.action(), null);
    }

    private ObjectNode baseDecision() throws IOException {
        return (ObjectNode) mapper.readTree("""
                {"duration":{"unit":"SCENE","value":1},"timeResolution":"COMPLETE",
                 "cultivation":{"progressGained":false,"breakthroughAttempted":false,"reason":"普通学习或应用","evidenceRefs":[]},
                 "itemChanges":[],"abilityChanges":[],"entities":[],"worldFacts":[],"assetProcess":{"steps":[]},
                 "actionUnderstanding":{"purpose":"完成本次尝试并保留实际限制","constraints":["不硬撑"]},
                 "orderedResult":{"steps":[{"action":"尝试并查看结果","resolution":"SUCCEEDED","result":"完成本次尝试后停下",
                   "realmProgressBinding":false,"assetStepIndexes":[],"dependsOn":[],"statements":[]}],
                   "stop":{"reason":"ACTION_RESOLVED"}}}
                """);
    }

    private ObjectNode lesson(JsonNode context, int turn) throws IOException {
        ObjectNode decision = baseDecision();
        ObjectNode step = firstStep(decision);
        step.put("result", LEARNING.get(turn - 1));
        ObjectNode ability = decision.withArray("abilityChanges").addObject().put("entityType", "ABILITY")
                .put("status", "ACTIVE").put("abilityState", turn == 1 ? "KNOWN" : "LEARNING")
                .put("visible", true).put("summary", LEARNING.get(turn - 1));
        if (turn == 1) {
            ability.put("localRef", "lesson").put("name", ABILITY);
            ability.putArray("attributes").addObject().put("key", "source").put("value", "药庄掌柜");
            decision.withArray("itemChanges").addObject().put("localRef", "book").put("name", BOOK)
                    .put("status", "随身携带").put("summary", TEACHING).put("visible", true);
            step.withArray("statements").addObject().put("sourceRef", "entity:case:merchant")
                    .put("sourceLabel", "药庄掌柜").put("statement", TEACHING);
            step.withArray("assetStepIndexes").add(0);
            ObjectNode asset = decision.withObject("assetProcess").withArray("steps").addObject()
                    .put("kind", "ACQUIRE").put("summary", "收下口诀抄本");
            asset.putArray("inputs");
            asset.putArray("observations");
            asset.putArray("outputs").addObject().put("entityRef", "book").put("amount", "1").put("unit", "本")
                    .putObject("custody").put("holderType", "PLAYER");
        } else {
            ability.put("existingEntityId", find(context.path("objective").path("relatedEntities"), ABILITY).path("id").asText());
        }
        String prose = switch (turn) {
            case 1 -> "掌柜说：" + TEACHING + "你收好抄本，记住他的演示，还没有独自练过。";
            case 2 -> "你盘坐下来，先放松双肩再缓缓呼气，独自找回平稳节奏。走动时是否有用，还需亲自试过。";
            case 3 -> "缓步时，你仍能按口诀调匀呼吸。加快步子后节奏散了，你立即停下；这门方法还帮不了你应战。";
            default -> throw new IllegalArgumentException("unexpected fixed learning turn");
        };
        ObjectNode candidate = complete(decision, prose);
        candidate.withObject("story").put("summary", LEARNING.get(turn - 1));
        return candidate;
    }

    private ObjectNode overspend() throws IOException {
        ObjectNode decision = baseDecision();
        firstStep(decision).withArray("assetStepIndexes").add(0);
        ObjectNode asset = decision.withObject("assetProcess").withArray("steps").addObject()
                .put("kind", "CONSUME").put("summary", "消耗四株青叶草");
        asset.putArray("outputs");
        asset.putArray("observations");
        asset.putArray("inputs").addObject().put("entityRef", "entity:case:herba").put("amount", "4").put("unit", "株")
                .putObject("custody").put("holderType", "PLAYER");
        return complete(decision, "你将四株青叶草投入药炉。");
    }

    private ObjectNode rankCandidate(String narrative) throws IOException {
        ObjectNode decision = baseDecision();
        decision.withObject("cultivation").put("progressGained", true).put("breakthroughAttempted", true)
                .put("reason", "以已有圆满积累完成本次冲关").withArray("evidenceRefs").add("PLAYER_ACTION");
        firstStep(decision).put("realmProgressBinding", true).put("result", narrative);
        return complete(decision, narrative);
    }

    private ObjectNode complete(ObjectNode decision, String narrative) {
        ObjectNode candidate = mapper.createObjectNode();
        candidate.set("decision", decision);
        ObjectNode story = candidate.putObject("story").put("title", "学以致用").put("narrative", narrative).put("summary", narrative);
        story.putArray("choices");
        return candidate;
    }

    private ObjectNode firstStep(ObjectNode decision) {
        return (ObjectNode) decision.path("orderedResult").path("steps").get(0);
    }

    private JsonNode find(JsonNode values, String name) {
        return StreamSupport.stream(values.spliterator(), false)
                .filter(value -> name.equals(value.path("name").asText())).findFirst().orElseThrow();
    }

    private Map<String, String> entity(PlayerIntentReplay.Authority authority, String name) {
        return authority.tables().get("story_entity").stream().filter(row -> name.equals(row.get("name"))).findFirst().orElseThrow();
    }

    private Map<String, String> storedTurn(PlayerIntentReplay.Authority authority, String requestId) {
        return authority.tables().get("story_turn").stream().filter(row -> requestId.equals(row.get("request_id"))).findFirst().orElseThrow();
    }

    private Map<String, String> newTurn(PlayerIntentReplay.ReplayResult result) {
        return result.after().tables().get("story_turn").stream()
                .filter(row -> result.before().tables().get("story_turn").stream()
                        .noneMatch(previous -> previous.get("id").equals(row.get("id"))))
                .findFirst().orElseThrow();
    }

    private JsonNode newPatch(PlayerIntentReplay.ReplayResult result) throws IOException {
        return mapper.readTree(newTurn(result).get("state_patch_json"));
    }

    private JsonNode state(PlayerIntentReplay.Authority authority) throws IOException {
        return mapper.readTree(authority.tables().get("story_save").getFirst().get("state_json"));
    }

    private void assertTeaching(JsonNode acceptedResult) {
        JsonNode source = acceptedResult.path("steps").get(0).path("statements").get(0);
        assertEquals("entity:case:merchant", source.path("sourceRef").asText());
        assertEquals("药庄掌柜", source.path("sourceLabel").asText());
        assertEquals(TEACHING, source.path("statement").asText());
    }

    private void assertGenerationUsage(PlayerIntentReplay.ReplayResult result, int calls, int input, int output) {
        JsonNode known = result.prepared().path("generation").path("modelCalls");
        assertEquals(calls, known.size());
        assertEquals(input, StreamSupport.stream(known.spliterator(), false).mapToInt(call -> call.path("inputTokens").asInt()).sum());
        assertEquals(output, StreamSupport.stream(known.spliterator(), false).mapToInt(call -> call.path("outputTokens").asInt()).sum());
    }

    private void assertCommitted(PlayerIntentReplay.ReplayResult result) {
        assertEquals("COMMITTED_IN_H2", result.status(), () -> jsonUnchecked(result));
    }

    private void assertUnchanged(PlayerIntentReplay.ReplayResult result) {
        assertEquals("FAILED", result.status(), () -> jsonUnchecked(result));
        assertTrue(result.unchangedOnFailure());
        assertEquals(result.before(), result.after());
    }

    private List<String> phases(PlayerIntentReplay.ReplayResult result) {
        return result.stages().stream().map(stage -> stage.path("phase").asText()).toList();
    }

    private String json(Object value) throws IOException { return mapper.writeValueAsString(value); }

    private String jsonUnchecked(Object value) {
        try { return json(value); }
        catch (IOException error) { throw new IllegalStateException(error); }
    }

    @FunctionalInterface
    private interface ResponseScript { String respond(JsonNode actualRequest, int call) throws IOException; }

    private final class Models {
        private final ResponseScript script;
        private final List<JsonNode> requests = new ArrayList<>();
        private final List<String> responses = new ArrayList<>();

        private Models(ResponseScript script) { this.script = script; }

        private ChatModelFactory factory() {
            return (definition, credential, options) -> {
                assertEquals(0, options.maximumRetries());
                assertNull(options.maximumOutputTokens());
                return new ChatModel() {
                    @Override public ChatResponse doChat(ChatRequest request) {
                        try {
                            String raw = request.messages().stream().filter(UserMessage.class::isInstance)
                                    .map(UserMessage.class::cast).reduce((previous, next) -> next).orElseThrow().singleText();
                            JsonNode input = mapper.readTree(raw);
                            assertTrue(input.path("mode").asText().endsWith("COMPLETE_CANDIDATE"),
                                    "只能发出完整候选请求，不能偷发独立 writer 请求");
                            requests.add(input);
                            int call = requests.size();
                            String response = script.respond(input, call);
                            responses.add(response);
                            return ChatResponse.builder().aiMessage(AiMessage.from(response))
                                    .tokenUsage(new TokenUsage(20 + call, 10 + call)).build();
                        } catch (IOException error) { throw new IllegalStateException(error); }
                    }
                };
            };
        }
    }
}
