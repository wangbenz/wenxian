package com.wenxian.game.service;

import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.config.SecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerAiServiceTest {
    @Test
    void publicQuotaCountsOnlyRecordedInvocationsAndUsesConfiguredLimit() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values ('user-1','one@example.com','ACTIVE',?,?)",
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            RuntimeAiConfigService runtime = new RuntimeAiConfigService(jdbc, "platform-key", "deepseek-v4-pro", "master-key", false);
            PlayerAiService service = new PlayerAiService(jdbc, runtime, RestClient.builder(), "https://api.deepseek.com", "master-key");
            service.ensureDefaults();

            PlayerAiService.Settings initial = service.settings("user-1");
            assertEquals(20, initial.turnLimit());
            assertEquals(0, initial.usedTurns());
            assertFalse(initial.usingCustomApi());

            PlayerAiService.Route route = service.route("user-1");
            service.assertPublicQuota("user-1", route);
            service.recordSuccessfulInvocation("user-1", route);

            PlayerAiService.Settings after = service.settings("user-1");
            assertEquals(1, after.usedTurns());
            assertEquals(19, after.remainingTurns());

            jdbc.update("insert into user_ai_credential(user_id,provider,api_key_ciphertext,masked_suffix,status,last_verified_at,created_at,updated_at) values ('user-1','DEEPSEEK',?,'1234','ACTIVE',?,?,?)",
                    SecretCipher.encrypt("sk-user-secret-123456789", "master-key"), Timestamp.from(Instant.now()),
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            PlayerAiService.Route customRoute = service.route("user-1");
            assertEquals(PlayerAiService.USER_SOURCE, customRoute.source());
            assertEquals("sk-user-secret-123456789", customRoute.config().apiKey());
            service.recordSuccessfulInvocation("user-1", customRoute);
            assertEquals(1, service.settings("user-1").usedTurns());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void disabledCustomApiRejectsCredentialBeforeCallingProvider() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values ('user-1','one@example.com','ACTIVE',?,?)",
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            RuntimeAiConfigService runtime = new RuntimeAiConfigService(jdbc, "platform-key", "deepseek-v4-pro", "master-key", false);
            PlayerAiService service = new PlayerAiService(jdbc, runtime, RestClient.builder(), "https://api.deepseek.com", "master-key");
            service.ensureDefaults();
            jdbc.update("update gameplay_config set config_value='false' where config_key='custom_api_enabled'");

            assertFalse(service.settings("user-1").customApiEnabled());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> service.saveCredential("user-1", "sk-user-secret-123456789"));
        } finally {
            database.shutdown();
        }
    }
}
