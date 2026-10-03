package com.wenxian.admin.plan;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** JDBC 实现负责聚合持久化和行锁，不在 SQL 中隐藏发布业务规则。 */
@Repository
public class JdbcServicePlanAdminRepository implements ServicePlanAdminRepository {
    private final JdbcTemplate jdbc;

    public JdbcServicePlanAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PlanVersionRecord> findVersions() {
        return jdbc.query("""
                select id, version_number, status, revision, default_tier_code, change_note,
                       based_on_version_id, created_by, created_at, published_by, published_at, retired_at
                from ai_service_plan_version order by version_number desc
                """, this::mapVersion);
    }

    @Override
    public Optional<PlanVersionRecord> findVersion(String planId) {
        return queryVersion("""
                select id, version_number, status, revision, default_tier_code, change_note,
                       based_on_version_id, created_by, created_at, published_by, published_at, retired_at
                from ai_service_plan_version where id=?
                """, planId);
    }

    @Override
    public Optional<PlanVersionRecord> lockVersion(String planId) {
        return queryVersion("""
                select id, version_number, status, revision, default_tier_code, change_note,
                       based_on_version_id, created_by, created_at, published_by, published_at, retired_at
                from ai_service_plan_version where id=? for update
                """, planId);
    }

    private Optional<PlanVersionRecord> queryVersion(String sql, String planId) {
        return jdbc.query(sql, this::mapVersion, planId).stream().findFirst();
    }

    private PlanVersionRecord mapVersion(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new PlanVersionRecord(row.getString("id"), row.getLong("version_number"),
                row.getString("status"), row.getLong("revision"), row.getString("default_tier_code"),
                row.getString("change_note"), row.getString("based_on_version_id"), row.getString("created_by"),
                instant(row.getTimestamp("created_at")), row.getString("published_by"),
                instant(row.getTimestamp("published_at")), instant(row.getTimestamp("retired_at")));
    }

    @Override
    public List<TierRecord> findTiers(String planId) {
        return jdbc.query("""
                select tier_code, display_name, description, point_cost, enabled,
                       primary_provider_code, primary_model_code, fallback_provider_code,
                       fallback_model_code, fallback_enabled
                from ai_service_plan_tier where plan_version_id=? order by
                    case tier_code when 'LIGHT' then 1 when 'BALANCED' then 2 when 'DEEP' then 3 else 4 end
                """, (row, index) -> new TierRecord(row.getString("tier_code"), row.getString("display_name"),
                row.getString("description"), row.getInt("point_cost"), row.getBoolean("enabled"),
                row.getString("primary_provider_code"), row.getString("primary_model_code"),
                row.getString("fallback_provider_code"), row.getString("fallback_model_code"),
                row.getBoolean("fallback_enabled")), planId);
    }

    @Override
    public List<TaskRouteRecord> findTaskRoutes(String planId) {
        return jdbc.query("""
                select tier_code, task_code, primary_provider_code, primary_model_code,
                       fallback_provider_code, fallback_model_code, temperature, timeout_seconds,
                       maximum_retries, maximum_output_tokens, disable_thinking, fallback_enabled
                from ai_service_plan_task_route where plan_version_id=? order by tier_code, task_code
                """, (row, index) -> new TaskRouteRecord(row.getString("tier_code"), row.getString("task_code"),
                row.getString("primary_provider_code"), row.getString("primary_model_code"),
                row.getString("fallback_provider_code"), row.getString("fallback_model_code"),
                row.getBigDecimal("temperature"), row.getInt("timeout_seconds"), row.getInt("maximum_retries"),
                row.getObject("maximum_output_tokens", Integer.class), row.getBoolean("disable_thinking"),
                row.getBoolean("fallback_enabled")), planId);
    }

    @Override
    public List<ModelSnapshotRecord> findModelSnapshots(String planId) {
        return jdbc.query("""
                select provider_code, model_code, provider_config_version, model_config_version,
                       validation_run_id, captured_at
                from ai_service_plan_model_snapshot where plan_version_id=? order by provider_code, model_code
                """, (row, index) -> new ModelSnapshotRecord(row.getString("provider_code"),
                row.getString("model_code"), row.getLong("provider_config_version"),
                row.getLong("model_config_version"), row.getString("validation_run_id"),
                row.getTimestamp("captured_at").toInstant()), planId);
    }

    @Override
    public List<String> findSupportedTaskCodes() {
        return jdbc.queryForList("select task_code from ai_model_task_route order by task_code", String.class);
    }

