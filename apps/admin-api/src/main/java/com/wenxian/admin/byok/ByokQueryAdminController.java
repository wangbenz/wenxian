package com.wenxian.admin.byok;

import com.wenxian.admin.byok.ByokAdminViews.ConversionView;
import com.wenxian.admin.byok.ByokAdminViews.CredentialPageView;
import com.wenxian.admin.byok.ByokAdminViews.CredentialSummaryView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 玩家自备服务转化与脱敏凭证台账 HTTP 入口。 */
@RestController
@RequestMapping("/api/admin/byok")
public class ByokQueryAdminController {
    private final ByokQueryService service;

    public ByokQueryAdminController(ByokQueryService service) {
        this.service = service;
    }

    @GetMapping("/conversion")
    public ConversionView conversion(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cohortFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cohortTo,
            @RequestParam(defaultValue = "14") int windowDays) {
        return service.conversion(cohortFrom, cohortTo, windowDays);
    }

    @GetMapping("/credentials/summary")
    public CredentialSummaryView credentialSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate usageFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate usageTo) {
        return service.credentialSummary(usageFrom, usageTo);
    }

    @GetMapping("/credentials")
    public CredentialPageView credentials(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String provider,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(required = false) Boolean selected,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate usageFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate usageTo) {
        return service.credentials(search, provider, status, selected, page, size, usageFrom, usageTo);
    }
}
