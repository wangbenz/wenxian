package com.wenxian.admin.model;

import com.wenxian.admin.SecretCipher;
import com.wenxian.admin.audit.AdminOperationLogRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelCatalogApplicationServiceTest {
    private static final String MASTER_KEY = "admin-test-master-key";
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private ModelCatalogApplicationService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("admin-generation-" + UUID.randomUUID()).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table admin_operation_log(id varchar(80) primary key, operated_at timestamp not null, operation varchar(80) not null, detail varchar(1000) not null, subject_user_id varchar(64))");
        jdbc.execute("""
                create table ai_provider_config(
                    provider_code varchar(32) primary key, display_name varchar(80) not null,
                    base_url varchar(255) not null, adapter_type varchar(32) not null,
                    platform_secret_ciphertext clob, enabled boolean not null,
                    user_key_enabled boolean not null, config_version bigint not null default 1,
                    updated_at timestamp not null)
                """);
        jdbc.execute("""
                create table ai_provider_model(
                    provider_code varchar(32) not null, model_code varchar(80) not null,
                    display_name varchar(80) not null, api_model_name varchar(120) not null,
                    capability_codes varchar(255) not null default '', context_window integer not null default 64000,
                    maximum_output_tokens integer not null default 8000, concurrency_limit integer not null default 4,
                    platform_enabled boolean not null, user_selectable boolean not null,
                    config_version bigint not null default 1,
                    updated_at timestamp not null, primary key(provider_code, model_code))
                """);
        jdbc.execute("""
                create table ai_model_validation_run(
                    id varchar(64) primary key, provider_code varchar(32) not null, model_code varchar(80) not null,
                    provider_config_version bigint not null, model_config_version bigint not null,
                    status varchar(24) not null, capability_codes varchar(255) not null default '',
                    latency_ms integer, error_code varchar(80), error_summary varchar(500),
                    checked_by varchar(64) not null, checked_at timestamp not null)
                """);
        jdbc.execute("""
                create table ai_model_task_route(
                    task_code varchar(48) primary key, primary_provider_code varchar(32) not null,
                    primary_model_code varchar(80) not null, fallback_provider_code varchar(32),
                    fallback_model_code varchar(80), fallback_enabled boolean not null,
                    updated_at timestamp not null)
                """);
        jdbc.execute("""
                create table ai_service_plan_version(
                    id varchar(64) primary key, version_number bigint not null, status varchar(16) not null)
                """);
        jdbc.execute("""
                create table ai_service_plan_tier(
                    plan_version_id varchar(64) not null, tier_code varchar(16) not null,
                    primary_provider_code varchar(32), primary_model_code varchar(80),
                    fallback_provider_code varchar(32), fallback_model_code varchar(80))
                """);
        jdbc.execute("""
                create table ai_service_plan_task_route(
                    plan_version_id varchar(64) not null, tier_code varchar(16) not null, task_code varchar(48) not null,
                    primary_provider_code varchar(32) not null, primary_model_code varchar(80) not null,
                    fallback_provider_code varchar(32), fallback_model_code varchar(80))
                """);
        Instant now = Instant.now();
        jdbc.update("""
                insert into ai_provider_config(provider_code,display_name,base_url,adapter_type,
                    platform_secret_ciphertext,enabled,user_key_enabled,updated_at) values (?,?,?,?,?,?,?,?)
                """, "DEEPSEEK", "DeepSeek",
                "https://api.deepseek.com", "OPENAI_COMPATIBLE",
                SecretCipher.encrypt("sk-platform-secret-123456789", MASTER_KEY), true, true, Timestamp.from(now));
        jdbc.update("""
                insert into ai_provider_model(provider_code,model_code,display_name,api_model_name,capability_codes,
                    context_window,maximum_output_tokens,concurrency_limit,platform_enabled,user_selectable,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, "DEEPSEEK", "deepseek-v4-flash", "Flash", "deepseek-v4-flash",
                "CHAT,JSON_MODE", 128000, 8000, 4, true, true, Timestamp.from(now));
        jdbc.update("""
                insert into ai_provider_model(provider_code,model_code,display_name,api_model_name,capability_codes,
                    context_window,maximum_output_tokens,concurrency_limit,platform_enabled,user_selectable,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, "DEEPSEEK", "deepseek-v4-pro", "Pro", "deepseek-v4-pro",
                "CHAT,JSON_MODE", 128000, 8000, 2, true, true, Timestamp.from(now));
        jdbc.update("insert into ai_model_task_route values (?,?,?,?,?,?,?)", "TURN_DIRECTOR", "DEEPSEEK",
                "deepseek-v4-flash", null, null, false, Timestamp.from(now));
        jdbc.update("insert into ai_model_task_route values (?,?,?,?,?,?,?)", "NARRATIVE_WRITER", "DEEPSEEK",
                "deepseek-v4-flash", "DEEPSEEK", "deepseek-v4-pro", true, Timestamp.from(now));
        jdbc.update("""
                insert into ai_model_validation_run values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, "validation-flash", "DEEPSEEK", "deepseek-v4-flash", 1L, 1L, "PASSED",
                "CHAT,JSON_MODE", 812, null, null, "admin-test", Timestamp.from(now));
        service = new ModelCatalogApplicationService(new JdbcModelCatalogAdminRepository(jdbc),
                new AdminOperationLogRepository(jdbc), MASTER_KEY);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void masksPlatformKeyAndBlankUpdatePreservesCiphertext() {
        String before = jdbc.queryForObject(
                "select platform_secret_ciphertext from ai_provider_config where provider_code='DEEPSEEK'", String.class);

        ModelCatalogProviderView view = service.providers().getFirst();
        assertEquals(true, view.keyConfigured());
        assertEquals("6789", view.keySuffix());
        assertEquals(2, view.platformTaskCount());
        assertEquals(1L, view.configVersion());
        assertFalse(view.toString().contains("sk-platform-secret-123456789"));
        ModelCatalogProviderView.ModelView primary = view.models().stream()
                .filter(model -> model.modelCode().equals("deepseek-v4-flash")).findFirst().orElseThrow();
        assertEquals("PASSED", primary.verificationStatus());
        assertEquals(java.util.List.of("CHAT", "JSON_MODE"), primary.capabilityCodes());
        assertEquals(812, primary.verificationLatencyMs());
        ModelCatalogProviderView.ModelView fallback = view.models().stream()
                .filter(model -> model.modelCode().equals("deepseek-v4-pro")).findFirst().orElseThrow();
        assertEquals(java.util.List.of("NARRATIVE_WRITER"), fallback.activeTaskCodes());
        assertFalse(fallback.allPlatformTasks());

        service.updateProvider("DEEPSEEK", "DeepSeek", "https://api.deepseek.com",
                true, true, "");
        String after = jdbc.queryForObject(
                "select platform_secret_ciphertext from ai_provider_config where provider_code='DEEPSEEK'", String.class);
        assertEquals(before, after);
        assertEquals(1L, service.providers().getFirst().configVersion());
    }

    @Test
    void marksValidationStaleWhenProviderRuntimeConfigChanges() {
        service.updateProvider("DEEPSEEK", "DeepSeek", "https://gateway.example.com",
                true, true, "");

        ModelCatalogProviderView provider = service.providers().getFirst();
        assertEquals(2L, provider.configVersion());
        ModelCatalogProviderView.ModelView model = provider.models().stream()
                .filter(item -> item.modelCode().equals("deepseek-v4-flash")).findFirst().orElseThrow();
        assertEquals("STALE", model.verificationStatus());
    }

    @Test
    void protectsPrimaryAndEnabledFallbackRoutesFromBeingDisabled() {
        assertThrows(ResponseStatusException.class, () -> service.updateProvider(
                "DEEPSEEK", "DeepSeek", "https://api.deepseek.com", false, true, ""));
        assertThrows(ResponseStatusException.class, () -> updateModel(
                "DEEPSEEK", "deepseek-v4-flash", "Flash", "deepseek-v4-flash", false, true, false));
        assertThrows(ResponseStatusException.class, () -> updateModel(
                "DEEPSEEK", "deepseek-v4-pro", "Pro", "deepseek-v4-pro", false, true, false));
    }

    @Test
    void protectsDraftAndActiveServicePlanReferencesFromBeingDisabled() {
        jdbc.update("insert into ai_service_plan_version values ('draft-plan', 1, 'DRAFT')");
        jdbc.update("""
                insert into ai_service_plan_tier values ('draft-plan', 'LIGHT', 'DEEPSEEK',
                    'deepseek-v4-pro', null, null)
                """);

        assertThrows(ResponseStatusException.class, () -> updateModel(
                "DEEPSEEK", "deepseek-v4-pro", "Pro", "deepseek-v4-pro", false, true, false));
        ModelCatalogProviderView.ModelView model = service.providers().getFirst().models().stream()
                .filter(item -> item.modelCode().equals("deepseek-v4-pro")).findFirst().orElseThrow();
        assertEquals(1, model.servicePlanReferences().size());
        assertEquals("TIER_PRIMARY", model.servicePlanReferences().getFirst().usage());
    }

    @Test
    void cannotCreateModelUnderMissingProviderOrRouteToProviderWithoutKey() {
        assertThrows(ResponseStatusException.class, () -> updateModel(
                "MISSING", "missing-chat", "Missing", "missing-chat", true, true, false));

        Instant now = Instant.now();
        jdbc.update("""
                insert into ai_provider_config(provider_code,display_name,base_url,adapter_type,
                    platform_secret_ciphertext,enabled,user_key_enabled,updated_at) values (?,?,?,?,?,?,?,?)
                """, "OTHER", "Other",
                "https://example.com", "OPENAI_COMPATIBLE", null, true, true, Timestamp.from(now));
        jdbc.update("""
                insert into ai_provider_model(provider_code,model_code,display_name,api_model_name,
                    platform_enabled,user_selectable,updated_at) values (?,?,?,?,?,?,?)
                """, "OTHER", "other-chat", "Other Chat", "other-chat", true, true, Timestamp.from(now));
        assertThrows(ResponseStatusException.class, () -> updateModel(
                "OTHER", "other-chat", "Other Chat", "other-chat", true, true, true));
        assertEquals(2, routeCount("DEEPSEEK", "deepseek-v4-flash"));
    }

    @Test
    void editsUnusedModelRuntimeLimitsAndIncrementsConfigurationVersion() {
        service.updateModel("DEEPSEEK", "unused-chat", "Unused", "unused-chat",
                java.util.List.of("CHAT", "JSON_MODE"), 64000, 4096, 3,
                false, false, false);
        service.updateModel("DEEPSEEK", "unused-chat", "Unused", "unused-chat-v2",
                java.util.List.of("CHAT", "JSON_MODE", "STREAMING"), 128000, 8192, 5,
                false, false, false);

        ModelCatalogProviderView.ModelView model = service.providers().getFirst().models().stream()
                .filter(item -> item.modelCode().equals("unused-chat")).findFirst().orElseThrow();
        assertEquals(2L, model.configVersion());
        assertEquals(128000, model.contextWindow());
        assertEquals(8192, model.maximumOutputTokens());
        assertEquals(5, model.concurrencyLimit());
        assertEquals(java.util.List.of("CHAT", "JSON_MODE", "STREAMING"), model.capabilityCodes());
    }

    @Test
    void refusesRuntimeParameterChangesWhileModelIsReferenced() {
        assertThrows(ResponseStatusException.class, () -> service.updateModel(
                "DEEPSEEK", "deepseek-v4-flash", "Flash", "deepseek-v4-flash",
                java.util.List.of("CHAT", "JSON_MODE"), 128000, 9000, 4,
                true, true, false));
        assertEquals(8000, jdbc.queryForObject("""
                select maximum_output_tokens from ai_provider_model
                where provider_code='DEEPSEEK' and model_code='deepseek-v4-flash'
                """, Integer.class));
    }

    @Test
    void switchesAllPrimaryTasksWithoutOverwritingFallbackRoute() {
        assertThrows(ResponseStatusException.class, () -> updateModel(
                "DEEPSEEK", "deepseek-v4-pro", "Pro", "deepseek-v4-pro", true, true, true));
        Instant now = Instant.now();
        jdbc.update("""
                insert into ai_model_validation_run values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, "validation-pro", "DEEPSEEK", "deepseek-v4-pro", 1L, 1L, "PASSED",
                "CHAT,JSON_MODE", 930, null, null, "admin-test", Timestamp.from(now));
        updateModel("DEEPSEEK", "deepseek-v4-pro", "Pro", "deepseek-v4-pro",
                true, true, true);
        assertEquals(2, routeCount("DEEPSEEK", "deepseek-v4-pro"));
        assertEquals("deepseek-v4-pro", jdbc.queryForObject("""
                select fallback_model_code from ai_model_task_route where task_code='NARRATIVE_WRITER'
                """, String.class));
    }

    private int routeCount(String provider, String model) {
        return jdbc.queryForObject("""
                select count(*) from ai_model_task_route
                where primary_provider_code=? and primary_model_code=?
                """, Integer.class, provider, model);
    }

    private void updateModel(String provider, String model, String displayName, String apiModelName,
                             boolean platformEnabled, boolean userSelectable, boolean applyToAllTasks) {
        java.util.List<java.util.Map<String, Object>> configs = jdbc.queryForList("""
                select capability_codes, context_window, maximum_output_tokens, concurrency_limit
                from ai_provider_model where provider_code=? and model_code=?
                """, provider, model);
        if (configs.isEmpty()) {
            service.updateModel(provider, model, displayName, apiModelName,
                    java.util.List.of(), 64000, 8000, 4,
                    platformEnabled, userSelectable, applyToAllTasks);
            return;
        }
        java.util.Map<String, Object> config = configs.getFirst();
        java.util.List<String> capabilities = java.util.Arrays.stream(
                        String.valueOf(config.get("CAPABILITY_CODES")).split(","))
                .filter(value -> !value.isBlank()).toList();
        service.updateModel(provider, model, displayName, apiModelName, capabilities,
                ((Number) config.get("CONTEXT_WINDOW")).intValue(),
                ((Number) config.get("MAXIMUM_OUTPUT_TOKENS")).intValue(),
                ((Number) config.get("CONCURRENCY_LIMIT")).intValue(),
                platformEnabled, userSelectable, applyToAllTasks);
    }
}
