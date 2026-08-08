package com.wenxian.game.story.adapter.v014;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.service.PlayerAiService;
import com.wenxian.game.v014.ai.V014ModelGateway;
import com.wenxian.game.v014.ai.V014StoryAgent;
import com.wenxian.game.v014.model.AiTurnEnvelope;
import com.wenxian.game.v014.model.V014State;
import com.wenxian.game.v014.persistence.V014SaveStore;
import com.wenxian.game.v014.persistence.V014TurnStore;
import com.wenxian.game.v014.service.V014GameService;
import com.wenxian.game.v014.service.V014ModelPreferenceService;
import com.wenxian.game.v014.service.V014StateReducer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V014ExpectedRevisionEntryTest {
    private static final UserIdentity USER = new UserIdentity("story-user", "story@test.local", true);

    @Test
    void staleTurnIsRejectedBeforeGenerationDependenciesAreUsed() {
        EmbeddedDatabase database = database();
        try {
            V014SaveStore saves = saveStore(database);
            V014GameService game = game(saves);

            GameApiException error = assertThrows(GameApiException.class,
                    () -> game.takeTurn(USER, "story-save-test", "继续前行", "request-12345678", 0));

            assertEquals("STATE_CONFLICT", error.code());
            assertEquals(1, saves.requireOwned(USER.userId(), "story-save-test").revision());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void staleCorrectionIsRejectedBeforeStateMutation() {
        EmbeddedDatabase database = database();
        try {
            V014SaveStore saves = saveStore(database);
            V014GameService game = game(saves);

            GameApiException error = assertThrows(GameApiException.class,
                    () -> game.correct(USER, "story-save-test", "当前位置：青岐城", 0));

            assertEquals("STATE_CONFLICT", error.code());
            assertEquals("青岐州", saves.requireOwned(USER.userId(), "story-save-test").state().currentLocation());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void concurrentTurnsWithSameRevisionOnlyAdvanceOnce() throws Exception {
        EmbeddedDatabase database = database();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            V014SaveStore saves = saveStore(database);
            CountDownLatch generationEntered = new CountDownLatch(1);
            CountDownLatch releaseGeneration = new CountDownLatch(1);
            V014GameService game = concurrentGame(database, jdbc, saves, generationEntered, releaseGeneration);

            Future<?> first = executor.submit(() -> game.takeTurn(USER, "story-save-test",
                    "进入山门", "request-first-1234", 1));
            if (!generationEntered.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("首个回合没有进入生成阶段");
            }
            Future<?> second = executor.submit(() -> game.takeTurn(USER, "story-save-test",
                    "转身离开", "request-second-123", 1));

            releaseGeneration.countDown();
            first.get(5, TimeUnit.SECONDS);
            ExecutionException conflict = assertThrows(ExecutionException.class,
                    () -> second.get(5, TimeUnit.SECONDS));

            assertEquals("STATE_CONFLICT", ((GameApiException) conflict.getCause()).code());
            assertEquals(2, saves.requireOwned(USER.userId(), "story-save-test").revision());
            assertEquals(1, jdbc.queryForObject("select count(*) from v014_game_turn", Integer.class));
        } finally {
            releaseAll(executor);
            database.shutdown();
        }
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                USER.userId(), USER.email(), "ACTIVE", now, now);
        return database;
    }

    private V014SaveStore saveStore(EmbeddedDatabase database) {
        V014SaveStore saves = new V014SaveStore(new JdbcTemplate(database),
                new ObjectMapper().findAndRegisterModules());
        saves.insert(USER.userId(), 1, state());
        return saves;
    }

    private V014GameService game(V014SaveStore saves) {
        return new V014GameService(saves, null, null, null, null, null,
                null, null, null, "https://example.invalid");
    }

    private V014GameService concurrentGame(EmbeddedDatabase database, JdbcTemplate jdbc, V014SaveStore saves,
                                           CountDownLatch generationEntered, CountDownLatch releaseGeneration) {
        V014TurnStore turns = new V014TurnStore(jdbc, new ObjectMapper().findAndRegisterModules());
        V014StoryAgent agent = new V014StoryAgent(null, null, new ObjectMapper(), null) {
            @Override
            public Generated generate(String userId, V014State current, String summary,
                                      List<V014TurnStore.RecentTurn> recent, String action,
                                      V014ModelPreferenceService.ResolvedRoute route) {
                generationEntered.countDown();
                try {
                    if (!releaseGeneration.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("测试未释放生成阶段");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                AiTurnEnvelope envelope = new AiTurnEnvelope("山门初见", "你踏入山门。", 60L,
                        "青岐山门", "无恙", null, null,
                        List.of(new AiTurnEnvelope.ChoiceDraft("继续前行", "查看山门内的动静")),
                        List.of(), List.of(), List.of(), List.of(), "已抵达青岐山门");
                return new Generated(envelope,
                        new V014ModelGateway.Result("{}", "response-test", "test-model", 10, 20, 30), "{}");
            }
        };
        V014ModelPreferenceService models = new V014ModelPreferenceService(jdbc, null) {
            @Override
            public ResolvedRoute resolve(String userId, String ignoredLegacyBaseUrl) {
                return new ResolvedRoute("https://example.invalid", "test-key", "DEEPSEEK",
                        "OPENAI_COMPATIBLE", "test-model", "PUBLIC", false);
            }
        };
        PlayerAiService playerAi = new PlayerAiService(jdbc, null, RestClient.builder(),
                "https://example.invalid", "test-key") {
            @Override public void assertPublicQuota(String userId, Route route) {}
            @Override public void recordSuccessfulInvocation(String userId, Route route) {}
        };
        return new V014GameService(saves, turns, null, agent, new V014StateReducer(), models,
                null, playerAi, new TransactionTemplate(new DataSourceTransactionManager(database)),
                "https://example.invalid");
    }

    private void releaseAll(ExecutorService executor) {
        executor.shutdownNow();
    }

    private V014State state() {
        return new V014State(14, "story-save-test", 0, "ACTIVE",
                new V014State.WorldProfile("XUANXU", "玄墟界", "OFFICIAL", "仙路未明", "轻叙事", "青岐州"),
                new V014State.Protagonist("陆沉", "人族", "男", "山村少年", "凡境", "随经历形成", "自由探索"),
                0, "青岐州", "无恙", "命卷初开", "山雨初歇。",
                List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
