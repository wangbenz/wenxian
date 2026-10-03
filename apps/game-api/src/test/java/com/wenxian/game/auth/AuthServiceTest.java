package com.wenxian.game.auth;

import com.wenxian.game.api.GameApiException;
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
    void isolatedProcessAllowlistDoesNotReuseSharedAccessMode() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            AuthService initial = new AuthService(jdbc, "LEGACY-INVITE", 30);
            AuthService.LoginOutcome invited = initial.register(
                    "Invited@Example.com", "password123", "10.0.0.41");
            AuthService.LoginOutcome ordinary = initial.register(
                    "ordinary@example.com", "password123", "10.0.0.42");
            java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());

            // 共享配置故意只允许 ordinary；独立进程仍必须只认自己的 invited 名单。
            jdbc.update("insert into game_access_whitelist(id,user_id,note,created_by,created_at,updated_at) values ('access-isolated',?,'正式名单','test',?,?)",
                    ordinary.user().userId(), now, now);
            jdbc.update("merge into gameplay_config(config_key,config_value,updated_at) key(config_key) values ('game_access_mode','WHITELIST',?)", now);
            AccessPolicyService isolatedPolicy = new AccessPolicyService(jdbc, true,
                    "invited@example.com", "受邀测试", "仅受邀账号可进入", "");
            AuthService isolated = new AuthService(jdbc, "LEGACY-INVITE", 30, 12,
                    "test-ip-pepper", 1, 5, isolatedPolicy);

            assertTrue(isolatedPolicy.status().restricted());
            assertEquals("受邀测试", isolatedPolicy.status().title());
            assertNotNull(isolated.login("invited@example.com", "password123", "10.0.0.43"),
                    "独立名单账号不应被共享数据库名单拦截");
            assertThrows(GameMaintenanceException.class,
                    () -> isolated.login("ordinary@example.com", "password123", "10.0.0.44"),
                    "共享数据库名单账号不能绕过独立灰度名单");
            assertThrows(GameMaintenanceException.class,
                    () -> isolated.register("new@example.com", "password123", "10.0.0.45"));
            assertThrows(GameMaintenanceException.class, () -> isolated.requireUser(ordinary.rawToken()));
            assertNotNull(isolated.requireUser(invited.rawToken()));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void whitelistModeAllowsOnlyConfiguredTestAccounts() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            AuthService service = new AuthService(jdbc, "LEGACY-INVITE", 30);
            AuthService.LoginOutcome allowed = service.register("allowed@example.com", "password123", "10.0.0.31");
            AuthService.LoginOutcome blocked = service.register("blocked@example.com", "password123", "10.0.0.32");
            java.sql.Timestamp now = java.sql.Timestamp.from(java.time.Instant.now());
            jdbc.update("insert into game_access_whitelist(id,user_id,note,created_by,created_at,updated_at) values ('access-1',?,'测试','test',?,?)",
                    allowed.user().userId(), now, now);
            jdbc.update("merge into gameplay_config(config_key,config_value,updated_at) key(config_key) values ('game_access_mode','WHITELIST',?)", now);
            jdbc.update("merge into gameplay_config(config_key,config_value,updated_at) key(config_key) values ('game_access_notice_title','更新中',?)", now);
            jdbc.update("merge into gameplay_config(config_key,config_value,updated_at) key(config_key) values ('game_access_notice_content','请稍后再来',?)", now);

            assertNotNull(service.login("allowed@example.com", "password123", "10.0.0.33"));
            assertThrows(GameMaintenanceException.class,
                    () -> service.login("blocked@example.com", "password123", "10.0.0.34"));
            assertThrows(GameMaintenanceException.class,
                    () -> service.register("new@example.com", "password123", "10.0.0.35"));
            assertThrows(GameMaintenanceException.class, () -> service.requireUser(blocked.rawToken()));
        } finally {
            database.shutdown();
        }
    }

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

            var error = assertThrows(GameApiException.class,
                    () -> service.register("second@example.com", "password123", "10.0.0.8"));
            assertEquals("REGISTRATION_NETWORK_LIMIT", error.code());
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

            var error = assertThrows(GameApiException.class,
                    () -> service.register("second@example.com", "password123", "10.0.0.22"));
            assertEquals("REGISTRATION_CAPACITY_REACHED", error.code());
            assertTrue(error.getMessage().contains("测试名额已满"));
            assertTrue(error.getMessage().contains("请加入测试群"));
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
    void sessionRefreshDoesNotMasqueradeAsMeaningfulLoginActivity() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            AuthService service = new AuthService(jdbc, "SESSION-INVITE", 30);
            AuthService.LoginOutcome login = service.register(
                    "session@example.com", "password123", "10.0.0.8");
            java.sql.Timestamp explicitLoginAt = jdbc.queryForObject(
                    "select last_login_at from app_user where id=?", java.sql.Timestamp.class,
                    login.user().userId());
            java.sql.Timestamp retentionAt = jdbc.queryForObject("""
                    select last_meaningful_activity_at from account_retention_state where user_id=?
                    """, java.sql.Timestamp.class, login.user().userId());

            assertNotNull(service.currentUser(login.rawToken()));

            assertEquals(explicitLoginAt, jdbc.queryForObject(
                    "select last_login_at from app_user where id=?", java.sql.Timestamp.class,
                    login.user().userId()));
            assertEquals(retentionAt, jdbc.queryForObject("""
                    select last_meaningful_activity_at from account_retention_state where user_id=?
                    """, java.sql.Timestamp.class, login.user().userId()));
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
            jdbc.update("insert into ai_provider_config(provider_code,display_name,base_url,adapter_type,enabled,user_key_enabled,updated_at) values ('DEEPSEEK','DeepSeek','https://api.deepseek.com','OPENAI_COMPATIBLE',true,true,?)", now);
            jdbc.update("insert into user_ai_provider_credential(user_id,provider_code,api_key_ciphertext,masked_suffix,status,created_at,updated_at) values (?,'DEEPSEEK','encrypted','1234','ACTIVE',?,?)", userId, now, now);
            jdbc.update("insert into user_daily_quota(user_id,usage_date,used_count,updated_at) values (?,current_date,3,?)", userId, now);
            jdbc.update("""
                    insert into story_save(
                        id,user_id,slot_no,active_slot_no,status,world_code,engine_code,engine_version,
                        state_schema_version,source_type,state_json,created_release,last_played_release,created_at,updated_at
                    ) values ('story-delete',?,1,1,'ACTIVE','world','LIGHT_NARRATIVE',1,1,'NATIVE','{}','UNRELEASED','UNRELEASED',?,?)
                    """, userId, now, now);
            jdbc.update("""
                    insert into analytics_event(id,event_key,event_type,user_id,save_id,release_version,engine_code,
                        data_origin,metadata_json,occurred_at,recorded_at)
                    values ('analytics-delete','delete:event','SAVE_CREATED',?,'story-delete','UNRELEASED',
                        'LIGHT_NARRATIVE','REALTIME','{}',?,?)
                    """, userId, now, now);
            jdbc.update("""
                    insert into analytics_user_day(activity_date,user_id,created_save_count,successful_turn_count,
                        correction_count,entity_removal_count,archived_save_count,first_activity_at,last_activity_at,
                        first_release_version,last_release_version,data_origin,updated_at)
                    values (current_date,?,1,0,0,0,0,?,?,'UNRELEASED','UNRELEASED','REALTIME',?)
                    """, userId, now, now, now);

            service.deleteAccount(login.rawToken());

            assertEquals(0, jdbc.queryForObject("select count(*) from app_user where id=?", Integer.class, userId));
            assertEquals(0, jdbc.queryForObject("select count(*) from story_save where user_id=?", Integer.class, userId));
            assertEquals(0, jdbc.queryForObject("select count(*) from analytics_event where user_id=?", Integer.class, userId));
            assertEquals(0, jdbc.queryForObject("select count(*) from analytics_user_day where user_id=?", Integer.class, userId));
            assertEquals(0, jdbc.queryForObject("select count(*) from user_ai_provider_credential where user_id=?", Integer.class, userId));
            assertEquals(0, jdbc.queryForObject("select count(*) from user_daily_quota where user_id=?", Integer.class, userId));
        } finally {
            database.shutdown();
        }
    }
}
