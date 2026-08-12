package com.wenxian.game.story.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.service.ActiveSavePolicyService;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.service.PlayerAiService;
import com.wenxian.game.story.agent.StoryModelFailureException;
import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.application.StoryTurnCommitService;
import com.wenxian.game.credit.PlatformTurnAccessService;
import com.wenxian.game.story.application.StoryTurnPreparationService;
import com.wenxian.game.story.domain.StoryActionPreconditionException;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.model.StoryApiModels.Ability;
import com.wenxian.game.story.model.StoryApiModels.BootstrapResponse;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteRequest;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteResponse;
import com.wenxian.game.story.model.StoryApiModels.Choice;
import com.wenxian.game.story.model.StoryApiModels.CorrectionRequest;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.story.model.StoryApiModels.EngineDescriptor;
import com.wenxian.game.story.model.StoryApiModels.Item;
import com.wenxian.game.story.model.StoryApiModels.Memory;
import com.wenxian.game.story.model.StoryApiModels.PendingDecision;
import com.wenxian.game.story.model.StoryApiModels.Protagonist;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.SaveSummary;
import com.wenxian.game.story.model.StoryApiModels.StoryState;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;
import com.wenxian.game.story.model.StoryApiModels.WorldProfile;
import com.wenxian.game.story.model.StoryApiModels.WorldOption;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryCorrectionRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository.StoredMemory;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewTurn;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import com.wenxian.game.v014.ai.V014StoryAgent;
import com.wenxian.game.v014.model.V014ApiModels.ModelPreference;
import com.wenxian.game.v014.model.V014State;
import com.wenxian.game.v014.persistence.V014TurnStore.RecentTurn;
import com.wenxian.game.v014.service.V014ModelPreferenceService;
import com.wenxian.game.v014.service.V014ModelPreferenceService.ResolvedRoute;
import com.wenxian.game.v014.service.V014CharacterRouteService;
import com.wenxian.game.v014.service.V014StateReducer;
import com.wenxian.game.v014.service.V014WorldCatalog;
import dev.langchain4j.exception.AuthenticationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于 story_* 通用表的轻叙事运行时。
 *
 * <p>该引擎是迁移后的权威读写边界：命卷快照、实体、记忆、回合、摘要和额度必须在同一事务中提交。
 * 迁移期暂时复用已经验证的 V0.14 Agent 与状态归并器，但不再写入 V0.14 表。运行开关默认关闭，
 * 只有灰度验证显式开启后才识别 story-* 命卷；失败不会回退写旧表。</p>
 */
@Component
public class PersistedLightNarrativeEngine implements StoryEngine {
    public static final String ROUTE_CODE = "LIGHT_NARRATIVE_STORY";
    private static final String ENGINE_CODE = "LIGHT_NARRATIVE";
    private static final int ENGINE_VERSION = 1;
    private static final int STATE_SCHEMA_VERSION = 1;
    private static final String SAVE_PREFIX = "story-";
    private static final String PROMPT_VERSION = "story-runtime-v1";
    private static final String FIELD_VALUE_END = "([^；;\\n]{1,%d})";
    private static final Pattern LOCATION = correctionField("(?:当前)?(?:地点|位置)", 60);
    private static final Pattern REALM = correctionField("(?:当前)?(?:境界|修为)", 40);
    private static final Pattern CONDITION = correctionField("(?:当前)?(?:状态|伤势)", 80);
    private static final Pattern ITEM_STATUS = Pattern.compile(
            "(?:物品)?[《「]?([^》」]{1,40})[》」]?(?:已经|应当)?(消耗|卖出|赠予|遗失|存放)");

    private final StorySaveRepository saves;
    private final StoryTurnRepository turns;
    private final StoryEntityRepository entities;
    private final StoryMemoryRepository memories;
    private final StoryConversationRepository conversations;
    private final StoryCorrectionRepository corrections;
    private final StoryTurnPreparationService trustedTurnPreparation;
    private final StoryTurnCommitService trustedTurnCommit;
    private final V014StoryAgent agent;
    private final V014StateReducer reducer;
    private final V014ModelPreferenceService models;
    private final V014WorldCatalog worlds;
    private final V014CharacterRouteService characterRoutes;
    private final ActiveSavePolicyService savePolicy;
    private final PlatformTurnAccessService turnAccess;
    private final GameVersionService releases;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final StoryProjectionPersistenceMapper projections;
    private final boolean enabled;
    private final boolean trustedTurnsEnabled;
    private final String legacyBaseUrl;
    private final Map<String, Object> accountLocks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> turnsInProgress = new ConcurrentHashMap<>();

    public PersistedLightNarrativeEngine(
            StorySaveRepository saves,
            StoryTurnRepository turns,
            StoryEntityRepository entities,
            StoryMemoryRepository memories,
            StoryConversationRepository conversations,
            StoryCorrectionRepository corrections,
            StoryTurnPreparationService trustedTurnPreparation,
            StoryTurnCommitService trustedTurnCommit,
            V014StoryAgent agent,
            V014StateReducer reducer,
            V014ModelPreferenceService models,
            V014WorldCatalog worlds,
            V014CharacterRouteService characterRoutes,
            ActiveSavePolicyService savePolicy,
            PlatformTurnAccessService turnAccess,
            GameVersionService releases,
            TransactionTemplate transactions,
            ObjectMapper mapper,
            @Value("${wenxian.story.persisted-runtime-enabled:false}") boolean enabled,
            @Value("${wenxian.story.trusted-turns-enabled:false}") boolean trustedTurnsEnabled,
            @Value("${deepseek.base-url:https://api.deepseek.com}") String legacyBaseUrl) {
        this.saves = saves;
        this.turns = turns;
        this.entities = entities;
        this.memories = memories;
        this.conversations = conversations;
        this.corrections = corrections;
        this.trustedTurnPreparation = trustedTurnPreparation;
        this.trustedTurnCommit = trustedTurnCommit;
        this.agent = agent;
        this.reducer = reducer;
        this.models = models;
        this.worlds = worlds;
        this.characterRoutes = characterRoutes;
        this.savePolicy = savePolicy;
        this.turnAccess = turnAccess;
        this.releases = releases;
        this.transactions = transactions;
        this.mapper = mapper;
        this.projections = new StoryProjectionPersistenceMapper(mapper);
        this.enabled = enabled;
        this.trustedTurnsEnabled = trustedTurnsEnabled;
        this.legacyBaseUrl = legacyBaseUrl;
    }

