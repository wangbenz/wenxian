package com.wenxian.game.credit;

import com.wenxian.game.credit.TurnCreditModels.CreditClass;
import com.wenxian.game.credit.TurnCreditModels.GrantCommand;
import com.wenxian.game.credit.TurnCreditModels.ReservationStatus;
import com.wenxian.game.credit.TurnCreditModels.ReserveCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享 RDS 上的平台回合账户显式写入冒烟。
 *
 * <p>默认不运行。维护者必须显式提供连接环境变量、测试邮箱和开启标志；测试发放带独立批次标记，
 * 最终消费一回合并撤销全部剩余测试余额，不改变玩家可用平台回合。</p>
 */
@EnabledIfEnvironmentVariable(named = "WENXIAN_CREDIT_RDS_SMOKE", matches = "(?i:true|1|yes)")
class TurnCreditRdsIntegrationTest {
    @Test
    void verifiesGrantReserveConsumeReleaseAndRevokeOnMysql() {
        String url = required("WENXIAN_RDS_URL");
        assertTrue(url.startsWith("jdbc:mysql://"), "平台回合 RDS 冒烟只能连接 MySQL");
        DriverManagerDataSource database = new DriverManagerDataSource(
                url, required("WENXIAN_RDS_USER"), required("WENXIAN_RDS_PASSWORD"));
        database.setDriverClassName("com.mysql.cj.jdbc.Driver");

        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
        schema.setContinueOnError(false);
        schema.execute(database);

        JdbcTemplate jdbc = new JdbcTemplate(database);
        assertEquals("wenxian", jdbc.queryForObject("select database()", String.class));
        String email = required("WENXIAN_CREDIT_RDS_TEST_EMAIL");
        String userId = jdbc.queryForObject("select id from app_user where email=?", String.class, email);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        TurnCreditService service = new TurnCreditService(new TurnCreditRepository(jdbc), transactions);

        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var grant = service.grant(new GrantCommand(userId, CreditClass.SPONSORED, "PLAYTEST",
                "V015-CREDIT-SMOKE", runId, "V015-CREDIT-SMOKE:" + runId, 3,
                now.plus(Duration.ofDays(1)), "V0.15平台回合账户RDS事务冒烟", "CODEX_RDS_SMOKE"));

        var first = service.reserve(new ReserveCommand(userId, "V015-CREDIT-SMOKE-SAVE-A",
                "V015-CREDIT-SMOKE-REQUEST-A:" + runId, Duration.ofMinutes(10)));
        var second = service.reserve(new ReserveCommand(userId, "V015-CREDIT-SMOKE-SAVE-B",
                "V015-CREDIT-SMOKE-REQUEST-B:" + runId, Duration.ofMinutes(10)));
        assertEquals(1, service.balance(userId).available());
        assertEquals(2, service.balance(userId).reserved());

        assertEquals(ReservationStatus.CONSUMED,
                service.consume(first.id(), "V015-CREDIT-SMOKE-TURN:" + runId).status());
        assertEquals(ReservationStatus.RELEASED,
                service.release(second.id(), "V0.15 RDS冒烟释放未提交行动").status());
        service.revoke(grant.id(), "V0.15 RDS冒烟结束，撤销剩余测试回合", "CODEX_RDS_SMOKE");

        var finalBalance = service.balance(userId);
        assertEquals(0, jdbc.queryForObject(
                "select available_count from turn_credit_grant where id=?", Integer.class, grant.id()));
        assertEquals(0, jdbc.queryForObject(
                "select reserved_count from turn_credit_grant where id=?", Integer.class, grant.id()));
        assertEquals(1, jdbc.queryForObject(
                "select consumed_count from turn_credit_grant where id=?", Integer.class, grant.id()));
        assertEquals(2, jdbc.queryForObject(
                "select revoked_count from turn_credit_grant where id=?", Integer.class, grant.id()));
        assertEquals(6, jdbc.queryForObject(
                "select count(*) from turn_credit_ledger where grant_id=?", Integer.class, grant.id()));
        assertTrue(finalBalance.available() >= 0, "账号总余额不能因测试出现负数");
        System.out.printf("CREDIT_RDS_SMOKE user=%s grant=%s consumed=1 revoked=2 availableDelta=0%n",
                userId, grant.id());

        // 共享 RDS 冒烟不得长期污染玩家端“累计赠送/已使用”展示；断言完成后清除本次专用数据。
        jdbc.update("delete from turn_credit_ledger where grant_id=?", grant.id());
        jdbc.update("delete from turn_credit_reservation where grant_id=?", grant.id());
        jdbc.update("delete from turn_credit_grant where id=?", grant.id());
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from turn_credit_grant where id=?", Integer.class, grant.id()));
    }

    private String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少环境变量：" + key);
        return value.strip();
    }
}
