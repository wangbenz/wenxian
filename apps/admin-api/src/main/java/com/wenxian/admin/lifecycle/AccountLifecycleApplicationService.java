package com.wenxian.admin.lifecycle;

import com.wenxian.admin.lifecycle.AccountLifecycleViews.DeletionAuditItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.DeletionJobItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.LifecycleEventItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.NoticeItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.Overview;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.Page;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.RuntimeStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/** 校验生命周期管理查询的状态、日期和分页边界，不承担任何删除裁决。 */
@Service
public class AccountLifecycleApplicationService {
    private static final Set<String> NOTICE_STATUSES = Set.of(
            "PENDING", "SENDING", "RETRYABLE_FAILED", "SENT", "CANCELED");
    private static final Set<String> DELETION_STATUSES = Set.of(
            "NOTICE_DUE", "NOTICE_SENT", "DELETE_DUE", "DELETING", "RETRYABLE_FAILED", "DELETED", "CANCELED");
    private static final Set<String> REASONS = Set.of("USER_REQUEST", "INACTIVITY", "LEGACY_CLEANUP");
    private static final Set<String> EVENT_TYPES = Set.of(
            "NOTICE_PREPARED", "NOTICE_SENT", "NOTICE_RETRYABLE_FAILED", "NOTICE_CANCELED",
            "DELETE_DUE", "DELETION_STARTED", "DELETION_COMPLETED",
            "DELETION_RETRYABLE_FAILED", "DELETION_CANCELED");

    private final AccountLifecycleRepository repository;
    private final Clock clock;

    public AccountLifecycleApplicationService(AccountLifecycleRepository repository) {
        this(repository, Clock.systemUTC());
    }

    AccountLifecycleApplicationService(AccountLifecycleRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Overview overview() {
        return repository.overview(clock.instant());
    }

    public Page<NoticeItem> notices(String status, int page, int size) {
        return repository.notices(code(status, NOTICE_STATUSES, "通知状态"), page(page), size(size));
    }

    public Page<DeletionJobItem> deletionJobs(String status, String reason, int page, int size) {
        return repository.deletionJobs(code(status, DELETION_STATUSES, "删除任务状态"),
                code(reason, REASONS, "删除原因"), page(page), size(size));
    }

    public Page<DeletionAuditItem> deletionAudits(
            String reason, String from, String to, int page, int size) {
        TimeRange range = range(from, to);
        return repository.deletionAudits(code(reason, REASONS, "删除原因"), range.from(), range.to(),
                page(page), size(size));
    }

    public Page<LifecycleEventItem> lifecycleEvents(
            String eventType, String reason, String from, String to, int page, int size) {
        TimeRange range = range(from, to);
        return repository.lifecycleEvents(code(eventType, EVENT_TYPES, "事件类型"),
                code(reason, REASONS, "删除原因"), range.from(), range.to(), page(page), size(size));
    }

    public RuntimeStatus runtimeStatus() {
        return repository.latestRun().map(run -> new RuntimeStatus(true, run))
                .orElseGet(() -> new RuntimeStatus(false, null));
    }

    private String code(String value, Set<String> allowed, String label) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.isEmpty() && !allowed.contains(normalized)) {
            throw badRequest(label + "不受支持");
        }
        return normalized;
    }

    private int page(int page) {
        if (page < 0 || page > 1_000_000) throw badRequest("页码需在 0 至 1000000 之间");
        return page;
    }

    private int size(int size) {
        if (size < 1 || size > 100) throw badRequest("每页数量需在 1 至 100 之间");
        return size;
    }

    private TimeRange range(String from, String to) {
        Instant start = instant(from, "开始时间");
        Instant end = instant(to, "结束时间");
        if (start != null && end != null && start.isAfter(end)) {
            throw badRequest("开始时间不能晚于结束时间");
        }
        return new TimeRange(start, end);
    }

    private Instant instant(String value, String label) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException invalid) {
            throw badRequest(label + "必须使用 ISO-8601 时间格式");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record TimeRange(Instant from, Instant to) { }
}
