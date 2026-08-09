package com.wenxian.game.v014.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.persistence.AiUsageStore;
import com.wenxian.game.service.ActiveSavePolicyService;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.service.PlayerAiService;
import com.wenxian.game.service.PublicTurnLimitException;
import com.wenxian.game.v014.ai.V014ModelGateway;
import com.wenxian.game.v014.ai.V014PromptAssembler;
import com.wenxian.game.v014.ai.V014ProviderCredentialVerifier;
import com.wenxian.game.v014.ai.V014StoryAgent;
import com.wenxian.game.v014.model.V014ApiModels.CreateSaveRequest;
import com.wenxian.game.v014.persistence.V014SaveStore;
import com.wenxian.game.v014.persistence.V014TurnStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V014GameServiceBoundaryTest {
    private EmbeddedDatabase database;

    @AfterEach
    void closeDatabase() {
        if (database != null) database.shutdown();
    }

    @Test
    void exhaustedPlatformQuotaStopsBeforeCallingModelAndPersonalKeyDoesNotConsumeQuota() {
        AtomicInteger calls = new AtomicInteger();
        Fixture fixture = fixture(request -> {
            calls.incrementAndGet();
            return result();
        });
        String saveId = fixture.createSave();
        fixture.jdbc.update("insert into user_daily_quota(user_id, usage_date, used_count, updated_at) values (?, ?, 1, ?)",
                fixture.user.userId(), Date.valueOf(LocalDate.now()), Timestamp.from(Instant.now()));

        assertThrows(PublicTurnLimitException.class,
                () -> fixture.game.takeTurn(fixture.user, saveId, "观察四周", "request-platform-exhausted"));
        assertEquals(0, calls.get(), "平台额度不足时不得产生真实模型费用");
        assertEquals(0, fixture.game.get(fixture.user, saveId).state().turnNumber());

        fixture.models.saveCredential(fixture.user.userId(), "DEEPSEEK", "deepseek-v4-flash",
                "sk-personal-test-key-1234567890");
        var response = fixture.game.takeTurn(fixture.user, saveId, "观察四周", "request-personal");

        assertEquals(1, calls.get());
        assertEquals(1, response.state().turnNumber());
        Integer used = fixture.jdbc.queryForObject(
                "select sum(used_count) from user_daily_quota where user_id=?", Integer.class, fixture.user.userId());
        assertEquals(1, used, "自备密钥回合不得增加平台赠送回合用量");
    }

    @Test
    void archivingWaitsForInFlightTurnAndCannotBeUndoneByLatePersistence() throws Exception {
        CountDownLatch enteredModel = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        Fixture fixture = fixture(request -> {
            enteredModel.countDown();
            try {
                assertTrue(releaseModel.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return result();
        });
        fixture.jdbc.update("update gameplay_config set config_value='20' where config_key='account_public_turn_limit'");
        String saveId = fixture.createSave();

        CompletableFuture<Void> turn = CompletableFuture.runAsync(() ->
                fixture.game.takeTurn(fixture.user, saveId, "观察四周", "request-in-flight"));
        assertTrue(enteredModel.await(2, TimeUnit.SECONDS));
        CompletableFuture<Void> archive = CompletableFuture.runAsync(() -> fixture.game.archive(fixture.user, saveId));

        Thread.sleep(100);
        assertFalse(archive.isDone(), "封存必须等待同账号正在生成的回合完成");
        releaseModel.countDown();
        turn.get(3, TimeUnit.SECONDS);
        archive.get(3, TimeUnit.SECONDS);

        var saved = fixture.game.get(fixture.user, saveId);
        assertEquals(1, saved.state().turnNumber());
        assertEquals("ARCHIVED", saved.state().status());
    }

    private Fixture fixture(V014ModelGateway gateway) {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Instant now = Instant.now();
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values ('u-v014-boundary','boundary@test.local','ACTIVE',?,?)",
                Timestamp.from(now), Timestamp.from(now));
        config(jdbc, "account_public_turn_limit", "1", now);
        config(jdbc, "account_public_turn_limit_message", "平台回合已用完", now);
        config(jdbc, "registration_contact_text", "《问仙录》二群：532549329", now);
        config(jdbc, "custom_api_enabled", "true", now);
        config(jdbc, "max_active_saves", "2", now);
        config(jdbc, "current_game_version", "V0.14", now);

        RuntimeAiConfigService runtime = new RuntimeAiConfigService(
                jdbc, "sk-platform-test-key-1234567890", "deepseek-v4-flash", "master-key", true);
        PlayerAiService playerAi = new PlayerAiService(jdbc, runtime, RestClient.builder(),
                "https://api.deepseek.com", "master-key");
        V014ProviderCredentialVerifier verifier = new V014ProviderCredentialVerifier() {
            @Override public void verify(String baseUrl, String adapterType, String apiKey, String model) { }
        };
        V014ModelPreferenceService models = new V014ModelPreferenceService(jdbc, playerAi, runtime,
                verifier, "master-key", "https://api.deepseek.com");
        models.ensureCatalog();

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        V014SaveStore saves = new V014SaveStore(jdbc, mapper);
        V014TurnStore turns = new V014TurnStore(jdbc, mapper);
        AiUsageStore usage = new AiUsageStore(jdbc, new GameVersionService(jdbc));
        V014StoryAgent agent = new V014StoryAgent(gateway, new V014PromptAssembler(mapper), mapper, usage);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        V014GameService game = new V014GameService(saves, turns, new V014WorldCatalog(false), agent,
                new V014StateReducer(), models, new ActiveSavePolicyService(jdbc), playerAi,
                transactions, "https://api.deepseek.com");
        return new Fixture(jdbc, game, models, new UserIdentity("u-v014-boundary", "boundary@test.local", true));
    }

    private void config(JdbcTemplate jdbc, String key, String value, Instant now) {
        jdbc.update("insert into gameplay_config(config_key, config_value, updated_at) values (?, ?, ?)",
                key, value, Timestamp.from(now));
    }

    private V014ModelGateway.Result result() {
        String json = """
                {"title":"山门初见","narrative":"你停在山门外，先看清四周来往的人。",
                 "elapsedMinutes":10,"location":"东陆青岐州","condition":"神完气足","realm":"凡境",
                 "lifeGoal":null,"choices":[{"title":"入山","description":"沿石阶入山。"}],
                 "items":[],"abilities":[],"characters":[],"memories":[],"rollingSummary":"你来到山门外。"}
                """;
        return new V014ModelGateway.Result(json, "response-test", "deepseek-v4-flash", 120, 80, 20);
    }

    private record Fixture(JdbcTemplate jdbc, V014GameService game,
                           V014ModelPreferenceService models, UserIdentity user) {
        String createSave() {
            return game.create(user, new CreateSaveRequest("XUANXU", null, "陆沉", "人族", "男",
                    "山村少年", null, "自由探索")).state().saveId();
        }
    }
}
