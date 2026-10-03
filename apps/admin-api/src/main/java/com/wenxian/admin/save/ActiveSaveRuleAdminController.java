package com.wenxian.admin.save;

import com.wenxian.admin.AdminAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 同账号可用命卷规则 HTTP 入口；不会执行命卷归档或删除。 */
@RestController
@RequestMapping("/api/admin/active-save-rules")
public class ActiveSaveRuleAdminController {
    private final ActiveSaveRuleApplicationService service;

    public ActiveSaveRuleAdminController(ActiveSaveRuleApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public ActiveSaveRuleApplicationService.ActiveSaveRuleView rule() {
        return service.rule();
    }

    @PutMapping
    public ActiveSaveRuleApplicationService.ActiveSaveRuleView update(
            @RequestBody UpdateActiveSaveRuleRequest request, HttpServletRequest servletRequest) {
        return service.update(request.maximumActiveSaves(), actor(servletRequest));
    }

    private String actor(HttpServletRequest request) {
        Object value = request.getAttribute(AdminAuthFilter.ADMIN_USER_ID_ATTRIBUTE);
        return value instanceof String userId ? userId : "admin-console";
    }

    public record UpdateActiveSaveRuleRequest(int maximumActiveSaves) { }
}
