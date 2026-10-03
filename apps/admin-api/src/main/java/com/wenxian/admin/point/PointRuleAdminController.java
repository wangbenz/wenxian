package com.wenxian.admin.point;

import com.wenxian.admin.AdminAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 推演点规则 HTTP 入口；Controller 只转换参数，校验和历史余额保护由应用服务负责。 */
@RestController
@RequestMapping("/api/admin/point-rules")
public class PointRuleAdminController {
    private final PointRuleApplicationService service;

    public PointRuleAdminController(PointRuleApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public PointRuleView rule() {
        return service.rule();
    }

    @PutMapping
    public PointRuleView update(@RequestBody UpdatePointRuleRequest request, HttpServletRequest servletRequest) {
        return service.update(request.openingGrant(), request.depletedMessage(), actor(servletRequest));
    }

    private String actor(HttpServletRequest request) {
        Object value = request.getAttribute(AdminAuthFilter.ADMIN_USER_ID_ATTRIBUTE);
        return value instanceof String userId ? userId : "admin-console";
    }

    public record UpdatePointRuleRequest(int openingGrant, String depletedMessage) { }
}
