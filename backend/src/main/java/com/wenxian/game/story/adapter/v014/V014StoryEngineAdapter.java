package com.wenxian.game.story.adapter.v014;

import com.wenxian.game.api.GameApiException;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.service.ActiveSavePolicyService;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.engine.StoryEngine;
import com.wenxian.game.story.migration.StoryMigrationRepository;
import com.wenxian.game.story.model.StoryApiModels.Ability;
import com.wenxian.game.story.model.StoryApiModels.BootstrapResponse;
import com.wenxian.game.story.model.StoryApiModels.Character;
import com.wenxian.game.story.model.StoryApiModels.CharacterRoute;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteRequest;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteResponse;
import com.wenxian.game.story.model.StoryApiModels.Choice;
import com.wenxian.game.story.model.StoryApiModels.CorrectionRequest;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.story.model.StoryApiModels.EngineDescriptor;
import com.wenxian.game.story.model.StoryApiModels.Item;
import com.wenxian.game.story.model.StoryApiModels.Memory;
import com.wenxian.game.story.model.StoryApiModels.Protagonist;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.SaveSummary;
import com.wenxian.game.story.model.StoryApiModels.StoryState;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;
import com.wenxian.game.story.model.StoryApiModels.WorldOption;
import com.wenxian.game.story.model.StoryApiModels.WorldProfile;
import com.wenxian.game.v014.model.V014ApiModels;
import com.wenxian.game.v014.model.V014State;
import com.wenxian.game.v014.service.V014CharacterRouteService;
import com.wenxian.game.v014.service.V014GameService;
import com.wenxian.game.v014.service.V014WorldCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 当前轻叙事引擎的 V0.14 兼容适配器。
 *
 * <p>该类只转换稳定 DTO、能力和错误码，权威写入仍由现有 V0.14 Service 及其事务完成。
 * 迁移期间不得在适配器内直接更新旧表；当统一表接管后，可替换适配器而不改变 H5 API。</p>
 */
@Component
public class V014StoryEngineAdapter implements StoryEngine {
    public static final String ENGINE_CODE = "LIGHT_NARRATIVE";
    public static final String ROUTE_CODE = "LIGHT_NARRATIVE_V014";
    private static final int ENGINE_VERSION = 1;
    private static final int STATE_SCHEMA_VERSION = 14;
    private static final String SAVE_PREFIX = "v014-save-";

    private final V014GameService game;
    private final V014WorldCatalog worlds;
    private final V014CharacterRouteService characterRoutes;
    private final GameVersionService version;
    private final ActiveSavePolicyService savePolicy;
    private final StoryMigrationRepository migrations;
    private final boolean persistedRuntimeEnabled;

    public V014StoryEngineAdapter(V014GameService game, V014WorldCatalog worlds,
                                  V014CharacterRouteService characterRoutes,
                                  GameVersionService version, ActiveSavePolicyService savePolicy,
                                  StoryMigrationRepository migrations,
                                  @Value("${wenxian.story.persisted-runtime-enabled:false}")
                                  boolean persistedRuntimeEnabled) {
        this.game = game;
        this.worlds = worlds;
        this.characterRoutes = characterRoutes;
        this.version = version;
        this.savePolicy = savePolicy;
        this.migrations = migrations;
        this.persistedRuntimeEnabled = persistedRuntimeEnabled;
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
        return saveId != null && saveId.startsWith(SAVE_PREFIX);
    }

    @Override
    public BootstrapResponse bootstrap() {
        List<WorldOption> options = worlds.all().stream()
                .map(world -> new WorldOption(world.code(), world.name(), world.premise(),
                        world.cultivationStyle(), world.openingRegion(), world.custom(),
                        world.available(), world.availabilityNote()))
                .toList();
        return new BootstrapResponse(version.current(), savePolicy.maximum(), descriptor(), options,
                List.of("人族", "妖族", "灵族", "自定种族"),
                List.of("凡俗出身", "宗门弟子", "世家之后", "王族血脉", "妖王后代", "器物化灵", "自由描述"));
    }

    @Override
    public CharacterRouteResponse characterRoutes(UserIdentity user, CharacterRouteRequest request) {
        return stable(() -> {
            V014ApiModels.CharacterRouteResponse result = characterRoutes.generate(user,
                    new V014ApiModels.CharacterRouteRequest(request.worldCode(), request.customWorld(),
                            request.playerName(), request.race(), request.gender(), request.roleSeed(),
                            request.preferenceSeed()));
            return new CharacterRouteResponse(result.routes().stream()
                    .map(route -> new CharacterRoute(route.id(), route.title(), route.origin(),
                            route.opening(), route.preference()))
                    .toList());
        });
    }

    @Override
    public SaveResponse create(UserIdentity user, CreateSaveRequest request) {
        return stable(() -> map(game.create(user, new V014ApiModels.CreateSaveRequest(
                request.worldCode(), request.customWorld(), request.playerName(), request.race(),
                request.gender(), request.origin(), request.opening(), request.storyPreference()))));
    }

    @Override
    public List<SaveSummary> list(UserIdentity user) {
        return stable(() -> {
            Set<String> activated = persistedRuntimeEnabled
                    ? migrations.activatedSourceIdsForUser(user.userId()) : Set.of();
            return game.list(user).stream().filter(summary -> !activated.contains(summary.id())).map(summary -> {
                SaveResponse full = get(user, summary.id());
                return new SaveSummary(summary.id(), summary.slotNo(), full.mode(), ENGINE_CODE, ENGINE_VERSION,
                        full.stateSchemaVersion(), full.revision(), summary.turnNumber(), full.capabilities(),
                        summary.worldName(), summary.playerName(), summary.race(), summary.realm(),
                        summary.status(), summary.updatedAt());
            }).toList();
        });
    }

