package com.wenxian.admin.history;

import java.time.Instant;
import java.util.List;

/** 管理台历史查询持久化端口；只读取问题诊断和管理操作审计，不修改任何业务状态。 */
public interface AdminHistoryRepository {
    HistoryPage<OperationProblemView> findProblems(HistoryFilter filter);

    HistoryPage<AdminAuditLogView> findAuditLogs(HistoryFilter filter);

    List<String> findAuditOperations();

    record HistoryFilter(String search, String type, Instant from, Instant to, int page, int size) { }

    record HistoryPage<T>(long total, int page, int size, List<T> rows) { }
}
