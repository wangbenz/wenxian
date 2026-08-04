package com.wenxian.game.v014.api;

import com.wenxian.game.auth.AuthService;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.service.ActiveSavePolicyService;
import com.wenxian.game.v014.model.V014ApiModels.BootstrapResponse;
import com.wenxian.game.v014.model.V014ApiModels.CharacterRouteRequest;
import com.wenxian.game.v014.model.V014ApiModels.CharacterRouteResponse;
import com.wenxian.game.v014.model.V014ApiModels.CorrectionRequest;
import com.wenxian.game.v014.model.V014ApiModels.CreateSaveRequest;
import com.wenxian.game.v014.model.V014ApiModels.GenerationSettings;
import com.wenxian.game.v014.model.V014ApiModels.GenerationSettingsRequest;
import com.wenxian.game.v014.model.V014ApiModels.ProviderCredentialRequest;
import com.wenxian.game.v014.model.V014ApiModels.SaveResponse;
import com.wenxian.game.v014.model.V014ApiModels.SaveSummary;
import com.wenxian.game.v014.model.V014ApiModels.TurnRequest;
import com.wenxian.game.v014.service.V014GameService;
import com.wenxian.game.v014.service.V014CharacterRouteService;
import com.wenxian.game.v014.service.V014ModelPreferenceService;
import com.wenxian.game.v014.service.V014WorldCatalog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/v014")
public class V014GameController {
    private final AuthService auth;
    private final V014GameService game;
    private final V014WorldCatalog worlds;
    private final V014ModelPreferenceService models;
    private final V014CharacterRouteService characterRoutes;
    private final GameVersionService version;
    private final ActiveSavePolicyService savePolicy;

    public V014GameController(AuthService auth, V014GameService game,
                              V014WorldCatalog worlds, V014ModelPreferenceService models,
                              V014CharacterRouteService characterRoutes, GameVersionService version,
                              ActiveSavePolicyService savePolicy) {
        this.auth = auth;
        this.game = game;
        this.worlds = worlds;
        this.models = models;
        this.characterRoutes = characterRoutes;
        this.version = version;
        this.savePolicy = savePolicy;
    }

    @PostMapping("/character-routes")
    public CharacterRouteResponse characterRoutes(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody CharacterRouteRequest request) {
        return characterRoutes.generate(user(token), request);
    }

    @GetMapping("/bootstrap")
    public BootstrapResponse bootstrap() {
        return new BootstrapResponse(version.current(), savePolicy.maximum(), worlds.all(),
                List.of("人族", "妖族", "灵族", "自定种族"),
                List.of("凡俗出身", "宗门弟子", "世家之后", "王族血脉", "妖王后代", "器物化灵", "自由描述"));
    }

    @PostMapping("/saves")
    public SaveResponse create(@CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
                               @Valid @RequestBody CreateSaveRequest request) {
        return game.create(user(token), request);
    }

    @GetMapping("/saves")
    public List<SaveSummary> saves(@CookieValue(name = AuthService.COOKIE_NAME, required = false) String token) {
        return game.list(user(token));
    }

    @GetMapping("/saves/{saveId}")
    public SaveResponse save(@CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
                             @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return game.get(user(token), saveId);
    }

    @PostMapping("/saves/{saveId}/turns")
    public SaveResponse turn(@CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
                             @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId,
                             @Valid @RequestBody TurnRequest request) {
        return game.takeTurn(user(token), saveId, request.action(), request.requestId());
    }

    @PostMapping("/saves/{saveId}/corrections")
    public SaveResponse correct(@CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
                                @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId,
                                @Valid @RequestBody CorrectionRequest request) {
        return game.correct(user(token), saveId, request.instruction());
    }

    @DeleteMapping("/saves/{saveId}")
    public void archive(@CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
                        @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        game.archive(user(token), saveId);
    }

    @DeleteMapping("/saves/{saveId}/items/{itemId}")
    public SaveResponse removeItem(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,100}") String itemId,
            @RequestParam long revision) {
        return game.removeItem(user(token), saveId, itemId, revision);
    }

    @DeleteMapping("/saves/{saveId}/characters/{characterId}")
    public SaveResponse untrackCharacter(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,100}") String characterId,
            @RequestParam long revision) {
        return game.untrackCharacter(user(token), saveId, characterId, revision);
    }

    @GetMapping("/generation-settings")
    public GenerationSettings generationSettings(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token) {
        return models.settings(user(token).userId());
    }

    @PutMapping("/generation-settings")
    public GenerationSettings generationSettings(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody GenerationSettingsRequest request) {
        return models.saveSettings(user(token).userId(), request.mode(), request.provider(), request.model());
    }

    @PutMapping("/generation-settings/providers/{provider}/credential")
    public GenerationSettings saveCredential(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[A-Z0-9_]{2,32}") String provider,
            @Valid @RequestBody ProviderCredentialRequest request) {
        return models.saveCredential(user(token).userId(), provider, request.model(), request.apiKey());
    }

    @DeleteMapping("/generation-settings/providers/{provider}/credential")
    public GenerationSettings deleteCredential(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[A-Z0-9_]{2,32}") String provider) {
        return models.deleteCredential(user(token).userId(), provider);
    }

    private UserIdentity user(String token) { return auth.requireUser(token); }
}
