package com.wenxian.admin.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.DeletionAuditItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.DeletionJobItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.LifecycleEventItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.NoticeItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.Overview;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.Page;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.RetentionRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据生命周期管理读模型的 JDBC 实现。
 *
 * <p>所有筛选都在数据库分页执行。匿名 JSON 只投影固定允许的尝试次数、活动来源和分域计数；
 * 即使表内出现未知字段，也不会把任意内容透传到管理台。</p>
 */
@Repository
public class JdbcAccountLifecycleRepository implements AccountLifecycleRepository {
    private static final List<String> RETENTION_STATES = List.of(
            "ACTIVE", "NOTICE_DUE", "NOTICE_SENT", "DELETE_DUE", "DELETING", "RETRYABLE_FAILED");
    private static final List<String> NOTICE_STATES = List.of(
            "PENDING", "SENDING", "RETRYABLE_FAILED", "SENT", "CANCELED");
    private static final List<String> JOB_STATES = List.of(
            "NOTICE_DUE", "NOTICE_SENT", "DELETE_DUE", "DELETING", "RETRYABLE_FAILED", "DELETED", "CANCELED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcAccountLifecycleRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Overview overview(Instant now) {
        Instant sevenDays = now.plus(Duration.ofDays(7));
        Long due24Hours = jdbc.queryForObject("""
                select count(*) from account_retention_state
                where policy_state in ('NOTICE_DUE','NOTICE_SENT')
                  and scheduled_deletion_at>? and scheduled_deletion_at<=?
                """, Long.class, Timestamp.from(now), Timestamp.from(now.plus(Duration.ofHours(24))));
        Long due7Days = jdbc.queryForObject("""
                select count(*) from account_retention_state
                where policy_state in ('NOTICE_DUE','NOTICE_SENT')
                  and scheduled_deletion_at>? and scheduled_deletion_at<=?
                """, Long.class, Timestamp.from(now), Timestamp.from(sevenDays));
        Long unresolved = jdbc.queryForObject(
                "select count(*) from ai_usage_log where attribution_status='UNRESOLVED'", Long.class);
        return new Overview(statusCounts("account_retention_state", "policy_state", RETENTION_STATES),
                statusCounts("account_deletion_notice", "status", NOTICE_STATES),
                statusCounts("account_deletion_job", "status", JOB_STATES),
                value(due24Hours), value(due7Days), value(unresolved));
    }

    @Override
    public Page<NoticeItem> notices(String status, int page, int size) {
        SqlFilter filter = statusFilter("status", status);
        Long total = count("account_deletion_notice", filter);
        List<Object> arguments = pageArguments(filter, page, size);
        List<NoticeItem> rows = jdbc.query("""
                select id,coalesce(user_id,'') user_id,status,scheduled_deletion_at,attempt_count,
                       next_attempt_at,coalesce(last_error_code,'') last_error_code,updated_at
                from account_deletion_notice %s
                order by updated_at desc,id desc limit ? offset ?
                """.formatted(filter.where()), (row, index) -> new NoticeItem(
                row.getString("id"), blankToNull(row.getString("user_id")), row.getString("status"),
                instant(row.getTimestamp("scheduled_deletion_at")), row.getInt("attempt_count"),
                instant(row.getTimestamp("next_attempt_at")), blankToNull(row.getString("last_error_code")),
                instant(row.getTimestamp("updated_at"))), arguments.toArray());
        return new Page<>(value(total), page, size, rows);
    }

    @Override
    public Page<DeletionJobItem> deletionJobs(String status, String reason, int page, int size) {
        SqlFilter filter = statusReasonFilter(status, reason);
        Long total = count("account_deletion_job", filter);
        List<Object> arguments = pageArguments(filter, page, size);
        List<DeletionJobItem> rows = jdbc.query("""
                select id,coalesce(user_id,'') user_id,reason,status,attempt_count,
                       coalesce(last_error_code,'') last_error_code,requested_at,updated_at,completed_at
                from account_deletion_job %s
                order by updated_at desc,id desc limit ? offset ?
                """.formatted(filter.where()), (row, index) -> new DeletionJobItem(
                row.getString("id"), blankToNull(row.getString("user_id")), row.getString("reason"),
                row.getString("status"), row.getInt("attempt_count"),
                blankToNull(row.getString("last_error_code")), instant(row.getTimestamp("requested_at")),
                instant(row.getTimestamp("updated_at")), instant(row.getTimestamp("completed_at"))),
                arguments.toArray());
        return new Page<>(value(total), page, size, rows);
    }

