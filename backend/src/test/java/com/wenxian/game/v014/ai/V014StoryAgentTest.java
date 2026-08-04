package com.wenxian.game.v014.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.persistence.AiUsageStore;
import com.wenxian.game.v014.model.V014State;
import com.wenxian.game.v014.service.V014ModelPreferenceService.ResolvedRoute;
import com.wenxian.game.service.GameVersionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
class V014StoryAgentTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private EmbeddedDatabase database;
    private AiUsageStore usage;
    private final ResolvedRoute route = new ResolvedRoute(
            "https://api.deepseek.com", "sk-test-key", "DEEPSEEK", "OPENAI_COMPATIBLE",
            "deepseek-v4-flash", "USER", true);

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        usage = new AiUsageStore(jdbc, new GameVersionService(jdbc));
    }

    @AfterEach
    void closeDatabase() {
        if (database != null) database.shutdown();
    }

    @Test
    void acceptsUnknownFieldsAndUnescapedLineBreakWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        V014StoryAgent agent = agent(request -> {
            calls.incrementAndGet();
            return result("""
                    {"title":"山雨","narrative":"你看见山雨落下。
                    你没有立刻离开。","elapsedMinutes":10,"currentWorldTime":"额外字段",
                    "choices":[],"items":[],"abilities":[],"characters":[],"memories":[]}
                    """);
        });

        var generated = agent.generate("user", state(), "", List.of(), "观察山雨", route);

        assertEquals(1, calls.get());
        assertEquals("你看见山雨落下。\n你没有立刻离开。", generated.envelope().narrative());
        assertDoesNotThrow(() -> mapper.readTree(generated.rawPatchJson()));
        assertFalse(generated.rawPatchJson().contains("currentWorldTime"));
    }

    @Test
    void repairsMalformedJsonOnceAndCombinesUsage() {
        AtomicInteger calls = new AtomicInteger();
        V014StoryAgent agent = agent(request -> calls.incrementAndGet() == 1
                ? result("{\"title\":\"破损\",\"narrative\":\"你看见山门\",\"下一段\"")
                : result(validJson()));

        var generated = agent.generate("user", state(), "", List.of(), "进入山门", route);

        assertEquals(2, calls.get());
        assertEquals("你走入山门，先向守门人行礼。", generated.envelope().narrative());
        assertEquals(240, generated.result().inputTokens());
        assertEquals(160, generated.result().outputTokens());
        assertEquals(40, generated.result().latencyMs());
        assertDoesNotThrow(() -> mapper.readTree(generated.rawPatchJson()));
    }

    @Test
    void rejectsSecondMalformedResponseInsteadOfPersistingRawJson() {
        AtomicInteger calls = new AtomicInteger();
        V014StoryAgent agent = agent(request -> {
            calls.incrementAndGet();
            return result("{\"title\":\"仍然破损\",\"narrative\":");
        });

        assertThrows(GameApiException.class,
                () -> agent.generate("user", state(), "", List.of(), "进入山门", route));
        assertEquals(2, calls.get());
    }

    private V014StoryAgent agent(V014ModelGateway gateway) {
        return new V014StoryAgent(gateway, new V014PromptAssembler(mapper), mapper, usage);
    }

    private V014ModelGateway.Result result(String text) {
        return new V014ModelGateway.Result(text, "response", "deepseek-v4-flash", 120, 80, 20);
    }

    private String validJson() {
        return """
                {"title":"入山","narrative":"你走入山门，先向守门人行礼。","elapsedMinutes":10,
                 "location":"青山门","condition":"神完气足","realm":"凡境","lifeGoal":null,
                 "choices":[{"title":"问路","description":"询问外门所在。"}],
                 "items":[],"abilities":[],"characters":[],"memories":[],"rollingSummary":"你来到青山门。"}
                """;
    }

    private V014State state() {
        return new V014State(14, "save-test", 0, "ACTIVE",
                new V014State.WorldProfile("XUANXU", "玄墟界", "OFFICIAL", "修士问道", "灵气修行", "青岐州"),
                new V014State.Protagonist("陆沉", "人族", "男", "山村少年", "凡境", "尚未定下", "自由探索"),
                0, "青岐州", "神完气足", "命卷初开", "你站在山门外。", List.of(),
                List.of(), List.of(), List.of(), List.of());
    }
}
