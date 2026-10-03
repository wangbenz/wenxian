package com.wenxian.game.credit;

import com.wenxian.game.credit.TurnCreditModels.CreditClass;
import com.wenxian.game.credit.TurnCreditModels.GrantCommand;
import com.wenxian.game.credit.TurnCreditModels.GrantStatus;
import com.wenxian.game.credit.TurnCreditModels.ReservationStatus;
import com.wenxian.game.credit.TurnCreditModels.ReserveCommand;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TurnCreditServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-12T08:00:00Z");

    @Test
    void legacyOpeningPreservesOldUsedTurnsAndRunsOnlyOnce() {
        try (Fixture fixture = fixture()) {
            var opening = fixture.service.ensureLegacyOpening("user-1", 20, 7, NOW);
            var duplicate = fixture.service.ensureLegacyOpening("user-1", 20, 9, NOW.plusSeconds(5));

            assertEquals(opening.id(), duplicate.id());
            assertEquals(13, fixture.service.balance("user-1", NOW.plusSeconds(6)).available());
            assertEquals(7, fixture.service.balance("user-1", NOW.plusSeconds(6)).consumed());
            assertEquals(1, fixture.jdbc.queryForObject(
                    "select count(*) from turn_credit_grant where grant_type='LEGACY_OPENING'", Integer.class));
            assertEquals(1, fixture.jdbc.queryForObject(
                    "select count(*) from turn_credit_ledger where event_key='GRANT:LEGACY_OPENING:user-1'",
                    Integer.class));
            assertConservation(fixture, opening.id());
        }
    }

    @Test
    void grantsAreAccountScopedAndIdempotentAcrossSaves() {
        try (Fixture fixture = fixture()) {
            var grant = fixture.service.grant(grant("initial:user-1", 3, null), NOW);
            var duplicate = fixture.service.grant(grant("initial:user-1", 3, null), NOW.plusSeconds(2));

            assertEquals(grant.id(), duplicate.id());
            assertEquals(3, fixture.service.balance("user-1", NOW).available());
            assertEquals(1, fixture.jdbc.queryForObject("select count(*) from turn_credit_grant", Integer.class));
            assertEquals(1, fixture.jdbc.queryForObject("select count(*) from turn_credit_ledger", Integer.class));

            var firstSave = fixture.service.reserve(new ReserveCommand(
                    "user-1", "save-a", "request-a", Duration.ofMinutes(10)), NOW);
            var secondSave = fixture.service.reserve(new ReserveCommand(
                    "user-1", "save-b", "request-b", Duration.ofMinutes(10)), NOW.plusSeconds(1));

            assertEquals(1, firstSave.amount());
            assertEquals(1, secondSave.amount());
            assertEquals(1, fixture.service.balance("user-1", NOW.plusSeconds(2)).available(),
                    "不同命卷必须共享账号余额");
            assertConservation(fixture, grant.id());
        }
    }

    @Test
    void reservationAndConsumptionAreIdempotentAndPreferNearestExpiry() {
        try (Fixture fixture = fixture()) {
            var permanent = fixture.service.grant(grant("admin:user-1:permanent", 2, null), NOW);
            var expiring = fixture.service.grant(grant("playtest:user-1:august", 1,
                    NOW.plus(Duration.ofDays(2))), NOW.plusSeconds(1));

            ReserveCommand command = new ReserveCommand("user-1", "save-a", "request-a", Duration.ofMinutes(10));
            var reserved = fixture.service.reserve(command, NOW.plusSeconds(2));
            var duplicate = fixture.service.reserve(command, NOW.plusSeconds(3));
            assertEquals(reserved.id(), duplicate.id());
            assertEquals(expiring.id(), reserved.grantId(), "应优先消耗最近到期批次");

            var consumed = fixture.service.consume(reserved.id(), "turn-1", NOW.plusSeconds(4));
            var consumedAgain = fixture.service.consume(reserved.id(), "turn-1", NOW.plusSeconds(5));
            assertEquals(ReservationStatus.CONSUMED, consumed.status());
            assertEquals(consumed.id(), consumedAgain.id());
            assertEquals(2, fixture.service.balance("user-1", NOW.plusSeconds(6)).available());
            assertEquals(1, fixture.service.balance("user-1", NOW.plusSeconds(6)).consumed());
            assertEquals(1, fixture.jdbc.queryForObject(
                    "select count(*) from turn_credit_ledger where event_type='CONSUME'", Integer.class));
            assertEquals(GrantStatus.EXHAUSTED.name(), fixture.jdbc.queryForObject(
                    "select status from turn_credit_grant where id=?", String.class, expiring.id()));
            assertConservation(fixture, permanent.id());
            assertConservation(fixture, expiring.id());

            assertThrows(IllegalStateException.class,
                    () -> fixture.service.consume(reserved.id(), "turn-other", NOW.plusSeconds(7)));
        }
    }

    @Test
    void pricedReservationFreezesTierAndConsumesAcrossMultipleGrantBatches() {
        try (Fixture fixture = fixture()) {
            fixture.insertPlanTier("plan-7", "DEEP", 3);
            var permanent = fixture.service.grant(grant("admin:user-1:permanent", 2, null), NOW);
            var expiring = fixture.service.grant(grant("activity:user-1:expiring", 1,
                    NOW.plus(Duration.ofDays(2))), NOW.plusSeconds(1));
            ReserveCommand command = new ReserveCommand("user-1", "save-a", "request-priced", 3,
                    "plan-7", "deep", Duration.ofMinutes(10));

            var reservation = fixture.service.reserve(command, NOW.plusSeconds(2));
            var duplicate = fixture.service.reserve(command, NOW.plusSeconds(3));

            assertEquals(reservation.id(), duplicate.id());
            assertEquals(3, reservation.amount());
            assertEquals("plan-7", reservation.servicePlanVersionId());
            assertEquals("DEEP", reservation.tierCode());
            assertEquals(expiring.id(), reservation.grantId(), "兼容批次指针仍应指向最先到期来源");
            assertEquals(2, fixture.jdbc.queryForObject("""
                    select count(*) from turn_credit_reservation_allocation where reservation_id=?
                    """, Integer.class, reservation.id()));
            assertEquals(3, fixture.jdbc.queryForObject("""
                    select sum(amount) from turn_credit_reservation_allocation where reservation_id=?
                    """, Integer.class, reservation.id()));
            assertEquals(0, fixture.service.balance("user-1", NOW.plusSeconds(3)).available());
            assertEquals(3, fixture.service.balance("user-1", NOW.plusSeconds(3)).reserved());

            fixture.service.consume(reservation.id(), "turn-priced", NOW.plusSeconds(4));

            assertEquals(3, fixture.service.balance("user-1", NOW.plusSeconds(5)).consumed());
            assertEquals(2, fixture.jdbc.queryForObject("""
                    select count(*) from turn_credit_ledger
                    where reservation_id=? and event_type='RESERVE'
                    """, Integer.class, reservation.id()));
            assertEquals(2, fixture.jdbc.queryForObject("""
                    select count(*) from turn_credit_ledger
                    where reservation_id=? and event_type='CONSUME'
                    """, Integer.class, reservation.id()));
            assertConservation(fixture, permanent.id());
            assertConservation(fixture, expiring.id());
        }
    }

    @Test
    void insufficientMultiBatchBalanceRollsBackWithoutPartialReservation() {
        try (Fixture fixture = fixture()) {
            var first = fixture.service.grant(grant("activity:user-1:first", 1,
                    NOW.plus(Duration.ofDays(1))), NOW);
            var second = fixture.service.grant(grant("admin:user-1:second", 1, null), NOW.plusSeconds(1));

            assertThrows(TurnCreditUnavailableException.class, () -> fixture.service.reserve(
                    new ReserveCommand("user-1", "save-a", "request-too-large", 3,
                            null, null, Duration.ofMinutes(10)), NOW.plusSeconds(2)));

            assertEquals(2, fixture.service.balance("user-1", NOW.plusSeconds(3)).available());
            assertEquals(0, fixture.service.balance("user-1", NOW.plusSeconds(3)).reserved());
            assertEquals(0, fixture.jdbc.queryForObject(
                    "select count(*) from turn_credit_reservation", Integer.class));
            assertEquals(0, fixture.jdbc.queryForObject(
                    "select count(*) from turn_credit_reservation_allocation", Integer.class));
            assertEquals(0, fixture.jdbc.queryForObject(
                    "select count(*) from turn_credit_ledger where event_type='RESERVE'", Integer.class));
            assertConservation(fixture, first.id());
            assertConservation(fixture, second.id());
        }
    }

    @Test
    void failedMultiBatchGenerationReleasesEveryAllocationOnlyOnce() {
        try (Fixture fixture = fixture()) {
            var first = fixture.service.grant(grant("activity:user-1:first", 1,
                    NOW.plus(Duration.ofDays(1))), NOW);
            var second = fixture.service.grant(grant("admin:user-1:second", 2, null), NOW.plusSeconds(1));
            var reservation = fixture.service.reserve(new ReserveCommand(
                    "user-1", "save-a", "request-release", 3,
                    null, null, Duration.ofMinutes(10)), NOW.plusSeconds(2));

            fixture.service.release(reservation.id(), "模型失败", NOW.plusSeconds(3));
            fixture.service.release(reservation.id(), "重复释放", NOW.plusSeconds(4));

            assertEquals(3, fixture.service.balance("user-1", NOW.plusSeconds(5)).available());
            assertEquals(0, fixture.service.balance("user-1", NOW.plusSeconds(5)).reserved());
            assertEquals(2, fixture.jdbc.queryForObject("""
                    select count(*) from turn_credit_ledger
                    where reservation_id=? and event_type='RELEASE'
                    """, Integer.class, reservation.id()));
            assertConservation(fixture, first.id());
            assertConservation(fixture, second.id());
        }
    }

    @Test
    void sameRequestCannotBeRepricedAfterReservation() {
        try (Fixture fixture = fixture()) {
            fixture.service.grant(grant("initial:user-1", 3, null), NOW);
            fixture.service.reserve(new ReserveCommand(
                    "user-1", "save-a", "request-a", 2,
                    null, null, Duration.ofMinutes(10)), NOW.plusSeconds(1));

            assertThrows(IllegalStateException.class, () -> fixture.service.reserve(new ReserveCommand(
                    "user-1", "save-a", "request-a", 1,
                    null, null, Duration.ofMinutes(10)), NOW.plusSeconds(2)));
            assertEquals(1, fixture.jdbc.queryForObject(
                    "select count(*) from turn_credit_reservation", Integer.class));
            assertEquals(2, fixture.service.balance("user-1", NOW.plusSeconds(3)).reserved());
        }
    }

    @Test
    void failedGenerationReleasesOnlyOnceAndDoesNotConsume() {
        try (Fixture fixture = fixture()) {
            var grant = fixture.service.grant(grant("feedback:user-1:case-7", 1, null), NOW);
            var reservation = fixture.service.reserve(new ReserveCommand(
                    "user-1", "save-a", "request-a", Duration.ofMinutes(10)), NOW.plusSeconds(1));

            var released = fixture.service.release(reservation.id(), "模型请求失败", NOW.plusSeconds(2));
            var releasedAgain = fixture.service.release(reservation.id(), "重复回调", NOW.plusSeconds(3));

            assertEquals(ReservationStatus.RELEASED, released.status());
            assertEquals(released.id(), releasedAgain.id());
            assertEquals(1, fixture.service.balance("user-1", NOW.plusSeconds(4)).available());
            assertEquals(0, fixture.service.balance("user-1", NOW.plusSeconds(4)).consumed());
            assertEquals(1, fixture.jdbc.queryForObject(
                    "select count(*) from turn_credit_ledger where event_type='RELEASE'", Integer.class));
            assertThrows(IllegalStateException.class,
                    () -> fixture.service.reserve(new ReserveCommand(
                            "user-1", "save-a", "request-a", Duration.ofMinutes(10)), NOW.plusSeconds(5)));
            assertConservation(fixture, grant.id());
        }
    }

    @Test
    void storyTransactionFailureRollsBackConsumption() {
        try (Fixture fixture = fixture()) {
            var grant = fixture.service.grant(grant("initial:user-1", 1, null), NOW);
            var reservation = fixture.service.reserve(new ReserveCommand(
                    "user-1", "save-a", "request-a", Duration.ofMinutes(10)), NOW.plusSeconds(1));

            assertThrows(IllegalStateException.class, () -> fixture.transactions.execute(status -> {
                fixture.service.consume(reservation.id(), "turn-1", NOW.plusSeconds(2));
                throw new IllegalStateException("模拟剧情快照提交失败");
            }));

            assertEquals(ReservationStatus.RESERVED.name(), fixture.jdbc.queryForObject(
                    "select status from turn_credit_reservation where id=?", String.class, reservation.id()));
            assertEquals(1, fixture.service.balance("user-1", NOW.plusSeconds(3)).reserved());
            assertEquals(0, fixture.service.balance("user-1", NOW.plusSeconds(3)).consumed());
            assertEquals(0, fixture.jdbc.queryForObject(
                    "select count(*) from turn_credit_ledger where event_type='CONSUME'", Integer.class));
            assertConservation(fixture, grant.id());
        }
    }

    @Test
    void expiryAndRevocationPreserveGrantConservation() {
        try (Fixture fixture = fixture()) {
            var expiring = fixture.service.grant(grant("activity:user-1:short", 2,
                    NOW.plusSeconds(60)), NOW);
            var reservation = fixture.service.reserve(new ReserveCommand(
                    "user-1", "save-a", "request-a", Duration.ofMinutes(10)), NOW.plusSeconds(1));

            assertEquals(1, fixture.service.cleanupExpired(20, NOW.plusSeconds(61)));
            assertEquals(GrantStatus.EXPIRED.name(), fixture.jdbc.queryForObject(
                    "select status from turn_credit_grant where id=?", String.class, expiring.id()));
            assertEquals(1, fixture.service.balance("user-1", NOW.plusSeconds(61)).expired());

            fixture.service.release(reservation.id(), "生成完成前批次到期", NOW.plusSeconds(62));
            assertEquals(2, fixture.service.balance("user-1", NOW.plusSeconds(63)).expired());
            assertEquals(0, fixture.service.balance("user-1", NOW.plusSeconds(63)).available());
            assertConservation(fixture, expiring.id());

            var revocable = fixture.service.grant(grant("admin:user-1:bonus", 2, null), NOW.plusSeconds(70));
            var pending = fixture.service.reserve(new ReserveCommand(
                    "user-1", "save-b", "request-b", Duration.ofMinutes(10)), NOW.plusSeconds(71));
            assertThrows(IllegalStateException.class,
                    () -> fixture.service.revoke(revocable.id(), "误发", "admin-1", NOW.plusSeconds(72)));
            fixture.service.release(pending.id(), "取消生成", NOW.plusSeconds(73));
            fixture.service.revoke(revocable.id(), "误发", "admin-1", NOW.plusSeconds(74));
            fixture.service.revoke(revocable.id(), "重复撤销", "admin-1", NOW.plusSeconds(75));
            assertEquals(GrantStatus.REVOKED.name(), fixture.jdbc.queryForObject(
                    "select status from turn_credit_grant where id=?", String.class, revocable.id()));
            assertEquals(2, fixture.service.balance("user-1", NOW.plusSeconds(76)).revoked());
            assertEquals(1, fixture.jdbc.queryForObject(
                    "select count(*) from turn_credit_ledger where event_type='REVOKE'", Integer.class));
            assertConservation(fixture, revocable.id());
        }
    }

    @Test
    void releasingAfterGrantExpiryExpiresUnreservedRemainderToo() {
        try (Fixture fixture = fixture()) {
            var grant = fixture.service.grant(grant("activity:user-1:short", 3,
                    NOW.plusSeconds(60)), NOW);
            var reservation = fixture.service.reserve(new ReserveCommand(
                    "user-1", "save-a", "request-a", Duration.ofMinutes(10)), NOW.plusSeconds(1));

            fixture.service.release(reservation.id(), "供应商超时", NOW.plusSeconds(61));

            assertEquals(0, fixture.service.balance("user-1", NOW.plusSeconds(62)).available());
            assertEquals(3, fixture.service.balance("user-1", NOW.plusSeconds(62)).expired());
            assertEquals(GrantStatus.EXPIRED.name(), fixture.jdbc.queryForObject(
                    "select status from turn_credit_grant where id=?", String.class, grant.id()));
            assertConservation(fixture, grant.id());
        }
    }

    @Test
    void emptyBalanceCannotBeOverdrawn() {
        try (Fixture fixture = fixture()) {
            var grant = fixture.service.grant(grant("initial:user-1", 1, null), NOW);
            fixture.service.reserve(new ReserveCommand(
                    "user-1", "save-a", "request-a", Duration.ofMinutes(10)), NOW.plusSeconds(1));

            assertThrows(TurnCreditUnavailableException.class,
                    () -> fixture.service.reserve(new ReserveCommand(
                            "user-1", "save-b", "request-b", Duration.ofMinutes(10)), NOW.plusSeconds(2)));
            assertConservation(fixture, grant.id());
        }
    }

    @Test
    void concurrentSavesCannotOverdrawOneAccountCredit() throws Exception {
        try (Fixture fixture = fixture(); var executor = Executors.newFixedThreadPool(2)) {
            var grant = fixture.service.grant(grant("initial:user-1", 1, null), NOW);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger success = new AtomicInteger();
            AtomicInteger unavailable = new AtomicInteger();

            for (int index = 0; index < 2; index++) {
                int sequence = index;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        fixture.service.reserve(new ReserveCommand("user-1", "save-" + sequence,
                                "request-" + sequence, Duration.ofMinutes(10)), NOW.plusSeconds(1));
                        success.incrementAndGet();
                    } catch (TurnCreditUnavailableException expected) {
                        unavailable.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await(2, TimeUnit.SECONDS);
            start.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            assertEquals(1, success.get());
            assertEquals(1, unavailable.get());
            assertEquals(0, fixture.service.balance("user-1", NOW.plusSeconds(2)).available());
            assertEquals(1, fixture.service.balance("user-1", NOW.plusSeconds(2)).reserved());
            assertConservation(fixture, grant.id());
        }
    }

    private GrantCommand grant(String key, int count, Instant expiresAt) {
        return new GrantCommand("user-1", CreditClass.SPONSORED, "PLAYTEST", "V015-RDS",
                "test-batch", key, count, expiresAt, "可信回合测试赠送", "SYSTEM");
    }

    private void assertConservation(Fixture fixture, String grantId) {
        Integer difference = fixture.jdbc.queryForObject("""
                select granted_count-(available_count+reserved_count+consumed_count+expired_count+revoked_count)
                from turn_credit_grant where id=?
                """, Integer.class, grantId);
        assertEquals(0, difference);
    }

    private Fixture fixture() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        jdbc.update("""
                insert into app_user(id,email,status,registered_at,last_login_at)
                values ('user-1','credit-test@wenxian.invalid','ACTIVE',?,?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        TurnCreditRepository repository = new TurnCreditRepository(jdbc);
        return new Fixture(database, jdbc, transactions, new TurnCreditService(repository, transactions));
    }

    private record Fixture(EmbeddedDatabase database, JdbcTemplate jdbc,
                           TransactionTemplate transactions, TurnCreditService service) implements AutoCloseable {
        void insertPlanTier(String planId, String tierCode, int pointCost) {
            jdbc.update("""
                    insert into ai_service_plan_version(
                        id,version_number,status,revision,default_tier_code,change_note,created_by,created_at
                    ) values (?,?,?,?,?,?,?,?)
                    """, planId, 7, "ACTIVE", 0, tierCode, "推演点冻结测试", "SYSTEM", Timestamp.from(NOW));
            jdbc.update("""
                    insert into ai_service_plan_tier(
                        plan_version_id,tier_code,display_name,description,point_cost,enabled,fallback_enabled
                    ) values (?,?,?,?,?,?,?)
                    """, planId, tierCode, "深度推演", "测试档位", pointCost, true, false);
        }

        @Override
        public void close() {
            database.shutdown();
        }
    }
}
