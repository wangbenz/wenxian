package com.wenxian.game.service;

import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.engine.GameCatalog;
import com.wenxian.game.engine.GameEngine;
import com.wenxian.game.engine.AgentTurn;
import com.wenxian.game.engine.ActionAdjudication;
import com.wenxian.game.engine.ConflictAdjudicationService;
import com.wenxian.game.engine.NarrativeAgent;
import com.wenxian.game.engine.RealmProgression;
import com.wenxian.game.engine.WorldGenerationService;
import com.wenxian.game.engine.LocationAuthority;
import com.wenxian.game.engine.CurrencyAuthority;
import com.wenxian.game.model.ApiModels.CharacterSheetResponse;
import com.wenxian.game.model.ApiModels.ActionResolutionView;
import com.wenxian.game.model.ApiModels.CreateSaveRequest;
import com.wenxian.game.model.ApiModels.CurrentSaveResponse;
import com.wenxian.game.model.ApiModels.GameResponse;
import com.wenxian.game.model.ApiModels.SaveListResponse;
import com.wenxian.game.model.ApiModels.SaveSummaryView;
import com.wenxian.game.model.ApiModels.TurnRequest;
import com.wenxian.game.model.ApiModels.WorldResponse;
import com.wenxian.game.model.GameState;
import com.wenxian.game.persistence.SaveStore;
import com.wenxian.game.persistence.AgentConversationStore;
import com.wenxian.game.persistence.GameAnalyticsStore;
import com.wenxian.game.persistence.TurnRequestStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final PlayerAiService playerAiService;
    private final ConflictAdjudicationService adjudicationService;
    private final ActiveSavePolicyService activeSavePolicy;
    private final GameAnalyticsStore analytics;
    private final TransactionTemplate transactions;
    private final Map<String, Object> saveLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> accountLocks = new ConcurrentHashMap<>();

    public GameService(SaveStore saveStore, TurnRequestStore turnRequestStore,
                       NarrativeAgent agentService,
                       WorldGenerationService fateGenerationService, GameEngine gameEngine,
                       CharacterNameService nameService, ActionInputGate inputGate,
                       AgentConversationStore conversationStore, PlayerAiService playerAiService,
                       ConflictAdjudicationService adjudicationService,
                       ActiveSavePolicyService activeSavePolicy,
                       GameAnalyticsStore analytics,
                       TransactionTemplate transactions) {
        this.saveStore = saveStore;
        this.turnRequestStore = turnRequestStore;
        this.agentService = agentService;
        this.fateGenerationService = fateGenerationService;
        this.gameEngine = gameEngine;
        this.nameService = nameService;
        this.inputGate = inputGate;
        this.conversationStore = conversationStore;
        this.playerAiService = playerAiService;
        this.adjudicationService = adjudicationService;
        this.activeSavePolicy = activeSavePolicy;
        this.analytics = analytics;
        this.transactions = transactions;
    }

    public GameResponse create(UserIdentity user, CreateSaveRequest request) {
        synchronized (accountLock(user.userId())) {
            int maximum = activeSavePolicy.maximum();
            if (saveStore.countCurrentForUser(user.userId()) >= maximum) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "当前账号的存活命卷已达上限");
            }
            var validation = nameService.validate(request.playerName());
            if (!validation.valid()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, validation.message());
            PlayerAiService.Route aiRoute = generationRoute(user.userId());
            String saveId = "save-" + UUID.randomUUID();
            GameState setup = fateGenerationService.generateFates(saveId, validation.normalizedName(),
                    request.race(), request.gender(), request.lifeGoal(), request.originPremise(), request.preference(),
                    aiRoute.config(), aiRoute.source());
            setup.difficulty = request.difficulty();
            return transactions.execute(status -> {
                int lockedMaximum = activeSavePolicy.lockMaximum();
                if (saveStore.countCurrentForUser(user.userId()) >= lockedMaximum) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "当前账号的存活命卷已达上限");
                }
                int slotNo = saveStore.nextAvailableSlot(user.userId(), lockedMaximum);
                if (slotNo == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "暂无可用命卷位置");
                SaveStore.StoredSave saved = saveStore.put(saveId, user.userId(), slotNo, setup, 0);
                String conversationId = conversationStore.ensure(saveId, aiRoute.recordedModel());
                conversationStore.append(conversationId, 0, "user", "创建角色：" + conversationStore.json(request),
                        null, 0, 0);
                conversationStore.append(conversationId, 0, "assistant", "三份候选命格：" + conversationStore.json(setup.protagonistOptions),
                        null, 0, 0);
                analytics.record(user.userId(), saveId, "CHARACTER_CREATED", Map.of(
                        "race", request.race(), "gender", request.gender(), "difficulty", request.difficulty(), "slotNo", slotNo));
                return response(saved);
            });
        }
    }

    public SaveListResponse list(UserIdentity user) {
        List<SaveSummaryView> summaries = saveStore.findAllCurrentForUser(user.userId()).stream()
                .filter(save -> !archiveLegacyDeath(save))
                .map(this::repairProgression)
                .map(this::summary)
                .toList();
        return new SaveListResponse(activeSavePolicy.maximum(), summaries);
    }

    public CurrentSaveResponse current(UserIdentity user) {
        SaveStore.StoredSave save = saveStore.findCurrentForUser(user.userId());
        if (save != null && archiveLegacyDeath(save)) save = saveStore.findCurrentForUser(user.userId());
        save = repairProgression(save);
        return new CurrentSaveResponse(save != null, save == null ? null : response(save));
    }

    public GameResponse get(UserIdentity user, String saveId) {
        return response(requireOwned(user, saveId));
    }

    public GameResponse start(UserIdentity user, String saveId, String protagonistId) {
        synchronized (accountLock(user.userId())) {
            SaveStore.StoredSave current = requireOwned(user, saveId);
            if (!"setup".equals(current.state.phase)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前存档不在择命阶段");
            }
            try {
                PlayerAiService.Route aiRoute = generationRoute(user.userId());
                GameState started = GameCatalog.start(current.state, protagonistId);
                LocationAuthority.repairLegacyState(started);
                started.talentOptions = fateGenerationService.generateTalentOptions(
                        started, aiRoute.config(), aiRoute.source());
                return transactions.execute(status -> {
                    requireRevision(user, saveId, current.revision);
                    SaveStore.StoredSave saved = saveStore.put(saveId, user.userId(), current.slotNo, started, current.revision + 1);
                    String conversationId = conversationStore.ensure(saveId, aiRoute.recordedModel());
                    conversationStore.append(conversationId, 0, "user", "我选择命格：" + protagonistId, null, 0, 0);
                    conversationStore.append(conversationId, 0, "assistant", "命卷与候选天赋：" + conversationStore.json(Map.of(
                            "narrative", started.narrative, "talents", started.talentOptions, "player", started.player)),
                            null, 0, 0);
                    analytics.record(user.userId(), saveId, "FATE_SELECTED", Map.of(
                            "candidateId", protagonistId, "difficulty", started.difficulty));
                    return response(saved);
                });
            } catch (IllegalArgumentException error) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
            }
        }
    }

    public GameResponse rerollTalents(UserIdentity user, String saveId) {
        synchronized (accountLock(user.userId())) {
            SaveStore.StoredSave current = requireTalentPhase(user, saveId);
            if (current.state.talentRerolled) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "本世的免费重新推演已经使用");
            }
            PlayerAiService.Route aiRoute = generationRoute(user.userId());
            GameState next = gameEngine.copy(current.state);
            next.talentOptions = fateGenerationService.generateTalentOptions(
                    next, aiRoute.config(), aiRoute.source());
            next.talentRerolled = true;
            return transactions.execute(status -> {
                requireRevision(user, saveId, current.revision);
                return response(saveStore.put(saveId, user.userId(), current.slotNo, next, current.revision + 1));
            });
        }
    }

    @Transactional
    public GameResponse selectTalent(UserIdentity user, String saveId, String talentId) {
        synchronized (accountLock(user.userId())) {
            SaveStore.StoredSave current = requireTalentPhase(user, saveId);
            try {
                GameState next = gameEngine.copy(current.state);
                GameCatalog.enterWorld(next, talentId);
                appendTalentDecision(saveId, next, "我选择先天天赋：" + talentId);
                SaveStore.StoredSave saved = saveStore.put(saveId, user.userId(), current.slotNo, next, current.revision + 1);
                analytics.record(user.userId(), saveId, "WORLD_ENTERED", Map.of("talent", "SELECTED"));
                return response(saved);
            } catch (IllegalArgumentException error) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
            }
        }
    }

    @Transactional
    public GameResponse skipTalent(UserIdentity user, String saveId) {
        synchronized (accountLock(user.userId())) {
            SaveStore.StoredSave current = requireTalentPhase(user, saveId);
            GameState next = gameEngine.copy(current.state);
            GameCatalog.enterWorldWithoutTalent(next);
            appendTalentDecision(saveId, next, "我选择不携带先天天赋");
            SaveStore.StoredSave saved = saveStore.put(saveId, user.userId(), current.slotNo, next, current.revision + 1);
            analytics.record(user.userId(), saveId, "WORLD_ENTERED", Map.of("talent", "SKIPPED"));
            return response(saved);
        }
    }

    public GameResponse act(UserIdentity user, String saveId, TurnRequest request) {
        synchronized (accountLock(user.userId())) {
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
            String conversationId = conversationStore.ensure(saveId, fateGenerationService.model());
            ActionInputGate.Assessment assessment = inputGate.assess(request, current.state);
            if (!assessment.accepted()) {
                return transactions.execute(status -> {
                    conversationStore.append(conversationId, current.state.turn, "user", assessment.action(), null, 0, 0);
                    conversationStore.append(conversationId, current.state.turn, "assistant",
                            conversationStore.json(Map.of("actionResolution", actionResolution(assessment))), null, 0, 0);
                    analytics.record(user.userId(), saveId, "ACTION_REJECTED", Map.of("stage", "INPUT", "turn", current.state.turn));
                    return response(current, actionResolution(assessment));
                });
            }
            String action = assessment.action();
            PlayerAiService.Route aiRoute = playerAiService.route(user.userId());
            playerAiService.assertPublicQuota(user.userId(), aiRoute);
            ActionAdjudication adjudication = adjudicationService.adjudicate(
                    request, action, current.state, aiRoute.config(), aiRoute.source());
            if (!adjudication.accepted()) {
                ActionResolutionView resolution = actionResolution(adjudication.actionResolution());
                return transactions.execute(status -> {
                    conversationStore.append(conversationId, current.state.turn, "user", action, null, 0, 0);
                    conversationStore.append(conversationId, current.state.turn, "assistant",
                            conversationStore.json(Map.of("actionResolution", adjudication.actionResolution())),
                            null, 0, 0);
                    analytics.record(user.userId(), saveId, "ACTION_REJECTED", Map.of(
                            "stage", "ADJUDICATION", "turn", current.state.turn,
                            "reasonCode", adjudication.actionResolution().code(),
                            "intentMode", adjudication.intent().intentMode(),
                            "actionType", adjudication.intent().actionType()));
                    return response(current, resolution);
                });
            }
            AgentTurn agentTurn = agentService.resolve(action, current.state,
                    conversationStore.loadHistory(saveId, current.state), aiRoute.config(), aiRoute.source(), adjudication);
            if (!"ACCEPTED".equals(agentTurn.result().actionResolution().result())) {
                ActionResolutionView resolution = actionResolution(agentTurn.result().actionResolution());
                return transactions.execute(status -> {
                    conversationStore.append(conversationId, current.state.turn, "user", action, null, 0, 0);
                    conversationStore.append(conversationId, current.state.turn, "assistant", agentTurn.assistantMessage(),
                            agentTurn.responseId(), agentTurn.promptTokens(), agentTurn.completionTokens());
                    analytics.record(user.userId(), saveId, "ACTION_REJECTED", Map.of(
                            "stage", "NARRATIVE", "turn", current.state.turn,
                            "reasonCode", agentTurn.result().actionResolution().code()));
                    return response(current, resolution);
                });
            }
            GameState next = gameEngine.apply(current.state, agentTurn.result());
            if (consumesAbilities(adjudication.intent())) {
                adjudication.intent().usedAbilityIds().forEach(abilityId -> gameEngine.consumeAbility(next, abilityId));
            }
            return transactions.execute(status -> {
                SaveStore.StoredSave latest = requireRevision(user, saveId, current.revision);
                if (turnRequestStore.exists(request.requestId(), saveId)) return response(latest);
                SaveStore.StoredSave saved = saveStore.put(saveId, user.userId(), current.slotNo, next, current.revision + 1);
                conversationStore.append(conversationId, next.turn, "user", action, null, 0, 0);
                conversationStore.append(conversationId, next.turn, "assistant", agentTurn.assistantMessage(),
                        agentTurn.responseId(), agentTurn.promptTokens(), agentTurn.completionTokens());
                conversationStore.recordTurn(request.requestId(), saveId, conversationId, next.turn, action,
                        current.state, next, agentTurn, fateGenerationService.model());
                playerAiService.recordSuccessfulInvocation(user.userId(), aiRoute);
                turnRequestStore.record(request.requestId(), saveId);
                analytics.record(user.userId(), saveId, "TURN_COMPLETED", Map.of(
                        "turn", next.turn, "inputType", request.inputType(), "difficulty", next.difficulty,
                        "credentialSource", aiRoute.source(), "dead", "dead".equals(saved.state.phase)));
                if ("dead".equals(saved.state.phase)) {
                    analytics.record(user.userId(), saveId, "CHARACTER_DIED", Map.of("turn", next.turn));
                    saveStore.archiveAndDelete(saved, "DEAD");
                }
                return response(saved);
            });
        }
    }

    private boolean consumesAbilities(com.wenxian.game.engine.ActionIntent intent) {
        return intent != null
                && java.util.Set.of("EXECUTE", "ATTEMPT").contains(intent.intentMode())
                && java.util.Set.of("USE_ABILITY", "COMBAT", "ESCAPE", "INFILTRATE")
                .contains(intent.actionType());
    }

    public void delete(UserIdentity user, String saveId) {
        synchronized (accountLock(user.userId())) {
            SaveStore.StoredSave save = requireOwned(user, saveId);
            String removalReason = "dead".equals(save.state.phase) ? "DEAD"
                    : save.state.player != null ? "RESTARTED" : "SETUP_DISCARDED";
            if (save.state.player != null || "dead".equals(save.state.phase)) {
                saveStore.archiveAndDelete(save, "dead".equals(save.state.phase) ? "DEAD" : "RESTARTED");
            } else {
                saveStore.delete(saveId, user.userId());
            }
            analytics.record(user.userId(), saveId, "SAVE_REMOVED", Map.of("reason", removalReason));
            saveLocks.remove(saveId);
        }
    }

    public void abandon(UserIdentity user, String saveId, String confirmedName) {
        synchronized (accountLock(user.userId())) {
            SaveStore.StoredSave save = requireOwned(user, saveId);
            String expectedName = displayName(save.state);
            if (!expectedName.equals(confirmedName == null ? "" : confirmedName.trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色名不一致，未封存命卷");
            }
            saveStore.archiveAndDelete(save, "ABANDONED");
            analytics.record(user.userId(), saveId, "CHARACTER_ABANDONED", Map.of("turn", save.state.turn));
            saveLocks.remove(saveId);
        }
    }

    public GameResponse restart(UserIdentity user, String saveId, CreateSaveRequest request) {
        synchronized (accountLock(user.userId())) {
            SaveStore.StoredSave current = requireOwned(user, saveId);
            var validation = nameService.validate(request.playerName());
            if (!validation.valid()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, validation.message());
            }
            PlayerAiService.Route aiRoute = generationRoute(user.userId());
            String replacementId = "save-" + UUID.randomUUID();
            GameState setup = fateGenerationService.generateFates(replacementId, validation.normalizedName(),
                    request.race(), request.gender(), request.lifeGoal(), request.originPremise(), request.preference(),
                    aiRoute.config(), aiRoute.source());
            setup.difficulty = request.difficulty();

            return transactions.execute(status -> {
                SaveStore.StoredSave confirmed = requireRevision(user, saveId, current.revision);
                int slotNo = confirmed.slotNo;
                saveStore.archiveAndDelete(confirmed, "RESTARTED");
                SaveStore.StoredSave saved = saveStore.put(
                        replacementId, user.userId(), slotNo, setup, 0);
                String conversationId = conversationStore.ensure(replacementId, aiRoute.recordedModel());
                conversationStore.append(conversationId, 0, "user",
                        "重开角色：" + conversationStore.json(request), null, 0, 0);
                conversationStore.append(conversationId, 0, "assistant",
                        "三份候选命格：" + conversationStore.json(setup.protagonistOptions), null, 0, 0);
                analytics.record(user.userId(), replacementId, "CHARACTER_RESTARTED", Map.of(
                        "previousSaveId", saveId, "race", request.race(), "gender", request.gender(),
                        "difficulty", request.difficulty(), "slotNo", slotNo));
                saveLocks.remove(saveId);
                return response(saved);
            });
        }
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
        return repairProgression(save);
    }

    private SaveStore.StoredSave requireRevision(UserIdentity user, String saveId, long expectedRevision) {
        SaveStore.StoredSave latest = requireOwned(user, saveId);
        if (latest.revision != expectedRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "游戏状态已经变化，请刷新当前场景");
        }
        return latest;
    }

    private SaveStore.StoredSave repairProgression(SaveStore.StoredSave save) {
        if (save == null) return null;
        synchronized (lock(save.id)) {
            GameState repaired = gameEngine.copy(save.state);
            boolean changed = adjudicationService.repairLegacyState(repaired);
            changed |= LocationAuthority.repairLegacyState(repaired);
            changed |= CurrencyAuthority.repairLegacyState(repaired);
            changed |= gameEngine.refreshDailyAbilities(repaired);
            if (!changed) return save;
            return saveStore.put(save.id, save.userId, save.slotNo, repaired, save.revision);
        }
    }

    private SaveStore.StoredSave requirePlaying(UserIdentity user, String saveId) {
        SaveStore.StoredSave save = requireOwned(user, saveId);
        if (!"playing".equals(save.state.phase)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "命卷尚未开启");
        }
        return save;
    }

    private SaveStore.StoredSave requireTalentPhase(UserIdentity user, String saveId) {
        SaveStore.StoredSave save = requireOwned(user, saveId);
        if (!"talent".equals(save.state.phase)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前命卷不在天赋觉醒阶段");
        }
        return save;
    }

    private void appendTalentDecision(String saveId, GameState state, String action) {
        String conversationId = conversationStore.ensure(saveId, fateGenerationService.model());
        conversationStore.append(conversationId, 0, "user", action, null, 0, 0);
        conversationStore.append(conversationId, 0, "assistant", "正式入世：" + conversationStore.json(Map.of(
                "narrative", state.narrative, "choices", state.choices, "abilities", state.abilities)), null, 0, 0);
    }

    private Object lock(String saveId) {
        return saveLocks.computeIfAbsent(saveId, ignored -> new Object());
    }

    private Object accountLock(String userId) {
        return accountLocks.computeIfAbsent(userId, ignored -> new Object());
    }

    private PlayerAiService.Route generationRoute(String userId) {
        PlayerAiService.Route route = playerAiService.route(userId);
        playerAiService.assertPublicQuota(userId, route);
        return route;
    }

    private boolean archiveLegacyDeath(SaveStore.StoredSave save) {
        if (!"dead".equals(save.state.phase)) return false;
        synchronized (accountLock(save.userId)) {
            SaveStore.StoredSave current = saveStore.find(save.id);
            if (current != null && "dead".equals(current.state.phase)) saveStore.archiveAndDelete(current, "DEAD");
        }
        return true;
    }

    private SaveSummaryView summary(SaveStore.StoredSave save) {
        GameState state = save.state;
        return new SaveSummaryView(save.id, save.slotNo, state.phase, displayName(state),
                state.player == null ? candidateRace(state) : state.player.race,
                state.player == null ? "命格未定" : state.player.realm,
                state.currentLocation, state.sceneTitle, state.turn, save.updatedAt);
    }

    private String displayName(GameState state) {
        if (state.player != null && state.player.name != null && !state.player.name.isBlank()) return state.player.name;
        if (state.protagonistOptions != null && !state.protagonistOptions.isEmpty()
                && state.protagonistOptions.get(0).name != null) return state.protagonistOptions.get(0).name;
        return "未定命格";
    }

    private String candidateRace(GameState state) {
        return state.protagonistOptions != null && !state.protagonistOptions.isEmpty()
                ? state.protagonistOptions.get(0).race : "UNKNOWN";
    }

    private GameResponse response(SaveStore.StoredSave save) {
        return response(save, null);
    }

    private GameResponse response(SaveStore.StoredSave save, ActionResolutionView actionResolution) {
        GameState copy = gameEngine.copy(save.state);
        List<GameState.StoryChoice> responseChoices = actionResolution == null ? copy.choices : List.of();
        return new GameResponse(copy, save.revision, save.updatedAt, responseChoices, agentInfo(), actionResolution);
    }

    private ActionResolutionView actionResolution(ActionInputGate.Assessment assessment) {
        return new ActionResolutionView(assessment.result(), "INPUT_REJECTED", assessment.title(),
                assessment.reason(), assessment.suggestions(), false);
    }

    private ActionResolutionView actionResolution(com.wenxian.game.engine.AgentResult.ActionResolution resolution) {
        return new ActionResolutionView(resolution.result(), resolution.code(), resolution.title(),
                resolution.reason(), resolution.suggestions(), resolution.turnConsumed());
    }
}
