package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoredTurnResultReaderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String stored = """
            {"acceptedResult":{"protocolVersion":1,"steps":[{"stepIndex":0,
              "action":"询价","resolution":"SUCCEEDED","result":"掌柜告知价格，尚未成交。",
              "cultivation":false,"assetStepIndexes":[],"dependsOn":[],
              "statements":[{"sourceRef":null,"sourceLabel":"清墨铺掌柜","statement":"这份灵墨八文，不赊欠。"}]}],
              "stop":{"reason":"DECISION_REQUIRED","detail":"由玩家决定是否接受。"}}}
            """;

    @Test
    void readsSourceScopedStatementExactlyWithoutPromotingItToAWorldFact() throws Exception {
        var result = StoredTurnResultReader.read(mapper.readTree(stored));
        assertEquals(1, result.protocolVersion());
        assertEquals("清墨铺掌柜", result.steps().getFirst().statements().getFirst().sourceLabel());
        assertEquals("这份灵墨八文，不赊欠。", result.steps().getFirst().statements().getFirst().statement());
        assertEquals(mapper.readTree(stored).get("acceptedResult"), mapper.valueToTree(result));
    }

    @Test
    void readsV2TeachingAndSeparateRealmResultAndRejectsMissingRealmBinding() throws Exception {
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(stored);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("acceptedResult")).put("protocolVersion", 2);
        var step = (com.fasterxml.jackson.databind.node.ObjectNode) json.at("/acceptedResult/steps/0");
        step.put("cultivation", true).put("result", "经指点松肩呼气，缓步时气息稳定，快走仍待练习");
        step.putObject("realmResult").put("resolution", "PHASE_ADVANCED").put("result", "进入炼气中期");
        var accepted = StoredTurnResultReader.read(json);
        assertEquals(2, accepted.protocolVersion());
        assertEquals(step.path("result").asText(), accepted.steps().getFirst().result());
        assertEquals("进入炼气中期", accepted.steps().getFirst().realmResult().result());
        assertEquals(json.get("acceptedResult"), mapper.valueToTree(accepted));
        var realm = (com.fasterxml.jackson.databind.node.ObjectNode) step.get("realmResult");
        realm.put("result", 42);
        assertThrows(IllegalStateException.class, () -> StoredTurnResultReader.read(json));
        realm.put("result", "进入炼气中期");
        step.put("resolution", "FAILED");
        assertThrows(IllegalStateException.class, () -> StoredTurnResultReader.read(json));
        step.put("resolution", "SUCCEEDED");
        step.remove("realmResult");
        assertThrows(IllegalStateException.class, () -> StoredTurnResultReader.read(json));
    }

    @Test
    void oldTurnKeepsMissingResultEvenIfItsNarrativeClaimsAPrice() throws Exception {
        assertNull(StoredTurnResultReader.read(mapper.readTree("{\"narrative\":\"八文已成交\"}")));
        assertNull(StoredTurnResultReader.read(mapper.readTree("{\"acceptedResult\":null}")));
    }

    @Test
    void unsupportedOrCorruptPresentResultCannotSilentlyFallBackToNarrative() throws Exception {
        var future = mapper.readTree(stored);
        ((com.fasterxml.jackson.databind.node.ObjectNode) future.get("acceptedResult")).put("protocolVersion", 99);
        assertThrows(IllegalStateException.class, () -> StoredTurnResultReader.read(future));
        var invalid = mapper.readTree(stored);
        ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.at("/acceptedResult/steps/0")).put("stepIndex", 2);
        assertThrows(IllegalStateException.class, () -> StoredTurnResultReader.read(invalid));
        var missingStatements = mapper.readTree(stored);
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingStatements.at("/acceptedResult/steps/0")).remove("statements");
        assertThrows(IllegalStateException.class, () -> StoredTurnResultReader.read(missingStatements));
    }
}
