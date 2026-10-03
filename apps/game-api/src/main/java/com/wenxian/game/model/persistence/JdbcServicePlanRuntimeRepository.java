package com.wenxian.game.model.persistence;

import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 使用 JDBC 读取不可变生效方案，并持久化账号跨版本复用的稳定档位编码。 */
@Repository
public class JdbcServicePlanRuntimeRepository implements ServicePlanRuntimeRepository {
    private final JdbcTemplate jdbc;

    public JdbcServicePlanRuntimeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ActivePlan> findActivePlan() {
        return jdbc.query("""
                select id, default_tier_code from ai_service_plan_version
                where status='ACTIVE' order by published_at desc limit 1
                """, (row, index) -> new ActivePlan(row.getString(1), row.getString(2)))
                .stream().findFirst();
    }

    @Override
    public List<Tier> findEnabledTiers(String planVersionId) {
        return jdbc.query("""
                select tier_code, display_name, description, point_cost
                from ai_service_plan_tier where plan_version_id=? and enabled=true
                order by case tier_code when 'LIGHT' then 1 when 'BALANCED' then 2 when 'DEEP' then 3 else 4 end
                """, (row, index) -> new Tier(row.getString(1), row.getString(2), row.getString(3), row.getInt(4)),
                required(planVersionId));
    }

    @Override
    public Optional<String> findUserTierPreference(String userId) {
        return jdbc.query("select tier_code from user_service_tier_preference where user_id=?",
                (row, index) -> row.getString(1), required(userId)).stream().findFirst();
    }

    @Override
    public void saveUserTierPreference(String userId, String tierCode) {
        String normalizedUser = required(userId);
        String normalizedTier = normalizeTier(tierCode);
        Timestamp now = Timestamp.from(Instant.now());
        int changed = jdbc.update("""
                update user_service_tier_preference set tier_code=?, updated_at=? where user_id=?
                """, normalizedTier, now, normalizedUser);
        if (changed == 0) {
            jdbc.update("""
                    insert into user_service_tier_preference(user_id, tier_code, updated_at) values (?, ?, ?)
                    """, normalizedUser, normalizedTier, now);
        }
    }

    @Override
    public Optional<TaskRoute> findTaskRoute(String planVersionId, String tierCode, ModelTask task) {
        String planId = required(planVersionId);
        String tier = normalizeTier(tierCode);
        List<TaskRoute> override = jdbc.query("""
                select task_code, primary_provider_code, primary_model_code,
                       fallback_provider_code, fallback_model_code, temperature, timeout_seconds,
                       maximum_retries, maximum_output_tokens, disable_thinking, fallback_enabled
                from ai_service_plan_task_route
                where plan_version_id=? and tier_code=? and task_code=?
                """, (row, index) -> taskRoute(row), planId, tier, task.name());
        if (!override.isEmpty()) return Optional.of(override.getFirst());
        return jdbc.query("""
                select primary_provider_code, primary_model_code, fallback_provider_code,
                       fallback_model_code, fallback_enabled
                from ai_service_plan_tier
                where plan_version_id=? and tier_code=? and enabled=true
                """, (row, index) -> new TaskRoute(task, row.getString(1), row.getString(2),
                row.getString(3), row.getString(4), ModelInvocationOptions.defaults(), row.getBoolean(5)),
                planId, tier).stream().findFirst();
    }

    private TaskRoute taskRoute(ResultSet row) throws SQLException {
        Integer maximumOutput = row.getObject(9, Integer.class);
        ModelInvocationOptions options = new ModelInvocationOptions(row.getDouble(6),
                Duration.ofSeconds(row.getInt(7)), row.getInt(8), maximumOutput, row.getBoolean(10));
        return new TaskRoute(ModelTask.valueOf(row.getString(1)), row.getString(2), row.getString(3),
                row.getString(4), row.getString(5), options, row.getBoolean(11));
    }

    private String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("运行时方案查询缺少稳定标识");
        return value.strip();
    }

    private String normalizeTier(String value) {
        return required(value).toUpperCase(Locale.ROOT);
    }
}
