package com.wenxian.admin.byok;

import com.wenxian.admin.byok.ByokAdminViews.CredentialPageView;
import com.wenxian.admin.byok.ByokAdminViews.CredentialRow;
import com.wenxian.admin.byok.ByokAdminViews.CredentialSnapshot;
import com.wenxian.admin.byok.ByokAdminViews.ConversionTrendDay;
import com.wenxian.admin.byok.ByokAdminViews.DataCoverage;
import com.wenxian.admin.byok.ByokAdminViews.ProviderSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JDBC 玩家自备服务查询仓储。
 *
 * <p>转化使用注册批次和固定观察窗口；成功回合以已提交的 {@code story_turn} 为准，调用失败以
 * {@code ai_usage_log} 为准。所有玩家凭证查询显式选择脱敏列，失败时不会降级读取密文字段。</p>
 */
@Repository
public class JdbcByokQueryRepository implements ByokQueryRepository {
    private static final String COHORT_FLAGS = """
            with cohort_flags as (
                select u.id, u.registered_at,
                       case when exists(
                           select 1 from story_save s
                           where s.user_id=u.id and s.created_at>=u.registered_at
                             and s.created_at<timestampadd(DAY, ?, u.registered_at)
                       ) then 1 else 0 end created_save,
                       case when exists(
                           select 1 from story_turn t join story_save s on s.id=t.save_id
                           where s.user_id=u.id and t.created_at>=u.registered_at
                             and t.created_at<timestampadd(DAY, ?, u.registered_at)
                       ) then 1 else 0 end first_turn,
                       case when exists(
                           select 1 from analytics_event e
                           where e.user_id=u.id and e.event_type='BYOK_CREDENTIAL_CONNECTED'
                             and e.occurred_at>=u.registered_at
                             and e.occurred_at<timestampadd(DAY, ?, u.registered_at)
                       ) then 1 else 0 end connected_key,
                       case when exists(
                           select 1 from story_turn t join story_save s on s.id=t.save_id
                           where s.user_id=u.id and t.credential_source in ('USER','PLAYER')
                             and t.created_at>=u.registered_at
                             and t.created_at<timestampadd(DAY, ?, u.registered_at)
                       ) then 1 else 0 end byok_turn
                from app_user u
                where u.registered_at>=? and u.registered_at<?
            )
            """;

    private final JdbcTemplate jdbc;

    public JdbcByokQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ConversionCounts conversion(LocalDate from, LocalDate to, int windowDays,
                                       Instant eligibleBefore) {
        Timestamp fromTime = Timestamp.valueOf(from.atStartOfDay());
        Timestamp toTime = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
        Timestamp eligible = Timestamp.from(eligibleBefore);
        long total = number("select count(*) from app_user where registered_at>=? and registered_at<?",
                fromTime, toTime);
        return jdbc.queryForObject(COHORT_FLAGS + """
                select count(*) registered_users,
                       coalesce(sum(created_save),0) created_save_users,
                       coalesce(sum(case when created_save=1 and first_turn=1 then 1 else 0 end),0) first_turn_users,
                       coalesce(sum(case when created_save=1 and first_turn=1 and connected_key=1 then 1 else 0 end),0) connected_users,
                       coalesce(sum(case when created_save=1 and first_turn=1 and connected_key=1 and byok_turn=1 then 1 else 0 end),0) byok_turn_users
                from cohort_flags where registered_at<=?
                """, (row, index) -> new ConversionCounts(total, row.getLong("registered_users"),
                row.getLong("created_save_users"), row.getLong("first_turn_users"),
                row.getLong("connected_users"), row.getLong("byok_turn_users")),
                windowDays, windowDays, windowDays, windowDays, fromTime, toTime, eligible);
    }

    @Override
    public List<ConversionTrendDay> conversionTrend(LocalDate from, LocalDate to, int windowDays,
                                                     Instant eligibleBefore) {
        Timestamp fromTime = Timestamp.valueOf(from.atStartOfDay());
        Timestamp toTime = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
        return jdbc.query(COHORT_FLAGS + """
                select cast(registered_at as date) cohort_date, count(*) registered_users,
                       coalesce(sum(created_save),0) created_save_users,
                       coalesce(sum(case when created_save=1 and first_turn=1 then 1 else 0 end),0) first_turn_users,
                       coalesce(sum(case when created_save=1 and first_turn=1 and connected_key=1 then 1 else 0 end),0) connected_users,
                       coalesce(sum(case when created_save=1 and first_turn=1 and connected_key=1 and byok_turn=1 then 1 else 0 end),0) byok_turn_users
                from cohort_flags where registered_at<=? group by cast(registered_at as date)
                order by cohort_date
                """, (row, index) -> new ConversionTrendDay(row.getDate("cohort_date").toLocalDate(),
                row.getLong("registered_users"), row.getLong("created_save_users"),
                row.getLong("first_turn_users"), row.getLong("connected_users"),
                row.getLong("byok_turn_users")), windowDays, windowDays, windowDays, windowDays,
                fromTime, toTime, Timestamp.from(eligibleBefore));
    }

