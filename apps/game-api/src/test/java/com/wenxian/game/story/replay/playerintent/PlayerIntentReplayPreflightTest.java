package com.wenxian.game.story.replay.playerintent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 外部案例的离线装载检查入口：不读取模型凭证、不构造 HTTP 模型，逐案输出正式导演边界前的检查结果。 */
@EnabledIfEnvironmentVariable(named = "PLAYER_INTENT_PREFLIGHT", matches = "1")
class PlayerIntentReplayPreflightTest {
    @Test
    void preflightReviewedCasesWithoutAnyModel() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        Path repository = Path.of("../..").toRealPath();
        Path output = Path.of(PlayerIntentReplayRealModelTest.required("PLAYER_INTENT_OUTPUT_DIR")).toRealPath();
        PlayerIntentReplayRealModelTest.requirePrivateExternal(output, repository);
        var cases = PlayerIntentReplayRealModelTest.loadCases(mapper, repository);
        var host = new PlayerIntentReplay(mapper);
        var results = cases.stream().map(value -> host.preflight(value.fixture())).toList();
        PlayerIntentReplayRealModelTest.writePrivate(output.resolve("offline-preflight-" + UUID.randomUUID() + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(results));
        assertEquals(0, results.stream().filter(value -> !"VALID_UNTIL_DIRECTOR".equals(value.status())).count(),
                "Inspect private preflight results; no model was constructed or called");
    }
}
