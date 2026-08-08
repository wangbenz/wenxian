package com.wenxian.game.story.application;

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

/**
 * 统一命卷用例门面。
 *
 * <p>该服务只做跨引擎路由和列表聚合，不直接访问数据库，也不修改引擎生成结果。
 * 单份命卷的事务边界始终由被选中的故事引擎维护。</p>
 */
@Service
public class StoryApplicationService {
    private final StoryEngineRegistry engines;

    public StoryApplicationService(StoryEngineRegistry engines) {
        this.engines = engines;
    }

    public BootstrapResponse bootstrap() {
        return engines.defaultEngine().bootstrap();
    }

    public CharacterRouteResponse characterRoutes(UserIdentity user, CharacterRouteRequest request) {
        return engines.defaultEngine().characterRoutes(user, request);
    }

    public SaveResponse create(UserIdentity user, CreateSaveRequest request) {
        return engines.defaultEngine().create(user, request);
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
        return engines.requireForSave(saveId).takeTurn(user, saveId, request);
    }

    public SaveResponse correct(UserIdentity user, String saveId, CorrectionRequest request) {
        return engines.requireForSave(saveId).correct(user, saveId, request);
    }

    public void archive(UserIdentity user, String saveId) {
        engines.requireForSave(saveId).archive(user, saveId);
    }

    public SaveResponse removeEntity(UserIdentity user, String saveId, String entityId, long expectedRevision) {
        return engines.requireForSave(saveId).removeEntity(user, saveId, entityId, expectedRevision);
    }
}
