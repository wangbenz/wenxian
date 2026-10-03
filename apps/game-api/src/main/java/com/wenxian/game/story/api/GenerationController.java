package com.wenxian.game.story.api;

import com.wenxian.game.auth.AuthService;
import com.wenxian.game.story.application.GenerationApplicationService;
import com.wenxian.game.story.model.GenerationApiModels.CredentialRequest;
import com.wenxian.game.story.model.GenerationApiModels.SettingsRequest;
import com.wenxian.game.story.model.GenerationApiModels.SettingsResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 版本无关的推演设置 HTTP 入口。
 *
 * <p>所有操作必须先验证登录身份；玩家密钥仅写入后端加密存储，响应永远只返回掩码后缀。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/generation")
public class GenerationController {
    private final AuthService auth;
    private final GenerationApplicationService generation;

    public GenerationController(AuthService auth, GenerationApplicationService generation) {
        this.auth = auth;
        this.generation = generation;
    }

    @GetMapping("/settings")
    public SettingsResponse settings(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token) {
        return generation.settings(userId(token));
    }

    @PutMapping("/settings")
    public SettingsResponse saveSettings(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody SettingsRequest request) {
        return generation.saveSettings(userId(token), request);
    }

    @PutMapping("/providers/{provider}/credential")
    public SettingsResponse saveCredential(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[A-Z0-9_]{2,32}") String provider,
            @Valid @RequestBody CredentialRequest request) {
        return generation.saveCredential(userId(token), provider, request);
    }

    @DeleteMapping("/providers/{provider}/credential")
    public SettingsResponse deleteCredential(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[A-Z0-9_]{2,32}") String provider) {
        return generation.deleteCredential(userId(token), provider);
    }

    private String userId(String token) {
        return auth.requireUser(token).userId();
    }
}
