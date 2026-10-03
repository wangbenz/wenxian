package com.wenxian.admin.model;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC 实现只维护模型供应商、模型目录和当前任务路由，不承担发布或停用裁决。 */
@Repository
public class JdbcModelCatalogAdminRepository implements ModelCatalogAdminRepository {
    private final JdbcTemplate jdbc;

    public JdbcModelCatalogAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int countPlatformTasks() {
        Integer count = jdbc.queryForObject("select count(*) from ai_model_task_route", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<ProviderRecord> findProviders() {
        return jdbc.query("""
                select provider_code, display_name, base_url, adapter_type, platform_secret_ciphertext,
                       enabled, user_key_enabled, config_version, updated_at
                from ai_provider_config order by display_name, provider_code
                """, (row, index) -> new ProviderRecord(row.getString("provider_code"),
                row.getString("display_name"), row.getString("base_url"), row.getString("adapter_type"),
                row.getString("platform_secret_ciphertext"), row.getBoolean("enabled"),
                row.getBoolean("user_key_enabled"), row.getLong("config_version"),
                row.getTimestamp("updated_at").toInstant()));
    }

    @Override
    public List<ModelRecord> findModels() {
        return jdbc.query("""
                select provider_code, model_code, display_name, api_model_name, capability_codes,
                       context_window, maximum_output_tokens, concurrency_limit, platform_enabled,
                       user_selectable, config_version, updated_at
                from ai_provider_model order by display_name, model_code
                """, (row, index) -> new ModelRecord(row.getString("provider_code"),
                row.getString("model_code"), row.getString("display_name"), row.getString("api_model_name"),
                row.getString("capability_codes"), row.getInt("context_window"),
                row.getInt("maximum_output_tokens"), row.getInt("concurrency_limit"),
                row.getBoolean("platform_enabled"), row.getBoolean("user_selectable"),
                row.getLong("config_version"),
                row.getTimestamp("updated_at").toInstant()));
    }

    @Override
    public List<RouteReference> findEffectiveRouteReferences() {
        return jdbc.query("""
                select task_code, primary_provider_code provider_code, primary_model_code model_code, true primary_route
                from ai_model_task_route
                union all
                select task_code, fallback_provider_code provider_code, fallback_model_code model_code, false primary_route
                from ai_model_task_route
                where fallback_enabled=true and fallback_provider_code is not null and fallback_model_code is not null
                order by task_code, provider_code, model_code
                """, (row, index) -> new RouteReference(row.getString("task_code"),
                row.getString("provider_code"), row.getString("model_code"), row.getBoolean("primary_route")));
    }

    @Override
    public List<ServicePlanReference> findServicePlanReferences() {
        return jdbc.query("""
                select reference.plan_id, reference.version_number, reference.status, reference.tier_code,
                       reference.task_code, reference.provider_code, reference.model_code, reference.usage_type
                from (
                    select version.id plan_id, version.version_number, version.status, tier.tier_code,
                           cast(null as char(48)) task_code, tier.primary_provider_code provider_code,
                           tier.primary_model_code model_code, 'TIER_PRIMARY' usage_type
                    from ai_service_plan_version version join ai_service_plan_tier tier
                      on tier.plan_version_id=version.id
                    where version.status in ('DRAFT','ACTIVE') and tier.primary_provider_code is not null
                    union all
                    select version.id, version.version_number, version.status, tier.tier_code,
                           cast(null as char(48)), tier.fallback_provider_code, tier.fallback_model_code,
                           'TIER_FALLBACK'
                    from ai_service_plan_version version join ai_service_plan_tier tier
                      on tier.plan_version_id=version.id
                    where version.status in ('DRAFT','ACTIVE') and tier.fallback_provider_code is not null
                    union all
                    select version.id, version.version_number, version.status, route.tier_code, route.task_code,
                           route.primary_provider_code, route.primary_model_code, 'TASK_PRIMARY'
                    from ai_service_plan_version version join ai_service_plan_task_route route
                      on route.plan_version_id=version.id
                    where version.status in ('DRAFT','ACTIVE')
                    union all
                    select version.id, version.version_number, version.status, route.tier_code, route.task_code,
                           route.fallback_provider_code, route.fallback_model_code, 'TASK_FALLBACK'
                    from ai_service_plan_version version join ai_service_plan_task_route route
                      on route.plan_version_id=version.id
                    where version.status in ('DRAFT','ACTIVE') and route.fallback_provider_code is not null
                ) reference
                order by reference.version_number desc, reference.tier_code, reference.task_code, reference.usage_type
                """, (row, index) -> new ServicePlanReference(row.getString("plan_id"),
                row.getLong("version_number"), row.getString("status"), row.getString("tier_code"),
                row.getString("task_code"), row.getString("provider_code"), row.getString("model_code"),
                row.getString("usage_type")));
    }

    @Override
    public List<ValidationRecord> findLatestValidations() {
        return jdbc.query("""
                select id, provider_code, model_code, provider_config_version, model_config_version,
                       status, capability_codes, latency_ms, error_code, error_summary, checked_at
                from (
                    select validation.*, row_number() over (
                        partition by provider_code, model_code order by checked_at desc, id desc
                    ) validation_rank
                    from ai_model_validation_run validation
                ) latest
                where validation_rank=1
                """, (row, index) -> new ValidationRecord(row.getString("id"), row.getString("provider_code"),
                row.getString("model_code"), row.getLong("provider_config_version"),
                row.getLong("model_config_version"), row.getString("status"),
                row.getString("capability_codes"), row.getObject("latency_ms", Integer.class),
                row.getString("error_code"), row.getString("error_summary"),
                row.getTimestamp("checked_at").toInstant()));
    }

    @Override
    public Optional<ProviderCredentialRecord> findProviderCredential(String providerCode) {
        return jdbc.query("""
                select platform_secret_ciphertext, enabled from ai_provider_config where provider_code=?
                """, (row, index) -> new ProviderCredentialRecord(
                row.getString("platform_secret_ciphertext"), row.getBoolean("enabled")), providerCode)
                .stream().findFirst();
    }

    @Override
    public boolean providerExists(String providerCode) {
        Integer count = jdbc.queryForObject(
                "select count(*) from ai_provider_config where provider_code=?", Integer.class, providerCode);
        return count != null && count > 0;
    }

    @Override
    public int updateProvider(String providerCode, String displayName, String baseUrl, String ciphertext,
                              boolean replaceCiphertext, boolean enabled, boolean userKeyEnabled,
                              Instant updatedAt) {
        if (!replaceCiphertext) {
            return jdbc.update("""
                    update ai_provider_config
                    set config_version=config_version + case when base_url<>? then 1 else 0 end,
                        display_name=?, base_url=?, enabled=?, user_key_enabled=?, updated_at=?
                    where provider_code=?
                    """, baseUrl, displayName, baseUrl, enabled, userKeyEnabled,
                    Timestamp.from(updatedAt), providerCode);
        }
        return jdbc.update("""
                update ai_provider_config set config_version=config_version + 1,
                    display_name=?, base_url=?, platform_secret_ciphertext=?,
                    enabled=?, user_key_enabled=?, updated_at=? where provider_code=?
                """, displayName, baseUrl, ciphertext, enabled, userKeyEnabled,
                Timestamp.from(updatedAt), providerCode);
    }

    @Override
    public void insertProvider(String providerCode, String displayName, String baseUrl, String ciphertext,
                               boolean enabled, boolean userKeyEnabled, Instant updatedAt) {
        jdbc.update("""
                insert into ai_provider_config(provider_code, display_name, base_url, adapter_type,
                    platform_secret_ciphertext, enabled, user_key_enabled, updated_at)
                values (?, ?, ?, 'OPENAI_COMPATIBLE', ?, ?, ?, ?)
                """, providerCode, displayName, baseUrl, ciphertext, enabled, userKeyEnabled,
                Timestamp.from(updatedAt));
    }

    @Override
    public int updateModel(String providerCode, String modelCode, String displayName, String apiModelName,
                           String capabilityCodes, int contextWindow, int maximumOutputTokens,
                           int concurrencyLimit, boolean platformEnabled, boolean userSelectable,
                           Instant updatedAt) {
        return jdbc.update("""
                update ai_provider_model
                set config_version=config_version + case
                        when api_model_name<>? or capability_codes<>? or context_window<>?
                          or maximum_output_tokens<>? or concurrency_limit<>? then 1 else 0 end,
                    display_name=?, api_model_name=?, capability_codes=?, context_window=?,
                    maximum_output_tokens=?, concurrency_limit=?, platform_enabled=?,
                    user_selectable=?, updated_at=? where provider_code=? and model_code=?
                """, apiModelName, capabilityCodes, contextWindow, maximumOutputTokens, concurrencyLimit,
                displayName, apiModelName, capabilityCodes, contextWindow, maximumOutputTokens,
                concurrencyLimit, platformEnabled, userSelectable,
                Timestamp.from(updatedAt), providerCode, modelCode);
    }

    @Override
    public void insertModel(String providerCode, String modelCode, String displayName, String apiModelName,
                            String capabilityCodes, int contextWindow, int maximumOutputTokens,
                            int concurrencyLimit, boolean platformEnabled, boolean userSelectable,
                            Instant updatedAt) {
        jdbc.update("""
                insert into ai_provider_model(provider_code, model_code, display_name, api_model_name,
                    capability_codes, context_window, maximum_output_tokens, concurrency_limit,
                    platform_enabled, user_selectable, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, providerCode, modelCode, displayName, apiModelName, capabilityCodes,
                contextWindow, maximumOutputTokens, concurrencyLimit, platformEnabled, userSelectable,
                Timestamp.from(updatedAt));
    }

    @Override
    public int routeAllPlatformTasks(String providerCode, String modelCode, Instant updatedAt) {
        return jdbc.update("""
                update ai_model_task_route set primary_provider_code=?, primary_model_code=?, updated_at=?
                """, providerCode, modelCode, Timestamp.from(updatedAt));
    }
}
