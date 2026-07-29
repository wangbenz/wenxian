package com.wenxian.game.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthServiceTest {
    @Test
    void registrationCreatesPasswordAccountAndLaterLoginWorksFromAnotherIp() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql")
                .build();
        try {
            AuthService service = new AuthService(new JdbcTemplate(database), "LEGACY-INVITE", 30);

            AuthService.LoginOutcome first = service.register("Friend@Example.com", "password123", "10.0.0.1");
            assertTrue(first.newUser());
            assertNotNull(service.currentUser(first.rawToken()));

            AuthService.LoginOutcome second = service.login("friend@example.com", "password123", "10.0.0.2");
            assertFalse(second.newUser());
            assertNotNull(service.currentUser(second.rawToken()));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void sameIpCanRegisterOnlyOneAccountWithinWindow() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            AuthService service = new AuthService(new JdbcTemplate(database), "LEGACY-INVITE", 30);
            service.register("first@example.com", "password123", "10.0.0.8");

            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> service.register("second@example.com", "password123", "10.0.0.8"));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void registrationCapacityRejectsOnlyNewAccountsWithConfiguredMessage() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            AuthService service = new AuthService(jdbc, "LEGACY-INVITE", 30);
            service.register("first@example.com", "password123", "10.0.0.21");
            java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
            jdbc.update("merge into gameplay_config(config_key,config_value,updated_at) key(config_key) values ('registration_max_users','1',?)", now);
            jdbc.update("merge into gameplay_config(config_key,config_value,updated_at) key(config_key) values ('registration_limit_message','测试名额已满',?)", now);
            jdbc.update("merge into gameplay_config(config_key,config_value,updated_at) key(config_key) values ('registration_contact_text','请加入测试群',?)", now);

            var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> service.register("second@example.com", "password123", "10.0.0.22"));
            assertTrue(error.getReason().contains("测试名额已满"));
            assertTrue(error.getReason().contains("请加入测试群"));
            assertNotNull(service.login("first@example.com", "password123", "10.0.0.23"));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void legacyAccountUsesFullEmailOnceAndMustThenSetPassword() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
            jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values ('legacy-1','legacy@example.com','ACTIVE',?,?)", now, now);
            AuthService service = new AuthService(jdbc, "LEGACY-INVITE", 30);

            AuthService.LoginOutcome first = service.login(
                    "legacy@example.com", "legacy@example.com", "10.0.0.10");
            assertFalse(first.user().passwordConfigured());

            AuthService.UserIdentity updated = service.setPassword(first.rawToken(), null, "new-password-123");
            assertTrue(updated.passwordConfigured());
            assertTrue(service.login("legacy@example.com", "new-password-123", "10.0.0.11")
                    .user().passwordConfigured());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void deletingAccountAlsoDeletesQuotaAndEncryptedCredential() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            AuthService service = new AuthService(jdbc, "DELETE-INVITE", 30);
            AuthService.LoginOutcome login = service.register("delete@example.com", "password123", "10.0.0.9");
            String userId = login.user().userId();
            java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
            jdbc.update("insert into user_ai_credential(user_id,provider,api_key_ciphertext,masked_suffix,status,created_at,updated_at) values (?,'DEEPSEEK','encrypted','1234','ACTIVE',?,?)", userId, now, now);
            jdbc.update("insert into user_daily_quota(user_id,usage_date,used_count,updated_at) values (?,current_date,3,?)", userId, now);

            service.deleteAccount(login.rawToken());

            assertEquals(0, jdbc.queryForObject("select count(*) from app_user where id=?", Integer.class, userId));
            assertEquals(0, jdbc.queryForObject("select count(*) from user_ai_credential where user_id=?", Integer.class, userId));
            assertEquals(0, jdbc.queryForObject("select count(*) from user_daily_quota where user_id=?", Integer.class, userId));
        } finally {
            database.shutdown();
        }
    }
}
