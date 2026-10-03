package com.wenxian.game.account;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 账号物理删除使用的数据归属清单。
 *
 * <p>清单同时覆盖当前 game-api schema 和删除服务仍支持的历史表。新增表必须先明确删除、匿名化、
 * 匿名保留或全局引用策略；包含主体字段却未登记的生产表会阻断账号删除，避免功能升级后静默遗漏玩家数据。</p>
 */
final class AccountDataOwnershipCatalog {
    enum Disposition {
        DELETE,
        DELETE_OR_ANONYMIZE,
        ANONYMIZE,
        RETAIN_ANONYMOUS,
        RETAIN_AGGREGATE,
        GLOBAL_REFERENCE
    }

    private static final Set<String> SUBJECT_COLUMNS = Set.of(
            "user_id", "subject_user_id", "save_id", "source_save_id", "target_save_id", "email_hash");

    private static final Map<String, Disposition> POLICIES = Map.ofEntries(
            Map.entry("world_package", Disposition.ANONYMIZE),
            Map.entry("world_package_revision", Disposition.GLOBAL_REFERENCE),
            Map.entry("world_package_asset", Disposition.GLOBAL_REFERENCE),
            Map.entry("world_package_publication_request", Disposition.RETAIN_ANONYMOUS),
            Map.entry("world_package_installation", Disposition.DELETE),
            Map.entry("world_package_creation_scope", Disposition.DELETE),
            Map.entry("world_package_availability", Disposition.DELETE),
            Map.entry("world_package_creation_default", Disposition.DELETE),
            Map.entry("world_package_audit", Disposition.RETAIN_ANONYMOUS),
            Map.entry("account_deletion_audit", Disposition.RETAIN_ANONYMOUS),
            Map.entry("account_deletion_job", Disposition.ANONYMIZE),
            Map.entry("account_deletion_notice", Disposition.DELETE),
            Map.entry("account_retention_state", Disposition.DELETE),
            Map.entry("account_lifecycle_event", Disposition.RETAIN_ANONYMOUS),
            Map.entry("account_retention_run", Disposition.RETAIN_AGGREGATE),
            Map.entry("agent_execution", Disposition.DELETE),
            Map.entry("ai_model_config", Disposition.GLOBAL_REFERENCE),
            Map.entry("ai_model_task_route", Disposition.GLOBAL_REFERENCE),
            Map.entry("ai_model_validation_run", Disposition.GLOBAL_REFERENCE),
            Map.entry("ai_provider_config", Disposition.GLOBAL_REFERENCE),
            Map.entry("ai_provider_model", Disposition.GLOBAL_REFERENCE),
            Map.entry("ai_service_plan_counter", Disposition.GLOBAL_REFERENCE),
            Map.entry("ai_service_plan_model_snapshot", Disposition.GLOBAL_REFERENCE),
            Map.entry("ai_service_plan_task_route", Disposition.GLOBAL_REFERENCE),
            Map.entry("ai_service_plan_tier", Disposition.GLOBAL_REFERENCE),
            Map.entry("ai_service_plan_version", Disposition.GLOBAL_REFERENCE),
            Map.entry("ai_usage_log", Disposition.DELETE_OR_ANONYMIZE),
            Map.entry("analytics_daily_metric", Disposition.RETAIN_AGGREGATE),
            Map.entry("analytics_event", Disposition.DELETE),
            Map.entry("analytics_retention_cohort", Disposition.RETAIN_AGGREGATE),
            Map.entry("analytics_user_day", Disposition.DELETE),
            Map.entry("app_user", Disposition.DELETE),
            Map.entry("auth_login_failure", Disposition.DELETE),
            Map.entry("auth_email_verification", Disposition.DELETE),
            Map.entry("auth_registration", Disposition.DELETE),
            Map.entry("game_access_whitelist", Disposition.DELETE),
            Map.entry("gameplay_config", Disposition.GLOBAL_REFERENCE),
            Map.entry("invite_code", Disposition.GLOBAL_REFERENCE),
            Map.entry("operation_error", Disposition.DELETE),
            Map.entry("release_note", Disposition.GLOBAL_REFERENCE),
            Map.entry("story_conversation", Disposition.DELETE),
            Map.entry("story_engine_catalog", Disposition.GLOBAL_REFERENCE),
            Map.entry("story_entity", Disposition.DELETE),
            Map.entry("story_entity_alias", Disposition.DELETE),
            Map.entry("story_memory", Disposition.DELETE),
            Map.entry("story_save", Disposition.DELETE),
            Map.entry("story_save_migration", Disposition.DELETE),
            Map.entry("story_state_correction", Disposition.DELETE),
            Map.entry("story_turn", Disposition.DELETE),
            Map.entry("story_turn_diagnostic", Disposition.DELETE),
            Map.entry("turn_credit_grant", Disposition.DELETE),
            Map.entry("turn_credit_ledger", Disposition.DELETE),
            Map.entry("turn_credit_reservation", Disposition.DELETE),
            Map.entry("turn_credit_reservation_allocation", Disposition.DELETE),
            Map.entry("user_ai_provider_credential", Disposition.DELETE),
            Map.entry("user_daily_quota", Disposition.DELETE),
            Map.entry("user_model_preference", Disposition.DELETE),
            Map.entry("user_release_receipt", Disposition.DELETE),
            Map.entry("user_service_tier_preference", Disposition.DELETE),
            Map.entry("user_session", Disposition.DELETE),

            // 当前删除服务仍支持的历史/共享表；它们不进入新运行时。
            Map.entry("admin_operation_log", Disposition.DELETE),
            Map.entry("agent_conversation", Disposition.DELETE),
            Map.entry("agent_message", Disposition.DELETE),
            Map.entry("game_analytics_event", Disposition.DELETE),
            Map.entry("game_life_archive", Disposition.DELETE),
            Map.entry("game_life_turn_archive", Disposition.DELETE),
            Map.entry("game_save", Disposition.DELETE),
            Map.entry("game_turn", Disposition.DELETE),
            Map.entry("game_turn_request", Disposition.DELETE),
            Map.entry("user_ai_credential", Disposition.DELETE),
            Map.entry("v014_conversation", Disposition.DELETE),
            Map.entry("v014_game_save", Disposition.DELETE),
            Map.entry("v014_game_turn", Disposition.DELETE),
            Map.entry("v014_state_correction", Disposition.DELETE),
            Map.entry("v014_user_model_preference", Disposition.DELETE)
    );

    private AccountDataOwnershipCatalog() { }

    static Map<String, Disposition> policies() {
        return POLICIES;
    }

    static void assertClassifiedSubjectTables(Map<String, Set<String>> schemaColumns) {
        Set<String> missing = new LinkedHashSet<>();
        Set<String> unsafeGlobal = new LinkedHashSet<>();
        schemaColumns.forEach((table, columns) -> {
            boolean subjectLinked = columns.stream().anyMatch(SUBJECT_COLUMNS::contains);
            if (!subjectLinked) return;
            Disposition disposition = POLICIES.get(table);
            if (disposition == null) {
                missing.add(table);
            } else if (disposition == Disposition.GLOBAL_REFERENCE
                    || disposition == Disposition.RETAIN_AGGREGATE) {
                unsafeGlobal.add(table);
            }
        });
        if (!missing.isEmpty()) {
            throw new IllegalStateException("账号数据归属清单缺少表：" + String.join(",", missing));
        }
        if (!unsafeGlobal.isEmpty()) {
            throw new IllegalStateException("含玩家主体字段的表不能声明为全局保留："
                    + String.join(",", unsafeGlobal));
        }
    }
}