    @Override
    public Page<DeletionAuditItem> deletionAudits(
            String reason, Instant from, Instant to, int page, int size) {
        SqlFilter filter = historyFilter(null, reason, from, to, "completed_at");
        Long total = count("account_deletion_audit", filter);
        List<Object> arguments = pageArguments(filter, page, size);
        List<DeletionAuditItem> rows = jdbc.query("""
                select id,reason,delete_counts_json,completed_at
                from account_deletion_audit %s
                order by completed_at desc,id desc limit ? offset ?
                """.formatted(filter.where()), (row, index) -> {
            ParsedCounts counts = counts(row.getString("delete_counts_json"));
            return new DeletionAuditItem(row.getString("id"), row.getString("reason"), counts.values(),
                    counts.readable(), instant(row.getTimestamp("completed_at")));
        }, arguments.toArray());
        return new Page<>(value(total), page, size, rows);
    }

    @Override
    public Page<LifecycleEventItem> lifecycleEvents(
            String eventType, String reason, Instant from, Instant to, int page, int size) {
        SqlFilter filter = historyFilter(eventType, reason, from, to, "occurred_at");
        Long total = count("account_lifecycle_event", filter);
        List<Object> arguments = pageArguments(filter, page, size);
        List<LifecycleEventItem> rows = jdbc.query("""
                select id,event_type,coalesce(reason,'') reason,reference_id,
                       coalesce(error_code,'') error_code,metadata_json,occurred_at
                from account_lifecycle_event %s
                order by occurred_at desc,id desc limit ? offset ?
                """.formatted(filter.where()), (row, index) -> {
            ParsedMetadata metadata = metadata(row.getString("metadata_json"));
            return new LifecycleEventItem(row.getString("id"), row.getString("event_type"),
                    blankToNull(row.getString("reason")), row.getString("reference_id"),
                    blankToNull(row.getString("error_code")), metadata.attemptCount(),
                    metadata.activitySource(), metadata.deleteCounts(), metadata.readable(),
                    instant(row.getTimestamp("occurred_at")));
        }, arguments.toArray());
        return new Page<>(value(total), page, size, rows);
    }

    @Override
    public Optional<RetentionRun> latestRun() {
        return jdbc.query("""
                select id,trigger_source,status,policy_evaluation_enabled,notification_delivery_enabled,
                       automatic_deletion_enabled,mail_gateway_ready,notice_after_days,delete_after_days,
                       minimum_notice_days,batch_size,notices_prepared,notices_delivered,
                       deletion_jobs_prepared,coalesce(error_code,'') error_code,started_at,finished_at
                from account_retention_run order by started_at desc,id desc limit 1
                """, (row, index) -> new RetentionRun(row.getString("id"), row.getString("trigger_source"),
                row.getString("status"), row.getBoolean("policy_evaluation_enabled"),
                row.getBoolean("notification_delivery_enabled"), row.getBoolean("automatic_deletion_enabled"),
                row.getBoolean("mail_gateway_ready"), row.getInt("notice_after_days"),
                row.getInt("delete_after_days"), row.getInt("minimum_notice_days"), row.getInt("batch_size"),
                row.getInt("notices_prepared"), row.getInt("notices_delivered"),
                row.getInt("deletion_jobs_prepared"), blankToNull(row.getString("error_code")),
                instant(row.getTimestamp("started_at")), instant(row.getTimestamp("finished_at"))))
                .stream().findFirst();
    }

    private Map<String, Long> statusCounts(String table, String column, List<String> expected) {
        Map<String, Long> counts = new LinkedHashMap<>();
        expected.forEach(status -> counts.put(status, 0L));
        jdbc.query("select " + column + ",count(*) from " + table + " group by " + column,
                (RowCallbackHandler) row -> counts.put(row.getString(1), row.getLong(2)));
        return Map.copyOf(counts);
    }

