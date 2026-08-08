package com.wenxian.game.story.api;

import com.wenxian.game.auth.AuthService;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.story.application.StoryApplicationService;
import com.wenxian.game.story.model.StoryApiModels.BootstrapResponse;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteRequest;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteResponse;
import com.wenxian.game.story.model.StoryApiModels.CorrectionRequest;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.SaveSummary;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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
 * 旧版命卷由后端适配器处理，H5 不需要识别底层表或 Java 包。</p>
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

    @PostMapping("/saves/{saveId}/corrections")
    public SaveResponse correct(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = SAVE_ID) String saveId,
            @Valid @RequestBody CorrectionRequest request) {
        return stories.correct(user(token), saveId, request);
    }

    @DeleteMapping("/saves/{saveId}")
    public void archive(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = SAVE_ID) String saveId) {
        stories.archive(user(token), saveId);
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
