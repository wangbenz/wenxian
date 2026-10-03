package com.wenxian.admin.model;

import com.wenxian.admin.AdminAuthFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 模型库 HTTP 入口只转换请求与响应，模型停用和路由保护由应用服务裁决。 */
@RestController
@RequestMapping("/api/admin/generation-providers")
public class ModelCatalogAdminController {
    private final ModelCatalogApplicationService service;
    private final GameModelValidationClient validation;

    public ModelCatalogAdminController(ModelCatalogApplicationService service,
                                       GameModelValidationClient validation) {
        this.service = service;
        this.validation = validation;
    }

    @GetMapping
    public List<ModelCatalogProviderView> providers() {
        return service.providers();
    }

    @PostMapping("/{providerCode}")
    public Map<String, Object> updateProvider(@PathVariable String providerCode,
                                              @RequestBody UpdateProviderRequest request) {
        service.updateProvider(providerCode, request.displayName(), request.baseUrl(), request.enabled(),
                request.userKeyEnabled(), request.apiKey());
        return Map.of("ok", true);
    }

    @PostMapping("/{providerCode}/models/{modelCode}")
    public Map<String, Object> updateModel(@PathVariable String providerCode,
                                           @PathVariable String modelCode,
                                           @RequestBody UpdateModelRequest request) {
        service.updateModel(providerCode, modelCode, request.displayName(), request.apiModelName(),
                request.capabilityCodes(), request.contextWindow(), request.maximumOutputTokens(),
                request.concurrencyLimit(), request.platformEnabled(), request.userSelectable(),
                request.applyToAllTasks());
        return Map.of("ok", true);
    }

    @PostMapping("/{providerCode}/models/{modelCode}/validation")
    public GameModelValidationClient.ValidationResult validateModel(
            @PathVariable String providerCode,
            @PathVariable String modelCode,
            @RequestAttribute(AdminAuthFilter.ADMIN_USER_ID_ATTRIBUTE) String adminUserId) {
        return validation.validate(providerCode, modelCode, adminUserId);
    }

    /** 空密钥表示保留现有密钥，防止管理台读取后再回传密钥明文。 */
    public record UpdateProviderRequest(String displayName, String baseUrl, boolean enabled,
                                        boolean userKeyEnabled, String apiKey) { }

    /** 批量应用只切换已有平台任务的主模型，不修改已经配置的回退链。 */
    public record UpdateModelRequest(String displayName, String apiModelName, List<String> capabilityCodes,
                                     int contextWindow, int maximumOutputTokens, int concurrencyLimit,
                                     boolean platformEnabled, boolean userSelectable,
                                     boolean applyToAllTasks) { }
}
