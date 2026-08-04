package com.wenxian.game.v014.service;

import com.wenxian.game.api.GameApiException;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.service.ActiveSavePolicyService;
import com.wenxian.game.service.PlayerAiService;
import com.wenxian.game.v014.ai.V014StoryAgent;
import com.wenxian.game.v014.model.V014ApiModels.CreateSaveRequest;
import com.wenxian.game.v014.model.V014ApiModels.SaveResponse;
import com.wenxian.game.v014.model.V014ApiModels.SaveSummary;
import com.wenxian.game.v014.model.V014State;
import com.wenxian.game.v014.model.V014State.Choice;
import com.wenxian.game.v014.model.V014State.Character;
import com.wenxian.game.v014.model.V014State.Item;
import com.wenxian.game.v014.model.V014State.Memory;
import com.wenxian.game.v014.model.V014State.Protagonist;
import com.wenxian.game.v014.persistence.V014SaveStore;
import com.wenxian.game.v014.persistence.V014TurnStore;
import com.wenxian.game.v014.service.V014ModelPreferenceService.ResolvedRoute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class V014GameService {
    private static final String FIELD_VALUE_END = "([^；;\\n]{1,%d})";
    private static final Pattern LOCATION = correctionField("(?:当前)?(?:地点|位置)", 60);
    private static final Pattern REALM = correctionField("(?:当前)?(?:境界|修为)", 40);
    private static final Pattern CONDITION = correctionField("(?:当前)?(?:状态|伤势)", 80);
    private static final Pattern ITEM_STATUS = Pattern.compile("(?:物品)?[《「]?([^》」]{1,40})[》」]?(?:已经|应当)?(消耗|卖出|赠予|遗失|存放)");

    private final V014SaveStore saves;
    private final V014TurnStore turns;
    private final V014WorldCatalog worlds;
    private final V014StoryAgent agent;
    private final V014StateReducer reducer;
    private final V014ModelPreferenceService models;
    private final ActiveSavePolicyService savePolicy;
    private final PlayerAiService playerAi;
    private final TransactionTemplate transactions;
    private final String baseUrl;
    private final Map<String, Object> accountLocks = new ConcurrentHashMap<>();

    public V014GameService(V014SaveStore saves, V014TurnStore turns, V014WorldCatalog worlds,
                           V014StoryAgent agent, V014StateReducer reducer,
                           V014ModelPreferenceService models, ActiveSavePolicyService savePolicy,
                           PlayerAiService playerAi, TransactionTemplate transactions,
                           @Value("${deepseek.base-url:https://api.deepseek.com}") String baseUrl) {
        this.saves = saves;
        this.turns = turns;
        this.worlds = worlds;
        this.agent = agent;
        this.reducer = reducer;
        this.models = models;
        this.savePolicy = savePolicy;
        this.playerAi = playerAi;
        this.transactions = transactions;
        this.baseUrl = baseUrl;
    }

    public SaveResponse create(UserIdentity user, CreateSaveRequest request) {
        synchronized (accountLock(user.userId())) {
            return createLocked(user, request);
        }
    }

    private SaveResponse createLocked(UserIdentity user, CreateSaveRequest request) {
        String saveId = "v014-save-" + UUID.randomUUID();
        V014State.WorldProfile world = worlds.resolve(request.worldCode(), request.customWorld());
        String goal = "尚未定下，随此生经历逐渐形成";
        String tone = text(request.storyTone(), "自由探索，机缘与风险并存");
        Protagonist protagonist = new Protagonist(request.playerName().strip(), request.race().strip(),
                request.gender().strip(), request.origin().strip(), initialRealm(request.race()), goal, tone);
        String opening = text(request.opening(), "你的人生并非从同一扇门开始。此刻，你身在"
                + world.openingRegion() + "，过往出身已经落定，前路却仍由自己选择。");
        V014State state = new V014State(14, saveId, 0, "ACTIVE", world, protagonist, 0,
                world.openingRegion(), "神完气足", "命卷初开", opening,
                List.of(new Choice(id("choice"), "观察周围", "先弄清自己身处何地与眼前局势。"),
                        new Choice(id("choice"), "追寻旧因", "从自己的出身与未竟之事开始行动。"),
                        new Choice(id("choice"), "主动寻机缘", "不等待命运安排，寻找修行的第一步。")),
                List.of(), List.of(), List.of(),
                List.of(new Memory(id("memory"), "此生来处", protagonist.origin(), 0, "KEY")));
        transactions.executeWithoutResult(status -> {
            int maximum = savePolicy.lockMaximum();
            int selected = saves.nextSlot(user.userId(), maximum);
            if (selected == 0) {
                throw GameApiException.of(HttpStatus.CONFLICT, "V014_ACTIVE_SAVE_LIMIT_REACHED", "STATE",
                        "当前账号的存活命卷已经达到上限",
                        "请先继续现有命卷，或将不再游玩的角色封存为只读档案。",
                        List.of("返回我的命卷", "封存一名现有角色"));
            }
            saves.insert(user.userId(), selected, state);
        });
        return response(user.userId(), state, 1);
    }

    public List<SaveSummary> list(UserIdentity user) { return saves.list(user.userId()); }

    public SaveResponse get(UserIdentity user, String saveId) {
        V014SaveStore.StoredSave saved = saves.requireOwned(user.userId(), saveId);
        return response(user.userId(), withStatus(saved.state(), saved.status()), saved.revision());
    }

    public SaveResponse takeTurn(UserIdentity user, String saveId, String action, String suppliedRequestId) {
        synchronized (accountLock(user.userId())) {
            return takeTurnLocked(user, saveId, action, suppliedRequestId);
        }
    }

    private SaveResponse takeTurnLocked(UserIdentity user, String saveId, String action, String suppliedRequestId) {
        V014SaveStore.StoredSave saved = saves.requireOwned(user.userId(), saveId);
        if (!"ACTIVE".equals(saved.status())) {
            throw GameApiException.of(HttpStatus.CONFLICT, "V014_SAVE_NOT_ACTIVE", "STATE",
                    "这份命卷已经封存", "封存命卷只能阅读，不能继续行动。", List.of("选择其他命卷", "创建新角色"));
        }
        String requestId = suppliedRequestId == null || suppliedRequestId.isBlank()
                ? "v014-request-" + UUID.randomUUID() : suppliedRequestId.strip();
        if (turns.requestExists(saveId, requestId)) return get(user, saveId);
        ResolvedRoute route = models.resolve(user.userId(), baseUrl);
        PlayerAiService.Route quotaRoute = quotaRoute(route);
        playerAi.assertPublicQuota(user.userId(), quotaRoute);
        V014StoryAgent.Generated generated = agent.generate(user.userId(), saved.state(), turns.summary(saveId),
                turns.recent(saveId, 2), action, route);
        V014StateReducer.Applied applied = reducer.apply(saved.state(), action, generated.envelope());
        Long revision = transactions.execute(status -> {
            long next = saves.update(saveId, saved.revision(), applied.state());
            turns.insert(saveId, requestId, applied.state().turnNumber(), action,
                    applied.state().narrative(), applied.state().choices(), generated.rawPatchJson(),
                    saved.state().worldTimeMinutes(), applied.state().worldTimeMinutes(), route.provider(),
                    route.model(), route.credentialSource(), generated.result().inputTokens(),
                    generated.result().outputTokens(), generated.result().latencyMs());
            if (!applied.rollingSummary().isBlank()) {
                turns.updateSummary(saveId, applied.rollingSummary(), applied.state().turnNumber());
            }
            playerAi.recordSuccessfulInvocation(user.userId(), quotaRoute);
            return next;
        });
        return playerResponse(applied.state(), revision == null ? saved.revision() + 1 : revision,
                route.credentialSource(), route.provider(), route.model());
    }

    public SaveResponse correct(UserIdentity user, String saveId, String instruction) {
        synchronized (accountLock(user.userId())) {
            return correctLocked(user, saveId, instruction);
        }
    }

    private SaveResponse correctLocked(UserIdentity user, String saveId, String instruction) {
        V014SaveStore.StoredSave saved = saves.requireOwned(user.userId(), saveId);
        if (!"ACTIVE".equals(saved.status())) {
            throw GameApiException.of(HttpStatus.CONFLICT, "V014_SAVE_NOT_ACTIVE", "STATE",
                    "这份命卷已经封存", "封存命卷只能阅读，不能再修正记录。", List.of("选择其他命卷", "创建新角色"));
        }
        V014State before = saved.state();
        V014State after = corrected(before, instruction.strip());
        Long revision = transactions.execute(status -> {
            long next = saves.update(saveId, saved.revision(), after);
            saves.recordCorrection(user.userId(), saveId, instruction.strip(), before, after);
            return next;
        });
        return response(user.userId(), after, revision == null ? saved.revision() + 1 : revision);
    }

    public SaveResponse removeItem(UserIdentity user, String saveId, String itemId, long expectedRevision) {
        synchronized (accountLock(user.userId())) {
            return removeItemLocked(user, saveId, itemId, expectedRevision);
        }
    }

    private SaveResponse removeItemLocked(UserIdentity user, String saveId, String itemId, long expectedRevision) {
        V014SaveStore.StoredSave saved = requireActive(user, saveId, expectedRevision);
        V014State before = saved.state();
        List<Item> inventory = new ArrayList<>(before.inventory());
        int index = -1;
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.get(i).id().equals(itemId) && Boolean.TRUE.equals(inventory.get(i).inPossession())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw GameApiException.of(HttpStatus.NOT_FOUND, "V014_ITEM_NOT_IN_BAG", "STATE",
                    "行囊中没有这件物品", "它可能已经被整理，或命卷刚刚在其他页面发生了变化。",
                    List.of("刷新角色页后再查看"));
        }
        Item old = inventory.get(index);
        inventory.set(index, new Item(old.id(), old.name(), old.type(), old.description(),
                "已由玩家移出行囊", old.source(), old.quantity(), false));
        V014State after = copy(before, inventory, before.characters());
        long revision = persistManualChange(user.userId(), saved, after, "移出行囊：" + old.name());
        return response(user.userId(), after, revision);
    }

    public SaveResponse untrackCharacter(UserIdentity user, String saveId, String characterId, long expectedRevision) {
        synchronized (accountLock(user.userId())) {
            return untrackCharacterLocked(user, saveId, characterId, expectedRevision);
        }
    }

    private SaveResponse untrackCharacterLocked(UserIdentity user, String saveId, String characterId, long expectedRevision) {
        V014SaveStore.StoredSave saved = requireActive(user, saveId, expectedRevision);
        V014State before = saved.state();
        List<Character> characters = new ArrayList<>(before.characters());
        int index = -1;
        for (int i = 0; i < characters.size(); i++) {
            if (characters.get(i).id().equals(characterId) && Boolean.TRUE.equals(characters.get(i).tracked())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw GameApiException.of(HttpStatus.NOT_FOUND, "V014_CHARACTER_NOT_TRACKED", "STATE",
                    "当前没有持续关注这个人物", "这段相识仍保留在历史中，只是不再出现在当前人物列表。",
                    List.of("刷新角色页后再查看"));
        }
        Character old = characters.get(index);
        characters.set(index, new Character(old.id(), old.name(), old.tier(), old.identity(),
                old.relationship(), old.status(), old.lastKnownLocation(), false));
        V014State after = copy(before, before.inventory(), characters);
        long revision = persistManualChange(user.userId(), saved, after, "不再关注：" + old.name());
        return response(user.userId(), after, revision);
    }

    public void archive(UserIdentity user, String saveId) {
        synchronized (accountLock(user.userId())) {
            saves.archive(user.userId(), saveId);
        }
    }

    private V014State corrected(V014State before, String instruction) {
        String location = match(LOCATION, instruction, before.currentLocation());
        String condition = match(CONDITION, instruction, before.condition());
        String realm = match(REALM, instruction, before.protagonist().realm());
        List<Item> inventory = new ArrayList<>(before.inventory());
        Matcher item = ITEM_STATUS.matcher(instruction);
        boolean changed = !location.equals(before.currentLocation()) || !condition.equals(before.condition())
                || !realm.equals(before.protagonist().realm());
        if (item.find()) {
            String name = item.group(1).strip();
            String status = switch (item.group(2)) {
                case "卖出" -> "SOLD"; case "赠予" -> "GIFTED"; case "遗失" -> "LOST";
                case "存放" -> "STORED"; default -> "CONSUMED";
            };
            for (int index = 0; index < inventory.size(); index++) {
                Item old = inventory.get(index);
                if (old.name().equalsIgnoreCase(name)) {
                    inventory.set(index, new Item(old.id(), old.name(), old.type(), old.description(),
                            status, old.source(), old.quantity(), !List.of("SOLD", "GIFTED", "LOST", "CONSUMED").contains(status)));
                    changed = true;
                }
            }
        }
        if (!changed) {
            throw GameApiException.of(HttpStatus.BAD_REQUEST, "CORRECTION_NOT_UNDERSTOOD", "INPUT",
                    "还不能确认要修正什么", "请明确写出地点、境界、状态，或哪件物品已消耗、卖出、赠予、遗失、存放。",
                    List.of("例如：地点应为青岐城", "例如：清心丹已经消耗"));
        }
        Protagonist protagonist = new Protagonist(before.protagonist().name(), before.protagonist().race(),
                before.protagonist().gender(), before.protagonist().origin(), realm,
                before.protagonist().lifeGoal(), before.protagonist().storyTone());
        return new V014State(before.schemaVersion(), before.saveId(), before.turnNumber(), before.status(),
                before.world(), protagonist, before.worldTimeMinutes(), location, condition,
                before.sceneTitle(), before.narrative(), before.choices(), inventory,
                before.abilities(), before.characters(), before.memories());
    }

    private SaveResponse response(String userId, V014State state, long revision) {
        var preference = models.get(userId);
        return playerResponse(state, revision, preference.credentialSource(), preference.provider(), preference.model());
    }

    private SaveResponse playerResponse(V014State state, long revision, String source,
                                        String provider, String model) {
        boolean personal = "USER".equals(source);
        return new SaveResponse(state, revision, personal ? "PERSONAL" : "PLATFORM",
                personal ? provider : null, personal ? model : null);
    }

    private V014SaveStore.StoredSave requireActive(UserIdentity user, String saveId, long expectedRevision) {
        V014SaveStore.StoredSave saved = saves.requireOwned(user.userId(), saveId);
        if (!"ACTIVE".equals(saved.status())) {
            throw GameApiException.of(HttpStatus.CONFLICT, "V014_SAVE_NOT_ACTIVE", "STATE",
                    "这份命卷已经封存", "封存命卷只能阅读，不能再整理当前记录。", List.of("选择其他命卷"));
        }
        if (saved.revision() != expectedRevision) {
            throw GameApiException.of(HttpStatus.CONFLICT, "STATE_CONFLICT", "STATE",
                    "命卷已经在别处发生变化", "已阻止覆盖较新的记录，请刷新后再操作。", List.of("刷新角色页后重试"));
        }
        return saved;
    }

    private long persistManualChange(String userId, V014SaveStore.StoredSave saved,
                                     V014State after, String instruction) {
        Long revision = transactions.execute(status -> {
            long next = saves.update(saved.state().saveId(), saved.revision(), after);
            saves.recordCorrection(userId, saved.state().saveId(), instruction, saved.state(), after);
            return next;
        });
        return revision == null ? saved.revision() + 1 : revision;
    }

    private V014State copy(V014State before, List<Item> inventory, List<Character> characters) {
        return new V014State(before.schemaVersion(), before.saveId(), before.turnNumber(), before.status(),
                before.world(), before.protagonist(), before.worldTimeMinutes(), before.currentLocation(),
                before.condition(), before.sceneTitle(), before.narrative(), before.choices(), inventory,
                before.abilities(), characters, before.memories());
    }

    private V014State withStatus(V014State state, String status) {
        if (status.equals(state.status())) return state;
        return new V014State(state.schemaVersion(), state.saveId(), state.turnNumber(), status,
                state.world(), state.protagonist(), state.worldTimeMinutes(), state.currentLocation(),
                state.condition(), state.sceneTitle(), state.narrative(), state.choices(), state.inventory(),
                state.abilities(), state.characters(), state.memories());
    }

    private String initialRealm(String race) {
        String value = race.toLowerCase(Locale.ROOT);
        if (value.contains("妖")) return "启灵";
        if (value.contains("灵")) return "凝形";
        return "凡境";
    }

    private String match(Pattern pattern, String input, String fallback) {
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1).strip() : fallback;
    }

    private static Pattern correctionField(String label, int maxLength) {
        return Pattern.compile(label + "\\s*(?:(?:改为|应为|是)\\s*[:：]?|[:：])\\s*"
                + FIELD_VALUE_END.formatted(maxLength));
    }

    private String text(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.strip(); }
    private String id(String prefix) { return prefix + "-" + UUID.randomUUID(); }

    private Object accountLock(String userId) {
        return accountLocks.computeIfAbsent(userId, ignored -> new Object());
    }

    private PlayerAiService.Route quotaRoute(ResolvedRoute route) {
        return new PlayerAiService.Route(
                new RuntimeAiConfigService.RuntimeConfig(route.provider(), route.model(), route.model(), route.apiKey()),
                route.credentialSource());
    }
}
