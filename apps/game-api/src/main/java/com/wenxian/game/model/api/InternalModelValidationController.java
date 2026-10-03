package com.wenxian.game.model.api;

import com.wenxian.game.model.application.ModelValidationApplicationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 仅供管理台后端调用的模型验证入口；共享令牌不返回前端，也不得写日志。 */
@RestController
@RequestMapping("/api/internal/model-validation")
public class InternalModelValidationController {
    private final ModelValidationApplicationService service;
    private final String token;

    public InternalModelValidationController(ModelValidationApplicationService service,
                                             @Value("${wenxian.internal.model-validation-token:}") String token) {
        this.service = service;
        this.token = token == null ? "" : token.strip();
    }

    @PostMapping("/{providerCode}/{modelCode}")
    public ModelValidationApplicationService.Result validate(
            @PathVariable String providerCode,
            @PathVariable String modelCode,
            @RequestBody ValidationRequest request,
            @RequestHeader(value = "X-Wenxian-Model-Validation-Token", defaultValue = "") String suppliedToken) {
        authorize(suppliedToken);
        return service.validate(providerCode, modelCode, request.checkedBy());
    }

    private void authorize(String suppliedToken) {
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型验证内部通道尚未配置");
        }
        boolean matches = MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                (suppliedToken == null ? "" : suppliedToken).getBytes(StandardCharsets.UTF_8));
        if (!matches) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "模型验证内部令牌无效");
    }

    public record ValidationRequest(String checkedBy) { }
}
