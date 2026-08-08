package com.wenxian.game.story.application;

import com.wenxian.game.analytics.AnalyticsEventPublisher;
import com.wenxian.game.analytics.AnalyticsEventType;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.story.engine.StoryEngineRegistry;
import com.wenxian.game.story.model.StoryApiModels.BootstrapResponse;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteRequest;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteResponse;
import com.wenxian.game.story.model.StoryApiModels.CorrectionRequest;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.SaveSummary;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 统一命卷用例门面。
 *
 * <p>该服务只做跨引擎路由和列表聚合，不直接访问数据库，也不修改引擎生成结果。
 * 单份命卷的事务边界始终由被选中的故事引擎维护。</p>
 */
@Service
public class StoryApplicationService {
    private final StoryEngineRegistry engines;
    private final AnalyticsEventPublisher analytics;

    public StoryApplicationService(StoryEngineRegistry engines, AnalyticsEventPublisher analytics) {
        this.engines = engines;
        this.analytics = analytics;
    }

    public BootstrapResponse bootstrap() {
        return engines.defaultEngine().bootstrap();
    }

    public CharacterRouteResponse characterRoutes(UserIdentity user, CharacterRouteRequest request) {
        return engines.defaultEngine().characterRoutes(user, request);
    }

    public SaveResponse create(UserIdentity user, CreateSaveRequest request) {
        SaveResponse created = engines.defaultEngine().create(user, request);
        analytics.publish("SAVE_CREATED:" + created.saveId(), AnalyticsEventType.SAVE_CREATED,
                user.userId(), created.saveId(), created.engineCode(), Map.of("worldCode", request.worldCode()));
        return created;
    }

    public List<SaveSummary> list(UserIdentity user) {
        return engines.all().stream().flatMap(engine -> engine.list(user).stream())
                .sorted(Comparator.comparing(SaveSummary::updatedAt).reversed())
                .toList();
    }

    public SaveResponse get(UserIdentity user, String saveId) {
        return engines.requireForSave(saveId).get(user, saveId);
    }

    public SaveResponse takeTurn(UserIdentity user, String saveId, TurnRequest request) {
        SaveResponse updated = engines.requireForSave(saveId).takeTurn(user, saveId, request);
        analytics.publish("TURN_SUCCEEDED:" + saveId + ":" + request.requestId(),
                AnalyticsEventType.TURN_SUCCEEDED, user.userId(), saveId, updated.engineCode(),
                Map.of("turnNumber", updated.turnNumber(), "generationMode", safe(updated.generationMode())));
        return updated;
    }

    public SaveResponse correct(UserIdentity user, String saveId, CorrectionRequest request) {
        SaveResponse updated = engines.requireForSave(saveId).correct(user, saveId, request);
        analytics.publish("CORRECTION_APPLIED:" + saveId + ":" + updated.revision(),
                AnalyticsEventType.CORRECTION_APPLIED, user.userId(), saveId, updated.engineCode(), Map.of());
        return updated;
    }

    public void archive(UserIdentity user, String saveId) {
        var engine = engines.requireForSave(saveId);
        engine.archive(user, saveId);
        analytics.publish("SAVE_ARCHIVED:" + saveId, AnalyticsEventType.SAVE_ARCHIVED,
                user.userId(), saveId, engine.descriptor().code(), Map.of());
    }

    public SaveResponse removeEntity(UserIdentity user, String saveId, String entityId, long expectedRevision) {
        SaveResponse updated = engines.requireForSave(saveId).removeEntity(user, saveId, entityId, expectedRevision);
        analytics.publish("ENTITY_REMOVED:" + saveId + ":" + entityId + ":" + updated.revision(),
                AnalyticsEventType.ENTITY_REMOVED, user.userId(), saveId, updated.engineCode(),
                Map.of("entityId", entityId));
        return updated;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
