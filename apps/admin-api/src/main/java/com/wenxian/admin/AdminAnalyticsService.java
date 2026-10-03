package com.wenxian.admin;

import com.wenxian.admin.analytics.AnalyticsDateRange;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理台统计查询服务。
 *
 * <p>所有趋势只读取独立统计表，不感知 V0.13、V0.14 或未来业务表名。</p>
 */
@Service
public class AdminAnalyticsService {

    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;

    public AdminAnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> overview(int requestedDays) {
        return overview(AnalyticsDateRange.trailing(requestedDays, LocalDate.now(REPORTING_ZONE)));
    }

    public Map<String, Object> overview(AnalyticsDateRange range) {
        LocalDate today = LocalDate.now(REPORTING_ZONE);
        List<Map<String, Object>> trend = dailySeries(range,
                List.of("REGISTERED_USERS", "ACTIVE_USERS", "SUCCESSFUL_TURNS", "FINAL_ERRORS"),
                "ALL", List.of("ALL"));
        Map<String, Object> current = trend.isEmpty() ? Map.of() : trend.getLast();
        List<String> alerts = new ArrayList<>();
        long requests = value(today, "AI_REQUESTS", "CREDENTIAL_SOURCE", "PLATFORM");
        long failures = value(today, "AI_FAILURES", "CREDENTIAL_SOURCE", "PLATFORM");
        long errors = number(current.get("FINAL_ERRORS"));
        if (requests >= 10 && failures * 10 >= requests) alerts.add("今日平台推演失败率超过 10%，建议检查模型服务。");
        if (errors > 0) alerts.add("今日有 " + errors + " 次最终操作失败，可前往问题中心查看。");
        return Map.of(
                "today", current,
                "totalUsers", scalar("select count(*) from app_user where status='ACTIVE'"),
                "totalSuccessfulTurns", metricTotal("SUCCESSFUL_TURNS"),
                "trend", trend,
                "alerts", alerts,
                "calculatedAt", latestCalculation(),
                "range", rangeMetadata(range)
        );
    }

    public Map<String, Object> growth(int requestedDays) {
        return growth(AnalyticsDateRange.trailing(requestedDays, LocalDate.now(REPORTING_ZONE)));
    }

    public Map<String, Object> growth(AnalyticsDateRange range) {
        List<Map<String, Object>> retention = jdbc.query("""
                select cohort_date, retention_day, registered_users, activated_users, retained_users,
                       case when registered_users=0 then 0 else retained_users*100.0/registered_users end retention_rate,
                       data_origin
                from analytics_retention_cohort
                where cohort_date>=? and cohort_date<=?
                order by cohort_date, retention_day
                """, (row, index) -> Map.<String, Object>of(
                "cohortDate", row.getDate("cohort_date").toLocalDate().toString(),
                "day", row.getInt("retention_day"),
                "registered", row.getLong("registered_users"),
                "activated", row.getLong("activated_users"),
                "retained", row.getLong("retained_users"),
                "rate", row.getBigDecimal("retention_rate"),
                "origin", row.getString("data_origin")
        ), Date.valueOf(range.from()), Date.valueOf(range.to()));
        Timestamp from = Timestamp.from(range.from().atStartOfDay(REPORTING_ZONE).toInstant());
        Timestamp to = Timestamp.from(range.to().plusDays(1).atStartOfDay(REPORTING_ZONE).toInstant());
        Map<String, Object> funnel = jdbc.queryForObject("""
                select count(*) registered,
                       sum(case when exists (
                           select 1 from analytics_user_day d
                           where d.user_id=u.id and d.created_save_count>0
                       ) then 1 else 0 end) created_save,
                       sum(case when exists (
                           select 1 from analytics_user_day d
                           where d.user_id=u.id and d.successful_turn_count>0
                       ) then 1 else 0 end) first_turn
                from app_user u
                where u.status='ACTIVE' and u.registered_at>=? and u.registered_at<?
                """, (row, index) -> Map.<String, Object>of(
                "registered", row.getLong("registered"),
                "createdSave", row.getLong("created_save"),
                "firstTurn", row.getLong("first_turn")
        ), from, to);
        return Map.of(
                "daily", dailySeries(range,
                        List.of("REGISTERED_USERS", "ACTIVE_USERS", "CREATED_SAVES", "SUCCESSFUL_TURNS"),
                        "ALL", List.of("ALL")),
                "retention", retention,
                "funnel", funnel == null ? Map.of("registered", 0, "createdSave", 0, "firstTurn", 0) : funnel,
                "definition", "活跃指当日创建命卷、完成回合、修正或整理实体，不以登录代替。",
                "range", rangeMetadata(range)
        );
    }

