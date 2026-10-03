package com.wenxian.admin.plan;

import com.wenxian.admin.AdminAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/** 三档方案 HTTP 入口只映射请求；草稿并发、发布证据和历史不可变规则由应用服务负责。 */
@RestController
@RequestMapping("/api/admin/service-plans")
public class ServicePlanAdminController {
    private final ServicePlanApplicationService service;

    public ServicePlanAdminController(ServicePlanApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ServicePlanView> plans() {
        return service.plans();
    }

    @GetMapping("/{planId}")
    public ServicePlanView plan(@PathVariable String planId) {
        return service.plan(planId);
    }

    @PostMapping("/drafts")
    public ServicePlanView createDraft(@RequestBody(required = false) CreateDraftRequest request,
                                       HttpServletRequest servletRequest) {
        return service.createDraft(request == null ? null : request.basedOnVersionId(), actor(servletRequest));
    }

    @PostMapping("/{planId}/clone")
    public ServicePlanView cloneVersion(@PathVariable String planId, HttpServletRequest servletRequest) {
        return service.createDraft(planId, actor(servletRequest));
    }

    @PutMapping("/{planId}")
    public ServicePlanView saveDraft(@PathVariable String planId, @RequestBody SaveDraftRequest request) {
        List<ServicePlanApplicationService.TierCommand> tiers = request.tiers() == null ? null
                : request.tiers().stream().map(TierRequest::command).toList();
        List<ServicePlanApplicationService.TaskRouteCommand> routes = request.taskRoutes() == null ? null
                : request.taskRoutes().stream().map(TaskRouteRequest::command).toList();
        return service.saveDraft(planId, new ServicePlanApplicationService.SaveDraftCommand(
                request.expectedRevision(), request.defaultTierCode(), request.changeNote(), tiers, routes));
    }

    @PostMapping("/{planId}/publish")
    public ServicePlanView publish(@PathVariable String planId, @RequestBody PublishRequest request,
                                   HttpServletRequest servletRequest) {
        return service.publish(planId, request.expectedRevision(), actor(servletRequest));
    }

    private static String actor(HttpServletRequest request) {
        Object value = request.getAttribute(AdminAuthFilter.ADMIN_USER_ID_ATTRIBUTE);
        return value instanceof String userId ? userId : "admin-console";
    }

    public record CreateDraftRequest(String basedOnVersionId) { }
    public record PublishRequest(long expectedRevision) { }
    public record SaveDraftRequest(long expectedRevision, String defaultTierCode, String changeNote,
                                   List<TierRequest> tiers, List<TaskRouteRequest> taskRoutes) { }

    public record TierRequest(String tierCode, String displayName, String description, int pointCost,
                              boolean enabled, String primaryProviderCode, String primaryModelCode,
                              String fallbackProviderCode, String fallbackModelCode, boolean fallbackEnabled) {
        ServicePlanApplicationService.TierCommand command() {
            return new ServicePlanApplicationService.TierCommand(tierCode, displayName, description, pointCost,
                    enabled, primaryProviderCode, primaryModelCode, fallbackProviderCode, fallbackModelCode,
                    fallbackEnabled);
        }
    }

    public record TaskRouteRequest(String tierCode, String taskCode, String primaryProviderCode,
                                   String primaryModelCode, String fallbackProviderCode, String fallbackModelCode,
                                   BigDecimal temperature, int timeoutSeconds, int maximumRetries,
                                   Integer maximumOutputTokens, boolean disableThinking, boolean fallbackEnabled) {
        ServicePlanApplicationService.TaskRouteCommand command() {
            return new ServicePlanApplicationService.TaskRouteCommand(tierCode, taskCode, primaryProviderCode,
                    primaryModelCode, fallbackProviderCode, fallbackModelCode, temperature, timeoutSeconds,
                    maximumRetries, maximumOutputTokens, disableThinking, fallbackEnabled);
        }
    }
}
