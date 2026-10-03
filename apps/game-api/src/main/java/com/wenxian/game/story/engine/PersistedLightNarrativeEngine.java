package com.wenxian.game.story.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.service.ActiveSavePolicyService;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.application.CharacterRouteGenerationService;
import com.wenxian.game.story.application.CharacterCreationCatalogService;
import com.wenxian.game.story.application.StoryTurnCommitService;
import com.wenxian.game.story.application.PreparedStoryTurn;
import com.wenxian.game.story.application.StorySaveViewService;
import com.wenxian.game.story.application.StorySaveCreationService;
import com.wenxian.game.story.application.StoryTurnFailureMapper;
import com.wenxian.game.credit.PlatformTurnAccessService;
import com.wenxian.game.story.application.TrustedStoryTurnPreparationService;
import com.wenxian.game.story.agent.ai.StoryCandidateGenerator.Session;
import com.wenxian.game.story.domain.StoryRuntimeState;
import com.wenxian.game.story.domain.WorldPackage;
import com.wenxian.game.story.model.StoryApiModels.BootstrapResponse;
import com.wenxian.game.story.model.StoryApiModels.CatalogOption;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteRequest;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteResponse;
import com.wenxian.game.story.model.StoryApiModels.CharacterOriginSubmission;
import com.wenxian.game.story.model.StoryApiModels.OriginFactSubmission;
import com.wenxian.game.story.model.StoryApiModels.OpeningSubmission;
import com.wenxian.game.story.model.StoryApiModels.StartingEntitySubmission;
import com.wenxian.game.story.model.StoryApiModels.StoryHookSubmission;
import com.wenxian.game.story.model.StoryApiModels.CreationCatalog;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.story.model.StoryApiModels.EngineDescriptor;
import com.wenxian.game.story.model.StoryApiModels.PlayPreferenceOptions;
import com.wenxian.game.story.model.StoryApiModels.RaceOption;
import com.wenxian.game.story.model.StoryApiModels.RegionOption;
import com.wenxian.game.story.model.StoryApiModels.RealmRankOption;
import com.wenxian.game.story.model.StoryApiModels.RealmScaleOption;
import com.wenxian.game.story.model.StoryApiModels.RaceRealmOption;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.SaveSummary;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;
import com.wenxian.game.story.model.StoryApiModels.WorldPackageOption;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryCorrectionRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository.StoredMemory;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 基于 story_* 通用表的轻叙事运行时。
 *
 * <p>该引擎是迁移后的权威读写边界：命卷快照、实体、记忆、回合、摘要和额度必须在同一事务中提交。
 * 普通回合统一使用类型化回合链：先读取权威状态并生成导演计划，再由确定性编译、校验与归并器
 * 计算新状态，最后生成叙事并在同一事务内提交。历史版本不再注册在线运行时，也不能成为普通回合的回退路径。</p>
 */
@Component
public class PersistedLightNarrativeEngine implements StoryEngine {
    private static final String ENGINE_CODE = "LIGHT_NARRATIVE";
    private static final int ENGINE_VERSION = 1;
    private static final int STATE_SCHEMA_VERSION = StorySaveCreationService.STATE_SCHEMA_VERSION;
    private final StorySaveRepository saves;
    private final StoryTurnRepository turns;
    private final StoryEntityRepository entities;
    private final StoryCorrectionRepository corrections;
    private final TrustedStoryTurnPreparationService trustedTurnPreparation;
    private final StoryTurnCommitService trustedTurnCommit;
    private final CharacterCreationCatalogService creationCatalog;
    private final CharacterRouteGenerationService characterRoutes;
    private final StorySaveCreationService saveCreation;
    private final ActiveSavePolicyService savePolicy;
    private final PlatformTurnAccessService turnAccess;
    private final GameVersionService releases;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final StorySaveViewService saveViews;
    private final StoryTurnFailureMapper turnFailures;
    private final Map<String, Object> accountLocks = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> turnsInProgress = new ConcurrentHashMap<>();

