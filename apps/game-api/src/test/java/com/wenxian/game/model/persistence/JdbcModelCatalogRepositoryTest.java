package com.wenxian.game.model.persistence;

import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcModelCatalogRepositoryTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private JdbcModelCatalogRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("""
                create table ai_provider_config(
                    provider_code varchar(32) primary key, base_url varchar(255), adapter_type varchar(32),
                    platform_secret_ciphertext clob, enabled boolean, user_key_enabled boolean)
                """);
        jdbc.execute("""
                create table ai_provider_model(
                    provider_code varchar(32), model_code varchar(80), api_model_name varchar(120),
                    capability_codes varchar(255), context_window integer, maximum_output_tokens integer,
                    platform_enabled boolean, user_selectable boolean, primary key(provider_code, model_code))
                """);
        jdbc.execute("""
                create table ai_model_task_route(
                    task_code varchar(48) primary key, primary_provider_code varchar(32), primary_model_code varchar(80),
                    fallback_provider_code varchar(32), fallback_model_code varchar(80), temperature decimal(4,3),
                    timeout_seconds integer, maximum_retries integer, maximum_output_tokens integer,
                    disable_thinking boolean, fallback_enabled boolean)
                """);
        jdbc.execute("""
                create table user_model_preference(
                    user_id varchar(64) primary key, provider_code varchar(32), model_code varchar(80),
                    credential_source varchar(20))
                """);
        jdbc.execute("""
                create table user_ai_provider_credential(
                    user_id varchar(64), provider_code varchar(32), api_key_ciphertext clob,
                    status varchar(24), primary key(user_id, provider_code))
                """);
        jdbc.update("""
                insert into ai_provider_config(provider_code, base_url, adapter_type,
                    platform_secret_ciphertext, enabled, user_key_enabled) values(?, ?, ?, ?, true, true)
                """, "DEEPSEEK", "https://example.invalid", "OPENAI_COMPATIBLE", "encrypted-platform");
        jdbc.update("""
                insert into ai_provider_model(provider_code, model_code, api_model_name, capability_codes,
                    context_window, maximum_output_tokens, platform_enabled, user_selectable)
                values('DEEPSEEK', 'flash', 'api-flash', 'CHAT,JSON_MODE,THINKING_CONTROL,CACHE_USAGE',
                    128000, 8000, true, true)
                """);
        jdbc.update("""
                insert into ai_model_task_route(task_code, primary_provider_code, primary_model_code,
                    temperature, timeout_seconds, maximum_retries, maximum_output_tokens,
                    disable_thinking, fallback_enabled)
                values('CHARACTER_CREATION', 'DEEPSEEK', 'flash', 0.6, 45, 0, 2200, true, false)
                """);
        jdbc.update("insert into user_model_preference values('user-1','DEEPSEEK','flash','USER')");
        jdbc.update("insert into user_ai_provider_credential values('user-1','DEEPSEEK','encrypted-user','ACTIVE')");
        repository = new JdbcModelCatalogRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.shutdown();
    }

    @Test
    void readsCapabilitiesRoutePreferenceAndCredentialsFromNeutralTables() {
        var model = repository.findPlatformModel("deepseek", "flash").orElseThrow();
        var route = repository.findTaskRoute(ModelTask.CHARACTER_CREATION).orElseThrow();

        assertThat(model.capabilities()).contains(ModelCapability.JSON_MODE,
                ModelCapability.THINKING_CONTROL, ModelCapability.CACHE_USAGE);
        assertThat(model.contextWindow()).isEqualTo(128_000);
        assertThat(route.options().temperature()).isEqualTo(0.6);
        assertThat(route.options().maximumOutputTokens()).isEqualTo(2200);
        assertThat(repository.findUserPreference("user-1")).get()
                .extracting(ModelCatalogRepository.UserPreference::credentialSource).isEqualTo("USER");
        assertThat(repository.findPlatformCredential("DEEPSEEK")).get()
                .extracting(ModelCatalogRepository.EncryptedCredential::ciphertext).isEqualTo("encrypted-platform");
        assertThat(repository.findActiveUserCredential("user-1", "DEEPSEEK")).isPresent();
    }
}
