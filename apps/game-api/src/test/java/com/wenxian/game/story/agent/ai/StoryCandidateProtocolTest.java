package com.wenxian.game.story.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.domain.StoryPatch;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 检查真实原 JSON 的候选边界；不调用模型，不把结构通过当作正文语义通过。 */
class StoryCandidateProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void collectsIndependentStateAndRecordErrorsWithoutDroppingOrCoercingEither() throws Exception {
        ObjectNode raw = candidate();
        changes(raw).putObject("coreState").put("condition", 7);
        changes(raw).putArray("itemChanges").addObject().put("existingEntityId", "coins-existing")
                .putArray("attributes").addObject().put("key", "quantity").put("value", "50");
        records(raw).addObject().put("kind", "EVENT").put("text", false).putArray("about");
        story(raw).put("title", 4);
        ObjectNode original = raw.deepCopy();

        var inspection = StoryCandidateProtocol.inspect(raw.toString());
        assertProblem(inspection, "coreState.condition");
        assertProblem(inspection, "受保护资产字段");
        assertProblem(inspection, "records[0].text");
        JsonNode normalized = mapper.readTree(inspection.normalizedJson());
        assertEquals(original.get("changes"), normalized.get("changes"));
        assertEquals(original.get("records"), normalized.get("records"));
        assertEquals(original, raw);
        assertEquals(List.of("story.title"), inspection.downgrades().stream().map(StoryCandidateProtocol.Downgrade::field).toList());
        assertTrue(normalized.at("/changes/coreState/condition").isIntegralNumber());
        assertTrue(normalized.at("/records/0/text").isBoolean());
    }

    @Test
    void missingOrWrongStateAndRecordsPartitionsCannotBecomeEmptyChanges() throws Exception {
        for (String field : List.of("changes", "records")) {
            ObjectNode missing = candidate();
            missing.remove(field);
            var inspected = StoryCandidateProtocol.inspect(missing.toString());
            assertProblem(inspected, field);
            assertFalse(mapper.readTree(inspected.normalizedJson()).has(field));

            ObjectNode wrong = candidate();
            wrong.put(field, "没有变化");
            var invalid = StoryCandidateProtocol.inspect(wrong.toString());
            assertProblem(invalid, field);
            assertEquals("没有变化", mapper.readTree(invalid.normalizedJson()).get(field).textValue());
        }
        ObjectNode invalidState = candidate();
        changes(invalidState).putArray("entities").addObject().put("existingEntityId", "npc-existing")
                .put("entityType", "CHARACTER").put("scenePresence", "MOVED_AWAY");
        var typed = mapper.treeToValue(invalidState, StoryCandidateProtocol.Draft.class);
        assertThrows(IllegalArgumentException.class, () -> StoryCandidateProtocol.adapt(typed),
                "字符串类型通过不代表未知状态枚举可以降级为空");
    }

    @Test
    void decisionMustBeExplicitNullOrANonblankQuestion() throws Exception {
        ObjectNode missing = candidate();
        story(missing).remove("decision");
        assertProblem(StoryCandidateProtocol.inspect(missing.toString()), "story.decision");
        for (JsonNode invalid : List.of(mapper.getNodeFactory().textNode("  "), mapper.getNodeFactory().numberNode(1))) {
            ObjectNode raw = candidate();
            story(raw).set("decision", invalid);
            var inspected = StoryCandidateProtocol.inspect(raw.toString());
            assertProblem(inspected, "story.decision");
            assertEquals(invalid, mapper.readTree(inspected.normalizedJson()).at("/story/decision"));
            assertTrue(inspected.downgrades().stream().noneMatch(value -> value.field().equals("story.decision")));
        }
        var none = accepted(candidate());
        assertFalse(none.decision().decisionCheckpoint().required());
        ObjectNode question = candidate();
        story(question).put("decision", "是否接受明日同行采药的邀请？");
        var pending = accepted(question);
        assertTrue(pending.decision().decisionCheckpoint().required());
        assertEquals("是否接受明日同行采药的邀请？", pending.decision().decisionCheckpoint().prompt());
    }

    @Test
    void legacyGraphAndWholeTurnOutcomeFieldsAreRejectedAndRemainInOriginal() throws Exception {
        List<Consumer<ObjectNode>> oldCommands = List.of(
                raw -> raw.putObject("orderedResult").putArray("steps"),
                raw -> raw.putObject("outcome").put("resolution", "SUCCEEDED").put("summary", "全部完成"),
                raw -> changes(raw).putObject("outcome").put("resolution", "SUCCEEDED"),
                raw -> raw.putObject("actionUnderstanding").put("purpose", "完成所有事"),
                raw -> records(raw).addObject().put("kind", "EVENT").put("text", "问过价")
                        .putArray("dependsOn").addObject().put("stepIndex", 0).put("expected", "SUCCEEDED"));
        for (Consumer<ObjectNode> oldCommand : oldCommands) {
            ObjectNode raw = candidate();
            oldCommand.accept(raw);
            var inspection = StoryCandidateProtocol.inspect(raw.toString());
            assertProblem(inspection, "未知字段");
            assertEquals(raw, mapper.readTree(inspection.normalizedJson()), "旧字段只报告，不能清掉后继续接受");
        }
    }

    @Test
    void sparseExistingChangesPreserveOmissionsAndNewEntitiesRequireTheirOwnSummary() throws Exception {
        ObjectNode raw = candidate();
        changes(raw).putObject("coreState").put("condition", "劳作后略感疲惫");
        changes(raw).putArray("entities").addObject().put("existingEntityId", "npc-zhao")
                .put("entityType", "CHARACTER").put("summary", "已经履行本次口授约定。");
        var adapted = accepted(raw);
        assertNull(adapted.decision().outcome());
        assertNull(adapted.decision().orderedResult());
        assertNull(adapted.decision().actionUnderstanding());
        assertNull(adapted.decision().cultivation());
        assertNull(adapted.decision().coreState().location());
        assertEquals("劳作后略感疲惫", adapted.decision().coreState().condition());
        assertEquals(1, adapted.decision().entities().size());
        var person = adapted.decision().entities().getFirst();
        assertEquals("npc-zhao", person.existingEntityId());
        assertTrue(person.name().isEmpty());
        assertTrue(person.status().isEmpty());
        assertNull(person.visible());
        assertNull(person.quantity());
        assertNull(person.custody());
        assertTrue(adapted.decision().assetProcess().steps().isEmpty());

        ObjectNode missingSummary = candidate();
        changes(missingSummary).putArray("abilityChanges").addObject().put("localRef", "lesson")
                .put("entityType", "ABILITY").put("name", "辨草入门").put("abilityState", "KNOWN");
        assertProblem(StoryCandidateProtocol.inspect(missingSummary.toString()), "summary");
        assertThrows(IllegalArgumentException.class,
                () -> StoryCandidateProtocol.adapt(mapper.treeToValue(missingSummary, StoryCandidateProtocol.Draft.class)));
    }

    @Test
    void responseBytesAndJsonDepthAreBoundedBeforeTypedAdaptation() throws Exception {
        ObjectNode raw = candidate();
        story(raw).put("narrative", "");
        int overhead = utf8(raw.toString());
        story(raw).put("narrative", "a".repeat(StoryCandidateProtocol.MAX_RESPONSE_BYTES - overhead));
        assertEquals(256 * 1024, utf8(raw.toString()));
        assertTrue(StoryCandidateProtocol.inspect(raw.toString()).problems().isEmpty());
        story(raw).put("narrative", raw.at("/story/narrative").textValue() + "a");
        var tooLarge = StoryCandidateProtocol.inspect(raw.toString());
        assertProblem(tooLarge, "256 KiB");
        assertNull(tooLarge.normalizedJson());

        String atDepth = "{\"x\":".repeat(32) + "0" + "}".repeat(32);
        assertNotNull(StoryCandidateProtocol.inspect(atDepth).normalizedJson(),
                "深度 32 仍可解析，随后由普通字段校验拒绝未知结构");
        String tooDeep = "{\"x\":".repeat(33) + "0" + "}".repeat(33);
        var rejected = StoryCandidateProtocol.inspect(tooDeep);
        assertNull(rejected.normalizedJson());
        assertProblem(rejected, "32 层");
    }

    @Test
    void perRecordAndTotalRecordLimitsUseUtf8BytesAndNeverTruncateTheArray() throws Exception {
        ObjectNode atEntry = candidate();
        records(atEntry).add(entryOfBytes(16 * 1024));
        assertTrue(StoryCandidateProtocol.inspect(atEntry.toString()).problems().isEmpty());

        ObjectNode beyondEntry = candidate();
        records(beyondEntry).add(entryOfBytes(16 * 1024 + 1));
        var singleRejected = StoryCandidateProtocol.inspect(beyondEntry.toString());
        assertProblem(singleRejected, "16 KiB");
        assertEquals(beyondEntry.get("records"), mapper.readTree(singleRejected.normalizedJson()).get("records"));

        ObjectNode atTotal = candidate();
        records(atTotal).add(entryOfBytes(16 * 1024));
        records(atTotal).add(entryOfBytes(16 * 1024 - 3)); // 数组的两个括号及一个逗号共三字节。
        assertEquals(32 * 1024, utf8(records(atTotal).toString()));
        assertTrue(StoryCandidateProtocol.inspect(atTotal.toString()).problems().isEmpty());
        ObjectNode second = (ObjectNode) records(atTotal).get(1);
        second.put("text", second.path("text").textValue() + "修");
        var totalRejected = StoryCandidateProtocol.inspect(atTotal.toString());
        assertProblem(totalRejected, "32 KiB");
        assertEquals(records(atTotal), mapper.readTree(totalRejected.normalizedJson()).get("records"));

        ObjectNode chinese = candidate();
        ObjectNode item = entryOfBytes(100);
        item.put("text", "修".repeat(5500));
        records(chinese).add(item);
        assertTrue(item.path("text").textValue().length() < 16 * 1024);
        assertProblem(StoryCandidateProtocol.inspect(chinese.toString()), "16 KiB");
    }

    @Test
    void onlyEffectiveCultivationRequiresLocalOutcomeAndNoGlobalSuccessIsInvented() throws Exception {
        ObjectNode noGrowth = candidate();
        changes(noGrowth).putObject("cultivation").put("progressGained", false).put("breakthroughAttempted", false)
                .put("reason", "仅听过方法，尚未形成境界进展。").putNull("actionOutcome").putArray("evidenceRefs");
        var accepted = accepted(noGrowth);
        assertNull(accepted.actionOutcome());
        assertNull(accepted.decision().outcome());
        assertFalse(accepted.decision().cultivation().progressGained());
        assertFalse(accepted.decision().cultivation().breakthroughAttempted());
        for (String intent : List.of("progressGained", "breakthroughAttempted")) {
            ObjectNode effective = noGrowth.deepCopy();
            ObjectNode cultivation = (ObjectNode) changes(effective).get("cultivation");
            cultivation.put(intent, true);
            cultivation.remove("actionOutcome");
            assertProblem(StoryCandidateProtocol.inspect(effective.toString()), "actionOutcome");
            assertThrows(IllegalArgumentException.class,
                    () -> StoryCandidateProtocol.adapt(mapper.treeToValue(effective, StoryCandidateProtocol.Draft.class)));
            for (StoryPatch.OutcomeResolution resolution : StoryPatch.OutcomeResolution.values()) {
                cultivation.put("actionOutcome", resolution.name());
                var proposal = accepted(effective);
                assertEquals(resolution, proposal.actionOutcome());
                assertNull(proposal.decision().outcome(), "局部失败或成功均不变成整轮成功枚举");
            }
        }
    }

    private ObjectNode candidate() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"story":{"title":"摊前请教","narrative":"你听过辨草方法，尚未独立验证。",
                "decision":null,"choices":[],"summary":""},
                "changes":{"duration":{"unit":"SCENE","value":1,"targetPhase":""},"timeResolution":"COMPLETE"},
                "records":[]}
                """);
    }

    private StoryCandidateProtocol.Adapted accepted(ObjectNode raw) throws Exception {
        var inspection = StoryCandidateProtocol.inspect(raw.toString());
        assertTrue(inspection.problems().isEmpty(), inspection.problems().toString());
        return StoryCandidateProtocol.adapt(mapper.readValue(inspection.normalizedJson(), StoryCandidateProtocol.Draft.class));
    }

    private ObjectNode entryOfBytes(int byteCount) {
        ObjectNode entry = mapper.createObjectNode().put("kind", "EVENT").put("text", "");
        entry.putNull("sourceRef").putNull("sourceLabel").putArray("about");
        entry.put("text", "a".repeat(byteCount - utf8(entry.toString())));
        assertEquals(byteCount, utf8(entry.toString()));
        return entry;
    }

    private static void assertProblem(StoryCandidateProtocol.Inspection inspection, String fragment) {
        assertTrue(inspection.problems().stream().anyMatch(problem -> problem.contains(fragment)),
                () -> "应包含问题 " + fragment + "，实际为 " + inspection.problems());
    }
    private static ObjectNode story(ObjectNode raw) { return (ObjectNode) raw.get("story"); }
    private static ObjectNode changes(ObjectNode raw) { return (ObjectNode) raw.get("changes"); }
    private static ArrayNode records(ObjectNode raw) { return (ArrayNode) raw.get("records"); }
    private static int utf8(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
}