    public PersistedLightNarrativeEngine(
            StorySaveRepository saves,
            StoryTurnRepository turns,
            StoryEntityRepository entities,
            StoryCorrectionRepository corrections,
            TrustedStoryTurnPreparationService trustedTurnPreparation,
            StoryTurnCommitService trustedTurnCommit,
            CharacterCreationCatalogService creationCatalog,
            CharacterRouteGenerationService characterRoutes,
            StorySaveCreationService saveCreation,
            ActiveSavePolicyService savePolicy,
            PlatformTurnAccessService turnAccess,
            GameVersionService releases,
            TransactionTemplate transactions,
            ObjectMapper mapper,
            StorySaveViewService saveViews,
            StoryTurnFailureMapper turnFailures) {
        this.saves = saves;
        this.turns = turns;
        this.entities = entities;
        this.corrections = corrections;
        this.trustedTurnPreparation = trustedTurnPreparation;
        this.trustedTurnCommit = trustedTurnCommit;
        this.creationCatalog = creationCatalog;
        this.characterRoutes = characterRoutes;
        this.saveCreation = saveCreation;
        this.savePolicy = savePolicy;
        this.turnAccess = turnAccess;
        this.releases = releases;
        this.transactions = transactions;
        this.mapper = mapper;
        this.saveViews = saveViews;
        this.turnFailures = turnFailures;
    }

    @Override
    public EngineDescriptor descriptor() {
        return new EngineDescriptor(ENGINE_CODE, ENGINE_VERSION, STATE_SCHEMA_VERSION);
    }

    @Override
    public BootstrapResponse bootstrap() {
        var catalog = creationCatalog.current();
        var world = catalog.world();
        var scale = world.realmScale();
        WorldPackageOption worldOption = new WorldPackageOption(world.packageId(), world.revision(), world.name(),
                world.premise(), world.coreRules(), world.narrativeGuidance(),
                world.races().stream().map(race -> new RaceOption(race.raceId(), race.name(),
                        race.description(), race.identityFacts(), race.socialHooks())).toList(),
                world.openingRegions().stream().map(region -> new RegionOption(region.regionId(),
                        region.name(), region.description())).toList(),
                new RealmScaleOption(scale.scaleId(), scale.revision(), scale.phases(),
                        scale.ranks().stream().map(rank -> new RealmRankOption(rank.rankKey(), rank.order(),
                                rank.raceRealms().stream().map(realm -> new RaceRealmOption(realm.raceId(),
                                        realm.stageId(), realm.name(), realm.description(), realm.capabilityBoundary(),
                                        realm.aliases())).toList())).toList()));
        var preferences = catalog.playPreferences();
        PlayPreferenceOptions playPreferences = new PlayPreferenceOptions(
                preferences.focus().stream().map(this::catalogOption).toList(),
                preferences.combatFrequency().stream().map(this::catalogOption).toList(),
                preferences.pace().stream().map(this::catalogOption).toList(),
                preferences.guidance().stream().map(this::catalogOption).toList(),
                preferences.defaultFocus(), preferences.defaultCombatFrequency(),
                preferences.defaultPace(), preferences.defaultGuidance());
        return new BootstrapResponse(releases.current(), savePolicy.maximum(), descriptor(),
                new CreationCatalog(worldOption, playPreferences), List.of("WORLD_REALM_SCALE_V1"));
    }

    private CatalogOption catalogOption(CharacterCreationCatalogService.CatalogOption option) {
        return new CatalogOption(option.code(), option.name(), option.description());
    }