    @Override
    public DataCoverage dataCoverage() {
        return jdbc.queryForObject("""
                select min(occurred_at) started_at,
                       coalesce(sum(case when data_origin='BACKFILL' then 1 else 0 end),0) backfilled
                from analytics_event where event_type='BYOK_CREDENTIAL_CONNECTED'
                """, (row, index) -> new DataCoverage(instant(row.getTimestamp("started_at")),
                row.getLong("backfilled"),
                "统计开始前已经删除或首次验证失败的凭证无法恢复；回填记录只代表迁移时仍存在的凭证。"));
    }

    @Override
    public CredentialSnapshot credentialSnapshot() {
        long users = number("select count(distinct user_id) from user_ai_provider_credential");
        long credentials = number("select count(*) from user_ai_provider_credential");
        long active = number("select count(*) from user_ai_provider_credential where status='ACTIVE'");
        long invalid = number("select count(*) from user_ai_provider_credential where status<>'ACTIVE'");
        long selected = number("""
                select count(distinct p.user_id) from user_model_preference p
                join user_ai_provider_credential c on c.user_id=p.user_id and c.provider_code=p.provider_code
                where p.credential_source in ('USER','PLAYER') and c.status='ACTIVE'
                """);
        long multiple = number("""
                select count(*) from (
                    select user_id from user_ai_provider_credential group by user_id having count(*)>1
                ) multiple_credentials
                """);
        return new CredentialSnapshot(users, credentials, active, invalid, selected, multiple);
    }

    @Override
    public List<ProviderSummary> providerSummaries(Instant usageFrom, Instant usageTo) {
        Map<String, ProviderAccumulator> providers = new LinkedHashMap<>();
        jdbc.query("""
                select c.provider_code, coalesce(p.display_name,c.provider_code) display_name,
                       count(distinct c.user_id) credential_users, count(*) credential_count,
                       sum(case when c.status='ACTIVE' then 1 else 0 end) active_credentials,
                       sum(case when c.status<>'ACTIVE' then 1 else 0 end) invalid_credentials,
                       count(distinct case when pref.credential_source in ('USER','PLAYER')
                           and pref.provider_code=c.provider_code then c.user_id end) selected_users
                from user_ai_provider_credential c
                left join ai_provider_config p on p.provider_code=c.provider_code
                left join user_model_preference pref on pref.user_id=c.user_id
                group by c.provider_code,p.display_name order by credential_users desc,c.provider_code
                """, row -> {
            String code = row.getString("provider_code");
            providers.put(code, new ProviderAccumulator(code, row.getString("display_name"),
                    row.getLong("credential_users"), row.getLong("credential_count"),
                    row.getLong("active_credentials"), row.getLong("invalid_credentials"),
                    row.getLong("selected_users")));
        });
        jdbc.query("""
                select t.provider, count(distinct s.user_id) usage_users, count(*) successful_turns,
                       max(t.created_at) last_used_at
                from story_turn t join story_save s on s.id=t.save_id
                where t.credential_source in ('USER','PLAYER') and t.created_at>=? and t.created_at<?
                group by t.provider
                """, row -> {
            ProviderAccumulator provider = providers.get(row.getString("provider"));
            if (provider != null) provider.usage(row.getLong("usage_users"),
                    row.getLong("successful_turns"), instant(row.getTimestamp("last_used_at")));
        }, Timestamp.from(usageFrom), Timestamp.from(usageTo));
        jdbc.query("""
                select provider, sum(case when status='FAILED' then 1 else 0 end) failed_calls
                from ai_usage_log where credential_source in ('USER','PLAYER')
                  and created_at>=? and created_at<? group by provider
                """, row -> {
            ProviderAccumulator provider = providers.get(row.getString("provider"));
            if (provider != null) provider.failedCalls = row.getLong("failed_calls");
        }, Timestamp.from(usageFrom), Timestamp.from(usageTo));
        return providers.values().stream().map(ProviderAccumulator::view).toList();
    }

