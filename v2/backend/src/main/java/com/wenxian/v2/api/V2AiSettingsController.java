package com.wenxian.v2.api;

import com.wenxian.v2.account.SharedAccountSessionService;
import com.wenxian.v2.ai.application.PlayerAiPreferenceService;
import com.wenxian.v2.ai.application.PlayerAiPreferenceService.Settings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/ai")
public class V2AiSettingsController {
    private final SharedAccountSessionService sessions;
    private final PlayerAiPreferenceService preferences;

    public V2AiSettingsController(SharedAccountSessionService sessions, PlayerAiPreferenceService preferences) {
        this.sessions = sessions;
        this.preferences = preferences;
    }

    @GetMapping("/preference")
    public Settings settings(@CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token) {
        return preferences.settings(sessions.requireAccount(token).userId());
    }

    @PutMapping("/preference")
    public Settings choose(@CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
                           @Valid @RequestBody PreferenceRequest request) {
        return preferences.choose(sessions.requireAccount(token).userId(), request.credentialSource(),
                request.modelCode());
    }

    @PutMapping("/credential")
    public Settings credential(@CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
                               @Valid @RequestBody CredentialRequest request) {
        return preferences.saveCredential(sessions.requireAccount(token).userId(), request.apiKey(),
                request.modelCode());
    }

    @DeleteMapping("/credential")
    public Settings delete(@CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token) {
        return preferences.deleteCredential(sessions.requireAccount(token).userId());
    }

    public record PreferenceRequest(@NotBlank @Size(max = 24) String credentialSource,
                                    @NotBlank @Size(max = 120) String modelCode) {
    }

    public record CredentialRequest(@NotBlank @Size(max = 512) String apiKey,
                                    @NotBlank @Size(max = 120) String modelCode) {
    }
}