    public Map<String, Object> gameplay(int requestedDays) {
        return gameplay(AnalyticsDateRange.trailing(requestedDays, LocalDate.now(REPORTING_ZONE)));
    }

    public Map<String, Object> gameplay(AnalyticsDateRange range) {
        return Map.of(
                "daily", dailySeries(range, List.of(
                        "ACTIVE_USERS", "CREATED_SAVES", "SUCCESSFUL_TURNS",
                        "CORRECTIONS", "ENTITY_REMOVALS", "ARCHIVED_SAVES"), "ALL", List.of("ALL")),
                "totals", Map.of(
                        "turns", metricTotal(range, "SUCCESSFUL_TURNS"),
                        "createdSaves", metricTotal(range, "CREATED_SAVES"),
                        "corrections", metricTotal(range, "CORRECTIONS"),
                        "entityRemovals", metricTotal(range, "ENTITY_REMOVALS"),
                        "archivedSaves", metricTotal(range, "ARCHIVED_SAVES")
                ),
                "range", rangeMetadata(range)
        );
    }

    public Map<String, Object> aiUsage(int requestedDays) {
        return aiUsage(AnalyticsDateRange.trailing(requestedDays, LocalDate.now(REPORTING_ZONE)));
    }

    public Map<String, Object> aiUsage(AnalyticsDateRange range) {
        List<Map<String, Object>> platform = dailySeries(range, List.of(
                "AI_REQUESTS", "AI_FAILURES", "PROMPT_TOKENS", "COMPLETION_TOKENS",
                "CACHE_HIT_TOKENS", "CACHE_MISS_TOKENS", "AVERAGE_LATENCY_MS"),
                "CREDENTIAL_SOURCE", List.of("PLATFORM", "PUBLIC"));
        List<Map<String, Object>> player = dailySeries(range, List.of(
                "AI_REQUESTS", "AI_FAILURES", "PROMPT_TOKENS", "COMPLETION_TOKENS",
                "CACHE_HIT_TOKENS", "CACHE_MISS_TOKENS", "AVERAGE_LATENCY_MS"),
                "CREDENTIAL_SOURCE", List.of("USER", "PLAYER"));
        Timestamp from = Timestamp.from(range.from().atStartOfDay(REPORTING_ZONE).toInstant());
        Timestamp to = Timestamp.from(range.to().plusDays(1).atStartOfDay(REPORTING_ZONE).toInstant());
        List<Map<String, Object>> models = jdbc.query("""
                select provider, substring_index(model, '@', 1) model,
                       case
                           when credential_source in ('PUBLIC','PLATFORM') then 'PLATFORM'
                           when credential_source in ('USER','PLAYER') then 'USER'
                           else credential_source
                       end credential_source,
                       count(*) requests,
                       sum(prompt_tokens) prompt_tokens, sum(completion_tokens) completion_tokens,
                       sum(cache_hit_tokens) cache_hit_tokens, sum(cache_miss_tokens) cache_miss_tokens,
                       sum(case when status='FAILED' then 1 else 0 end) failures,
                       avg(nullif(latency_ms,0)) average_latency
                from ai_usage_log where created_at>=? and created_at<?
                group by provider, substring_index(model, '@', 1),
                         case
                             when credential_source in ('PUBLIC','PLATFORM') then 'PLATFORM'
                             when credential_source in ('USER','PLAYER') then 'USER'
                             else credential_source
                         end
                order by requests desc
                """, (row, index) -> Map.<String, Object>ofEntries(
                Map.entry("provider", row.getString("provider")),
                Map.entry("model", row.getString("model")),
                Map.entry("source", row.getString("credential_source")),
                Map.entry("requests", row.getLong("requests")),
                Map.entry("promptTokens", row.getLong("prompt_tokens")),
                Map.entry("completionTokens", row.getLong("completion_tokens")),
                Map.entry("cacheHitTokens", row.getLong("cache_hit_tokens")),
                Map.entry("cacheMissTokens", row.getLong("cache_miss_tokens")),
                Map.entry("failures", row.getLong("failures")),
                Map.entry("averageLatency", row.getBigDecimal("average_latency") == null ? BigDecimal.ZERO : row.getBigDecimal("average_latency"))
        ), from, to);
        return Map.of("platform", platform, "player", player, "models", models, "range", rangeMetadata(range));
    }

