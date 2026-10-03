package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.application.StorySaveCreationService;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 保护共享 v2 命卷样例能够被游戏端权威 mapper 读取和重写。 */
class StoryStateV2ContractTest {
    private static final String FIXTURE = "/contracts/story-state/story-state-v2.json";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final StoryProjectionPersistenceMapper projections = new StoryProjectionPersistenceMapper(json);

    @Test
    void sharedFixtureMatchesTheAuthoritativeGameStateContract() throws Exception {
        ObjectNode state = fixture();

        assertEquals(StorySaveCreationService.STATE_SCHEMA_VERSION, state.path("schemaVersion").asInt());
        JsonNode trusted = state.path("trustedProjection");
        assertFalse(trusted.has("realmScale"));
        assertFalse(trusted.has("protagonistRealm"));
        assertFalse(trusted.has("playLifecycle"));

        var loaded = projections.load(save(state), List.of(), List.of());
        assertEquals("沈砚", loaded.creationContract().identity().protagonistName());
        assertEquals("炼气初期", loaded.coreState().realm());
        assertEquals("realm:xuanxu:human:l1", loaded.protagonistCultivation().currentStageId());

        ObjectNode rewritten = projections.writeState(state, loaded);
        JsonNode rewrittenTrusted = rewritten.path("trustedProjection");
        assertNotNull(rewrittenTrusted.get("cultivationSystem"));
        assertNotNull(rewrittenTrusted.get("protagonistCultivation"));
        assertEquals("沈砚", rewrittenTrusted.path("creationContract").path("identity")
                .path("protagonistName").asText());
    }

    private ObjectNode fixture() throws Exception {
        try (InputStream input = StoryStateV2ContractTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(input, "共享 Story v2 fixture 必须进入测试 classpath");
            return (ObjectNode) json.readTree(input);
        }
    }

    private StoredSave save(ObjectNode state) {
        return new StoredSave("story-contract-v2", "user-contract", 1, 1, "ACTIVE", "XUANXU",
                "LIGHT_NARRATIVE", 1, StorySaveCreationService.STATE_SCHEMA_VERSION,
                "NATIVE", "V0.16", null, null, state, 1, 0,
                "V0.16", "V0.16", Instant.EPOCH, Instant.EPOCH, null, null, null);
    }
}
