package com.wenxian.game.api;

import com.wenxian.game.auth.AuthService;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.engine.GameCatalog;
import com.wenxian.game.model.ApiModels.BootstrapResponse;
import com.wenxian.game.model.ApiModels.CharacterSheetResponse;
import com.wenxian.game.model.ApiModels.CreateSaveRequest;
import com.wenxian.game.model.ApiModels.CurrentSaveResponse;
import com.wenxian.game.model.ApiModels.GameResponse;
import com.wenxian.game.model.ApiModels.GenerateNameRequest;
import com.wenxian.game.model.ApiModels.GenerateNameResponse;
import com.wenxian.game.model.ApiModels.SelectProtagonistRequest;
import com.wenxian.game.model.ApiModels.SelectTalentRequest;
import com.wenxian.game.model.ApiModels.TurnRequest;
import com.wenxian.game.model.ApiModels.ValidateNameRequest;
import com.wenxian.game.model.ApiModels.ValidateNameResponse;
import com.wenxian.game.model.ApiModels.WorldResponse;
import com.wenxian.game.model.ApiModels.ReleaseNoteView;
import com.wenxian.game.model.ApiModels.AiSettingsView;
import com.wenxian.game.model.ApiModels.SaveAiCredentialRequest;
import com.wenxian.game.model.ApiModels.SaveListResponse;
import com.wenxian.game.model.ApiModels.AbandonSaveRequest;
import com.wenxian.game.model.GameState;
import com.wenxian.game.service.CharacterNameService;
import com.wenxian.game.service.GameService;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.service.ReleaseNoteService;
import com.wenxian.game.service.PlayerAiService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1")
public class GameController {
    private final GameService gameService;
    private final AuthService authService;
    private final CharacterNameService nameService;
    private final ReleaseNoteService releaseNotes;
    private final PlayerAiService playerAiService;
    private final GameVersionService gameVersionService;

    public GameController(GameService gameService, AuthService authService, CharacterNameService nameService,
                          ReleaseNoteService releaseNotes, PlayerAiService playerAiService,
                          GameVersionService gameVersionService) {
        this.gameService = gameService;
        this.authService = authService;
        this.nameService = nameService;
        this.releaseNotes = releaseNotes;
        this.playerAiService = playerAiService;
        this.gameVersionService = gameVersionService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "agent", gameService.agentInfo(), "persistence", "mysql");
    }

    @GetMapping("/game/bootstrap")
    public BootstrapResponse bootstrap() {
        return GameCatalog.bootstrap(gameVersionService.current());
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

    @GetMapping("/ai/settings")
    public AiSettingsView aiSettings(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token) {
        var settings = playerAiService.settings(user(token).userId());
        return new AiSettingsView(settings.turnLimit(), settings.usedTurns(), settings.remainingTurns(),
                settings.customApiEnabled(), settings.usingCustomApi(), settings.credentialConfigured(),
                settings.maskedSuffix(), settings.credentialStatus(), settings.lastVerifiedAt());
    }

    @PutMapping("/ai/credential")
    public AiSettingsView saveAiCredential(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody SaveAiCredentialRequest request) {
        var settings = playerAiService.saveCredential(user(token).userId(), request.apiKey());
        return new AiSettingsView(settings.turnLimit(), settings.usedTurns(), settings.remainingTurns(),
                settings.customApiEnabled(), settings.usingCustomApi(), settings.credentialConfigured(),
                settings.maskedSuffix(), settings.credentialStatus(), settings.lastVerifiedAt());
    }

    @DeleteMapping("/ai/credential")
    public void deleteAiCredential(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token) {
        playerAiService.deleteCredential(user(token).userId());
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

    @PostMapping("/saves")
    public GameResponse createSave(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @Valid @RequestBody CreateSaveRequest request) {
        return gameService.create(user(token), request);
    }

    @GetMapping("/saves/current")
    public CurrentSaveResponse currentSave(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token) {
        return gameService.current(user(token));
    }

    @GetMapping("/saves")
    public SaveListResponse saves(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token) {
        return gameService.list(user(token));
    }

    @GetMapping("/saves/{saveId}")
    public GameResponse getSave(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return gameService.get(user(token), saveId);
    }

    @DeleteMapping("/saves/{saveId}")
    public void deleteSave(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        gameService.delete(user(token), saveId);
    }

    @PostMapping("/saves/{saveId}/restart")
    public GameResponse restartSave(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId,
            @Valid @RequestBody CreateSaveRequest request) {
        return gameService.restart(user(token), saveId, request);
    }

    @PostMapping("/saves/{saveId}/abandon")
    public void abandonSave(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId,
            @Valid @RequestBody AbandonSaveRequest request) {
        gameService.abandon(user(token), saveId, request.playerName());
    }

    @PostMapping("/saves/{saveId}/protagonist")
    public GameResponse selectProtagonist(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId,
            @Valid @RequestBody SelectProtagonistRequest request) {
        return gameService.start(user(token), saveId, request.candidateId());
    }

    @PostMapping("/saves/{saveId}/talents/reroll")
    public GameResponse rerollTalents(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return gameService.rerollTalents(user(token), saveId);
    }

    @PostMapping("/saves/{saveId}/talents/select")
    public GameResponse selectTalent(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId,
            @Valid @RequestBody SelectTalentRequest request) {
        return gameService.selectTalent(user(token), saveId, request.talentId());
    }

    @PostMapping("/saves/{saveId}/talents/skip")
    public GameResponse skipTalent(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return gameService.skipTalent(user(token), saveId);
    }

    @PostMapping("/saves/{saveId}/turns")
    public GameResponse takeTurn(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId,
            @Valid @RequestBody TurnRequest request) {
        return gameService.act(user(token), saveId, request);
    }

    @GetMapping("/saves/{saveId}/scene")
    public GameResponse scene(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return gameService.get(user(token), saveId);
    }

    @GetMapping("/saves/{saveId}/state")
    public GameResponse state(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return gameService.get(user(token), saveId);
    }

    @GetMapping("/saves/{saveId}/character-sheet")
    public CharacterSheetResponse characterSheet(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return gameService.characterSheet(user(token), saveId);
    }

    @GetMapping("/saves/{saveId}/inventory")
    public List<GameState.InventoryItem> inventory(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return gameService.inventory(user(token), saveId);
    }

    @GetMapping("/saves/{saveId}/abilities")
    public List<GameState.AbilityRecord> abilities(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return gameService.abilities(user(token), saveId);
    }

    @GetMapping("/saves/{saveId}/characters")
    public List<GameState.CharacterRecord> characters(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return gameService.characters(user(token), saveId);
    }

    @GetMapping("/saves/{saveId}/timeline")
    public List<GameState.ChronicleEvent> timeline(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return gameService.timeline(user(token), saveId);
    }

    @GetMapping("/saves/{saveId}/threads")
    public List<GameState.QuestThread> threads(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return gameService.threads(user(token), saveId);
    }

    @GetMapping("/saves/{saveId}/world")
    public WorldResponse world(
            @CookieValue(name = AuthService.COOKIE_NAME, required = false) String token,
            @PathVariable @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId) {
        return gameService.world(user(token), saveId);
    }

    private UserIdentity user(String token) {
        return authService.requireUser(token);
    }
}
