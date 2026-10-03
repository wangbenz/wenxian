package com.wenxian.game.api;

import com.wenxian.game.auth.AuthService;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.service.CharacterNameService;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.service.ReleaseNoteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.wenxian.game.api.PlatformApiModels.*;

/**
 * 与故事引擎无关的平台 HTTP 入口。
 *
 * <p>这里只承载健康检查、公告和姓名工具。命卷创建、读取、推进与推演设置必须经由
 * {@code /api/v1/story}，不再暴露早期 {@code /api/v1/saves} 运行链。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class PlatformController {
    private final AuthService authService;
    private final CharacterNameService nameService;
    private final ReleaseNoteService releaseNotes;
    private final GameVersionService versions;

    public PlatformController(AuthService authService, CharacterNameService nameService,
                              ReleaseNoteService releaseNotes, GameVersionService versions) {
        this.authService = authService;
        this.nameService = nameService;
        this.releaseNotes = releaseNotes;
        this.versions = versions;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "wenxian-story", "version", versions.current(),
                "persistence", "mysql");
    }

    @GetMapping("/release-notes")
    public List<ReleaseNoteView> releaseNotes(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token) {
        return releaseNotes.published(user(token).userId());
    }

    @GetMapping("/release-notes/public-latest")
    public ReleaseNoteView latestPublicReleaseNote() {
        return releaseNotes.latestPublicAnnouncement();
    }

    @GetMapping("/release-notes/pending")
    public ReleaseNoteView pendingReleaseNote(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token) {
        return releaseNotes.pendingAnnouncement(user(token).userId());
    }

    @PostMapping("/release-notes/{releaseId}/read")
    public void markReleaseNoteRead(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String releaseId) {
        releaseNotes.markRead(user(token).userId(), releaseId);
    }

    @PostMapping("/character-names/generate")
    public GenerateNameResponse generateName(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody GenerateNameRequest request) {
        authService.requireUser(token);
        return nameService.generate(request.race(), request.gender(), request.exclude());
    }

    @PostMapping("/character-names/validate")
    public ValidateNameResponse validateName(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody ValidateNameRequest request) {
        authService.requireUser(token);
        return nameService.validate(request.name());
    }

    private UserIdentity user(String token) {
        return authService.requireUser(token);
    }

}
