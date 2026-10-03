package com.wenxian.game.story.agent.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.agent.ai.TurnDirectorAi.*;
import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.AssetProcessCompiler;
import com.wenxian.game.story.domain.StoryPatch;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 验证模型窄协议的确定性转换、严格解码与修复重编码；不调用模型或访问数据库。 */
class DirectorAssetDraftAdapterTest {
    private final DirectorAssetDraftAdapter adapter = new DirectorAssetDraftAdapter();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final CustodyDraft PLAYER = new CustodyDraft("PLAYER", "", "");

    @Test
    void suppliesInternalVersionAndOrderedStepIdsWithoutRequestingThemFromModel() {
        AssetProcessDraft draft = new AssetProcessDraft(List.of(
                step(AssetProcess.Kind.CONSUME, List.of(flow("herb", "3")), List.of()),
                step(AssetProcess.Kind.CONSUME, List.of(flow("other-herb", "ALL")), List.of()),
                step(AssetProcess.Kind.ACQUIRE, List.of(), List.of(flow("medicine", "UNKNOWN")))));
        AssetProcess.Proposal result = adapter.toDomain(draft);
        assertEquals(AssetProcess.VERSION, result.protocolVersion());
        assertEquals(List.of("step-1", "step-2", "step-3"), result.steps().stream().map(AssetProcess.Step::stepId).toList());
        assertEquals(new StoryPatch.QuantityValue(true, 3), result.steps().getFirst().inputs().getFirst().amount());
        assertFalse(result.steps().getFirst().inputs().getFirst().all());
        assertTrue(result.steps().get(1).inputs().getFirst().all());
        assertNull(result.steps().get(1).inputs().getFirst().amount());
        assertEquals(new StoryPatch.QuantityValue(false, null), result.steps().getLast().outputs().getFirst().amount());
        assertFalse(result.steps().getLast().outputs().getFirst().all());
    }

    @Test
    void acceptsMaximumPositiveIntegerAndRejectsOverflowWithoutNumericCoercion() {
        AssetProcess.Proposal maximum = adapter.toDomain(process(step(AssetProcess.Kind.CONSUME,
                List.of(flow("herb", Integer.toString(Integer.MAX_VALUE))), List.of())));
        assertEquals(Integer.MAX_VALUE, maximum.steps().getFirst().inputs().getFirst().amount().value());
        AssetProcess.ProtocolException error = assertThrows(AssetProcess.ProtocolException.class,
                () -> adapter.toDomain(process(step(AssetProcess.Kind.CONSUME,
                        List.of(flow("herb", "2147483648")), List.of()))));
        assertEquals("OVERFLOW", error.code());
        assertEquals("assetProcess.steps[0].inputs[0].amount", error.path());
    }

    @Test
    void rejectsNaturalLanguageZeroNegativeDecimalExponentWhitespaceAndCompositeAmounts() {
        for (String invalid : List.of("", "0", "-1", "+1", "1.5", "1e3", "01", " 3", "3 ",
                "三", "全部", "ALL+1", "unknown", "all", "999999999999999999999999999")) {
            AssetProcess.ProtocolException error = assertThrows(AssetProcess.ProtocolException.class,
                    () -> adapter.toDomain(process(step(AssetProcess.Kind.CONSUME,
                            List.of(flow("herb", invalid)), List.of()))), invalid);
            assertEquals("AMOUNT_LITERAL", error.code(), invalid);
        }
    }

    @Test
    void allIsOnlyAnInputAndNullCannotMeanUnknown() {
        AssetProcess.ProtocolException output = assertThrows(AssetProcess.ProtocolException.class,
                () -> adapter.toDomain(process(step(AssetProcess.Kind.ACQUIRE, List.of(), List.of(flow("herb", "ALL"))))));
        assertEquals("ALL_DIRECTION", output.code());
        assertEquals("assetProcess.steps[0].outputs[0].amount", output.path());
        assertEquals("REQUIRED", assertThrows(AssetProcess.ProtocolException.class,
                () -> adapter.toDomain(process(step(AssetProcess.Kind.CONSUME,
                        List.of(flow("herb", null)), List.of())))).code());
    }

    @Test
    void preservesInventoryOrderAndItsSeparateZeroQuantityContract() {
        ObservationDraft counted = new ObservationDraft("herb", new QuantityDraft(true, 2), "份", PLAYER, "实际清点两份");
        ObservationDraft empty = new ObservationDraft("herb", new QuantityDraft(true, 0), "份",
                new CustodyDraft("NONE", "", ""), "使用后核对为空");
        AssetProcessDraft draft = new AssetProcessDraft(List.of(
                new AssetStepDraft(AssetProcess.Kind.INVENTORY, "先盘点", List.of(), List.of(), List.of(counted)),
                step(AssetProcess.Kind.CONSUME, List.of(flow("herb", "2")), List.of()),
                new AssetStepDraft(AssetProcess.Kind.INVENTORY, "再核对", List.of(), List.of(), List.of(empty))));
        var result = adapter.toDomain(draft);
        assertEquals(List.of(AssetProcess.Kind.INVENTORY, AssetProcess.Kind.CONSUME, AssetProcess.Kind.INVENTORY),
                result.steps().stream().map(AssetProcess.Step::kind).toList());
        assertEquals(2, result.steps().getFirst().observations().getFirst().quantity().value());
        assertEquals(0, result.steps().getLast().observations().getFirst().quantity().value());
    }

