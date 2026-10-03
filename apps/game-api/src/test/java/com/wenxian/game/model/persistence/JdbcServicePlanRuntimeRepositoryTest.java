package com.wenxian.game.model.persistence;

import com.wenxian.game.model.domain.ModelTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcServicePlanRuntimeRepositoryTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private JdbcServicePlanRuntimeRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        jdbc = new JdbcTemplate(database);
        repository = new JdbcServicePlanRuntimeRepository(jdbc);
        new ModelCatalogInitializer(jdbc, "https://api.deepseek.com",
                "sk-platform-test-secret-123456789", "deepseek-v4-flash", "master-key", false).initialize();
        Timestamp now = Timestamp.from(Instant.parse("2026-08-27T00:00:00Z"));
        jdbc.update("""
                insert into app_user(id,email,status,registered_at,last_login_at)
                values ('tier-user','tier@test.invalid','ACTIVE',?,?)
                """, now, now);
        jdbc.update("""
                insert into ai_service_plan_version(id,version_number,status,revision,default_tier_code,
                    change_note,created_by,created_at,published_by,published_at)
                values ('plan-active',12,'ACTIVE',2,'BALANCED','运行时','admin',?,'admin',?)
                """, now, now);
        jdbc.update("""
                insert into ai_service_plan_tier(plan_version_id,tier_code,display_name,description,point_cost,
                    enabled,primary_provider_code,primary_model_code,fallback_enabled)
                values ('plan-active','LIGHT','轻量','快速推进',1,true,null,null,false),
                       ('plan-active','BALANCED','均衡','默认体验',2,true,null,null,false),
                       ('plan-active','DEEP','深度','细致推演',4,true,null,null,false)
                """);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void readsActiveTiersAndPersistsTheCrossVersionPreference() {
        assertThat(repository.findActivePlan()).get()
                .extracting(ServicePlanRuntimeRepository.ActivePlan::id).isEqualTo("plan-active");
        assertThat(repository.findEnabledTiers("plan-active"))
                .extracting(ServicePlanRuntimeRepository.Tier::tierCode)
                .containsExactly("LIGHT", "BALANCED", "DEEP");

        repository.saveUserTierPreference("tier-user", "deep");

        assertThat(repository.findUserTierPreference("tier-user")).contains("DEEP");
    }

    @Test
    void taskOverrideKeepsItsOwnFrozenInvocationOptions() {
        jdbc.update("""
                insert into ai_service_plan_task_route(plan_version_id,tier_code,task_code,
                    primary_provider_code,primary_model_code,temperature,timeout_seconds,maximum_retries,
                    maximum_output_tokens,disable_thinking,fallback_enabled)
                values ('plan-active','DEEP','NARRATIVE_WRITER','DEEPSEEK','deepseek-v4-pro',
                    0.55,120,1,5000,true,false)
                """);

        var route = repository.findTaskRoute("plan-active", "DEEP", ModelTask.NARRATIVE_WRITER).orElseThrow();

        assertThat(route.primaryModelCode()).isEqualTo("deepseek-v4-pro");
        assertThat(route.options().temperature()).isEqualTo(0.55);
        assertThat(route.options().maximumRetries()).isEqualTo(1);
        assertThat(route.options().maximumOutputTokens()).isEqualTo(5000);
    }
}
