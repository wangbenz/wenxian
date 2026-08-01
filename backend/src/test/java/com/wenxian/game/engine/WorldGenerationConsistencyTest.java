package com.wenxian.game.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.model.GameState;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenerationConsistencyTest {
    @Test
    void fateGenerationRetriesWithACompactPromptWhenProviderTruncatesJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int call = calls.incrementAndGet();
            String content;
            String finishReason = "stop";
            if (call == 1) {
                content = "{\"protagonists\":[";
                finishReason = "length";
            } else if (call == 2) {
                content = mapper.writeValueAsString(java.util.Map.of(
                        "protagonists", List.of(option(0), option(1), option(2))));
            } else {
                content = "{\"inputCompatible\":true,\"valid\":true,\"reason\":\"\",\"issues\":[]}";
            }
            byte[] response = mapper.writeValueAsBytes(java.util.Map.of(
                    "id", "response-test-" + call,
                    "choices", List.of(java.util.Map.of(
                            "finish_reason", finishReason,
                            "message", java.util.Map.of("content", content))),
                    "usage", java.util.Map.of()));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            WorldGenerationService service = new WorldGenerationService(
                    mapper, RestClient.builder(), "test-key", baseUrl, "test-model");

            GameState state = service.generateFates("save-retry", "石微", "SPIRIT", "FEMALE",
                    "TRUTH", "一块矿石在灵脉中生出灵智", "重谋略");

            assertEquals(3, calls.get());
            assertEquals(3, state.protagonistOptions.size());
            assertTrue(requests.get(1).contains("总长度不得超过 2200 个汉字"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void explicitGenerationRouteUsesThePlayersCredential() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"response-test","choices":[{"message":{"content":"{\\"ok\\":true}"}}],"usage":{}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            WorldGenerationService service = new WorldGenerationService(
                    new ObjectMapper(), RestClient.builder(), "platform-key", baseUrl, "platform-model");
            RuntimeAiConfigService.RuntimeConfig custom = new RuntimeAiConfigService.RuntimeConfig(
                    "DEEPSEEK", "custom-model", "自备模型", "user-key");

            String content = service.requestJson("system", "user", 20, 0,
                    custom, "FATE_GENERATION", "save-test", 0, "USER");

            assertEquals("{\"ok\":true}", content);
            assertEquals("Bearer user-key", authorization.get());
            assertTrue(requestBody.get().contains("\"model\":\"custom-model\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void spiritOriginPremiseIsAuthoritativeAcrossAllCandidatesAndSelectedPlayer() {
        WorldGenerationService service = new WorldGenerationService(
                new ObjectMapper(), RestClient.builder(), "test-key", "http://localhost", "test-model");
        WorldGenerationService.FateDraft draft = new WorldGenerationService.FateDraft();
        draft.protagonists = new ArrayList<>();
        for (int index = 0; index < 3; index++) draft.protagonists.add(option(index));

        GameState setup = service.normalize("save-test", "石微", "SPIRIT", "FEMALE",
                "TRUTH", "一块矿石在灵脉中生出灵智", "重谋略", draft);

        assertEquals("SPIRIT", setup.creationProfile.race);
        assertEquals("一块矿石在灵脉中生出灵智", setup.creationProfile.originPremise);
        assertTrue(setup.protagonistOptions.stream().allMatch(option -> "SPIRIT".equals(option.race)));
        assertTrue(setup.protagonistOptions.stream()
                .allMatch(option -> setup.creationProfile.originPremise.equals(option.originPremise)));

        GameState selected = GameCatalog.start(setup, setup.protagonistOptions.getFirst().id);
        assertEquals("SPIRIT", selected.player.race);
        assertEquals("矿脉深处苏醒的石灵", selected.player.origin);
        assertEquals("一块矿石在灵脉中生出灵智", selected.player.originPremise);
        assertEquals("感知矿脉震动", selected.player.trait);
    }

    private static GameState.ProtagonistOption option(int index) {
        GameState.ProtagonistOption option = new GameState.ProtagonistOption();
        option.id = "fate-" + index;
        option.name = "错误姓名";
        option.race = "HUMAN";
        option.gender = "MALE";
        option.lifeGoal = "POWER";
        option.age = 18;
        option.identity = "被困矿灵";
        option.origin = "矿脉深处苏醒的石灵";
        option.trait = "感知矿脉震动";
        option.goal = "寻找本源";
        option.burden = "受困旧矿";
        option.constitution = "玄矿灵体";
        option.vitality = 60;
        option.insight = 6;
        option.opening = new GameState.OpeningSeed();
        option.opening.sceneId = "opening-" + index;
        option.opening.sceneTitle = "矿脉初醒";
        option.opening.location = "西境灵墟";
        option.opening.day = 1;
        option.opening.minute = 480;
        option.opening.narrative = List.of("石壳中传来第一缕知觉。", "矿道外有人正在靠近。");
        option.opening.choices = new ArrayList<>(List.of(
                choice("observe", "继续观察"),
                choice("hide", "收敛灵光"),
                choice("speak", "尝试回应")
        ));
        return option;
    }

    private static GameState.StoryChoice choice(String id, String label) {
        return new GameState.StoryChoice(id, label, "谨慎决定下一步", "default",
                "OTHER", null, null, "LOW");
    }
}
