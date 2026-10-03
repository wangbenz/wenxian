package com.wenxian.admin.byok;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 玩家自备服务管理台只读 DTO 集合。
 *
 * <p>所有视图只包含转化事实、脱敏尾号和标准状态，禁止加入密钥密文、完整密钥或供应商原始异常。</p>
 */
public final class ByokAdminViews {
    private ByokAdminViews() { }

    public record ConversionView(LocalDate cohortFrom, LocalDate cohortTo, int windowDays,
                                 long immatureUsers, List<ConversionStage> stages,
                                 List<ConversionTrendDay> trend, DataCoverage dataCoverage,
                                 Instant calculatedAt) { }

    public record ConversionStage(String code, String label, long users,
                                  double rateFromPrevious, double rateFromRegistered) { }

    public record ConversionTrendDay(LocalDate date, long registeredUsers, long createdSaveUsers,
                                     long firstTurnUsers, long connectedUsers, long byokTurnUsers) { }

    public record DataCoverage(Instant connectedEventsStartedAt, long backfilledConnections,
                               String note) { }

    public record CredentialSummaryView(CredentialSnapshot snapshot,
                                        List<ProviderSummary> providers,
                                        LocalDate usageFrom, LocalDate usageTo,
                                        Instant calculatedAt) { }

    public record CredentialSnapshot(long credentialUsers, long credentialCount,
                                     long activeCredentials, long invalidCredentials,
                                     long selectedUsers, long multiProviderUsers) { }

    public record ProviderSummary(String providerCode, String displayName,
                                  long credentialUsers, long credentialCount,
                                  long activeCredentials, long invalidCredentials,
                                  long selectedUsers, long usageUsers,
                                  long successfulTurns, long failedCalls,
                                  Instant lastUsedAt) { }

    public record CredentialPageView(long total, int page, int size,
                                     LocalDate usageFrom, LocalDate usageTo,
                                     List<CredentialRow> rows) { }

    public record CredentialRow(String userId, String email, String providerCode,
                                String providerName, String maskedSuffix, String status,
                                boolean selected, String selectedModel,
                                Instant lastVerifiedAt, String lastErrorCode,
                                Instant createdAt, Instant updatedAt, Instant lastUsedAt,
                                long periodSuccessfulTurns, long periodFailedCalls) { }
}