    private Long count(String table, SqlFilter filter) {
        return jdbc.queryForObject("select count(*) from " + table + " " + filter.where(),
                Long.class, filter.arguments().toArray());
    }

    private SqlFilter statusFilter(String column, String status) {
        if (status.isBlank()) return new SqlFilter("where 1=1", List.of());
        return new SqlFilter("where " + column + "=?", List.of(status));
    }

    private SqlFilter statusReasonFilter(String status, String reason) {
        StringBuilder where = new StringBuilder("where 1=1");
        List<Object> arguments = new ArrayList<>();
        if (!status.isBlank()) {
            where.append(" and status=?");
            arguments.add(status);
        }
        if (!reason.isBlank()) {
            where.append(" and reason=?");
            arguments.add(reason);
        }
        return new SqlFilter(where.toString(), arguments);
    }

    private SqlFilter historyFilter(String eventType, String reason, Instant from, Instant to, String timeColumn) {
        StringBuilder where = new StringBuilder("where 1=1");
        List<Object> arguments = new ArrayList<>();
        if (eventType != null && !eventType.isBlank()) {
            where.append(" and event_type=?");
            arguments.add(eventType);
        }
        if (reason != null && !reason.isBlank()) {
            where.append(" and reason=?");
            arguments.add(reason);
        }
        if (from != null) {
            where.append(" and ").append(timeColumn).append(">=?");
            arguments.add(Timestamp.from(from));
        }
        if (to != null) {
            where.append(" and ").append(timeColumn).append("<=?");
            arguments.add(Timestamp.from(to));
        }
        return new SqlFilter(where.toString(), arguments);
    }

    private List<Object> pageArguments(SqlFilter filter, int page, int size) {
        List<Object> arguments = new ArrayList<>(filter.arguments());
        arguments.add(size);
        arguments.add(page * size);
        return arguments;
    }

    private ParsedCounts counts(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) return new ParsedCounts(Map.of(), false);
            Map<String, Integer> values = new LinkedHashMap<>();
            boolean readable = true;
            var fields = root.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getKey().matches("[a-zA-Z0-9_]{1,80}") || !field.getValue().canConvertToInt()) {
                    readable = false;
                    continue;
                }
                values.put(field.getKey(), field.getValue().intValue());
            }
            return new ParsedCounts(Map.copyOf(values), readable);
        } catch (Exception invalid) {
            return new ParsedCounts(Map.of(), false);
        }
    }

    private ParsedMetadata metadata(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) return ParsedMetadata.unreadable();
            boolean readable = true;
            Integer attempt = null;
            String activitySource = null;
            Map<String, Integer> deleteCounts = Map.of();
            var fields = root.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                switch (field.getKey()) {
                    case "attemptCount" -> {
                        if (field.getValue().canConvertToInt()) attempt = field.getValue().intValue();
                        else readable = false;
                    }
                    case "activitySource" -> {
                        String source = field.getValue().asText("");
                        if (source.matches("[A-Z0-9_]{1,40}")) activitySource = source;
                        else readable = false;
                    }
                    case "deleteCounts" -> {
                        ParsedCounts parsed = counts(field.getValue().toString());
                        deleteCounts = parsed.values();
                        readable &= parsed.readable();
                    }
                    default -> readable = false;
                }
            }
            return new ParsedMetadata(attempt, activitySource, deleteCounts, readable);
        } catch (Exception invalid) {
            return ParsedMetadata.unreadable();
        }
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private long value(Long value) {
        return value == null ? 0 : value;
    }

    private record SqlFilter(String where, List<Object> arguments) { }
    private record ParsedCounts(Map<String, Integer> values, boolean readable) { }
    private record ParsedMetadata(Integer attemptCount, String activitySource,
                                  Map<String, Integer> deleteCounts, boolean readable) {
        private static ParsedMetadata unreadable() {
            return new ParsedMetadata(null, null, Map.of(), false);
        }
    }
}
