package com.wenxian.admin.byok;

import com.wenxian.admin.byok.ByokAdminViews.CredentialPageView;
import com.wenxian.admin.byok.ByokAdminViews.CredentialSnapshot;
import com.wenxian.admin.byok.ByokAdminViews.ConversionTrendDay;
import com.wenxian.admin.byok.ByokAdminViews.DataCoverage;
import com.wenxian.admin.byok.ByokAdminViews.ProviderSummary;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 玩家自备服务脱敏查询仓储；不承担规则开关、凭证验证或密钥解密。 */
public interface ByokQueryRepository {
    ConversionCounts conversion(LocalDate from, LocalDate to, int windowDays, Instant eligibleBefore);

    List<ConversionTrendDay> conversionTrend(LocalDate from, LocalDate to, int windowDays,
                                              Instant eligibleBefore);

    DataCoverage dataCoverage();

    CredentialSnapshot credentialSnapshot();

    List<ProviderSummary> providerSummaries(Instant usageFrom, Instant usageTo);

    CredentialPageView credentials(CredentialFilter filter, Instant usageFrom, Instant usageTo,
                                   LocalDate usageFromDate, LocalDate usageToDate);

    record ConversionCounts(long totalCohortUsers, long registeredUsers, long createdSaveUsers,
                            long firstTurnUsers, long connectedUsers, long byokTurnUsers) { }

    record CredentialFilter(String search, String providerCode, String status,
                            Boolean selected, int page, int size) { }
}
