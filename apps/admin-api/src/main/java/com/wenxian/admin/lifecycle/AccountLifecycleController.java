package com.wenxian.admin.lifecycle;

import com.wenxian.admin.lifecycle.AccountLifecycleViews.DeletionAuditItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.DeletionJobItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.LifecycleEventItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.NoticeItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.Overview;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.Page;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.RuntimeStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 数据生命周期只读 HTTP 入口；首版不提供强制删除、重试或开关修改。 */
@RestController
@RequestMapping("/api/admin/lifecycle")
public class AccountLifecycleController {
    private final AccountLifecycleApplicationService service;

    public AccountLifecycleController(AccountLifecycleApplicationService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public Overview overview() {
        return service.overview();
    }

    @GetMapping("/notices")
    public Page<NoticeItem> notices(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.notices(status, page, size);
    }

    @GetMapping("/deletion-jobs")
    public Page<DeletionJobItem> deletionJobs(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String reason,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.deletionJobs(status, reason, page, size);
    }

    @GetMapping("/audits")
    public Page<DeletionAuditItem> deletionAudits(
            @RequestParam(defaultValue = "") String reason,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.deletionAudits(reason, from, to, page, size);
    }

    @GetMapping("/events")
    public Page<LifecycleEventItem> lifecycleEvents(
            @RequestParam(defaultValue = "") String eventType,
            @RequestParam(defaultValue = "") String reason,
            @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.lifecycleEvents(eventType, reason, from, to, page, size);
    }

    @GetMapping("/runtime-status")
    public RuntimeStatus runtimeStatus() {
        return service.runtimeStatus();
    }
}