    @Override
    public CredentialPageView credentials(CredentialFilter filter, Instant usageFrom, Instant usageTo,
                                          LocalDate usageFromDate, LocalDate usageToDate) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> args = new ArrayList<>();
        if (!filter.search().isBlank()) {
            where.append(" and (lower(u.email) like ? or lower(c.user_id) like ?) ");
            String like = "%" + filter.search().toLowerCase(Locale.ROOT) + "%";
            args.add(like); args.add(like);
        }
        if (!filter.providerCode().isBlank()) {
            where.append(" and c.provider_code=? "); args.add(filter.providerCode());
        }
        if (!filter.status().isBlank()) {
            where.append(" and c.status=? "); args.add(filter.status());
        }
        if (filter.selected() != null) {
            where.append(filter.selected()
                    ? " and pref.credential_source in ('USER','PLAYER') and pref.provider_code=c.provider_code "
                    : " and not (coalesce(pref.credential_source,'') in ('USER','PLAYER') and pref.provider_code=c.provider_code) ");
        }
        String from = """
                from user_ai_provider_credential c
                join app_user u on u.id=c.user_id
                left join ai_provider_config p on p.provider_code=c.provider_code
                left join user_model_preference pref on pref.user_id=c.user_id
                """;
        long total = number("select count(*) " + from + where, args.toArray());
        List<Object> pageArgs = new ArrayList<>();
        pageArgs.add(Timestamp.from(usageFrom)); pageArgs.add(Timestamp.from(usageTo));
        pageArgs.add(Timestamp.from(usageFrom)); pageArgs.add(Timestamp.from(usageTo));
        pageArgs.addAll(args); pageArgs.add(filter.size()); pageArgs.add(filter.page() * filter.size());
        List<CredentialRow> rows = jdbc.query("""
                select c.user_id,u.email,c.provider_code,coalesce(p.display_name,c.provider_code) provider_name,
                       c.masked_suffix,c.status,c.last_verified_at,c.last_error_code,c.created_at,c.updated_at,
                       case when pref.credential_source in ('USER','PLAYER') and pref.provider_code=c.provider_code
                           then true else false end selected,
                       case when pref.provider_code=c.provider_code then pref.model_code else null end selected_model,
                       usage.last_used_at,coalesce(usage.period_turns,0) period_turns,
                       coalesce(failures.period_failures,0) period_failures
                """ + from + """
                left join (
                    select s.user_id,t.provider,max(t.created_at) last_used_at,
                           sum(case when t.created_at>=? and t.created_at<? then 1 else 0 end) period_turns
                    from story_turn t join story_save s on s.id=t.save_id
                    where t.credential_source in ('USER','PLAYER') group by s.user_id,t.provider
                ) usage on usage.user_id=c.user_id and usage.provider=c.provider_code
                left join (
                    select s.user_id,l.provider,
                           sum(case when l.status='FAILED' and l.created_at>=? and l.created_at<? then 1 else 0 end) period_failures
                    from ai_usage_log l join story_save s on s.id=l.save_id
                    where l.credential_source in ('USER','PLAYER') group by s.user_id,l.provider
                ) failures on failures.user_id=c.user_id and failures.provider=c.provider_code
                """ + where + " order by c.updated_at desc,c.user_id,c.provider_code limit ? offset ?",
                (row, index) -> new CredentialRow(row.getString("user_id"), row.getString("email"),
                        row.getString("provider_code"), row.getString("provider_name"),
                        row.getString("masked_suffix"), row.getString("status"), row.getBoolean("selected"),
                        row.getString("selected_model"), instant(row.getTimestamp("last_verified_at")),
                        row.getString("last_error_code"), instant(row.getTimestamp("created_at")),
                        instant(row.getTimestamp("updated_at")), instant(row.getTimestamp("last_used_at")),
                        row.getLong("period_turns"), row.getLong("period_failures")), pageArgs.toArray());
        return new CredentialPageView(total, filter.page(), filter.size(), usageFromDate, usageToDate, rows);
    }

    private long number(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static final class ProviderAccumulator {
        private final String code;
        private final String name;
        private final long users;
        private final long count;
        private final long active;
        private final long invalid;
        private final long selected;
        private long usageUsers;
        private long successfulTurns;
        private long failedCalls;
        private Instant lastUsedAt;

        private ProviderAccumulator(String code, String name, long users, long count,
                                    long active, long invalid, long selected) {
            this.code = code; this.name = name; this.users = users; this.count = count;
            this.active = active; this.invalid = invalid; this.selected = selected;
        }

        private void usage(long nextUsers, long nextTurns, Instant nextLastUsedAt) {
            usageUsers = nextUsers; successfulTurns = nextTurns; lastUsedAt = nextLastUsedAt;
        }

        private ProviderSummary view() {
            return new ProviderSummary(code, name, users, count, active, invalid, selected,
                    usageUsers, successfulTurns, failedCalls, lastUsedAt);
        }
    }
}
