package com.wenxian.game.credit;

import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ServicePlanSelection;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformTurnAccessServiceTest {

    @Test
    void platformRouteReservesAndConsumesNewAccountBalance() {
        try (Fixture fixture = fixture(true)) {
            var permit = fixture.access.reserve("user-1", "story-save-a", "request-a",
                    CredentialSource.PLATFORM);

            assertEquals(PlatformTurnAccessService.Mode.CREDIT, permit.mode());
            assertEquals(19, fixture.credits.balance("user-1").available());
            assertEquals(1, fixture.credits.balance("user-1").reserved());

            fixture.transactions.executeWithoutResult(status ->
                    fixture.access.consume(permit, "user-1", "story-turn-a"));

            assertEquals(19, fixture.credits.balance("user-1").available());
            assertEquals(0, fixture.credits.balance("user-1").reserved());
            assertEquals(1, fixture.credits.balance("user-1").consumed());
            PlatformTurnQuotaService.Settings settings = fixture.quota.settings("user-1");
            assertEquals(20, settings.turnLimit());
            assertEquals(1, settings.usedTurns());
            assertEquals(19, settings.remainingTurns());
        }
    }

    @Test
    void failedPlatformTurnReleasesReservationAndPersonalRouteNeverCreatesAccount() {
        try (Fixture fixture = fixture(true)) {
            var platform = fixture.access.reserve("user-1", "story-save-a", "request-a",
                    CredentialSource.PLATFORM);
            fixture.access.release(platform, "模拟模型失败");
            assertEquals(20, fixture.credits.balance("user-1").available());
            assertEquals(0, fixture.credits.balance("user-1").consumed());

            var personal = fixture.access.reserve("user-2", "story-save-b", "request-b",
                    CredentialSource.USER);
            fixture.access.consume(personal, "user-2", "story-turn-b");
            assertEquals(PlatformTurnAccessService.Mode.PERSONAL, personal.mode());
            assertEquals(0, fixture.jdbc.queryForObject(
                    "select count(*) from turn_credit_grant where user_id='user-2'", Integer.class));
        }
    }

    @Test
    void freezesTierPriceInTheSingleReservation() {
        try (Fixture fixture = fixture(true)) {
            Timestamp now = Timestamp.from(Instant.parse("2026-08-12T08:00:00Z"));
            fixture.jdbc.update("""
                    insert into ai_service_plan_version(id,version_number,status,revision,default_tier_code,
                        change_note,created_by,created_at,published_by,published_at)
                    values ('plan-v7',7,'ACTIVE',1,'DEEP','测试价格','admin',?,'admin',?)
                    """, now, now);
            fixture.jdbc.update("""
                    insert into ai_service_plan_tier(plan_version_id,tier_code,display_name,description,
                        point_cost,enabled,fallback_enabled)
                    values ('plan-v7','DEEP','深度推演','完整推演',3,true,false)
                    """);

            var permit = fixture.access.reserve("user-1", "story-save-a", "request-priced",
                    CredentialSource.PLATFORM, new ServicePlanSelection("plan-v7", "DEEP", 3));

            assertEquals(17, fixture.credits.balance("user-1").available());
            assertEquals(3, fixture.credits.balance("user-1").reserved());
            assertEquals(3, fixture.jdbc.queryForObject("""
                    select amount from turn_credit_reservation where id=?
                    """, Integer.class, permit.reservationId()));
            assertEquals("plan-v7", fixture.jdbc.queryForObject("""
                    select service_plan_version_id from turn_credit_reservation where id=?
                    """, String.class, permit.reservationId()));
            assertEquals("DEEP", fixture.jdbc.queryForObject("""
                    select tier_code from turn_credit_reservation where id=?
                    """, String.class, permit.reservationId()));
        }
    }

    @Test
    void disabledFlagKeepsLegacyQuotaPath() {
        try (Fixture fixture = fixture(false)) {
            var permit = fixture.access.reserve("user-1", "story-save-a", "request-a",
                    CredentialSource.PLATFORM);
            fixture.transactions.executeWithoutResult(status ->
                    fixture.access.consume(permit, "user-1", "story-turn-a"));

            assertEquals(PlatformTurnAccessService.Mode.LEGACY, permit.mode());
            assertEquals(1, fixture.quota.legacyQuota("user-1").usedTurns());
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
        PlatformTurnQuotaService quota = new PlatformTurnQuotaService(jdbc, credits, enabled);
        quota.ensureDefaults();
        PlatformTurnAccessService access = new PlatformTurnAccessService(quota, credits, enabled);
        return new Fixture(database, jdbc, transactions, credits, quota, access);
    }

    private record Fixture(EmbeddedDatabase database, JdbcTemplate jdbc, TransactionTemplate transactions,
                           TurnCreditService credits, PlatformTurnQuotaService quota,
                           PlatformTurnAccessService access) implements AutoCloseable {
        @Override
        public void close() {
            database.shutdown();
        }
    }
}