    @Override
    public String routeCode() {
        return ROUTE_CODE;
    }

    @Override
    public EngineDescriptor descriptor() {
        return new EngineDescriptor(ENGINE_CODE, ENGINE_VERSION, STATE_SCHEMA_VERSION);
    }

    @Override
    public boolean supports(String saveId) {
        return enabled && saveId != null && saveId.startsWith(SAVE_PREFIX);
    }

    @Override
    public BootstrapResponse bootstrap() {
        assertCreationEnabled();
        List<WorldOption> options = worlds.all().stream()
                .map(world -> new WorldOption(world.code(), world.name(), world.premise(),
                        world.cultivationStyle(), world.openingRegion(), world.custom(),
                        world.available(), world.availabilityNote()))
                .toList();
        return new BootstrapResponse(releases.current(), savePolicy.maximum(), descriptor(), options,
                List.of("人族", "妖族", "灵族", "自定种族"),
                List.of("凡俗出身", "宗门弟子", "世家之后", "王族血脉", "妖王后代", "器物化灵", "自由描述"));
    }

    @Override
    public CharacterRouteResponse characterRoutes(UserIdentity user, CharacterRouteRequest request) {
        assertCreationEnabled();
        var result = characterRoutes.generate(user,
                new com.wenxian.game.v014.model.V014ApiModels.CharacterRouteRequest(
                        request.worldCode(), request.customWorld(), request.playerName(), request.race(),
                        request.gender(), request.roleSeed(), request.preferenceSeed()));
        return new CharacterRouteResponse(result.routes().stream()
                .map(route -> new com.wenxian.game.story.model.StoryApiModels.CharacterRoute(
                        route.id(), route.title(), route.origin(), route.opening(), route.preference()))
                .toList());
    }

    @Override
    public SaveResponse create(UserIdentity user, CreateSaveRequest request) {
        assertCreationEnabled();
        synchronized (accountLock(user.userId())) {
            StoredSave created = transactions.execute(status -> createLocked(user, request));
            if (created == null) throw new IllegalStateException("统一命卷创建事务没有返回结果");
            return response(user.userId(), created, readState(created));
        }
    }

    private StoredSave createLocked(UserIdentity user, CreateSaveRequest request) {
        int maximum = savePolicy.lockMaximum();
        if (saves.countActive(user.userId()) >= maximum) {
            throw GameApiException.of(HttpStatus.CONFLICT, "ACTIVE_SAVE_LIMIT_REACHED", "STATE",
                    "当前账号的存活命卷已经达到上限",
                    "请先继续现有命卷，或删除一份不再游玩的命卷。",
                    List.of("返回我的命卷", "删除一份现有命卷"));
        }
        int slotNo = saves.nextSlotNo(user.userId());
        int activeSlotNo = saves.nextActiveSlotNo(user.userId(), maximum);
        String saveId = SAVE_PREFIX + UUID.randomUUID();
        V014State.WorldProfile world = worlds.resolve(request.worldCode(), request.customWorld());
        String preference = text(request.storyPreference(), "自由探索，机缘与风险并存");
        V014State.Protagonist protagonist = new V014State.Protagonist(
                request.playerName().strip(), request.race().strip(), request.gender().strip(),
                request.origin().strip(), initialRealm(request.race()), "尚未定下，随此生经历逐渐形成", preference);
        String opening = text(request.opening(), "你的人生并非从同一扇门开始。此刻，你身在"
                + world.openingRegion() + "，过往出身已经落定，前路却仍由自己选择。");
        V014State state = new V014State(14, saveId, 0, "ACTIVE", world, protagonist, 0,
                world.openingRegion(), "神完气足", "命卷初开", opening,
                List.of(new V014State.Choice(id("choice"), "观察周围", "先弄清自己身处何地与眼前局势。"),
                        new V014State.Choice(id("choice"), "追寻旧因", "从自己的出身与未竟之事开始行动。"),
                        new V014State.Choice(id("choice"), "主动寻机缘", "不等待命运安排，寻找修行的第一步。")),
                List.of(), List.of(), List.of(),
                List.of(new V014State.Memory(id("memory"), "此生来处", protagonist.origin(), 0, "KEY")));
        String release = releases.current();
        Instant now = Instant.now();
        saves.insert(new com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave(
                saveId, user.userId(), slotNo, activeSlotNo, "ACTIVE", world.code(), ENGINE_CODE, ENGINE_VERSION,
                STATE_SCHEMA_VERSION, "NATIVE", release, null, null, coreState(state), 0,
                release, release, now));
        appendNewMemories(saveId, state);
        conversations.initialize(saveId, opening, 0);
        return saves.findOwned(user.userId(), saveId)
                .orElseThrow(() -> new IllegalStateException("统一命卷创建后无法回读：" + saveId));
    }