    private List<Map<String, Object>> dailySeries(AnalyticsDateRange range, List<String> codes,
                                                   String dimensionType, List<String> dimensionValues) {
        String firstDimension = dimensionValues.getFirst();
        String secondDimension = dimensionValues.size() > 1 ? dimensionValues.get(1) : firstDimension;
        List<Map<String, Object>> rows = jdbc.query("""
                select metric_date, metric_code,
                       case when metric_code='AVERAGE_LATENCY_MS' then avg(metric_value) else sum(metric_value) end metric_value
                from analytics_daily_metric
                where metric_date>=? and metric_date<=? and dimension_type=? and dimension_value in (?,?)
                group by metric_date, metric_code
                order by metric_date, metric_code
                """, (row, index) -> Map.<String, Object>of(
                "date", row.getDate("metric_date").toLocalDate(),
                "code", row.getString("metric_code"),
                "value", row.getBigDecimal("metric_value")
        ), Date.valueOf(range.from()), Date.valueOf(range.to()), dimensionType, firstDimension, secondDimension);
        Map<LocalDate, Map<String, Object>> byDay = new LinkedHashMap<>();
        for (LocalDate day = range.from(); !day.isAfter(range.to()); day = day.plusDays(1)) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("date", day.toString());
            codes.forEach(code -> values.put(code, 0L));
            byDay.put(day, values);
        }
        rows.forEach(row -> {
            String code = String.valueOf(row.get("code"));
            if (codes.contains(code)) byDay.get((LocalDate) row.get("date")).put(code, row.get("value"));
        });
        return new ArrayList<>(byDay.values());
    }

    private long metricTotal(String code) {
        return scalar("select coalesce(sum(metric_value),0) from analytics_daily_metric where metric_code=? and dimension_type='ALL' and dimension_value='ALL'", code);
    }

    private long metricTotal(AnalyticsDateRange range, String code) {
        return scalar("""
                select coalesce(sum(metric_value),0) from analytics_daily_metric
                where metric_date>=? and metric_date<=? and metric_code=?
                  and dimension_type='ALL' and dimension_value='ALL'
                """, Date.valueOf(range.from()), Date.valueOf(range.to()), code);
    }

    private long value(LocalDate date, String code, String dimensionType, String dimensionValue) {
        return scalar("select coalesce(max(metric_value),0) from analytics_daily_metric where metric_date=? and metric_code=? and dimension_type=? and dimension_value=?",
                Date.valueOf(date), code, dimensionType, dimensionValue);
    }

    private long scalar(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? 0 : value.longValue();
    }

    private String latestCalculation() {
        var timestamp = jdbc.queryForObject("select max(calculated_at) from analytics_daily_metric", java.sql.Timestamp.class);
        return timestamp == null ? "" : timestamp.toInstant().toString();
    }

    private Map<String, Object> rangeMetadata(AnalyticsDateRange range) {
        return Map.of("from", range.from().toString(), "to", range.to().toString(), "days", range.days());
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
