package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.account.AccountActivityService;
import com.wenxian.game.account.AccountActivitySource;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.service.ActiveSavePolicyService;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.domain.CreationContract;
import com.wenxian.game.story.domain.CreationContract.CharacterIdentity;
import com.wenxian.game.story.domain.CreationContract.CharacterOrigin;
import com.wenxian.game.story.domain.CreationContract.RealmBinding;
import com.wenxian.game.story.domain.CreationContract.OriginFact;
import com.wenxian.game.story.domain.CreationContract.OriginFactType;
import com.wenxian.game.story.domain.CreationContract.StoryHook;
import com.wenxian.game.story.domain.CreationContract.WorldBinding;
import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.CultivationSystemValidator;
import com.wenxian.game.story.domain.PlayPreference;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.domain.WorldPackage;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.story.model.StoryApiModels.StartingEntitySubmission;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository.StoredMemory;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StorySaveRepository;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 原子创建一份已经完成世界、身份、根源、偏好和修行确认的命卷。
 *
 * <p>世界包解析、语义校验、稳定 ID 分配与第零回合准备发生在写事务之前；事务只负责槽位裁决、
 * 命卷、实体、记忆和会话的完整提交。任一步失败都不能留下半份命卷，也不能提前删除被替换命卷。</p>
 */
@Service
public class StorySaveCreationService {
    public static final int STATE_SCHEMA_VERSION = 2;
    private static final String SAVE_PREFIX = "story-";

    private final StorySaveRepository saves;
    private final StoryEntityRepository entities;
    private final StoryMemoryRepository memories;
    private final StoryConversationRepository conversations;
    private final CharacterCreationCatalogService catalog;
    private final OpeningSceneService openings;
    private final ActiveSavePolicyService savePolicy;
    private final GameVersionService releases;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final StoryProjectionPersistenceMapper projections;
    private final AccountActivityService accountActivity;
    private final CultivationSystemValidator cultivationValidator = new CultivationSystemValidator();

    @Autowired
    public StorySaveCreationService(StorySaveRepository saves, StoryEntityRepository entities,
                                    StoryMemoryRepository memories, StoryConversationRepository conversations,
                                    CharacterCreationCatalogService catalog, OpeningSceneService openings,
                                    ActiveSavePolicyService savePolicy, GameVersionService releases,
                                    TransactionTemplate transactions, ObjectMapper mapper,
                                    AccountActivityService accountActivity) {
        this.saves = saves;
        this.entities = entities;
        this.memories = memories;
        this.conversations = conversations;
        this.catalog = catalog;
        this.openings = openings;
        this.savePolicy = savePolicy;
        this.releases = releases;
        this.transactions = transactions;
        this.mapper = mapper;
        this.projections = new StoryProjectionPersistenceMapper(mapper);
        this.accountActivity = accountActivity;
    }

    /** 兼容不加载账号生命周期表的纯故事单元测试；生产装配必须使用带活动服务的构造器。 */
    public StorySaveCreationService(StorySaveRepository saves, StoryEntityRepository entities,
                             StoryMemoryRepository memories, StoryConversationRepository conversations,
                             CharacterCreationCatalogService catalog, OpeningSceneService openings,
                             ActiveSavePolicyService savePolicy, GameVersionService releases,
                             TransactionTemplate transactions, ObjectMapper mapper) {
        this(saves, entities, memories, conversations, catalog, openings, savePolicy, releases,
                transactions, mapper, null);
    }

    public StoredSave create(String userId, CreateSaveRequest request, String engineCode, int engineVersion) {
        PreparedCreation prepared = prepare(userId, request);
        StoredSave created = transactions.execute(status -> {
            catalog.lockForCreation(userId, request.worldPackageId(), request.worldRevision());
            return commit(userId, request.replaceSaveId(), prepared, engineCode, engineVersion);
        });
        if (created == null) throw new IllegalStateException("命卷创建事务没有返回结果");
        return created;
    }