    @Override
    public List<SaveSummary> list(UserIdentity user) {
        if (!enabled) return List.of();
        return saves.listOwned(user.userId()).stream()
                .filter(this::belongsToThisEngine)
                .map(saved -> {
                    V014State state = readState(saved);
                    return new SaveSummary(saved.id(), saved.activeSlotNo(), mode(saved.status()), ENGINE_CODE,
                            saved.engineVersion(), saved.stateSchemaVersion(), saved.revision(), saved.turnNumber(),
                            capabilities(saved.status()), state.world().name(), state.protagonist().name(),
                            state.protagonist().race(), state.protagonist().realm(), saved.status(), saved.updatedAt());
                }).toList();
    }

    @Override
    public SaveResponse get(UserIdentity user, String saveId) {
        StoredSave saved = requireOwned(user, saveId);
        return response(user.userId(), saved, readState(saved));
    }

    @Override
    public SaveResponse takeTurn(UserIdentity user, String saveId, TurnRequest request) {
        return withTurnGate(user.userId(), () -> {
            synchronized (accountLock(user.userId())) {
                return takeTurnLocked(user, saveId, request);
            }
        });
    }

    private SaveResponse takeTurnLocked(UserIdentity user, String saveId, TurnRequest request) {
        StoredSave saved = requireOwned(user, saveId);
        assertActive(saved);
        if (turns.findByRequestId(saveId, request.requestId()).isPresent()) return get(user, saveId);
        assertRevision(saved, request.expectedRevision());
        V014State before = readState(saved);
        ResolvedRoute route = models.resolve(user.userId(), legacyBaseUrl);
        PlayerAiService.Route quotaRoute = quotaRoute(route);
        PlatformTurnAccessService.Permit turnPermit = turnAccess.reserve(
                user.userId(), saveId, request.requestId(), quotaRoute);

        if (trustedTurnsEnabled) {
            return takeTrustedTurn(user, saved, request, route, turnPermit);
        }

        V014StoryAgent.Generated generated;
        try {
            List<RecentTurn> recent = turns.recent(saveId, 2).stream()
                    .map(turn -> new RecentTurn(turn.turnNumber(), turn.playerAction(), turn.narrative()))
                    .toList();
            generated = agent.generate(user.userId(), before, conversations.summary(saveId), recent,
                    request.action(), route);
        } catch (RuntimeException error) {
            releaseTurnAfterFailure(turnPermit, "剧情模型调用失败，释放平台回合", error);
            if (route.hasCustomCredential() && causedByAuthentication(error)) {
                models.markCredentialInvalid(user.userId(), route.provider(), "AUTHENTICATION_FAILED");
                throw new GameApiException(HttpStatus.CONFLICT, "PERSONAL_KEY_REJECTED", "CREDENTIAL",
                        "自己的推演密钥已失效", "推演服务拒绝了当前密钥，本次未消耗回合或修改命卷。",
                        List.of("前往设置重新验证密钥", "确认密钥仍有额度且未被停用"), false,
                        false, false, Map.of(), "personal credential rejected by provider", error);
            }
            throw error;
        }

        V014StateReducer.Applied applied;
        Long revision;
        try {
            applied = reducer.apply(before, request.action(), generated.envelope());
            V014StateReducer.Applied accepted = applied;
            revision = transactions.execute(status -> {
                JsonNode core = coreState(accepted.state());
                long next = saves.updateState(user.userId(), saveId, saved.revision(), core,
                        STATE_SCHEMA_VERSION, accepted.state().turnNumber(), accepted.state().world().code(),
                        saved.status(), releases.current());
                synchronizeEntities(saveId, accepted.state());
                appendNewMemories(saveId, accepted.state());
                String turnId = "story-turn-" + UUID.randomUUID();
                turns.insert(new NewTurn(turnId, saveId, request.requestId(),
                        accepted.state().turnNumber(), request.action(), accepted.state().narrative(),
                        mapper.valueToTree(accepted.state().choices()), readJson(generated.rawPatchJson()),
                        before.worldTimeMinutes(), accepted.state().worldTimeMinutes(), ENGINE_CODE, ENGINE_VERSION,
                        PROMPT_VERSION, route.provider(), route.model(), route.credentialSource(),
                        generated.result().inputTokens(), generated.result().outputTokens(), 0, 0,
                        generated.result().latencyMs(), releases.current(), Instant.now()));
                if (!accepted.rollingSummary().isBlank()) {
                    conversations.updateSummary(saveId, accepted.rollingSummary(), accepted.state().turnNumber());
                }
                turnAccess.consume(turnPermit, user.userId(), turnId);
                return next;
            });
        } catch (RuntimeException error) {
            releaseTurnAfterFailure(turnPermit, "剧情状态未能提交，释放平台回合", error);
            throw error;
        }
        StoredSave updated = new StoredSave(saved.id(), saved.userId(), saved.slotNo(), saved.activeSlotNo(), saved.status(),
                applied.state().world().code(), saved.engineCode(), saved.engineVersion(), STATE_SCHEMA_VERSION,
                saved.sourceType(), saved.sourceEdition(), saved.sourceSaveId(), saved.parentSaveId(),
                coreState(applied.state()), revision == null ? saved.revision() + 1 : revision,
                applied.state().turnNumber(), saved.createdRelease(), releases.current(), saved.createdAt(), Instant.now(),
                saved.deletedAt(), saved.deletedBy(), saved.deleteReason());
        return response(user.userId(), updated, applied.state(), route);
    }

