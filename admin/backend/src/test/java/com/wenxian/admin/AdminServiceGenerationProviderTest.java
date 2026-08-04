package com.wenxian.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminServiceGenerationProviderTest {
    private static final String MASTER_KEY = "admin-test-master-key";
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AdminService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("admin-generation-" + UUID.randomUUID()).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table gameplay_config(config_key varchar(80) primary key, config_value varchar(1000) not null, updated_at timestamp not null)");
        jdbc.execute("create table admin_operation_log(id varchar(80) primary key, operated_at timestamp not null, operation varchar(80) not null, detail varchar(1000) not null)");
        jdbc.execute("""
                create table ai_provider_config(
                    provider_code varchar(32) primary key, display_name varchar(80) not null,
                    base_url varchar(255) not null, adapter_type varchar(32) not null,
                    platform_secret_ciphertext clob, enabled boolean not null,
                    user_key_enabled boolean not null, updated_at timestamp not null)
                """);
        jdbc.execute("""
                create table ai_provider_model(
                    provider_code varchar(32) not null, model_code varchar(80) not null,
                    display_name varchar(80) not null, api_model_name varchar(120) not null,
                    platform_enabled boolean not null, user_selectable boolean not null,
                    updated_at timestamp not null, primary key(provider_code, model_code))
                """);
        jdbc.execute("""
                create table ai_model_config(
                    provider_code varchar(32) not null, model_code varchar(80) primary key,
                    display_name varchar(80) not null, secret_ciphertext clob,
                    active boolean not null, updated_at timestamp not null)
                """);
        Instant now = Instant.now();
        config("v014_platform_provider", "DEEPSEEK", now);
        config("v014_platform_model", "deepseek-v4-flash", now);
        jdbc.update("insert into ai_provider_config values (?,?,?,?,?,?,?,?)", "DEEPSEEK", "DeepSeek",
                "https://api.deepseek.com", "OPENAI_COMPATIBLE",
                SecretCipher.encrypt("sk-platform-secret-123456789", MASTER_KEY), true, true, Timestamp.from(now));
        jdbc.update("insert into ai_provider_model values (?,?,?,?,?,?,?)", "DEEPSEEK", "deepseek-v4-flash",
                "Flash", "deepseek-v4-flash", true, true, Timestamp.from(now));
        jdbc.update("insert into ai_provider_model values (?,?,?,?,?,?,?)", "DEEPSEEK", "deepseek-v4-pro",
                "Pro", "deepseek-v4-pro", true, true, Timestamp.from(now));
        service = new AdminService(jdbc, MASTER_KEY, "", "", "");
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void masksPlatformKeyAndBlankUpdatePreservesCiphertext() {
        String before = jdbc.queryForObject(
                "select platform_secret_ciphertext from ai_provider_config where provider_code='DEEPSEEK'", String.class);

        Map<String, Object> view = service.generationProviders().getFirst();
        assertEquals(true, view.get("keyConfigured"));
        assertEquals("6789", view.get("keySuffix"));
        assertFalse(view.containsKey("apiKey"));
        assertFalse(view.containsValue("sk-platform-secret-123456789"));

        service.updateGenerationProvider("DEEPSEEK", "DeepSeek", "https://api.deepseek.com",
                true, true, "");
        String after = jdbc.queryForObject(
                "select platform_secret_ciphertext from ai_provider_config where provider_code='DEEPSEEK'", String.class);
        assertEquals(before, after);
    }

    @Test
    void cannotDisableCurrentRouteOrSwitchToProviderWithoutPlatformKey() {
        assertThrows(ResponseStatusException.class, () -> service.updateGenerationProvider(
                "DEEPSEEK", "DeepSeek", "https://api.deepseek.com", false, true, ""));
        assertThrows(ResponseStatusException.class, () -> service.updateGenerationModel(
                "DEEPSEEK", "deepseek-v4-flash", "Flash", "deepseek-v4-flash", false, true, false));

        Instant now = Instant.now();
        jdbc.update("insert into ai_provider_config values (?,?,?,?,?,?,?,?)", "OTHER", "Other",
                "https://example.com", "OPENAI_COMPATIBLE", null, true, true, Timestamp.from(now));
        jdbc.update("insert into ai_provider_model values (?,?,?,?,?,?,?)", "OTHER", "other-chat",
                "Other Chat", "other-chat", true, true, Timestamp.from(now));
        assertThrows(ResponseStatusException.class, () -> service.updateGenerationModel(
                "OTHER", "other-chat", "Other Chat", "other-chat", true, true, true));

        assertEquals("DEEPSEEK", value("v014_platform_provider"));
        assertEquals("deepseek-v4-flash", value("v014_platform_model"));
    }

    @Test
    void switchesToAnotherEnabledModelWithoutChangingTheStoredKey() {
        service.updateGenerationModel("DEEPSEEK", "deepseek-v4-pro", "Pro", "deepseek-v4-pro",
                true, true, true);
        assertEquals("DEEPSEEK", value("v014_platform_provider"));
        assertEquals("deepseek-v4-pro", value("v014_platform_model"));
    }

    private void config(String key, String value, Instant now) {
        jdbc.update("insert into gameplay_config values (?,?,?)", key, value, Timestamp.from(now));
    }

    private String value(String key) {
        return jdbc.queryForObject("select config_value from gameplay_config where config_key=?", String.class, key);
    }
}