    @Override
    public CharacterRouteResponse characterRoutes(UserIdentity user, CharacterRouteRequest request) {
        WorldPackage world = creationCatalog.resolveForCreation(user.userId(), request.worldPackageId(), request.worldRevision());
        WorldPackage.RaceDefinition race = world.races().stream()
                .filter(value -> value.raceId().equals(request.identity().raceId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选种族不属于当前世界包修订"));
        WorldPackage.RegionDefinition openingRegion = world.defaultOpeningRegion();
        var result = characterRoutes.generate(user.userId(), new CharacterRouteGenerationService.Input(
                world.packageId(), world.revision(), world.name(), world.premise(), world.coreRules(),
                world.narrativeGuidance(), openingRegion.regionId(), openingRegion.name(), request.identity().name(),
                race.raceId(), race.name(), request.identity().genderCode(), request.roleSeed(), request.preferenceSeed(),
                request.mode(), world.realmScale().startingStage(race.raceId()).name() + "初期"));
        return new CharacterRouteResponse(result.stream()
                .map(route -> new com.wenxian.game.story.model.StoryApiModels.CharacterRoute(
                        route.id(), route.title(), new CharacterOriginSubmission(route.origin(),
                        java.util.stream.IntStream.range(0, route.establishedFacts().size())
                                .mapToObj(index -> new OriginFactSubmission("origin:fact:" + (index + 1),
                                        "PAST_EVENT", route.establishedFacts().get(index))).toList(),
                        java.util.stream.IntStream.range(0, route.openHooks().size())
                                .mapToObj(index -> new StoryHookSubmission("origin:hook:" + (index + 1),
                                        route.openHooks().get(index))).toList(),
                        route.startingEntities().stream().map(value -> new StartingEntitySubmission(
                                value.localRef(), value.entityType(), value.name(), value.summary())).toList()),
                        new OpeningSubmission(openingRegion.regionId(), route.opening(),
                                new com.wenxian.game.story.model.StoryApiModels.OpeningSceneSubmission(
                                        route.scene().title(), route.scene().narrative(), route.scene().choices().stream()
                                        .map(choice -> new com.wenxian.game.story.model.StoryApiModels.OpeningChoiceSubmission(
                                                choice.title(), choice.description())).toList()))))
                .toList());
    }

    @Override
    public SaveResponse create(UserIdentity user, CreateSaveRequest request) {
        synchronized (accountLock(user.userId())) {
            StoredSave created = saveCreation.create(user.userId(), request, ENGINE_CODE, ENGINE_VERSION);
            return saveViews.response(user.userId(), created, saveViews.read(created), ENGINE_CODE);
        }
    }

    @Override
    public List<SaveSummary> list(UserIdentity user) {
        return saves.listOwnedIndex(user.userId()).stream()
                .map(saved -> saveViews.summary(saved, descriptor()))
                .toList();
    }

    @Override
    public SaveResponse get(UserIdentity user, String saveId) {
        StoredSave saved = requireOwned(user, saveId);
        return saveViews.response(user.userId(), saved, saveViews.read(saved), ENGINE_CODE);
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
        Session session = trustedTurnPreparation.openSession(saved, request.requestId());
        PlatformTurnAccessService.Permit turnPermit = turnAccess.reserve(
                user.userId(), saveId, request.requestId(), session.credentialSource(),
                session.servicePlanSelection().orElse(null));

        return takeTrustedTurn(user, saved, request, session, turnPermit);
    }

    private SaveResponse takeTrustedTurn(UserIdentity user, StoredSave saved, TurnRequest request,
                                         Session session,
                                         PlatformTurnAccessService.Permit turnPermit) {
        PreparedStoryTurn prepared;
        try {
            prepared = trustedTurnPreparation.prepare(saved, request.requestId(), request.action(),
                    request.choiceId(), session);
        } catch (RuntimeException error) {
            releaseTurnAfterFailure(turnPermit, "可信剧情生成或审阅失败，释放推演点", error);
            throw turnFailures.map(user.userId(), session.credentialSource(), error);
        }
        try {
            trustedTurnCommit.commit(user.userId(), saved, request.requestId(), request.action(),
                    prepared, turnPermit);
        } catch (RuntimeException error) {
            if (prepared.diagnostic() != null) prepared.diagnostic().failed("COMMIT_FAILED", error);
            releaseTurnAfterFailure(turnPermit, "可信剧情事务未能提交，释放推演点", error);
            if (error instanceof OptimisticLockingFailureException) {
                throw new GameApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", "STATE",
                        "命卷已经在别处发生变化", "已阻止覆盖较新的记录，本次未消耗回合。",
                        List.of("刷新当前命卷后继续"), true, false, false, Map.of(),
                        "trusted story turn revision conflict", error);
            }
            throw error;
        }
        StoredSave updated = requireOwned(user, saved.id());
        return saveViews.response(user.userId(), updated, saveViews.read(updated), ENGINE_CODE);
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
    public void deleteSave(UserIdentity user, String saveId) {
        synchronized (accountLock(user.userId())) {
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
                        saved.stateSchemaVersion(), mapper.valueToTree(target),
                        mapper.createObjectNode().put("visible", false));
                return next;
            });
            StoredSave updated = withRevision(saved, revision == null ? saved.revision() + 1 : revision,
                    saved.state(), saved.turnNumber());
            return saveViews.response(user.userId(), updated, saveViews.read(updated), ENGINE_CODE);
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

    private StoredSave withRevision(StoredSave saved, long revision, JsonNode state, int turnNumber) {
        return new StoredSave(saved.id(), saved.userId(), saved.slotNo(), saved.activeSlotNo(), saved.status(), saved.worldCode(),
                saved.engineCode(), saved.engineVersion(), saved.stateSchemaVersion(), saved.sourceType(),
                saved.sourceEdition(), saved.sourceSaveId(), saved.parentSaveId(), state, revision, turnNumber,
                saved.createdRelease(), releases.current(), saved.createdAt(), Instant.now(),
                saved.deletedAt(), saved.deletedBy(), saved.deleteReason());
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
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

}