    private SaveResponse takeTrustedTurn(UserIdentity user, StoredSave saved, TurnRequest request,
                                         ResolvedRoute resolvedRoute,
                                         PlatformTurnAccessService.Permit turnPermit) {
        StoryModelRoute route = new StoryModelRoute(resolvedRoute.baseUrl(), resolvedRoute.apiKey(),
                resolvedRoute.provider(), resolvedRoute.adapterType(), resolvedRoute.model(),
                resolvedRoute.credentialSource());
        StoryTurnPreparationService.Prepared prepared;
        try {
            prepared = trustedTurnPreparation.prepare(saved, request.action(), route);
        } catch (RuntimeException error) {
            releaseTurnAfterFailure(turnPermit, "可信剧情生成或审阅失败，释放平台回合", error);
            if (resolvedRoute.hasCustomCredential() && causedByAuthentication(error)) {
                models.markCredentialInvalid(user.userId(), resolvedRoute.provider(), "AUTHENTICATION_FAILED");
                throw new GameApiException(HttpStatus.CONFLICT, "PERSONAL_KEY_REJECTED", "CREDENTIAL",
                        "自己的推演密钥已失效", "推演服务拒绝了当前密钥，本次未消耗回合或修改命卷。",
                        List.of("前往设置重新验证密钥", "确认密钥仍有额度且未被停用"), false,
                        false, false, Map.of(), "personal credential rejected by provider", error);
            }
            StoryModelFailureException modelFailure = findModelFailure(error);
            if (modelFailure != null) {
                throw playerSafeModelFailure(modelFailure, resolvedRoute.hasCustomCredential());
            }
            if (error instanceof StoryActionPreconditionException conflict) {
                List<String> suggestions = conflict.correction().isBlank()
                        ? List.of("刷新命卷后重新描述行动") : List.of(conflict.correction());
                throw new GameApiException(HttpStatus.CONFLICT, "ACTION_PRECONDITION_FAILED", "STATE",
                        "这次行动的前提与命卷不一致", conflict.getMessage(), suggestions,
                        false, false, false, Map.of("correction", conflict.correction()),
                        "story action fact contract rejected", conflict);
            }
            if (error instanceof IllegalArgumentException) {
                throw GameApiException.temporary("STORY_PATCH_REJECTED", "这一轮没有形成一致结果",
                        "系统已阻止不完整或相互矛盾的变化写入命卷，本次未消耗回合。",
                        List.of("重新尝试本轮行动", "换一种更明确的方式描述行动"),
                        "trusted story turn proposal rejected", error);
            }
            throw error;
        }
        try {
            trustedTurnCommit.commit(user.userId(), saved, request.requestId(), request.action(),
                    prepared, route, turnPermit);
        } catch (RuntimeException error) {
            releaseTurnAfterFailure(turnPermit, "可信剧情事务未能提交，释放平台回合", error);
            if (error instanceof OptimisticLockingFailureException) {
                throw new GameApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", "STATE",
                        "命卷已经在别处发生变化", "已阻止覆盖较新的记录，本次未消耗回合。",
                        List.of("刷新当前命卷后继续"), true, false, false, Map.of(),
                        "trusted story turn revision conflict", error);
            }
            throw error;
        }
        StoredSave updated = requireOwned(user, saved.id());
        return response(user.userId(), updated, readState(updated), resolvedRoute);
    }

    /** 归还失败回合时不覆盖原始业务异常；归还失败的预占仍可由超时清理任务恢复。 */
    private void releaseTurnAfterFailure(PlatformTurnAccessService.Permit permit, String reason,
                                         RuntimeException original) {
        try {
            turnAccess.release(permit, reason);
        } catch (RuntimeException releaseFailure) {
            original.addSuppressed(releaseFailure);
        }
    }

    @Override
    public SaveResponse correct(UserIdentity user, String saveId, CorrectionRequest request) {
        synchronized (accountLock(user.userId())) {
            StoredSave saved = requireActive(user, saveId, request.expectedRevision());
            V014State before = readState(saved);
            V014State after = corrected(before, request.instruction().strip());
            Long revision = transactions.execute(status -> {
                long next = saves.updateState(user.userId(), saveId, saved.revision(), coreState(after),
                        STATE_SCHEMA_VERSION, after.turnNumber(), after.world().code(), saved.status(), releases.current());
                synchronizeEntities(saveId, after);
                corrections.insert(user.userId(), saveId, request.instruction().strip(), "OTHER",
                        mapper.valueToTree(before), mapper.valueToTree(after));
                return next;
            });
            StoredSave updated = withRevision(saved, revision == null ? saved.revision() + 1 : revision,
                    coreState(after), after.turnNumber());
            return response(user.userId(), updated, after);
        }
    }

    @Override
    public void deleteSave(UserIdentity user, String saveId) {
        synchronized (accountLock(user.userId())) {
            requireOwned(user, saveId);
            try {
                saves.logicalDelete(user.userId(), saveId);
            } catch (OptimisticLockingFailureException error) {
                throw GameApiException.of(HttpStatus.CONFLICT, "SAVE_NOT_ACTIVE", "STATE",
                        "这份命卷已经不在当前列表", "它可能已经被删除，或刚刚在其他页面发生了变化。",
                        List.of("刷新我的命卷"));
            }
        }
    }

    @Override
    public SaveResponse removeEntity(UserIdentity user, String saveId, String entityId, long expectedRevision) {
        synchronized (accountLock(user.userId())) {
            StoredSave saved = requireActive(user, saveId, expectedRevision);
            Entity target = entities.listVisible(saveId).stream().filter(entity -> entity.id().equals(entityId))
                    .findFirst().orElseThrow(() -> GameApiException.of(HttpStatus.NOT_FOUND,
                            "STORY_ENTITY_NOT_FOUND", "STATE", "当前记录中没有这个内容",
                            "它可能已经被整理，或命卷刚刚在其他页面发生了变化。",
                            List.of("刷新角色页后再查看")));
            if (!List.of("ITEM", "CHARACTER").contains(target.type())) {
                throw GameApiException.of(HttpStatus.BAD_REQUEST, "STORY_ENTITY_CANNOT_REMOVE", "STATE",
                        "这项记录不能从当前页面移除", "只有行囊物品和关注人物可以由玩家主动整理。",
                        List.of("返回角色页"));
            }
            Long revision = transactions.execute(status -> {
                long next = saves.updateState(user.userId(), saveId, saved.revision(), saved.state(),
                        saved.stateSchemaVersion(), saved.turnNumber(), saved.worldCode(), saved.status(), releases.current());
                int changed = entities.hide(saveId, entityId, saved.turnNumber(),
                        "ITEM".equals(target.type()) ? "已由玩家移出行囊" : "已由玩家停止关注");
                if (changed != 1) throw new OptimisticLockingFailureException("实体已经发生变化");
                corrections.insert(user.userId(), saveId, "整理当前记录：" + target.name(), target.type(),
                        mapper.valueToTree(target), mapper.createObjectNode().put("visible", false));
                return next;
            });
            StoredSave updated = withRevision(saved, revision == null ? saved.revision() + 1 : revision,
                    saved.state(), saved.turnNumber());
            return response(user.userId(), updated, readState(updated));
        }
    }

    private StoredSave requireOwned(UserIdentity user, String saveId) {
        StoredSave saved = saves.findOwned(user.userId(), saveId)
                .orElseThrow(() -> GameApiException.of(HttpStatus.NOT_FOUND, "STORY_SAVE_NOT_FOUND", "STATE",
                        "没有找到这份命卷", "命卷可能已经封存到其他档案，或不属于当前账号。",
                        List.of("刷新我的命卷")));
        if (!belongsToThisEngine(saved)) {
            throw GameApiException.of(HttpStatus.CONFLICT, "STORY_ENGINE_MISMATCH", "STATE",
                    "这份命卷暂时无法读取", "命卷记录的故事引擎与当前运行时不一致。",
                    List.of("联系管理员并提供命卷编号"));
        }
        return saved;
    }

    private StoredSave requireActive(UserIdentity user, String saveId, long expectedRevision) {
        StoredSave saved = requireOwned(user, saveId);
        assertActive(saved);
        assertRevision(saved, expectedRevision);
        return saved;
    }

    private void assertActive(StoredSave saved) {
        if (!"ACTIVE".equals(saved.status())) {
            throw GameApiException.of(HttpStatus.CONFLICT, "SAVE_NOT_ACTIVE", "STATE",
                    "这份命卷已经封存", "封存命卷只能阅读，不能继续改变。", List.of("选择其他命卷"));
        }
    }

    private void assertRevision(StoredSave saved, long expectedRevision) {
        if (saved.revision() != expectedRevision) {
            throw GameApiException.of(HttpStatus.CONFLICT, "STATE_CONFLICT", "STATE",
                    "命卷已经在别处发生变化", "已阻止覆盖较新的记录，请刷新命卷后再操作。",
                    List.of("刷新当前命卷后继续"));
        }
    }

    private boolean belongsToThisEngine(StoredSave save) {
        return ENGINE_CODE.equals(save.engineCode()) && save.engineVersion() == ENGINE_VERSION;
    }

    private V014State readState(StoredSave saved) {
        V014State core;
        try {
            JsonNode compatibleState = saved.state();
            if (compatibleState instanceof ObjectNode object) {
                ObjectNode copy = object.deepCopy();
                // 可信投影属于通用 Story 内部扩展，不应泄漏给旧版 H5 状态模型反序列化。
                copy.remove("_trustedProjection");
                copy.remove("_trustedScene");
                compatibleState = copy;
            }
            core = mapper.treeToValue(compatibleState, V014State.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("统一命卷核心状态无法读取：" + saved.id(), error);
        }
        List<V014State.Item> inventory = new ArrayList<>();
        List<V014State.Ability> abilities = new ArrayList<>();
        List<V014State.Character> characters = new ArrayList<>();
        for (Entity entity : entities.listVisible(saved.id())) {
            JsonNode attributes = entity.attributes();
            switch (entity.type()) {
                case "ITEM" -> {
                    // 旧版 H5 的 inventory 是“当前行囊”视图，不是所有历史物品实体。
                    // 赠予人物、留在地点、已消耗或整理的物品仍保留在可追溯事实中，但不应出现在玩家行囊。
                    if (isCurrentlyHeldByPlayer(attributes)) {
                        inventory.add(new V014State.Item(entity.id(), entity.name(),
                                attributes.path("itemType").asText("物品"), entity.summary(), entity.status(),
                                attributes.path("source").asText("故事所得"),
                                trustedQuantityKnown(attributes)
                                        ? Math.max(0, trustedQuantityValue(attributes)) : 0,
                                trustedQuantityKnown(attributes), true));
                    }
                }
                case "ABILITY" -> abilities.add(new V014State.Ability(entity.id(), entity.name(),
                        attributes.path("abilityType").asText("能力"), entity.summary(), entity.status(),
                        attributes.path("source").asText("故事所得")));
                case "CHARACTER" -> characters.add(new V014State.Character(entity.id(), entity.name(),
                        attributes.path("tier").asText("KNOWN"),
                        attributes.path("identity").asText("身份未明"),
                        attributes.path("relationship").asText("初次相识"), entity.status(),
                        attributes.path("lastKnownLocation").asText("未知"), true));
                default -> { /* 势力和地点尚不属于当前轻叙事角色页，保留在实体表但不塞入旧状态模型。 */ }
            }
        }
        List<V014State.Memory> stateMemories = memories.recentActive(saved.id(), 80).stream()
                .map(memory -> new V014State.Memory(memory.id(), memory.title(), memory.summary(),
                        memory.worldTime(), memory.importance())).toList();
        return new V014State(14, saved.id(), saved.turnNumber(), saved.status(), core.world(), core.protagonist(),
                core.worldTimeMinutes(), core.currentLocation(), core.condition(), core.sceneTitle(), core.narrative(),
                core.choices(), inventory, abilities, characters, stateMemories);
    }

    private ObjectNode coreState(V014State state) {
        ObjectNode core = mapper.createObjectNode();
        core.put("schemaVersion", STATE_SCHEMA_VERSION);
        core.put("saveId", state.saveId());
        core.put("turnNumber", state.turnNumber());
        core.put("status", state.status());
        core.set("world", mapper.valueToTree(state.world()));
        core.set("protagonist", mapper.valueToTree(state.protagonist()));
        core.put("worldTimeMinutes", state.worldTimeMinutes());
        core.put("currentLocation", state.currentLocation());
        core.put("condition", state.condition());
        core.put("sceneTitle", state.sceneTitle());
        core.put("narrative", state.narrative());
        core.set("choices", mapper.valueToTree(state.choices()));
        return core;
    }

    private void synchronizeEntities(String saveId, V014State state) {
        Map<String, Entity> existing = new HashMap<>();
        // 状态归并必须读取全部事实，不能把已整理或暂时不可见的实体误判为从未存在。
        entities.listAll(saveId).forEach(entity -> existing.put(entity.id(), entity));
        for (V014State.Item item : state.inventory()) {
            ObjectNode attributes = mapper.createObjectNode();
            attributes.put("itemType", item.type());
            attributes.put("source", item.source());
            attributes.put("quantity", Math.max(0, item.quantity()));
            attributes.put("inPossession", Boolean.TRUE.equals(item.inPossession()));
            upsertEntity(existing, new Entity(item.id(), saveId, "ITEM", item.name(), item.status(),
                    Boolean.TRUE.equals(item.inPossession()) && item.quantity() > 0, item.description(), attributes,
                    firstTurn(existing, item.id(), state.turnNumber()), state.turnNumber(), Instant.now(), Instant.now()));
        }
        for (V014State.Ability ability : state.abilities()) {
            ObjectNode attributes = mapper.createObjectNode();
            attributes.put("abilityType", ability.type());
            attributes.put("source", ability.source());
            boolean visible = !terminalAbility(ability.status());
            upsertEntity(existing, new Entity(ability.id(), saveId, "ABILITY", ability.name(), ability.status(),
                    visible, ability.description(), attributes, firstTurn(existing, ability.id(), state.turnNumber()),
                    state.turnNumber(), Instant.now(), Instant.now()));
        }
        for (V014State.Character character : state.characters()) {
            ObjectNode attributes = mapper.createObjectNode();
            attributes.put("tier", character.tier());
            attributes.put("identity", character.identity());
            attributes.put("relationship", character.relationship());
            attributes.put("lastKnownLocation", character.lastKnownLocation());
            String summary = String.join("；", character.identity(), character.relationship(),
                    character.lastKnownLocation());
            upsertEntity(existing, new Entity(character.id(), saveId, "CHARACTER", character.name(),
                    character.status(), Boolean.TRUE.equals(character.tracked()), summary, attributes,
                    firstTurn(existing, character.id(), state.turnNumber()), state.turnNumber(),
                    Instant.now(), Instant.now()));
        }
    }

    private void upsertEntity(Map<String, Entity> existing, Entity value) {
        if (existing.containsKey(value.id())) {
            if (entities.update(value) != 1) throw new OptimisticLockingFailureException("统一实体已经发生变化");
        } else {
            entities.insert(value);
        }
    }

    private int firstTurn(Map<String, Entity> existing, String id, int fallback) {
        Entity value = existing.get(id);
        return value == null ? fallback : value.firstTurn();
    }

    private void appendNewMemories(String saveId, V014State state) {
        for (V014State.Memory memory : state.memories()) {
            if (!memories.exists(saveId, memory.id())) {
                memories.insert(saveId, new StoredMemory(memory.id(), "EVENT", memory.title(), memory.summary(),
                        state.turnNumber(), memory.worldTimeMinutes(), normalizeImportance(memory.importance())));
            }
        }
    }

    private SaveResponse response(String userId, StoredSave saved, V014State state) {
        ModelPreference preference = models.get(userId);
        boolean personal = "USER".equals(preference.credentialSource());
        return new SaveResponse(saved.id(), mode(saved.status()), ENGINE_CODE, saved.engineVersion(),
                saved.stateSchemaVersion(), saved.revision(), saved.turnNumber(), capabilities(saved.status()),
                mapState(saved, state), personal ? "PERSONAL" : "PLATFORM",
                personal ? preference.provider() : null, personal ? preference.model() : null);
    }

    private SaveResponse response(String userId, StoredSave saved, V014State state, ResolvedRoute route) {
        boolean personal = "USER".equals(route.credentialSource());
        return new SaveResponse(saved.id(), mode(saved.status()), ENGINE_CODE, saved.engineVersion(),
                saved.stateSchemaVersion(), saved.revision(), saved.turnNumber(), capabilities(saved.status()),
                mapState(saved, state), personal ? "PERSONAL" : "PLATFORM",
                personal ? route.provider() : null, personal ? route.model() : null);
    }

    private StoryState mapState(StoredSave saved, V014State state) {
        return new StoryState(saved.id(), saved.turnNumber(), saved.status(),
                new WorldProfile(state.world().code(), state.world().name(), state.world().source(),
                        state.world().premise(), state.world().cultivationStyle(), state.world().openingRegion()),
                new Protagonist(state.protagonist().name(), state.protagonist().race(),
                        state.protagonist().gender(), state.protagonist().origin(), state.protagonist().realm(),
                        state.protagonist().lifeGoal(), state.protagonist().storyTone()),
                state.worldTimeMinutes(), state.currentLocation(), state.condition(), state.sceneTitle(),
                state.narrative(), pendingDecision(saved), state.choices().stream()
                        .map(value -> new Choice(value.id(), value.title(), value.description())).toList(),
                state.inventory().stream().map(value -> new Item(value.id(), value.name(), value.type(),
                        value.description(), value.status(), value.source(), value.quantity(),
                        Boolean.TRUE.equals(value.quantityKnown()), Boolean.TRUE.equals(value.inPossession()))).toList(),
                state.abilities().stream().map(value -> new Ability(value.id(), value.name(), value.type(),
                        value.description(), value.status(), value.source())).toList(),
                state.characters().stream().map(value -> new com.wenxian.game.story.model.StoryApiModels.Character(
                        value.id(), value.name(), value.tier(), value.identity(), value.relationship(), value.status(),
                        value.lastKnownLocation(), Boolean.TRUE.equals(value.tracked()))).toList(),
                state.memories().stream().map(value -> new Memory(value.id(), value.title(), value.summary(),
                        value.worldTimeMinutes(), value.importance())).toList());
    }

    private PendingDecision pendingDecision(StoredSave saved) {
        StoryPatch.DecisionCheckpoint checkpoint = projections.loadDecisionCheckpoint(saved.state());
        return checkpoint == null ? null
                : new PendingDecision(checkpoint.prompt());
    }

    private V014State corrected(V014State before, String instruction) {
        String location = match(LOCATION, instruction, before.currentLocation());
        String condition = match(CONDITION, instruction, before.condition());
        String realm = match(REALM, instruction, before.protagonist().realm());
        List<V014State.Item> inventory = new ArrayList<>(before.inventory());
        Matcher item = ITEM_STATUS.matcher(instruction);
        boolean changed = !location.equals(before.currentLocation()) || !condition.equals(before.condition())
                || !realm.equals(before.protagonist().realm());
        if (item.find()) {
            String name = item.group(1).strip();
            String status = switch (item.group(2)) {
                case "卖出" -> "SOLD";
                case "赠予" -> "GIFTED";
                case "遗失" -> "LOST";
                case "存放" -> "STORED";
                default -> "CONSUMED";
            };
            for (int index = 0; index < inventory.size(); index++) {
                V014State.Item old = inventory.get(index);
                if (old.name().equalsIgnoreCase(name)) {
                    inventory.set(index, new V014State.Item(old.id(), old.name(), old.type(), old.description(),
                            status, old.source(), old.quantity(),
                            !List.of("SOLD", "GIFTED", "LOST", "CONSUMED").contains(status)));
                    changed = true;
                }
            }
        }
        if (!changed) {
            throw GameApiException.of(HttpStatus.BAD_REQUEST, "CORRECTION_NOT_UNDERSTOOD", "INPUT",
                    "还不能确认要修正什么", "请明确写出地点、境界、状态，或哪件物品已消耗、卖出、赠予、遗失、存放。",
                    List.of("例如：地点应为青岐城", "例如：清心丹已经消耗"));
        }
        V014State.Protagonist protagonist = new V014State.Protagonist(before.protagonist().name(),
                before.protagonist().race(), before.protagonist().gender(), before.protagonist().origin(), realm,
                before.protagonist().lifeGoal(), before.protagonist().storyTone());
        return new V014State(14, before.saveId(), before.turnNumber(), before.status(), before.world(), protagonist,
                before.worldTimeMinutes(), location, condition, before.sceneTitle(), before.narrative(),
                before.choices(), inventory, before.abilities(), before.characters(), before.memories());
    }

    private StoredSave withRevision(StoredSave saved, long revision, JsonNode state, int turnNumber) {
        return new StoredSave(saved.id(), saved.userId(), saved.slotNo(), saved.activeSlotNo(), saved.status(), saved.worldCode(),
                saved.engineCode(), saved.engineVersion(), saved.stateSchemaVersion(), saved.sourceType(),
                saved.sourceEdition(), saved.sourceSaveId(), saved.parentSaveId(), state, revision, turnNumber,
                saved.createdRelease(), releases.current(), saved.createdAt(), Instant.now(),
                saved.deletedAt(), saved.deletedBy(), saved.deleteReason());
    }

    private String mode(String status) {
        return "ACTIVE".equals(status) ? "PLAYABLE" : "ENDED";
    }

    private List<String> capabilities(String status) {
        if (!"ACTIVE".equals(status)) return List.of("READ");
        return List.of("READ", "PLAY", "DELETE", "CORRECT", "REMOVE_ENTITY");
    }

    private void assertCreationEnabled() {
        if (!enabled) throw new IllegalStateException("通用表运行时尚未启用");
    }

    private String initialRealm(String race) {
        String value = race.toLowerCase(Locale.ROOT);
        if (value.contains("妖")) return "启灵";
        if (value.contains("灵")) return "凝形";
        return "凡境";
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private String id(String prefix) { return prefix + "-" + UUID.randomUUID(); }

    private JsonNode readJson(String value) {
        try {
            return mapper.readTree(value == null || value.isBlank() ? "{}" : value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("已归一化的推演补丁无法保存", error);
        }
    }

    private PlayerAiService.Route quotaRoute(ResolvedRoute route) {
        return new PlayerAiService.Route(new RuntimeAiConfigService.RuntimeConfig(
                route.provider(), route.model(), route.model(), route.apiKey()), route.credentialSource());
    }

    private boolean causedByAuthentication(Throwable error) {
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth++ < 8) {
            if (cursor instanceof AuthenticationException) return true;
            if (cursor instanceof StoryModelFailureException failure
                    && failure.kind() == StoryModelFailureException.Kind.AUTHENTICATION) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private StoryModelFailureException findModelFailure(Throwable error) {
        Throwable cursor = error;
        int depth = 0;
        while (cursor != null && depth++ < 8) {
            if (cursor instanceof StoryModelFailureException failure) return failure;
            cursor = cursor.getCause();
        }
        return null;
    }

    private GameApiException playerSafeModelFailure(StoryModelFailureException failure, boolean personal) {
        return switch (failure.kind()) {
            case REQUEST_REJECTED -> personal
                    ? new GameApiException(HttpStatus.CONFLICT, "PERSONAL_GENERATION_REJECTED", "CREDENTIAL",
                    "自己的推演服务没有接受请求",
                    "常见原因是账户余额不足、当前能力不可用或密钥权限不足。本次未消耗回合，也没有改变命卷。",
                    List.of("检查对应账户余额与权限", "重新验证或更换自己的密钥"), false,
                    false, false, Map.of(), "personal model request rejected", failure)
                    : new GameApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLATFORM_GENERATION_UNAVAILABLE", "SYSTEM",
                    "推演服务暂时不可用",
                    "平台推演配置当前需要维护，本次未消耗回合，也没有改变命卷。",
                    List.of("稍后重新尝试", "留意后续服务通知"), true,
                    false, false, Map.of(), "platform model request rejected", failure);
            case RATE_LIMITED -> new GameApiException(HttpStatus.BAD_GATEWAY, "GENERATION_RATE_LIMITED", "SYSTEM",
                    "当前推演请求较多", "服务暂时没有完成这一轮，本次未消耗回合，也没有改变命卷。",
                    List.of("稍等片刻后重试"), true, false, false, Map.of(),
                    "model request rate limited", failure);
            case CONTENT_FILTERED -> new GameApiException(HttpStatus.BAD_REQUEST, "STORY_CONTENT_FILTERED", "INPUT",
                    "这一轮暂时无法生成", "本轮描述未能通过推演服务检查，命卷没有发生变化。",
                    List.of("换一种更简洁的方式描述行动", "减少可能引起误解的细节"), false,
                    false, false, Map.of(), "model content filtered", failure);
            case TEMPORARY_UNAVAILABLE -> GameApiException.temporary("STORY_GENERATION_UNAVAILABLE",
                    "推演服务暂时没有响应", "本次未消耗回合，也没有改变命卷。",
                    List.of("稍后重新尝试本轮行动"), "model temporarily unavailable", failure);
            case AUTHENTICATION -> new GameApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PLATFORM_GENERATION_UNAVAILABLE", "SYSTEM", "推演服务暂时不可用",
                    "平台推演配置当前需要维护，本次未消耗回合，也没有改变命卷。",
                    List.of("稍后重新尝试", "留意后续服务通知"), true,
                    false, false, Map.of(), "platform model authentication rejected", failure);
        };
    }

    private SaveResponse withTurnGate(String userId, Supplier<SaveResponse> action) {
        AtomicBoolean gate = turnsInProgress.computeIfAbsent(userId, ignored -> new AtomicBoolean());
        if (!gate.compareAndSet(false, true)) {
            throw new GameApiException(HttpStatus.CONFLICT, "TURN_IN_PROGRESS", "STATE",
                    "上一轮仍在推演", "请等待上一轮完成；刷新或重新进入后，可以读取已经写入的结果。",
                    List.of("等待片刻后刷新命卷", "不要连续重复提交行动"), true,
                    false, false, Map.of(), "duplicate turn rejected while generation is in progress", null);
        }
        try {
            return action.get();
        } finally {
            turnsInProgress.remove(userId, gate);
        }
    }

    private Object accountLock(String userId) {
        return accountLocks.computeIfAbsent(userId, ignored -> new Object());
    }

    private String match(Pattern pattern, String input, String fallback) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).strip() : fallback;
    }

    private static Pattern correctionField(String label, int maxLength) {
        return Pattern.compile(label + "\\s*(?:(?:改为|应为|是)\\s*[:：]?|[:：])\\s*"
                + FIELD_VALUE_END.formatted(maxLength));
    }

    private boolean terminalAbility(String value) {
        if (value == null) return false;
        return List.of("LOST", "REMOVED", "FORGOTTEN").contains(value.strip().toUpperCase(Locale.ROOT));
    }

    private boolean trustedQuantityKnown(JsonNode attributes) {
        JsonNode quantity = attributes.path("_trusted").path("quantity");
        if (quantity.isObject() && quantity.has("known")) return quantity.path("known").asBoolean(false);
        return attributes.has("quantity") && attributes.path("quantity").canConvertToInt();
    }

    private int trustedQuantityValue(JsonNode attributes) {
        JsonNode quantity = attributes.path("_trusted").path("quantity");
        if (quantity.isObject() && quantity.path("known").asBoolean(false)) {
            return quantity.path("value").asInt(0);
        }
        return attributes.path("quantity").asInt(0);
    }

    /** 优先使用可信保管关系，旧数据才回退到 inPossession；无字段的老存档继续视为持有。 */
    private boolean isCurrentlyHeldByPlayer(JsonNode attributes) {
        JsonNode trusted = attributes.path("_trusted");
        JsonNode custody = trusted.path("custody");
        if (custody.isObject() && custody.hasNonNull("holderType")) {
            if (!"PLAYER".equalsIgnoreCase(custody.path("holderType").asText())) return false;
        } else if (trusted.has("inPossession")) {
            if (!trusted.path("inPossession").asBoolean(false)) return false;
        } else if (attributes.has("inPossession") && !attributes.path("inPossession").asBoolean(false)) {
            return false;
        }
        return !trustedQuantityKnown(attributes) || trustedQuantityValue(attributes) > 0;
    }

    private String normalizeImportance(String value) {
        if (value == null) return "NORMAL";
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if ("KEY".equals(normalized)) return "KEY";
        if (List.of("IMPORTANT", "HIGH", "重要").contains(normalized)) return "IMPORTANT";
        return "NORMAL";
    }
}
