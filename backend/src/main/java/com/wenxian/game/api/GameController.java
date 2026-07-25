package com.wenxian.game.api;

import com.wenxian.game.model.ApiModels.ActionRequest;
import com.wenxian.game.model.ApiModels.GameResponse;
import com.wenxian.game.model.ApiModels.ResetRequest;
import com.wenxian.game.service.GameService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/api")
public class GameController {
    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "agent", "java-rule-agent-v1", "persistence", "json-event-snapshot");
    }

    @GetMapping("/game")
    public GameResponse getGame(
            @RequestParam @Pattern(regexp = "[a-zA-Z0-9-]{8,80}") String saveId
    ) {
        return gameService.get(saveId);
    }

    @PostMapping("/game/action")
    public GameResponse act(@Valid @RequestBody ActionRequest request) {
        return gameService.act(
                request.saveId(),
                request.actionId() == null ? "" : request.actionId(),
                request.freeText() == null ? "" : request.freeText()
        );
    }

    @PostMapping("/game/reset")
    public GameResponse reset(@Valid @RequestBody ResetRequest request) {
        return gameService.reset(request.saveId());
    }
}