    private PreparedCreation prepare(String userId, CreateSaveRequest request) {
        WorldPackage world = catalog.resolveForCreation(userId, request.worldPackageId(), request.worldRevision());
        WorldPackage.RaceDefinition race = world.races().stream()
                .filter(value -> value.raceId().equals(request.identity().raceId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选种族不属于当前世界包修订"));
        WorldPackage.RegionDefinition region = world.regions().stream()
                .filter(value -> value.regionId().equals(request.opening().regionId()) && value.openingEligible())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("所选地域不能作为当前世界的开场"));
        Instant now = Instant.now();
        validateRequiredIdentityFact(race, request);
        PlayPreference preference = preference(request, now);
        ConfirmedCultivation cultivation = cultivation(world, race, preference, now);
        String saveId = SAVE_PREFIX + UUID.randomUUID();

        Map<String, String> entityIds = new HashMap<>();
        for (StartingEntitySubmission entity : request.origin().startingEntities()) {
            if (entityIds.putIfAbsent(entity.localRef(), entityId(entity.entityType())) != null) {
                throw new IllegalArgumentException("开局实体局部引用不能重复");
            }
        }
        CreationContract contract = new CreationContract(
                new WorldBinding(world.packageId(), world.revision(), world.name(), world.premise(), region.regionId()),
                new CharacterIdentity(request.identity().name(), request.identity().genderCode(), race.raceId(), race.name()),
                new CharacterOrigin(request.origin().summary(), request.origin().establishedFacts().stream()
                        .map(value -> new OriginFact(value.localRef(), OriginFactType.valueOf(value.type()),
                                value.statement())).toList(), request.origin().openHooks().stream()
                        .map(value -> new StoryHook(value.localRef(), value.summary())).toList(),
                        request.origin().startingEntities().stream().map(value -> entityIds.get(value.localRef())).toList()),
                new CreationContract.Opening(region.regionId(), request.opening().situation()),
                new RealmBinding(world.realmScale().scaleId(), world.realmScale().revision(), "L1",
                        WorldPackage.RealmPhase.EARLY));
        OpeningSceneService.PreparedScene scene = request.opening().scene() != null
                ? openings.confirmed(request.opening())
                : openings.prepare(world, race, region, request.identity().name(),
                request.origin().summary(), request.opening().situation(), preference,
                request.origin().startingEntities().stream().map(StartingEntitySubmission::name).toList(),
                request.origin().openHooks().stream().map(value -> value.summary()).toList());
        ObjectNode state = state(saveId, contract, preference, cultivation, region, scene);
        List<Entity> startingEntities = request.origin().startingEntities().stream()
                .map(value -> entity(saveId, entityIds.get(value.localRef()), value, region, now)).toList();
        return new PreparedCreation(saveId, world, contract, state, startingEntities, scene, now);
    }

    private StoredSave commit(String userId, String replaceSaveId, PreparedCreation prepared,
                              String engineCode, int engineVersion) {
        int maximum = savePolicy.lockMaximum();
        Integer replacementSlot = null;
        if (replaceSaveId != null && !replaceSaveId.isBlank()) {
            StoredSave replacement = saves.findOwned(userId, replaceSaveId.strip())
                    .orElseThrow(() -> new IllegalArgumentException("待替换命卷不存在或不属于当前账号"));
            replacementSlot = replacement.activeSlotNo();
            saves.archiveForReplacement(userId, replacement.id());
        }
        if (saves.countActive(userId) >= maximum) {
            throw GameApiException.of(HttpStatus.CONFLICT, "ACTIVE_SAVE_LIMIT_REACHED", "STATE",
                    "当前账号的存活命卷已经达到上限", "请先选择一份命卷重新创角，或删除不再游玩的命卷。",
                    List.of("返回我的命卷", "选择一份命卷重新创角"));
        }
        int slotNo = saves.nextSlotNo(userId);
        int activeSlotNo = replacementSlot == null ? saves.nextActiveSlotNo(userId, maximum) : replacementSlot;
        String release = releases.current();
        saves.insert(new NewSave(prepared.saveId(), userId, slotNo, activeSlotNo, "ACTIVE",
                prepared.world().packageId(), engineCode, engineVersion, STATE_SCHEMA_VERSION, "NATIVE", release,
                null, null, prepared.state(), 0, release, release, prepared.createdAt()));
        prepared.entities().forEach(entities::insert);
        memories.insert(prepared.saveId(), new StoredMemory("memory-" + UUID.randomUUID(), "EVENT", "此生来处",
                prepared.contract().origin().summary(), 0, StoryWorldTime.opening().toOrder(), "KEY"));
        conversations.initialize(prepared.saveId(), prepared.scene().narrative(), 0);
        if (accountActivity != null) {
            accountActivity.record(userId, AccountActivitySource.SAVE_CREATED, prepared.createdAt());
        }
        return saves.findOwned(userId, prepared.saveId())
                .orElseThrow(() -> new IllegalStateException("命卷创建后无法回读：" + prepared.saveId()));
    }

    private ObjectNode state(String saveId, CreationContract contract, PlayPreference preference,
                             ConfirmedCultivation cultivation, WorldPackage.RegionDefinition region,
                             OpeningSceneService.PreparedScene scene) {
        ObjectNode state = mapper.createObjectNode();
        state.put("schemaVersion", STATE_SCHEMA_VERSION);
        state.put("saveId", saveId);
        state.put("turnNumber", 0);
        state.put("status", "ACTIVE");
        state.set("worldTime", mapper.valueToTree(StoryWorldTime.opening()));
        ObjectNode core = state.putObject("coreState");
        core.put("currentLocationRef", region.regionId());
        core.put("currentLocationName", region.name());
        core.put("condition", "神完气足");
        core.put("lifeGoal", "");
        ObjectNode stored = projections.writeInitialCreation(state, contract, preference,
                cultivation.system(), cultivation.protagonist());
        ObjectNode sceneNode = stored.putObject("scene");
        sceneNode.put("title", scene.title());
        sceneNode.put("narrative", scene.narrative());
        sceneNode.put("situation", scene.situation());
        sceneNode.putNull("pendingDecision");
        sceneNode.set("choices", mapper.valueToTree(scene.choices().stream()
                .map(value -> new SceneChoice("choice-" + UUID.randomUUID(), value.title(), value.description()))
                .toList()));
        // 第零回合没有 story_turn 行；保留原开场，避免第一回合更新当前场景和摘要时覆盖唯一原件。
        stored.set("openingScene", sceneNode.deepCopy());
        return stored;
    }

    private PlayPreference preference(CreateSaveRequest request, Instant now) {
        var value = request.playPreference();
        return new PlayPreference(value.focus().stream().map(PlayPreference.Focus::valueOf).toList(),
                PlayPreference.CombatFrequency.valueOf(value.combatFrequency()),
                PlayPreference.Pace.valueOf(value.pace()), PlayPreference.Guidance.valueOf(value.guidance()),
                value.additionalNote(), now);
    }

    private ConfirmedCultivation cultivation(WorldPackage world, WorldPackage.RaceDefinition race,
                                              PlayPreference preference, Instant boundAt) {
        var scale = world.realmScale();
        List<WorldPackage.RealmRank> orderedRanks = scale.ranks().stream()
                .sorted(java.util.Comparator.comparingInt(WorldPackage.RealmRank::order)).toList();
        List<CultivationSystem.Stage> stages = orderedRanks.stream().map(rank -> {
            var stage = rank.raceStages().get(race.raceId());
            String next = rank.order() == orderedRanks.size() ? null
                    : orderedRanks.get(rank.order()).raceStages().get(race.raceId()).stageId();
            return new CultivationSystem.Stage(stage.stageId(), rank.order(), stage.name(), stage.description(),
                    stage.aliases(), next == null ? List.of() : List.of(next));
        }).toList();
        var starting = scale.startingStage(race.raceId());
        CultivationSystem system = new CultivationSystem(scale.scaleId(), Math.toIntExact(scale.revision()),
                world.name() + "公共境界", "世界包固定的三族八境公共标尺。", race.name(), starting.stageId(), stages,
                new CultivationSystem.Confirmation(CultivationSystem.ConfirmationSource.WORLD_PACKAGE_FIXED,
                        boundAt));
        int requiredMarks = switch (preference.pace()) {
            case FAST -> 1;
            case BALANCED -> 2;
            case RELAXED -> 3;
        };
        ProtagonistCultivation protagonist = new ProtagonistCultivation(system.systemId(), starting.stageId(),
                WorldPackage.RealmPhase.EARLY, 0, requiredMarks, "");
        cultivationValidator.validateState(system, protagonist);
        return new ConfirmedCultivation(system, protagonist);
    }

    /** 妖族本相与灵族本源必须在创角时成为可观察的身份事实，不能留给模型随机补全。 */
    private void validateRequiredIdentityFact(WorldPackage.RaceDefinition race, CreateSaveRequest request) {
        catalog.validateOriginFacts(request.worldPackageId(), request.worldRevision(), race.raceId(),
                request.origin().establishedFacts().stream().map(value -> value.type()).collect(java.util.stream.Collectors.toSet()));
    }

    private Entity entity(String saveId, String entityId, StartingEntitySubmission source,
                          WorldPackage.RegionDefinition region, Instant now) {
        ObjectNode attributes = mapper.createObjectNode();
        attributes.put("source", "角色根源");
        ObjectNode trusted = attributes.putObject("_trusted");
        String status;
        switch (source.entityType()) {
            case "ITEM" -> {
                status = "ACTIVE";
                attributes.put("itemType", "根源物品");
                trusted.set("quantity", mapper.valueToTree(new com.wenxian.game.story.domain.StoryPatch.QuantityValue(false, null)));
                trusted.set("custody", mapper.valueToTree(new com.wenxian.game.story.domain.StoryPatch.CustodyValue(
                        com.wenxian.game.story.domain.StoryPatch.HolderType.PLAYER, null)));
            }
            case "ABILITY" -> {
                status = "KNOWN";
                attributes.put("abilityType", "根源能力");
                trusted.put("abilityState", "KNOWN");
            }
            case "CHARACTER" -> {
                status = "ACTIVE";
                attributes.put("tier", "KNOWN");
                attributes.put("identity", source.summary());
                attributes.put("lastKnownLocation", region.name());
                trusted.put("lifecycle", "ACTIVE");
                trusted.set("relationship", mapper.valueToTree(
                        new com.wenxian.game.story.domain.StoryPatch.RelationshipValue("创角既有关系", "角色根源确认")));
            }
            default -> throw new IllegalArgumentException("不支持的开局实体类型：" + source.entityType());
        }
        return new Entity(entityId, saveId, source.entityType(), source.name(), status, true, source.summary(),
                attributes, 0, 0, now, now);
    }

    private String entityId(String type) {
        return switch (type) {
            case "ITEM" -> "item-";
            case "ABILITY" -> "ability-";
            case "CHARACTER" -> "character-";
            default -> throw new IllegalArgumentException("不支持的开局实体类型：" + type);
        } + UUID.randomUUID();
    }

    private record PreparedCreation(String saveId, WorldPackage world, CreationContract contract,
                                    ObjectNode state, List<Entity> entities,
                                    OpeningSceneService.PreparedScene scene, Instant createdAt) {}

    private record ConfirmedCultivation(CultivationSystem system, ProtagonistCultivation protagonist) {}

    private record SceneChoice(String id, String title, String description) {}
}
