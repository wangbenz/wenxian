package com.wenxian.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 独立统计聚合服务。
 *
 * <p>业务表只在历史回算阶段读取；管理台日常页面只读取 analytics_* 表，
 * 避免玩家和回合数据增长后拖慢管理台。</p>
 */
@Service
public class AnalyticsAggregationService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsAggregationService.class);
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int CALCULATION_VERSION = 2;
    private final JdbcTemplate jdbc;

    public AnalyticsAggregationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 每五分钟刷新近期指标，失败不会影响玩家服务。 */
    @Scheduled(initialDelay = 15_000, fixedDelay = 300_000)
    public void refreshRecentHistory() {
        try {
            LocalDate today = LocalDate.now(REPORTING_ZONE);
            BigDecimal completed = jdbc.queryForObject(
                    "select max(metric_value) from analytics_daily_metric where metric_code='BACKFILL_COMPLETED'", BigDecimal.class);
            if (completed == null || completed.intValue() < CALCULATION_VERSION) {
                backfillUserDays();
                // 版本 2 起回算全部注册历史；不能为了启动速度把更早事实静默截成近一年。
                LocalDate firstDate = firstRegistrationDate(today);
                for (LocalDate day = firstDate; !day.isAfter(today); day = day.plusDays(1)) {
                    aggregateDay(day);
                }
                aggregateRetention(firstDate, today);
                // 最后写入完成标记。此前任何一步中断，下一次都会幂等重试完整回算。
                put(today, "BACKFILL_COMPLETED", CALCULATION_VERSION);
            } else {
                // 首次回算完成后只刷新独立统计层，避免管理台启动时反复扫描业务事实表。
                aggregateDay(today.minusDays(1));
                aggregateDay(today);
            }
        } catch (RuntimeException error) {
            log.error("统计聚合失败，已保留上一版可用数据", error);
        }
    }

    /**
     * 每天低峰期刷新统计汇总。玩家事实由实时统计事件写入，不再跨版本扫描业务表。
     */
    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Shanghai")
    public void reconcileSourceFacts() {
        try {
            LocalDate today = LocalDate.now(REPORTING_ZONE);
            aggregateDay(today.minusDays(1));
            aggregateDay(today);
        } catch (RuntimeException error) {
            log.error("统计每日对账失败，已保留上一版可用数据", error);
        }
    }

    /** 留存成熟速度慢，按小时刷新最近三十个注册批次即可。 */
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void refreshRecentRetention() {
        LocalDate today = LocalDate.now(REPORTING_ZONE);
        try {
            aggregateRetention(today.minusDays(30), today);
        } catch (RuntimeException error) {
            log.error("近期留存聚合失败，已保留上一版可用数据", error);
        }
    }

    /**
     * 首次启用统计平台时，从统一命卷事实回算玩家日表。
     */
    void backfillUserDays() {
        jdbc.update("""
                insert into analytics_user_day(
                    activity_date, user_id, created_save_count, successful_turn_count,
                    correction_count, entity_removal_count, archived_save_count,
                    first_activity_at, last_activity_at, first_release_version,
                    last_release_version, data_origin, updated_at
                )
                select date(f.occurred_at), f.user_id,
                       sum(f.created_saves), sum(f.turns), sum(f.corrections),
                       sum(f.entity_removals), sum(f.archived_saves),
                       min(f.occurred_at), max(f.occurred_at),
                       min(f.release_version), max(f.release_version), 'BACKFILL', current_timestamp(6)
                from (
                    select user_id, created_at occurred_at,
                           1 created_saves, 0 turns, 0 corrections, 0 entity_removals, 0 archived_saves,
                           created_release release_version
                    from story_save
                    union all
                    select s.user_id, t.created_at, 0, 1, 0, 0, 0, t.release_version
                    from story_turn t join story_save s on s.id=t.save_id
                    union all
                    select c.user_id, c.created_at, 0, 0,
                           case when c.correction_type in ('ITEM','CHARACTER') then 0 else 1 end,
                           case when c.correction_type in ('ITEM','CHARACTER') then 1 else 0 end,
                           0, s.created_release
                    from story_state_correction c join story_save s on s.id=c.save_id
                    union all
                    select s.user_id, s.updated_at, 0, 0, 0, 0, 1,
                           s.last_played_release
                    from story_save s
                    where s.deleted_at is not null
                ) f
                group by date(f.occurred_at), f.user_id
                on duplicate key update
                    created_save_count=greatest(created_save_count, values(created_save_count)),
                    successful_turn_count=greatest(successful_turn_count, values(successful_turn_count)),
                    archived_save_count=greatest(archived_save_count, values(archived_save_count)),
                    first_activity_at=least(first_activity_at, values(first_activity_at)),
                    last_activity_at=greatest(last_activity_at, values(last_activity_at)),
                    data_origin=if(data_origin='REALTIME', 'REALTIME', 'BACKFILL'),
                    updated_at=current_timestamp(6)
                """);
    }

    void aggregateDay(LocalDate day) {
        Instant fromInstant = day.atStartOfDay(REPORTING_ZONE).toInstant();
        Instant toInstant = day.plusDays(1).atStartOfDay(REPORTING_ZONE).toInstant();
        Timestamp from = Timestamp.from(fromInstant);
        Timestamp to = Timestamp.from(toInstant);

        put(day, "REGISTERED_USERS", scalar("select count(*) from app_user where registered_at>=? and registered_at<?", from, to));
        put(day, "ACTIVE_USERS", scalar("select count(*) from analytics_user_day where activity_date=?", Date.valueOf(day)));
        put(day, "CREATED_SAVES", scalar("select coalesce(sum(created_save_count),0) from analytics_user_day where activity_date=?", Date.valueOf(day)));
        put(day, "SUCCESSFUL_TURNS", scalar("select coalesce(sum(successful_turn_count),0) from analytics_user_day where activity_date=?", Date.valueOf(day)));
        put(day, "CORRECTIONS", scalar("select coalesce(sum(correction_count),0) from analytics_user_day where activity_date=?", Date.valueOf(day)));
        put(day, "ENTITY_REMOVALS", scalar("select coalesce(sum(entity_removal_count),0) from analytics_user_day where activity_date=?", Date.valueOf(day)));
        put(day, "ARCHIVED_SAVES", scalar("select coalesce(sum(archived_save_count),0) from analytics_user_day where activity_date=?", Date.valueOf(day)));
        put(day, "FINAL_ERRORS", scalar("select count(*) from operation_error where created_at>=? and created_at<?", from, to));

        for (String source : List.of("PUBLIC", "USER")) {
            String dimension = source.equals("PUBLIC") ? "PLATFORM" : "PLAYER";
            Object[] args = {from, to, source};
            put(day, "AI_REQUESTS", "CREDENTIAL_SOURCE", dimension,
                    scalar("select count(*) from ai_usage_log where created_at>=? and created_at<? and credential_source=?", args));
            put(day, "AI_FAILURES", "CREDENTIAL_SOURCE", dimension,
                    scalar("select count(*) from ai_usage_log where created_at>=? and created_at<? and credential_source=? and status='FAILED'", args));
            put(day, "PROMPT_TOKENS", "CREDENTIAL_SOURCE", dimension,
                    scalar("select coalesce(sum(prompt_tokens),0) from ai_usage_log where created_at>=? and created_at<? and credential_source=?", args));
            put(day, "COMPLETION_TOKENS", "CREDENTIAL_SOURCE", dimension,
                    scalar("select coalesce(sum(completion_tokens),0) from ai_usage_log where created_at>=? and created_at<? and credential_source=?", args));
            put(day, "CACHE_HIT_TOKENS", "CREDENTIAL_SOURCE", dimension,
                    scalar("select coalesce(sum(cache_hit_tokens),0) from ai_usage_log where created_at>=? and created_at<? and credential_source=?", args));
            put(day, "CACHE_MISS_TOKENS", "CREDENTIAL_SOURCE", dimension,
                    scalar("select coalesce(sum(cache_miss_tokens),0) from ai_usage_log where created_at>=? and created_at<? and credential_source=?", args));
            put(day, "AVERAGE_LATENCY_MS", "CREDENTIAL_SOURCE", dimension,
                    scalar("select coalesce(avg(nullif(latency_ms,0)),0) from ai_usage_log where created_at>=? and created_at<? and credential_source=?", args));
        }
    }

    void aggregateRetention(LocalDate from, LocalDate today) {
        for (LocalDate cohort = from; !cohort.isAfter(today); cohort = cohort.plusDays(1)) {
            int registered = scalar("select count(*) from app_user where date(registered_at)=?", Date.valueOf(cohort)).intValue();
            int activated = scalar("""
                    select count(*) from app_user u
                    where date(u.registered_at)=? and exists(
                        select 1 from analytics_user_day d where d.user_id=u.id and d.successful_turn_count>0
                    )
                    """, Date.valueOf(cohort)).intValue();
            for (int retentionDay : List.of(1, 3, 7, 14)) {
                if (cohort.plusDays(retentionDay).isAfter(today)) continue;
                int retained = scalar("""
                        select count(*) from app_user u join analytics_user_day d on d.user_id=u.id
                        where date(u.registered_at)=? and d.activity_date=?
                          and (d.successful_turn_count+d.created_save_count+d.correction_count+d.entity_removal_count)>0
                        """, Date.valueOf(cohort), Date.valueOf(cohort.plusDays(retentionDay))).intValue();
                jdbc.update("""
                        insert into analytics_retention_cohort(
                            cohort_date, retention_day, registered_users, activated_users,
                            retained_users, data_origin, calculation_version, calculated_at
                        ) values (?, ?, ?, ?, ?, 'BACKFILL', ?, current_timestamp(6))
                        on duplicate key update registered_users=values(registered_users),
                            activated_users=values(activated_users), retained_users=values(retained_users),
                            calculation_version=values(calculation_version), calculated_at=current_timestamp(6)
                        """, Date.valueOf(cohort), retentionDay, registered, activated, retained, CALCULATION_VERSION);
            }
        }
    }

    private Number scalar(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private LocalDate firstRegistrationDate(LocalDate fallback) {
        Date date = jdbc.queryForObject("select min(date(registered_at)) from app_user", Date.class);
        return date == null ? fallback : date.toLocalDate();
    }

    private void put(LocalDate day, String code, Number value) {
        put(day, code, "ALL", "ALL", value);
    }

    private void put(LocalDate day, String code, String dimensionType, String dimensionValue, Number value) {
        jdbc.update("""
                insert into analytics_daily_metric(
                    metric_date, metric_code, dimension_type, dimension_value,
                    metric_value, data_origin, calculation_version, calculated_at
                ) values (?, ?, ?, ?, ?, 'REALTIME', ?, current_timestamp(6))
                on duplicate key update metric_value=values(metric_value), data_origin=values(data_origin),
                    calculation_version=values(calculation_version), calculated_at=current_timestamp(6)
                """, Date.valueOf(day), code, dimensionType, dimensionValue,
                new BigDecimal(value.toString()), CALCULATION_VERSION);
    }
}