    @Override
    public long allocateVersionNumber() {
        Long value = jdbc.queryForObject("""
                select next_value from ai_service_plan_counter where counter_key='SERVICE_PLAN' for update
                """, Long.class);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "方案版本计数器尚未初始化");
        }
        jdbc.update("update ai_service_plan_counter set next_value=? where counter_key='SERVICE_PLAN'", value + 1);
        return value;
    }

    @Override
    public void lockPublicationSequence() {
        jdbc.queryForObject("""
                select next_value from ai_service_plan_counter where counter_key='SERVICE_PLAN' for update
                """, Long.class);
    }

    @Override
    public void insertVersion(PlanVersionRecord version) {
        jdbc.update("""
                insert into ai_service_plan_version(id, version_number, status, revision, default_tier_code,
                    change_note, based_on_version_id, created_by, created_at, published_by, published_at, retired_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, version.id(), version.versionNumber(), version.status(), version.revision(),
                version.defaultTierCode(), version.changeNote(), version.basedOnVersionId(), version.createdBy(),
                timestamp(version.createdAt()), version.publishedBy(), timestamp(version.publishedAt()),
                timestamp(version.retiredAt()));
    }

    @Override
    public void insertTiers(String planId, List<TierRecord> tiers) {
        tiers.forEach(tier -> jdbc.update("""
                insert into ai_service_plan_tier(plan_version_id, tier_code, display_name, description,
                    point_cost, enabled, primary_provider_code, primary_model_code,
                    fallback_provider_code, fallback_model_code, fallback_enabled)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, planId, tier.tierCode(), tier.displayName(), tier.description(), tier.pointCost(), tier.enabled(),
                tier.primaryProviderCode(), tier.primaryModelCode(), tier.fallbackProviderCode(),
                tier.fallbackModelCode(), tier.fallbackEnabled()));
    }

    @Override
    public void insertTaskRoutes(String planId, List<TaskRouteRecord> routes) {
        routes.forEach(route -> jdbc.update("""
                insert into ai_service_plan_task_route(plan_version_id, tier_code, task_code,
                    primary_provider_code, primary_model_code, fallback_provider_code, fallback_model_code,
                    temperature, timeout_seconds, maximum_retries, maximum_output_tokens,
                    disable_thinking, fallback_enabled)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, planId, route.tierCode(), route.taskCode(), route.primaryProviderCode(),
                route.primaryModelCode(), route.fallbackProviderCode(), route.fallbackModelCode(),
                route.temperature(), route.timeoutSeconds(), route.maximumRetries(), route.maximumOutputTokens(),
                route.disableThinking(), route.fallbackEnabled()));
    }

    @Override
    public void replaceModelSnapshots(String planId, List<ModelSnapshotRecord> snapshots) {
        jdbc.update("delete from ai_service_plan_model_snapshot where plan_version_id=?", planId);
        snapshots.forEach(snapshot -> jdbc.update("""
                insert into ai_service_plan_model_snapshot(plan_version_id, provider_code, model_code,
                    provider_config_version, model_config_version, validation_run_id, captured_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, planId, snapshot.providerCode(), snapshot.modelCode(), snapshot.providerConfigVersion(),
                snapshot.modelConfigVersion(), snapshot.validationRunId(), Timestamp.from(snapshot.capturedAt())));
    }

    @Override
    public void replaceDraftAggregate(String planId, long expectedRevision, String defaultTierCode,
                                      String changeNote, List<TierRecord> tiers, List<TaskRouteRecord> routes) {
        int changed = jdbc.update("""
                update ai_service_plan_version set default_tier_code=?, change_note=?, revision=revision+1
                where id=? and status='DRAFT' and revision=?
                """, defaultTierCode, changeNote, planId, expectedRevision);
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "草稿已被其他操作更新，请刷新后重试");
        }
        jdbc.update("delete from ai_service_plan_task_route where plan_version_id=?", planId);
        jdbc.update("delete from ai_service_plan_tier where plan_version_id=?", planId);
        insertTiers(planId, tiers);
        insertTaskRoutes(planId, routes);
    }

    @Override
    public int retireActive(Instant retiredAt) {
        return jdbc.update("""
                update ai_service_plan_version set status='RETIRED', retired_at=? where status='ACTIVE'
                """, Timestamp.from(retiredAt));
    }

    @Override
    public int activateDraft(String planId, long expectedRevision, String publishedBy, Instant publishedAt) {
        return jdbc.update("""
                update ai_service_plan_version
                set status='ACTIVE', published_by=?, published_at=?, retired_at=null
                where id=? and status='DRAFT' and revision=?
                """, publishedBy, Timestamp.from(publishedAt), planId, expectedRevision);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
