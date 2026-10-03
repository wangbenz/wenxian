package com.wenxian.admin.plan;

import com.wenxian.admin.audit.AdminOperationLogRepository;
import com.wenxian.admin.model.JdbcModelCatalogAdminRepository;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServicePlanApplicationServiceTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private ServicePlanApplicationService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("admin-service-plan-" + UUID.randomUUID()).build();
        jdbc = new JdbcTemplate(database);
        createSchema();
        seedCatalog();
        service = new ServicePlanApplicationService(new JdbcServicePlanAdminRepository(jdbc),
                new JdbcModelCatalogAdminRepository(jdbc), new AdminOperationLogRepository(jdbc), true);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void publishesImmutableVersionAndRollsBackByPublishingClone() {
        ServicePlanView draft = service.createDraft(null, "admin-1");
        assertEquals(3, draft.tiers().size());
        assertFalse(draft.publishable());
        assertFalse(draft.gameRuntimeApplied());

        ServicePlanView saved = service.saveDraft(draft.id(), completeCommand(0, "首次配置",
                "model-light", "model-balanced", "model-deep"));
        assertEquals(1, saved.revision());
        assertTrue(saved.publishable(), saved.blockingReasons().toString());

        ServicePlanView active = service.publish(saved.id(), saved.revision(), "admin-1");
        assertEquals("ACTIVE", active.status());
        assertEquals(3, active.modelSnapshots().size());
        assertTrue(active.modelSnapshots().stream().allMatch(snapshot -> snapshot.validationRunId() != null));
        assertTrue(active.gameRuntimeApplied());
        assertThrows(ResponseStatusException.class, () -> service.saveDraft(active.id(),
                completeCommand(active.revision(), "非法原地修改", "model-light", "model-balanced", "model-deep")));

        ServicePlanView rollbackDraft = service.createDraft(active.id(), "admin-2");
        assertEquals(active.id(), rollbackDraft.basedOnVersionId());
        assertTrue(rollbackDraft.modelSnapshots().isEmpty());
        assertTrue(rollbackDraft.publishable(), rollbackDraft.blockingReasons().toString());
        ServicePlanView replacement = service.publish(rollbackDraft.id(), rollbackDraft.revision(), "admin-2");

        assertEquals("ACTIVE", replacement.status());
        assertEquals("RETIRED", service.plan(active.id()).status());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from ai_service_plan_version where status='ACTIVE'", Integer.class));
        assertEquals(5, jdbc.queryForObject("select count(*) from admin_operation_log", Integer.class));
    }

    @Test
    void blocksDuplicatePrimaryModelsAndStaleValidationEvidence() {
        ServicePlanView draft = service.createDraft(null, "admin-1");
        ServicePlanView duplicate = service.saveDraft(draft.id(), completeCommand(0, "重复模型",
                "model-light", "model-light", "model-deep"));
        assertFalse(duplicate.publishable());
        assertTrue(duplicate.blockingReasons().stream().anyMatch(reason -> reason.contains("互不相同")));
        assertThrows(ResponseStatusException.class,
                () -> service.publish(duplicate.id(), duplicate.revision(), "admin-1"));

        ServicePlanView valid = service.saveDraft(duplicate.id(), completeCommand(duplicate.revision(), "修正模型",
                "model-light", "model-balanced", "model-deep"));
        jdbc.update("""
                update ai_provider_model set config_version=2
                where provider_code='TEST' and model_code='model-deep'
                """);
        ServicePlanView stale = service.plan(valid.id());
        assertFalse(stale.publishable());
        assertTrue(stale.blockingReasons().stream().anyMatch(reason -> reason.contains("验证记录已因配置变化失效")));
        assertThrows(ResponseStatusException.class,
                () -> service.publish(stale.id(), stale.revision(), "admin-1"));
    }

    @Test
    void staleRevisionDoesNotPartiallyReplaceTierAggregate() {
        ServicePlanView draft = service.createDraft(null, "admin-1");
        ServicePlanView saved = service.saveDraft(draft.id(), completeCommand(0, "第一版",
                "model-light", "model-balanced", "model-deep"));
        assertEquals(1, saved.revision());

        assertThrows(ResponseStatusException.class, () -> service.saveDraft(draft.id(), completeCommand(0,
                "过期覆盖", "model-deep", "model-balanced", "model-light")));

        ServicePlanView unchanged = service.plan(draft.id());
        assertEquals(1, unchanged.revision());
        assertEquals("model-light", unchanged.tiers().stream()
                .filter(tier -> tier.tierCode().equals("LIGHT")).findFirst().orElseThrow().primaryModelCode());
    }

    private ServicePlanApplicationService.SaveDraftCommand completeCommand(long revision, String note,
                                                                            String light, String balanced,
                                                                            String deep) {
        return new ServicePlanApplicationService.SaveDraftCommand(revision, "BALANCED", note, List.of(
                tier("LIGHT", "轻量", 1, light),
                tier("BALANCED", "均衡", 2, balanced),
                tier("DEEP", "深度", 4, deep)
        ), List.of());
    }

    private ServicePlanApplicationService.TierCommand tier(String code, String name, int pointCost, String model) {
        return new ServicePlanApplicationService.TierCommand(code, name, name + "说明", pointCost, true,
                "TEST", model, null, null, false);
    }

    private void createSchema() {
        jdbc.execute("create table admin_operation_log(id varchar(80) primary key, operated_at timestamp not null, operation varchar(80) not null, detail varchar(1000) not null, subject_user_id varchar(64))");
        jdbc.execute("create table ai_service_plan_counter(counter_key varchar(32) primary key, next_value bigint not null)");
        jdbc.update("insert into ai_service_plan_counter values ('SERVICE_PLAN', 1)");
        jdbc.execute("""
                create table ai_service_plan_version(
                    id varchar(64) primary key, version_number bigint not null unique, status varchar(16) not null,
                    revision bigint not null, default_tier_code varchar(16) not null, change_note varchar(500) not null,
                    based_on_version_id varchar(64), created_by varchar(64) not null, created_at timestamp not null,
                    published_by varchar(64), published_at timestamp, retired_at timestamp)
                """);
        jdbc.execute("""
                create table ai_service_plan_tier(
                    plan_version_id varchar(64) not null, tier_code varchar(16) not null,
                    display_name varchar(40) not null, description varchar(240) not null, point_cost integer not null,
                    enabled boolean not null, primary_provider_code varchar(32), primary_model_code varchar(80),
                    fallback_provider_code varchar(32), fallback_model_code varchar(80), fallback_enabled boolean not null,
                    primary key(plan_version_id, tier_code))
                """);
        jdbc.execute("""
                create table ai_service_plan_task_route(
                    plan_version_id varchar(64) not null, tier_code varchar(16) not null, task_code varchar(48) not null,
                    primary_provider_code varchar(32) not null, primary_model_code varchar(80) not null,
                    fallback_provider_code varchar(32), fallback_model_code varchar(80), temperature decimal(4,3) not null,
                    timeout_seconds integer not null, maximum_retries integer not null, maximum_output_tokens integer,
                    disable_thinking boolean not null, fallback_enabled boolean not null,
                    primary key(plan_version_id, tier_code, task_code))
                """);
        jdbc.execute("""
                create table ai_service_plan_model_snapshot(
                    plan_version_id varchar(64) not null, provider_code varchar(32) not null,
                    model_code varchar(80) not null, provider_config_version bigint not null,
                    model_config_version bigint not null, validation_run_id varchar(64) not null,
                    captured_at timestamp not null, primary key(plan_version_id, provider_code, model_code))
                """);
        jdbc.execute("""
                create table ai_provider_config(
                    provider_code varchar(32) primary key, display_name varchar(80) not null,
                    base_url varchar(255) not null, adapter_type varchar(32) not null,
                    platform_secret_ciphertext clob, enabled boolean not null, user_key_enabled boolean not null,
                    config_version bigint not null, updated_at timestamp not null)
                """);
        jdbc.execute("""
                create table ai_provider_model(
                    provider_code varchar(32) not null, model_code varchar(80) not null,
                    display_name varchar(80) not null, api_model_name varchar(120) not null,
                    capability_codes varchar(255) not null, context_window integer not null,
                    maximum_output_tokens integer not null, concurrency_limit integer not null,
                    platform_enabled boolean not null, user_selectable boolean not null,
                    config_version bigint not null, updated_at timestamp not null,
                    primary key(provider_code, model_code))
                """);
        jdbc.execute("""
                create table ai_model_validation_run(
                    id varchar(64) primary key, provider_code varchar(32) not null, model_code varchar(80) not null,
                    provider_config_version bigint not null, model_config_version bigint not null,
                    status varchar(24) not null, capability_codes varchar(255) not null, latency_ms integer,
                    error_code varchar(80), error_summary varchar(500), checked_by varchar(64) not null,
                    checked_at timestamp not null)
                """);
        jdbc.execute("create table ai_model_task_route(task_code varchar(48) primary key)");
    }

    private void seedCatalog() {
        Instant now = Instant.now();
        jdbc.update("""
                insert into ai_provider_config values ('TEST','测试供应商','https://example.com',
                    'OPENAI_COMPATIBLE','encrypted-secret',true,true,1,?)
                """, Timestamp.from(now));
        for (String model : List.of("model-light", "model-balanced", "model-deep")) {
            jdbc.update("""
                    insert into ai_provider_model values ('TEST',?,?,?,'CHAT,JSON_MODE',128000,8000,4,true,true,1,?)
                    """, model, model, model, Timestamp.from(now));
            jdbc.update("""
                    insert into ai_model_validation_run values (?,?,?,?,?,'PASSED','CHAT,JSON_MODE',800,null,null,?,?)
                    """, "validation-" + model, "TEST", model, 1L, 1L, "admin-test", Timestamp.from(now));
        }
        jdbc.update("insert into ai_model_task_route values ('TURN_DIRECTOR')");
        jdbc.update("insert into ai_model_task_route values ('NARRATIVE_WRITER')");
    }
}
