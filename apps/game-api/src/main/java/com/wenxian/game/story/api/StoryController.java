package com.wenxian.game.story.api;

import com.wenxian.game.auth.AuthService;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.story.application.StoryApplicationService;
import com.wenxian.game.story.model.StoryApiModels.BootstrapResponse;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteRequest;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteResponse;
import com.wenxian.game.story.model.StoryApiModels.CorrectionConfirmRequest;
import com.wenxian.game.story.model.StoryApiModels.CorrectionPreviewRequest;
import com.wenxian.game.story.model.StoryApiModels.CorrectionPreviewResponse;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.SaveSummary;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;
import com.wenxian.game.story.model.StoryApiModels.TurnRequestStatusResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 版本无关的命卷 HTTP 入口。
 *
 * <p>控制器只负责身份、参数校验和用例调用。API 中的 v1 表示契约主版本，和产品发布版本无关；
 * 当前在线命卷统一进入中立 Story 运行时，历史档案不参与玩家请求路由。</p>
 */
@Validated
@RestController
@RequestMapping("/api/v1/story")
public class StoryController {
    private static final String SAVE_ID = "[a-zA-Z0-9-]{8,80}";
    private static final String ENTITY_ID = "[a-zA-Z0-9-]{8,100}";

    private final AuthService auth;
    private final StoryApplicationService stories;

    public StoryController(AuthService auth, StoryApplicationService stories) {
        this.auth = auth;
        this.stories = stories;
    }

    @GetMapping("/bootstrap")
    public BootstrapResponse bootstrap() {
        return stories.bootstrap();
    }

    @PostMapping("/character-routes")
    public CharacterRouteResponse characterRoutes(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody CharacterRouteRequest request) {
        return stories.characterRoutes(user(token), request);
    }

    @PostMapping("/saves")
    public SaveResponse create(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody CreateSaveRequest request) {
        return stories.create(user(token), request);
    }

    @GetMapping("/saves")
    public List<SaveSummary> saves(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token) {
        return stories.list(user(token));
    }

    @GetMapping("/saves/{saveId}")
    public SaveResponse save(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = SAVE_ID) String saveId) {
        return stories.get(user(token), saveId);
    }

    @PostMapping("/saves/{saveId}/turns")
    public SaveResponse turn(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = SAVE_ID) String saveId,
            @Valid @RequestBody TurnRequest request) {
        return stories.takeTurn(user(token), saveId, request);
    }

    @GetMapping("/saves/{saveId}/turn-requests/{requestId}")
    public TurnRequestStatusResponse turnRequestStatus(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = SAVE_ID) String saveId,
            @PathVariable @Size(min = 1, max = 80) String requestId) {
        return stories.turnRequestStatus(user(token), saveId, requestId);
    }

    @PostMapping("/saves/{saveId}/corrections/preview")
    public CorrectionPreviewResponse previewCorrection(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = SAVE_ID) String saveId,
            @Valid @RequestBody CorrectionPreviewRequest request) {
        return stories.previewCorrection(user(token), saveId, request);
    }

    @PostMapping("/saves/{saveId}/corrections/confirm")
    public SaveResponse confirmCorrection(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = SAVE_ID) String saveId,
            @Valid @RequestBody CorrectionConfirmRequest request) {
        return stories.confirmCorrection(user(token), saveId, request);
    }

    @DeleteMapping("/saves/{saveId}")
    public void deleteSave(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = SAVE_ID) String saveId) {
        stories.deleteSave(user(token), saveId);
    }

    @DeleteMapping("/saves/{saveId}/entities/{entityId}")
    public SaveResponse removeEntity(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = SAVE_ID) String saveId,
            @PathVariable @Pattern(regexp = ENTITY_ID) String entityId,
            @RequestParam @Positive long revision) {
        return stories.removeEntity(user(token), saveId, entityId, revision);
    }

    private UserIdentity user(String token) {
        return auth.requireUser(token);
    }
}
