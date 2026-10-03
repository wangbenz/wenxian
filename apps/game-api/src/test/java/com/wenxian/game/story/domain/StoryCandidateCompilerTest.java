package com.wenxian.game.story.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.agent.ai.StoryCandidateProtocol;
import com.wenxian.game.story.support.StoryTestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 直接验证正式候选编译的资产、身份及来源边界，不以旧实验编译器代替正式实现。 */
class StoryCandidateCompilerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StoryCandidateCompiler compiler = new StoryCandidateCompiler();

    @Test
    void computesSettledExchangeAndKeepsBothAssetsOnTheirExistingIds() throws Exception {
        StoryProjection before = projection(Map.of("herb", item("herb", "青叶草", 3),
                "coin", item("coin", "铜钱", 20)));
        var draft = draft("""
                "assetProcess":{"steps":[{"kind":"EXCHANGE","summary":"售出三株草药，收五十文。",
                "inputs":[{"entityRef":"herb","amount":"ALL","unit":"份","custody":{"holderType":"PLAYER"}}],
                "outputs":[{"entityRef":"coin","amount":"50","unit":"份","custody":{"holderType":"PLAYER"}}],"observations":[]}]}
                """, "[]");

        var result = compiler.compile(before, StoryCandidateProtocol.adapt(draft), draft.story().narrative(), List.of(), null);
        var after = result.applied().projection();

        assertEquals(before.entities().keySet(), after.entities().keySet());
        assertEquals(0, after.entities().get("herb").quantity().value());
        assertEquals(StoryPatch.HolderType.NONE, after.entities().get("herb").custody().holderType());
        assertEquals(70, after.entities().get("coin").quantity().value());
        var exchange = result.process().steps().getFirst();
        assertEquals("herb", exchange.inputs().getFirst().entityRef());
        assertEquals(3, exchange.inputs().getFirst().beforeQuantity().value());
        assertEquals(0, exchange.inputs().getFirst().afterQuantity().value());
        assertEquals("coin", exchange.outputs().getFirst().entityRef());
        assertEquals(20, exchange.outputs().getFirst().beforeQuantity().value());
        assertEquals(70, exchange.outputs().getFirst().afterQuantity().value());
        assertEquals(20, before.entities().get("coin").quantity().value(), "预演不修改冻结前态");
        assertNull(result.applied().acceptedPatch().outcome());
    }

    @Test
    void rejectsOverspendingAndDoesNotLetAbsentAssetProcessCreateAnItem() throws Exception {
        StoryProjection before = projection(Map.of("herb", item("herb", "青叶草", 3)));
        var overspend = draft("""
                "assetProcess":{"steps":[{"kind":"CONSUME","summary":"使用四份草药。",
                "inputs":[{"entityRef":"herb","amount":"4","unit":"份","custody":{"holderType":"PLAYER"}}],"outputs":[],"observations":[]}]}
                """, "[]");
        var error = assertThrows(AssetProcess.ProtocolException.class,
                () -> compiler.compile(before, StoryCandidateProtocol.adapt(overspend), overspend.story().narrative(), List.of(), null));
        assertEquals("INSUFFICIENT_QUANTITY", error.code());
        assertEquals(3, before.entities().get("herb").quantity().value());

        var noAcquisition = draft("""
                "itemChanges":[{"localRef":"paper","name":"辨草抄本","summary":"一页手抄的辨草方法。"}]
                """, "[]");
        assertTrue(StoryCandidateProtocol.adapt(noAcquisition).decision().assetProcess().steps().isEmpty());
        assertThrows(AssetProcess.ProtocolException.class, () -> compiler.compile(before,
                StoryCandidateProtocol.adapt(noAcquisition), noAcquisition.story().narrative(), List.of(), null));
        assertEquals(1, before.entities().size());
    }

    @Test
    void resolvesNewSpeakerOnceAndKeepsStatementDistinctFromAWorldFact() throws Exception {
        StoryProjection before = projection(Map.of());
        var draft = draft("""
                "entities":[{"localRef":"药铺掌柜","entityType":"CHARACTER","name":"郑掌柜",
                "summary":"这间药铺的掌柜，本轮向你介绍收药办法。"}]
                """, """
                [{"kind":"STATEMENT","text":"完整的青叶草眼下每株收十文。",
                "sourceRef":"药铺掌柜","sourceLabel":"模型占位名称","about":["PLAYER"]}]
                """);
        var adapted = StoryCandidateProtocol.adapt(draft);
        var pending = StoryRecords.prepare(before, adapted.decision().entities(), draft.records());
        var result = compiler.compile(before, adapted, draft.story().narrative(), pending.memories(), null);
        var records = StoryRecords.resolve(pending, result.requestedAliases(), result.applied().acceptedPatch().entities());
        var npc = result.applied().projection().entities().values().iterator().next();

        assertNotEquals("药铺掌柜", npc.id());
        assertEquals(npc.id(), records.getFirst().sourceRef());
        assertEquals("郑掌柜", records.getFirst().sourceLabel());
        assertEquals(List.of("PLAYER"), records.getFirst().about());
        assertTrue(result.applied().projection().worldFacts().isEmpty());
        assertEquals(1, result.applied().projection().memories().size());
        assertEquals("CLUE", result.applied().projection().memories().getFirst().memoryType());
        assertEquals("郑掌柜当时表示：完整的青叶草眼下每株收十文。",
                result.applied().projection().memories().getFirst().summary());
    }

    private StoryCandidateProtocol.Draft draft(String changes, String records) throws Exception {
        return mapper.readValue("""
                {"story":{"title":"药铺见闻","narrative":"你在药铺办事，停在当前结果处。",
                "decision":null,"choices":[],"summary":"药铺见闻。"},
                "changes":{"duration":{"unit":"SCENE","value":1,"targetPhase":""},
                "timeResolution":"COMPLETE",%s},"records":%s}
                """.formatted(changes, records), StoryCandidateProtocol.Draft.class);
    }

    private StoryProjection projection(Map<String, StoryProjection.EntityState> entities) {
        return new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("药铺", "平静", "炼气初期", "问道"),
                entities, Map.of(), Map.of(), List.of(), StoryTestFixtures.creationContract(),
                StoryTestFixtures.playPreference(), null, null);
    }

    private StoryProjection.EntityState item(String id, String name, int quantity) {
        return new StoryProjection.EntityState(id, "ITEM", name, "随身", "原有物品",
                mapper.createObjectNode().put("quantityUnit", "份"), true,
                new StoryPatch.QuantityValue(true, quantity), true,
                new StoryPatch.CustodyValue(StoryPatch.HolderType.PLAYER, null), null, null);
    }
}
