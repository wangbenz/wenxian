package com.wenxian.game.credit;

import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.service.PlayerAiService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformTurnAccessServiceTest {

    @Test
    void platformRouteReservesAndConsumesNewAccountBalance() {
        try (Fixture fixture = fixture(true)) {
            PlayerAiService.Route route = platformRoute();
            var permit = fixture.access.reserve("user-1", "story-save-a", "request-a", route);

            assertEquals(PlatformTurnAccessService.Mode.CREDIT, permit.mode());
            assertEquals(19, fixture.credits.balance("user-1").available());
            assertEquals(1, fixture.credits.balance("user-1").reserved());

            fixture.transactions.executeWithoutResult(status ->
                    fixture.access.consume(permit, "user-1", "story-turn-a"));

            assertEquals(19, fixture.credits.balance("user-1").available());
            assertEquals(0, fixture.credits.balance("user-1").reserved());
            assertEquals(1, fixture.credits.balance("user-1").consumed());
            PlayerAiService.Settings settings = fixture.playerAi.settings("user-1");
            assertEquals(20, settings.turnLimit());
            assertEquals(1, settings.usedTurns());
            assertEquals(19, settings.remainingTurns());
        }
    }

    @Test
    void failedPlatformTurnReleasesReservationAndPersonalRouteNeverCreatesAccount() {
        try (Fixture fixture = fixture(true)) {
            var platform = fixture.access.reserve("user-1", "story-save-a", "request-a", platformRoute());
            fixture.access.release(platform, "模拟模型失败");
            assertEquals(20, fixture.credits.balance("user-1").available());
            assertEquals(0, fixture.credits.balance("user-1").consumed());

            var personal = fixture.access.reserve("user-2", "story-save-b", "request-b", personalRoute());
            fixture.access.consume(personal, "user-2", "story-turn-b");
            assertEquals(PlatformTurnAccessService.Mode.PERSONAL, personal.mode());
            assertEquals(0, fixture.jdbc.queryForObject(
                    "select count(*) from turn_credit_grant where user_id='user-2'", Integer.class));
        }
    }

    @Test
    void disabledFlagKeepsLegacyQuotaPath() {
        try (Fixture fixture = fixture(false)) {
            var permit = fixture.access.reserve("user-1", "story-save-a", "request-a", platformRoute());
            fixture.transactions.executeWithoutResult(status ->
                    fixture.access.consume(permit, "user-1", "story-turn-a"));

            assertEquals(PlatformTurnAccessService.Mode.LEGACY, permit.mode());
            assertEquals(1, fixture.playerAi.legacyQuota("user-1").usedTurns());
            assertEquals(0, fixture.jdbc.queryForObject("select count(*) from turn_credit_grant", Integer.class));
        }
    }

    private Fixture fixture(boolean enabled) {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-12T08:00:00Z"));
        jdbc.update("""
                insert into app_user(id,email,status,registered_at,last_login_at)
                values ('user-1','credit-one@wenxian.invalid','ACTIVE',?,?),
                       ('user-2','credit-two@wenxian.invalid','ACTIVE',?,?)
                """, now, now, now, now);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        TurnCreditService credits = new TurnCreditService(new TurnCreditRepository(jdbc), transactions);
        PlayerAiService playerAi = new PlayerAiService(jdbc, null, RestClient.builder(),
                "https://example.invalid", "test-master-key", credits, enabled);
        PlatformTurnAccessService access = new PlatformTurnAccessService(playerAi, credits, enabled);
        return new Fixture(database, jdbc, transactions, credits, playerAi, access);
    }

    private PlayerAiService.Route platformRoute() {
        return new PlayerAiService.Route(new RuntimeAiConfigService.RuntimeConfig(
                "DEEPSEEK", "flash", "平台", "platform-key"), PlayerAiService.PUBLIC_SOURCE);
    }

    private PlayerAiService.Route personalRoute() {
        return new PlayerAiService.Route(new RuntimeAiConfigService.RuntimeConfig(
                "DEEPSEEK", "flash", "自备", "personal-key"), PlayerAiService.USER_SOURCE);
    }

    private record Fixture(EmbeddedDatabase database, JdbcTemplate jdbc, TransactionTemplate transactions,
                           TurnCreditService credits, PlayerAiService playerAi,
                           PlatformTurnAccessService access) implements AutoCloseable {
        @Override
        public void close() {
            database.shutdown();
        }
    }
}