    @Test
    void shapePrecheckRetainsExistingRulesInsteadOfSilentlyDroppingUnsupportedFields() {
        AssetStepDraft invalid = new AssetStepDraft(AssetProcess.Kind.PRODUCE, "炼药", List.of(),
                List.of(flow("pill", "1")), List.of(new ObservationDraft("pill", new QuantityDraft(true, 1), "份", PLAYER, "重复余额")));
        AssetProcess.ProtocolException error = assertThrows(AssetProcess.ProtocolException.class,
                () -> adapter.validateShape(process(invalid)));
        assertEquals("assetProcess.steps[0].observations", error.path());
        assertTrue(error.getMessage().contains("PRODUCE 的 observations 必须为空数组"));
        var direct = adapter.toDomain(process(invalid));
        assertEquals(error.getMessage(), assertThrows(AssetProcess.ProtocolException.class,
                () -> new AssetProcessCompiler().validateShape(direct)).getMessage());
    }

    @Test
    void missingCollectionsAreNotADeclarationOfNoAssetChange() {
        assertThrows(AssetProcess.ProtocolException.class, () -> adapter.toDomain(null));
        assertThrows(AssetProcess.ProtocolException.class, () -> adapter.toDomain(new AssetProcessDraft(null)));
        assertThrows(AssetProcess.ProtocolException.class, () -> adapter.toDomain(process(
                new AssetStepDraft(AssetProcess.Kind.ACQUIRE, "取得", null, List.of(flow("pill", "1")), List.of()))));
        assertThrows(AssetProcess.ProtocolException.class, () -> adapter.toDomain(process(
                new AssetStepDraft(AssetProcess.Kind.ACQUIRE, "取得", List.of(), List.of(flow("pill", "1")), null))));
        assertTrue(adapter.toDomain(new AssetProcessDraft(List.of())).steps().isEmpty());
    }

    @Test
    void repairReencodingContainsOnlyNewAmountFormatAndKeepsReferencesUnitsAndCustody() {
        var localCustody = new StoryTurnDecision.CustodyIntent(StoryPatch.HolderType.CHARACTER, "", "new-keeper");
        AssetProcess.Proposal internal = new AssetProcess.Proposal(AssetProcess.VERSION, List.of(
                new AssetProcess.Step("private-id", AssetProcess.Kind.TRANSFER, "全部交付", List.of(
                        new AssetProcess.Flow("stable-herb", null, true, "株",
                                new StoryTurnDecision.CustodyIntent(StoryPatch.HolderType.PLAYER, "", ""))),
                        List.of(new AssetProcess.Flow("new-herb-stack", new StoryPatch.QuantityValue(false, null), false,
                                "株", localCustody)), List.of())));
        AssetProcessDraft repaired = adapter.fromDomain(internal);
        JsonNode json = mapper.valueToTree(repaired);
        assertFalse(json.has("protocolVersion"));
        assertFalse(json.path("steps").get(0).has("stepId"));
        JsonNode input = json.path("steps").get(0).path("inputs").get(0);
        assertTrue(input.path("amount").isTextual());
        assertEquals("ALL", input.path("amount").asText());
        assertFalse(input.has("all"));
        assertEquals("UNKNOWN", json.path("steps").get(0).path("outputs").get(0).path("amount").asText());
        assertEquals("new-keeper", repaired.steps().getFirst().outputs().getFirst().custody().localHolderRef());
        assertEquals("株", repaired.steps().getFirst().outputs().getFirst().unit());
        assertEquals("stable-herb", adapter.toDomain(repaired).steps().getFirst().inputs().getFirst().entityRef());
    }

    @Test
    void actualJacksonDecodingDoesNotCoerceNumbersObjectsBooleansOrOldFlowFields() throws Exception {
        FlowDraft valid = mapper.readValue("{\"entityRef\":\"item\",\"amount\":\"3\",\"unit\":\"粒\",\"custody\":null}", FlowDraft.class);
        assertEquals("3", valid.amount());
        for (String invalid : List.of("3", "true", "{\"known\":true,\"value\":3}")) {
            String json = "{\"entityRef\":\"item\",\"amount\":" + invalid + ",\"unit\":\"粒\",\"custody\":null}";
            assertThrows(JsonProcessingException.class, () -> mapper.readValue(json, FlowDraft.class));
        }
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(
                "{\"entityRef\":\"item\",\"amount\":\"3\",\"all\":false}", FlowDraft.class));
    }

    @Test
    void itemModelRecordHasOnlyMetadataAndRejectsLegacyAuthorityFields() {
        Set<String> fields = Arrays.stream(ItemDraft.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("existingEntityId", "localRef", "name", "status", "summary", "attributes", "visible"), fields);
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(
                "{\"existingEntityId\":\"item\",\"quantity\":{\"known\":true,\"value\":1}}", ItemDraft.class));
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(
                "{\"existingEntityId\":\"item\",\"custody\":{\"holderType\":\"PLAYER\"}}", ItemDraft.class));
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(
                "{\"steps\":[],\"protocolVersion\":1}", AssetProcessDraft.class));
    }

    private AssetProcessDraft process(AssetStepDraft step) { return new AssetProcessDraft(List.of(step)); }

    private AssetStepDraft step(AssetProcess.Kind kind, List<FlowDraft> inputs, List<FlowDraft> outputs) {
        return new AssetStepDraft(kind, "已发生的资产过程", inputs, outputs, List.of());
    }

    private FlowDraft flow(String ref, String amount) { return new FlowDraft(ref, amount, "份", PLAYER); }
}
