package com.wenxian.admin.byok;

import com.wenxian.admin.byok.ByokAdminViews.ConversionStage;
import com.wenxian.admin.byok.ByokAdminViews.ConversionView;
import com.wenxian.admin.byok.ByokAdminViews.CredentialPageView;
import com.wenxian.admin.byok.ByokAdminViews.CredentialSummaryView;
import com.wenxian.admin.byok.ByokQueryRepository.ConversionCounts;
import com.wenxian.admin.byok.ByokQueryRepository.CredentialFilter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * 玩家自备服务只读查询用例。
 *
 * <p>服务统一校验北京时间、观察窗口和分页边界，保证“当前凭证库存”与“注册批次转化”使用不同且明确的口径。</p>
 */
@Service
public class ByokQueryService {
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private final ByokQueryRepository repository;
    private final Clock clock;

    public ByokQueryService(ByokQueryRepository repository) {
        this(repository, Clock.system(REPORTING_ZONE));
    }

    ByokQueryService(ByokQueryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public ConversionView conversion(LocalDate requestedFrom, LocalDate requestedTo, int windowDays) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = requestedFrom == null ? today.minusDays(29) : requestedFrom;
        LocalDate to = requestedTo == null ? today : requestedTo;
        validateRange(from, to);
        if (windowDays != 7 && windowDays != 14 && windowDays != 30) {
            throw badRequest("转化窗口只支持7天、14天或30天");
        }
        Instant now = clock.instant();
        Instant eligibleBefore = now.minus(windowDays, ChronoUnit.DAYS);
        ConversionCounts counts = repository.conversion(from, to, windowDays, eligibleBefore);
        List<ConversionStage> stages = List.of(
                stage("REGISTERED", "注册玩家", counts.registeredUsers(), counts.registeredUsers(), counts.registeredUsers()),
                stage("SAVE_CREATED", "创建命卷", counts.createdSaveUsers(), counts.registeredUsers(), counts.registeredUsers()),
                stage("FIRST_TURN", "完成首回合", counts.firstTurnUsers(), counts.createdSaveUsers(), counts.registeredUsers()),
                stage("KEY_CONNECTED", "接入 Key", counts.connectedUsers(), counts.firstTurnUsers(), counts.registeredUsers()),
                stage("BYOK_TURN", "成功自备推演", counts.byokTurnUsers(), counts.connectedUsers(), counts.registeredUsers())
        );
        return new ConversionView(from, to, windowDays,
                Math.max(0, counts.totalCohortUsers() - counts.registeredUsers()), stages,
                repository.conversionTrend(from, to, windowDays, eligibleBefore),
                repository.dataCoverage(), now);
    }

    public CredentialSummaryView credentialSummary(LocalDate requestedFrom, LocalDate requestedTo) {
        DateRange range = usageRange(requestedFrom, requestedTo);
        return new CredentialSummaryView(repository.credentialSnapshot(),
                repository.providerSummaries(range.fromInstant(), range.toInstant()),
                range.from(), range.to(), clock.instant());
    }

    public CredentialPageView credentials(String search, String provider, String status, Boolean selected,
                                          int page, int size, LocalDate requestedFrom, LocalDate requestedTo) {
        DateRange range = usageRange(requestedFrom, requestedTo);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(10, Math.min(size, 50));
        String safeStatus = normalize(status);
        if (!safeStatus.isBlank() && !List.of("ACTIVE", "INVALID").contains(safeStatus)) {
            throw badRequest("凭证状态只支持ACTIVE或INVALID");
        }
        CredentialFilter filter = new CredentialFilter(strip(search), normalize(provider), safeStatus,
                selected, safePage, safeSize);
        return repository.credentials(filter, range.fromInstant(), range.toInstant(), range.from(), range.to());
    }

    private DateRange usageRange(LocalDate requestedFrom, LocalDate requestedTo) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = requestedFrom == null ? today.minusDays(29) : requestedFrom;
        LocalDate to = requestedTo == null ? today : requestedTo;
        validateRange(from, to);
        return new DateRange(from, to,
                from.atStartOfDay(REPORTING_ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(REPORTING_ZONE).toInstant());
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw badRequest("开始日期不能晚于结束日期");
        if (ChronoUnit.DAYS.between(from, to) > 365) throw badRequest("单次查询最多支持366天");
    }

    private ConversionStage stage(String code, String label, long users, long previous, long registered) {
        return new ConversionStage(code, label, users, rate(users, previous), rate(users, registered));
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : Math.round(numerator * 1000.0 / denominator) / 10.0;
    }

    private String strip(String value) {
        return value == null ? "" : value.strip();
    }

    private String normalize(String value) {
        return strip(value).toUpperCase(Locale.ROOT);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record DateRange(LocalDate from, LocalDate to, Instant fromInstant, Instant toInstant) { }
}
