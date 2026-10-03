package com.wenxian.admin.byok;

import com.wenxian.admin.AdminAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 玩家自备推演服务 HTTP 入口不接触凭证明文，只映射全局策略开关。 */
@RestController
@RequestMapping("/api/admin/byok-rules")
public class ByokRuleAdminController {
    private final ByokRuleApplicationService service;

    public ByokRuleAdminController(ByokRuleApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public ByokRuleApplicationService.ByokRuleView rule() {
        return service.rule();
    }

    @PutMapping
    public ByokRuleApplicationService.ByokRuleView update(@RequestBody UpdateByokRuleRequest request,
                                                           HttpServletRequest servletRequest) {
        return service.update(request.enabled(), actor(servletRequest));
    }

    private String actor(HttpServletRequest request) {
        Object value = request.getAttribute(AdminAuthFilter.ADMIN_USER_ID_ATTRIBUTE);
        return value instanceof String userId ? userId : "admin-console";
    }

    public record UpdateByokRuleRequest(boolean enabled) { }
}