    @Override
    public SaveResponse get(UserIdentity user, String saveId) {
        return stable(() -> map(game.get(user, saveId)));
    }

    @Override
    public SaveResponse takeTurn(UserIdentity user, String saveId, TurnRequest request) {
        return stable(() -> map(game.takeTurn(user, saveId, request.action(), request.requestId(),
                request.expectedRevision())));
    }

    @Override
    public SaveResponse correct(UserIdentity user, String saveId, CorrectionRequest request) {
        return stable(() -> map(game.correct(user, saveId, request.instruction(), request.expectedRevision())));
    }

    @Override
    public void archive(UserIdentity user, String saveId) {
        stableVoid(() -> game.archive(user, saveId));
    }

    @Override
    public SaveResponse removeEntity(UserIdentity user, String saveId, String entityId, long expectedRevision) {
        SaveResponse current = get(user, saveId);
        assertExpectedRevision(current.revision(), expectedRevision);
        boolean item = current.state().inventory().stream()
                .anyMatch(value -> value.id().equals(entityId) && value.inPossession());
        if (item) {
            return stable(() -> map(game.removeItem(user, saveId, entityId, expectedRevision)));
        }
        boolean character = current.state().characters().stream()
                .anyMatch(value -> value.id().equals(entityId) && value.tracked());
        if (character) {
            return stable(() -> map(game.untrackCharacter(user, saveId, entityId, expectedRevision)));
        }
        throw GameApiException.of(HttpStatus.NOT_FOUND, "STORY_ENTITY_NOT_FOUND", "STATE",
                "当前记录中没有这个内容", "它可能已经被整理，或命卷刚刚在其他页面发生了变化。",
                List.of("刷新角色页后再查看"));
    }

    private void assertExpectedRevision(long currentRevision, long expectedRevision) {
        if (currentRevision != expectedRevision) {
            throw GameApiException.of(HttpStatus.CONFLICT, "STATE_CONFLICT", "STATE",
                    "命卷已经在别处发生变化", "已阻止覆盖较新的记录，请刷新命卷后再操作。",
                    List.of("刷新当前命卷后继续"));
        }
    }

    private SaveResponse map(V014ApiModels.SaveResponse source) {
        V014State state = source.state();
        String mode = "ACTIVE".equals(state.status()) ? "PLAYABLE" : "ARCHIVE";
        return new SaveResponse(state.saveId(), mode, ENGINE_CODE, ENGINE_VERSION,
                state.schemaVersion(), source.revision(), state.turnNumber(), capabilities(state.status()),
                map(state), source.generationMode(), source.personalProvider(), source.personalModel());
    }

    private StoryState map(V014State state) {
        return new StoryState(state.saveId(), state.turnNumber(), state.status(),
                new WorldProfile(state.world().code(), state.world().name(), state.world().source(),
                        state.world().premise(), state.world().cultivationStyle(), state.world().openingRegion()),
                new Protagonist(state.protagonist().name(), state.protagonist().race(),
                        state.protagonist().gender(), state.protagonist().origin(), state.protagonist().realm(),
                        state.protagonist().lifeGoal(), state.protagonist().storyTone()),
                state.worldTimeMinutes(), state.currentLocation(), state.condition(), state.sceneTitle(),
                state.narrative(), state.choices().stream()
                        .map(value -> new Choice(value.id(), value.title(), value.description())).toList(),
                state.inventory().stream().map(value -> new Item(value.id(), value.name(), value.type(),
                        value.description(), value.status(), value.source(), value.quantity(),
                        Boolean.TRUE.equals(value.inPossession()))).toList(),
                state.abilities().stream().map(value -> new Ability(value.id(), value.name(), value.type(),
                        value.description(), value.status(), value.source())).toList(),
                state.characters().stream().map(value -> new Character(value.id(), value.name(), value.tier(),
                        value.identity(), value.relationship(), value.status(), value.lastKnownLocation(),
                        Boolean.TRUE.equals(value.tracked()))).toList(),
                state.memories().stream().map(value -> new Memory(value.id(), value.title(), value.summary(),
                        value.worldTimeMinutes(), value.importance())).toList());
    }

    private List<String> capabilities(String status) {
        if (!"ACTIVE".equals(status)) return List.of("READ");
        return List.of("READ", "PLAY", "ARCHIVE", "CORRECT", "REMOVE_ENTITY");
    }

    private <T> T stable(Supplier<T> action) {
        try {
            return action.get();
        } catch (GameApiException error) {
            throw normalize(error);
        }
    }

    private void stableVoid(Runnable action) {
        stable(() -> {
            action.run();
            return null;
        });
    }

    private GameApiException normalize(GameApiException error) {
        String code = error.code().startsWith("V014_") ? error.code().substring("V014_".length()) : error.code();
        if (code.equals(error.code())) return error;
        return new GameApiException(error.status(), code, error.category(), error.title(), error.getMessage(),
                error.suggestions(), error.retryable(), error.turnConsumed(), error.stateChanged(),
                error.details(), error.diagnostic(), error);
    }
}
