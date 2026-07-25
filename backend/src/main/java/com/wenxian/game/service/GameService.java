package com.wenxian.game.service;

import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.engine.GameCatalog;
import com.wenxian.game.engine.GameEngine;
import com.wenxian.game.engine.AgentTurn;
import com.wenxian.game.engine.NarrativeAgent;
import com.wenxian.game.engine.WorldGenerationService;
import com.wenxian.game.model.ApiModels.CharacterSheetResponse;
import com.wenxian.game.model.ApiModels.CreateSaveRequest;
import com.wenxian.game.model.ApiModels.CurrentSaveResponse;
import com.wenxian.game.model.ApiModels.GameResponse;
import com.wenxian.game.model.ApiModels.TurnRequest;
import com.wenxian.game.model.ApiModels.WorldResponse;
import com.wenxian.game.model.GameState;
import com.wenxian.game.persistence.SaveStore;
import com.wenxian.game.persistence.AgentConversationStore;
import com.wenxian.game.persistence.TurnRequestStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GameService {
    private final SaveStore saveStore;
    private final TurnRequestStore turnRequestStore;
    private final NarrativeAgent agentService;
    private final WorldGenerationService fateGenerationService;
    private final GameEngine gameEngine;
    private final CharacterNameService nameService;
    private final ActionInputGate inputGate;
    private final AgentConversationStore conversationStore;
    private final Map<String, Object> saveLocks = new ConcurrentHashMap<>();

    public GameService(SaveStore saveStore, TurnRequestStore turnRequestStore,
                       NarrativeAgent agentService,
                       WorldGenerationService fateGenerationService, GameEngine gameEngine,
                       CharacterNameService nameService, ActionInputGate inputGate,
                       AgentConversationStore conversationStore) {
        this.saveStore = saveStore;
        this.turnRequestStore = turnRequestStore;
        this.agentService = agentService;
        this.fateGenerationService = fateGenerationService;
        this.gameEngine = gameEngine;
        this.nameService = nameService;
        this.inputGate = inputGate;
        this.conversationStore = conversationStore;
    }

    @Transactional
    public GameResponse create(UserIdentity user, CreateSaveRequest request) {
        if (saveStore.findCurrentForUser(user.userId()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前已有存档，请先继续本世或删除旧存档");
        }
        var validation = nameService.validate(request.playerName());
        if (!validation.valid()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, validation.message());
        String saveId = "save-" + UUID.randomUUID();
        GameState setup = fateGenerationService.generateFates(saveId, validation.normalizedName(),
                request.race(), request.gender(), request.lifeGoal(), request.preference());
        SaveStore.StoredSave saved = saveStore.put(saveId, user.userId(), setup, 0);
        String conversationId = conversationStore.ensure(saveId, fateGenerationService.model());
        conversationStore.append(conversationId, 0, "user", "创建角色：" + conversationStore.json(request),
                null, 0, 0);
        conversationStore.append(conversationId, 0, "assistant", "三份候选命格：" + conversationStore.json(setup.protagonistOptions),
                null, 0, 0);
        return response(saved);
    }

    public CurrentSaveResponse current(UserIdentity user) {
        SaveStore.StoredSave save = saveStore.findCurrentForUser(user.userId());
        return new CurrentSaveResponse(save != null, save == null ? null : response(save));
    }

    public GameResponse get(UserIdentity user, String saveId) {
        return response(requireOwned(user, saveId));
    }

    @Transactional
    public GameResponse start(UserIdentity user, String saveId, String protagonistId) {
        synchronized (lock(saveId)) {
            SaveStore.StoredSave current = requireOwned(user, saveId);
            if (!"setup".equals(current.state.phase)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前存档不在择命阶段");
            }
            try {
                GameState started = GameCatalog.start(current.state, protagonistId);
                SaveStore.StoredSave saved = saveStore.put(saveId, user.userId(), started, current.revision + 1);
                String conversationId = conversationStore.ensure(saveId, fateGenerationService.model());
                conversationStore.append(conversationId, 0, "user", "我选择命格：" + protagonistId, null, 0, 0);
                conversationStore.append(conversationId, 0, "assistant", "命卷开场：" + conversationStore.json(Map.of(
                        "narrative", started.narrative, "choices", started.choices, "player", started.player)),
                        null, 0, 0);
                return response(saved);
            } catch (IllegalArgumentException error) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
            }
        }
    }

    @Transactional
    public GameResponse act(UserIdentity user, String saveId, TurnRequest request) {
        synchronized (lock(saveId)) {
            SaveStore.StoredSave current = requireOwned(user, saveId);
            if (turnRequestStore.exists(request.requestId(), saveId)) return response(current);
            if (request.stateVersion() != current.revision) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "游戏状态已经变化，请刷新当前场景");
            }
            if (!"playing".equals(current.state.phase)) {
                String message = "dead".equals(current.state.phase)
                        ? "本世已终，只能查看纪事或转世重开"
                        : "请先选择主角并开启命卷";
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
            }
            String action = inputGate.normalize(request, current.state);
            String conversationId = conversationStore.ensure(saveId, fateGenerationService.model());
            AgentTurn agentTurn = agentService.resolve(action, current.state,
                    conversationStore.loadHistory(saveId, current.state));
            GameState next = gameEngine.apply(current.state, agentTurn.result());
            if ("USE_ABILITY".equals(request.inputType())) gameEngine.consumeAbility(next, request.targetId());
            SaveStore.StoredSave saved = saveStore.put(saveId, user.userId(), next, current.revision + 1);
            conversationStore.append(conversationId, next.turn, "user", action, null, 0, 0);
            conversationStore.append(conversationId, next.turn, "assistant", agentTurn.assistantMessage(),
                    agentTurn.responseId(), agentTurn.promptTokens(), agentTurn.completionTokens());
            conversationStore.recordTurn(request.requestId(), saveId, conversationId, next.turn, action,
                    current.state, next, agentTurn, fateGenerationService.model());
            turnRequestStore.record(request.requestId(), saveId);
            return response(saved);
        }
    }

    public void delete(UserIdentity user, String saveId) {
        SaveStore.StoredSave save = requireOwned(user, saveId);
        if ("dead".equals(save.state.phase)) saveStore.archiveAndDelete(save);
        else saveStore.delete(saveId, user.userId());
        saveLocks.remove(saveId);
    }

    public CharacterSheetResponse characterSheet(UserIdentity user, String saveId) {
        GameState state = requirePlaying(user, saveId).state;
        List<GameState.AbilityRecord> talents = state.abilities.stream().filter(value -> "TALENT".equals(value.type)).toList();
        List<GameState.AbilityRecord> techniques = state.abilities.stream().filter(value -> !"TALENT".equals(value.type)).toList();
        return new CharacterSheetResponse(state.player, talents, techniques, state.threads);
    }

    public List<GameState.InventoryItem> inventory(UserIdentity user, String saveId) {
        return List.copyOf(requirePlaying(user, saveId).state.inventory);
    }

    public List<GameState.AbilityRecord> abilities(UserIdentity user, String saveId) {
        return List.copyOf(requirePlaying(user, saveId).state.abilities);
    }

    public List<GameState.CharacterRecord> characters(UserIdentity user, String saveId) {
        return List.copyOf(requirePlaying(user, saveId).state.characters);
    }

    public List<GameState.ChronicleEvent> timeline(UserIdentity user, String saveId) {
        return List.copyOf(requirePlaying(user, saveId).state.events);
    }

    public List<GameState.QuestThread> threads(UserIdentity user, String saveId) {
        return List.copyOf(requirePlaying(user, saveId).state.threads);
    }

    public WorldResponse world(UserIdentity user, String saveId) {
        GameState state = requireOwned(user, saveId).state;
        return new WorldResponse(state.world, state.currentLocation, state.worldYear, state.day, state.minute,
                state.characters, state.threads);
    }

    public Map<String, Object> agentInfo() {
        return Map.of(
                "mode", agentService.mode(),
                "fateGenerator", fateGenerationService.mode(),
                "world", "fixed-canon:v0.1",
                "authoritative", true,
                "deepSeekConfigured", fateGenerationService.configured()
        );
    }

    private SaveStore.StoredSave requireOwned(UserIdentity user, String saveId) {
        SaveStore.StoredSave save = saveStore.findOwned(saveId, user.userId());
        if (save == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "存档不存在");
        return save;
    }

    private SaveStore.StoredSave requirePlaying(UserIdentity user, String saveId) {
        SaveStore.StoredSave save = requireOwned(user, saveId);
        if (!"playing".equals(save.state.phase)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "命卷尚未开启");
        }
        return save;
    }

    private Object lock(String saveId) {
        return saveLocks.computeIfAbsent(saveId, ignored -> new Object());
    }

    private GameResponse response(SaveStore.StoredSave save) {
        GameState copy = gameEngine.copy(save.state);
        return new GameResponse(copy, save.revision, save.updatedAt, copy.choices, agentInfo());
    }
}
