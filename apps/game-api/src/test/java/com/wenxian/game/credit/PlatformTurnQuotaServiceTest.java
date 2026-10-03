package com.wenxian.game.credit;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformTurnQuotaServiceTest {
    @Test
    void quotaCountsOnlyCommittedLegacyInvocationsAndUsesConfiguredMessage() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values ('user-1','one@example.com','ACTIVE',?,?)",
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            PlatformTurnQuotaService service = new PlatformTurnQuotaService(jdbc);
            service.ensureDefaults();

            PlatformTurnQuotaService.Settings initial = service.settings("user-1");
            assertEquals(20, initial.turnLimit());
            assertEquals(0, initial.usedTurns());

            service.assertPlatformQuota("user-1");
            service.recordSuccessfulPlatformInvocation("user-1");
            assertEquals(1, service.settings("user-1").usedTurns());
            assertEquals(19, service.settings("user-1").remainingTurns());

            jdbc.update("update gameplay_config set config_value='1' where config_key='account_public_turn_limit'");
            jdbc.update("update gameplay_config set config_value='赠送回合已用完，请选择后续方式。' where config_key='account_public_turn_limit_message'");
            jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values ('registration_contact_text','请加入《问仙录》二群：532549329',?)",
                    Timestamp.from(Instant.now()));
            PublicTurnLimitException quotaError = assertThrows(PublicTurnLimitException.class,
                    () -> service.assertPlatformQuota("user-1"));
            assertEquals("赠送回合已用完，请选择后续方式。", quotaError.getMessage());
            assertEquals("532549329", quotaError.supportGroup());
        } finally {
            database.shutdown();
        }
    }
}
