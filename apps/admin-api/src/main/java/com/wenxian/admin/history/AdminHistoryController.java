package com.wenxian.admin.history;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 历史查询 HTTP 入口；旧列表接口继续保留，迁移后的页面使用这里的分页契约。 */
@RestController
@RequestMapping("/api/admin/history")
public class AdminHistoryController {
    private final AdminHistoryApplicationService service;

    public AdminHistoryController(AdminHistoryApplicationService service) {
        this.service = service;
    }

    @GetMapping("/problems")
    public AdminHistoryRepository.HistoryPage<OperationProblemView> problems(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.problems(search, category, from, to, page, size);
    }

    @GetMapping("/audit-logs")
    public AdminHistoryRepository.HistoryPage<AdminAuditLogView> auditLogs(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String operation,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.auditLogs(search, operation, from, to, page, size);
    }

    @GetMapping("/audit-logs/operations")
    public List<String> auditOperations() {
        return service.auditOperations();
    }
}
