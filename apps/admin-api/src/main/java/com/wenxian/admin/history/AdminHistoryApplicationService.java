package com.wenxian.admin.history;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * 历史查询应用服务，统一校验筛选、日期和分页边界。
 *
 * <p>默认不施加近 7 天、14 天或 100 条的业务截断；调用方可以不传日期查看全部历史。
 * 页面大小仍受上限保护，避免一次请求把完整诊断表载入内存。</p>
 */
@Service
public class AdminHistoryApplicationService {
    private final AdminHistoryRepository repository;

    public AdminHistoryApplicationService(AdminHistoryRepository repository) {
        this.repository = repository;
    }

    public AdminHistoryRepository.HistoryPage<OperationProblemView> problems(
            String search, String category, String from, String to, int page, int size) {
        return repository.findProblems(filter(search, normalizedCode(category, "问题分类"), from, to, page, size));
    }

    public AdminHistoryRepository.HistoryPage<AdminAuditLogView> auditLogs(
            String search, String operation, String from, String to, int page, int size) {
        return repository.findAuditLogs(filter(search, normalizedCode(operation, "操作类型"), from, to, page, size));
    }

    public List<String> auditOperations() {
        return repository.findAuditOperations();
    }

    private AdminHistoryRepository.HistoryFilter filter(String search, String type, String from, String to,
                                                        int page, int size) {
        String safeSearch = search == null ? "" : search.strip();
        if (safeSearch.length() > 160) {
            throw badRequest("搜索内容不能超过 160 个字符");
        }
        if (page < 0) throw badRequest("页码不能小于 0");
        if (size < 1 || size > 100) throw badRequest("每页数量需在 1 至 100 之间");
        Instant start = instant(from, "开始时间");
        Instant end = instant(to, "结束时间");
        if (start != null && end != null && start.isAfter(end)) {
            throw badRequest("开始时间不能晚于结束时间");
        }
        return new AdminHistoryRepository.HistoryFilter(safeSearch, type, start, end, page, size);
    }

    private String normalizedCode(String value, String label) {
        String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.isEmpty() && !normalized.matches("[A-Z0-9_:-]{1,120}")) {
            throw badRequest(label + "格式不正确");
        }
        return normalized;
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
}
