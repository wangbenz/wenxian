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
import com.wenxian.v2.game.persistence.GameSaveRepository;
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
    private final V2Properties properties;

    public SaveApplicationService(GameSaveRepository saves, LocationRepository locations,
                                  TurnRepository turns, V2Properties properties) {
        this.saves = saves;
        this.locations = locations;
        this.turns = turns;
        this.properties = properties;
    }

    @Transactional
    public SaveAggregate create(String userId, CreateSaveCommand command) {
        validate(command);
        saves.lockUser(userId);
        if (saves.activeSaveCount(userId) >= properties.maxActiveSaves()) {
            throw V2ApiException.conflict("ACTIVE_SAVE_LIMIT_REACHED",
                    "当前账号的存活命卷已经达到上限。", "封存一份旧命卷后再创建");
        }

        Race race = Race.parse(command.race());
        Difficulty difficulty = Difficulty.parse(command.difficulty());
        RealmLadder.Realm initialRealm = RealmLadder.initial(race);
        Instant now = Instant.now();
        String saveId = "v2-save-" + UUID.randomUUID();
        String characterId = "v2-character-" + UUID.randomUUID();
        String inventoryId = "v2-inventory-" + UUID.randomUUID();
        String locationId = startingLocation(race);
        int slotNo = saves.nextSlotNo(userId);

        Save save = new Save(saveId, userId, slotNo, "ACTIVE", difficulty, characterId,
                properties.engineVersion(), properties.contentVersion(), 0, 0, now, now);
        Character character = new Character(characterId, saveId, command.name().strip(),
                command.genderExpression().strip(), race, command.rootType().strip(),
                command.rootDescription().strip(), command.lifeGoal().strip(), "ALIVE");
        CharacterState state = new CharacterState(characterId, locationId, "",
                initialRealm.code(), initialRealm.code(), initialRealm.displayName(), "INITIAL",
                initialIdentity(race, command.rootType()), "安稳", command.lifeGoal().strip(), 0);

        saves.insertSave(save);
        saves.insertWorldClock(saveId, INITIAL_WORLD_MINUTE, now);
        saves.insertCharacter(character, INITIAL_WORLD_MINUTE, now);
        saves.insertCharacterState(state, now);
        saves.insertInventory(inventoryId, saveId, characterId, now);
        saves.insertStarterKeepsake(saveId, inventoryId, characterId, command.rootDescription().strip(),
                INITIAL_WORLD_MINUTE, now);
        saves.insertWallets(saveId, characterId, now);
        saves.insertCultivation(saveId, characterId, initialRealm.code(), now);
        saves.bindBaseContent(saveId, now);
        saves.revealStartingLocations(saveId, locationId, INITIAL_WORLD_MINUTE, now);
        saves.insertInitialWorldEvent(saveId, INITIAL_WORLD_MINUTE, now);
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
        requireAggregate(saveId, userId);
        return locations.knownLocations(saveId);
    }

    public List<TurnRepository.TurnSummary> chronicle(String saveId, String userId, int limit) {
        requireAggregate(saveId, userId);
        return turns.recentTurns(saveId, limit);
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

    public record CreateSaveCommand(
            String name,
            String genderExpression,
            String race,
            String rootType,
            String rootDescription,
            String lifeGoal,
            String difficulty
    ) {
    }
}
