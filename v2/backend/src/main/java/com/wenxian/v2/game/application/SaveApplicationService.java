package com.wenxian.v2.game.application;

import com.wenxian.v2.api.V2ApiException;
import com.wenxian.v2.config.V2Properties;
import com.wenxian.v2.game.domain.Difficulty;
import com.wenxian.v2.game.domain.GameRecords.Character;
import com.wenxian.v2.game.domain.GameRecords.CharacterState;
import com.wenxian.v2.game.domain.GameRecords.Save;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.domain.Race;
import com.wenxian.v2.game.domain.RealmLadder;
import com.wenxian.v2.game.domain.WorldCalendar;
import com.wenxian.v2.game.application.OriginApplicationService.FateCandidate;
import com.wenxian.v2.game.application.OriginApplicationService.OriginRequest;
import com.wenxian.v2.game.persistence.GameSaveRepository;
import com.wenxian.v2.game.persistence.HighImpactRepository;
import com.wenxian.v2.game.persistence.LocationRepository;
import com.wenxian.v2.game.persistence.TurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SaveApplicationService {
    public static final long INITIAL_WORLD_MINUTE = WorldCalendar.toWorldMinute(3001, 1, 1, 8 * 60);

    private final GameSaveRepository saves;
    private final LocationRepository locations;
    private final TurnRepository turns;
    private final HighImpactRepository highImpact;
    private final OriginApplicationService origins;
    private final V2Properties properties;
    private final SaveCapacityService capacity;

    public SaveApplicationService(GameSaveRepository saves, LocationRepository locations,
                                  TurnRepository turns, HighImpactRepository highImpact,
                                  OriginApplicationService origins, V2Properties properties,
                                  SaveCapacityService capacity) {
        this.saves = saves;
        this.locations = locations;
        this.turns = turns;
        this.highImpact = highImpact;
        this.origins = origins;
        this.properties = properties;
        this.capacity = capacity;
    }

    @Transactional
    public SaveAggregate create(String userId, CreateSaveCommand command) {
        validate(command);
        saves.lockUser(userId);
        if (saves.activeSaveCount(userId) >= capacity.limit()) {
            throw V2ApiException.conflict("ACTIVE_SAVE_LIMIT_REACHED",
                    "当前账号的存活命卷已经达到上限。", "封存一份旧命卷后再创建");
        }

        Race race = Race.parse(command.race());
        Difficulty difficulty = Difficulty.parse(command.difficulty());
        RealmLadder.Realm initialRealm = RealmLadder.initial(race);
        Instant now = Instant.now();
        FateCandidate origin = origins.consume(userId, command.originProposalId(), command.fateCandidateId(),
                new OriginRequest(command.name(), command.genderExpression(), command.race(), command.rootType(),
                        command.rootDescription(), command.lifeGoal(), command.difficulty()), now);
        String saveId = "v2-save-" + UUID.randomUUID();
        String characterId = "v2-character-" + UUID.randomUUID();
        String inventoryId = "v2-inventory-" + UUID.randomUUID();
        String locationId = startingLocation(race);
        int slotNo = saves.nextSlotNo(userId);

        Save save = new Save(saveId, userId, slotNo, "ACTIVE", difficulty, characterId,
                properties.engineVersion(), properties.contentVersion(), 0, 0, null, now, now, null);
        Character character = new Character(characterId, saveId, command.name().strip(),
                command.genderExpression().strip(), race, command.rootType().strip(),
                command.rootDescription().strip(), command.lifeGoal().strip(), "ALIVE");
        CharacterState state = new CharacterState(characterId, locationId, "",
                initialLifeStage(race), initialRealm.code(), initialRealm.displayName(), "INITIAL",
                origin.publicIdentity(), "安稳", command.lifeGoal().strip(), 0);

        saves.insertSave(save);
        saves.insertWorldClock(saveId, INITIAL_WORLD_MINUTE, now);
        saves.insertCharacter(character, INITIAL_WORLD_MINUTE, now);
        saves.insertCharacterState(state, now);
        saves.insertCharacterOrigin(characterId, saveId, origin.title(), origin.publicIdentity(),
                origin.openingSituation(), origin.unpaidDebt(), now);
        saves.insertInventory(inventoryId, saveId, characterId, now);
        saves.insertStarterKeepsake(saveId, inventoryId, characterId, command.rootDescription().strip(),
                INITIAL_WORLD_MINUTE, now);
        saves.insertWallets(saveId, characterId, now);
        saves.insertCultivation(saveId, characterId, initialRealm.code(), now);
        saves.bindBaseContent(saveId, now);
        saves.revealStartingLocations(saveId, locationId, INITIAL_WORLD_MINUTE, now);
        saves.insertInitialWorldEvent(saveId, INITIAL_WORLD_MINUTE, now);
        if (command.starterTalentDefinitionId() != null && !command.starterTalentDefinitionId().isBlank()) {
            HighImpactRepository.AbilityDefinition talent = highImpact.abilityDefinition(command.starterTalentDefinitionId());
            if (talent == null || !"TALENT".equals(talent.type())
                    || !("ANY".equals(talent.raceScope()) || race.name().equals(talent.raceScope()))) {
                throw V2ApiException.badRequest("STARTER_TALENT_INVALID", "所选先天天赋不属于这个种族。");
            }
            highImpact.learnAbility(saveId, characterId, talent.id(), "ORIGIN", null,
                    INITIAL_WORLD_MINUTE, now);
        }
        return requireAggregate(saveId, userId);
    }

    public List<SaveAggregate> list(String userId) {
        return saves.list(userId).stream()
                .map(save -> requireAggregate(save.id(), userId))
                .toList();
    }

    public SaveAggregate requireAggregate(String saveId, String userId) {
        SaveAggregate aggregate = saves.aggregate(saveId, userId);
        if (aggregate == null) throw V2ApiException.notFound("SAVE_NOT_FOUND", "没有找到这份命卷。");
        return aggregate;
    }

    public List<LocationRepository.KnownLocation> knownWorld(String saveId, String userId) {
        SaveAggregate aggregate = requireAggregate(saveId, userId);
        return locations.knownLocations(saveId, aggregate.characterState().locationId());
    }

    public List<TurnRepository.TurnSummary> chronicle(String saveId, String userId, int limit) {
        return chronicle(saveId, userId, null, limit);
    }

    public List<TurnRepository.TurnSummary> chronicle(String saveId, String userId,
                                                       Integer beforeTurn, int limit) {
        requireAggregate(saveId, userId);
        return turns.recentTurns(saveId, beforeTurn, limit);
    }

    @Transactional
    public SaveAggregate archive(String saveId, String userId, String confirmationName) {
        saves.lockUser(userId);
        Save save = saves.lockOwned(saveId, userId);
        if (save == null) throw V2ApiException.notFound("SAVE_NOT_FOUND", "没有找到这份命卷。");
        SaveAggregate aggregate = requireAggregate(saveId, userId);
        if (!"ACTIVE".equals(save.status())) {
            throw V2ApiException.conflict("SAVE_NOT_ACTIVE", "这份命卷已经封存。", "回到命卷架查看");
        }
        if (turns.activeForUser(userId) > 0) {
            throw V2ApiException.conflict("TURN_ALREADY_RUNNING", "当前还有一轮命卷正在推演，暂时不能封存。",
                    "等待当前推演完成");
        }
        String normalized = confirmationName == null ? "" : confirmationName.strip();
        if (!aggregate.protagonist().name().equals(normalized)) {
            throw V2ApiException.badRequest("ARCHIVE_CONFIRMATION_MISMATCH", "角色名不一致，没有封存命卷。",
                    "完整输入角色名以确认");
        }
        saves.archive(saveId, aggregate.protagonist().id(), Instant.now());
        return requireAggregate(saveId, userId);
    }

    @Transactional
    public SaveAggregate endLife(String saveId, String userId, String confirmationName, String endingType) {
        saves.lockUser(userId);
        Save save = saves.lockOwned(saveId, userId);
        if (save == null) throw V2ApiException.notFound("SAVE_NOT_FOUND", "没有找到这份命卷。");
        SaveAggregate aggregate = requireAggregate(saveId, userId);
        if (!"ACTIVE".equals(save.status())) {
            throw V2ApiException.conflict("SAVE_NOT_ACTIVE", "这份命卷已经不能再作人生收束。",
                    "回到命卷架查看终卷");
        }
        if (turns.activeForUser(userId) > 0) {
            throw V2ApiException.conflict("TURN_ALREADY_RUNNING", "当前还有一轮命卷正在推演，暂时不能结束此生。",
                    "等待当前推演完成");
        }
        String normalizedName = confirmationName == null ? "" : confirmationName.strip();
        if (!aggregate.protagonist().name().equals(normalizedName)) {
            throw V2ApiException.badRequest("ENDING_CONFIRMATION_MISMATCH", "角色名不一致，此生没有结束。",
                    "完整输入角色名以确认");
        }
        String normalizedType = endingType == null ? "" : endingType.strip().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("RETIREMENT", "LEGACY").contains(normalizedType)) {
            throw V2ApiException.badRequest("ENDING_TYPE_INVALID", "请选择归隐或留下传承。");
        }
        if (save.turnNumber() < 5) {
            throw V2ApiException.conflict("LIFE_TOO_EARLY_TO_END", "此生经历尚浅，还不足以形成一份完整终卷。",
                    "至少经历五回后再决定归隐", "如只想腾出位置，可以封存命卷");
        }
        if ("LEGACY".equals(normalizedType) && save.turnNumber() < 20) {
            throw V2ApiException.conflict("LEGACY_NOT_ESTABLISHED", "当前经历还不足以留下可被后来者承接的传承。",
                    "经历至少二十回后再留下传承", "也可以选择归隐");
        }
        String title = "LEGACY".equals(normalizedType)
                ? aggregate.protagonist().name() + "传"
                : aggregate.protagonist().name() + "归隐录";
        String summary = endingSummary(aggregate, normalizedType);
        saves.endLife(saveId, aggregate.protagonist().id(), normalizedType, title, summary,
                aggregate.characterState(), save.turnNumber(), aggregate.worldMinute(), Instant.now());
        return requireAggregate(saveId, userId);
    }

    private String endingSummary(SaveAggregate aggregate, String endingType) {
        String action = "LEGACY".equals(endingType) ? "将此生所见与所得留给后来者" : "决定退出旧日纷争";
        return "%s在%s走到%s，历经%d回后%s。其最初所求是“%s”，命卷保留了这一路真实发生的得失。"
                .formatted(aggregate.protagonist().name(), aggregate.characterState().locationName(),
                        aggregate.characterState().realmDisplayName(), aggregate.save().turnNumber(),
                        action, aggregate.protagonist().lifeGoal());
    }

    private void validate(CreateSaveCommand command) {
        if (command == null) throw V2ApiException.badRequest("CREATE_SAVE_REQUIRED", "缺少命卷信息。");
        requireLength(command.name(), 1, 20, "角色姓名需要1至20个字符");
        requireLength(command.genderExpression(), 1, 20, "请选择或填写性别表达");
        requireLength(command.rootType(), 1, 40, "请填写角色根源类型");
        requireLength(command.rootDescription(), 2, 300, "角色根源需要2至300个字符");
        requireLength(command.lifeGoal(), 2, 160, "此生所求需要2至160个字符");
    }

    private void requireLength(String value, int min, int max, String message) {
        if (value == null || value.strip().length() < min || value.strip().length() > max) {
            throw V2ApiException.badRequest("CHARACTER_FACT_INVALID", message);
        }
    }

    private String startingLocation(Race race) {
        return switch (race) {
            case HUMAN -> "loc-qingmu-east-gate";
            case DEMON -> "loc-northern-road";
            case SPIRIT -> "loc-zhaogu-mine";
        };
    }

    private String initialIdentity(Race race, String rootType) {
        return switch (race) {
            case HUMAN -> "初入青岐州的人族";
            case DEMON -> "行于青岐边地的" + rootType.strip();
            case SPIRIT -> "刚刚萌生连续自我的" + rootType.strip();
        };
    }

    private String initialLifeStage(Race race) {
        return race == Race.SPIRIT ? "AWAKENED" : "YOUTH";
    }

    public record CreateSaveCommand(
            String name,
            String genderExpression,
            String race,
            String rootType,
            String rootDescription,
            String lifeGoal,
            String difficulty,
            String starterTalentDefinitionId,
            String originProposalId,
            String fateCandidateId
    ) {
        public CreateSaveCommand(String name, String genderExpression, String race, String rootType,
                                 String rootDescription, String lifeGoal, String difficulty) {
            this(name, genderExpression, race, rootType, rootDescription, lifeGoal, difficulty,
                    null, null, null);
        }

        public CreateSaveCommand(String name, String genderExpression, String race, String rootType,
                                 String rootDescription, String lifeGoal, String difficulty,
                                 String starterTalentDefinitionId) {
            this(name, genderExpression, race, rootType, rootDescription, lifeGoal, difficulty,
                    starterTalentDefinitionId, null, null);
        }
    }
}
