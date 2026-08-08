package com.wenxian.game.story.engine;

import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.story.model.StoryApiModels.BootstrapResponse;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteRequest;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteResponse;
import com.wenxian.game.story.model.StoryApiModels.CorrectionRequest;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.story.model.StoryApiModels.EngineDescriptor;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.SaveSummary;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;

import java.util.List;

/**
 * 可插拔故事引擎的应用边界。
 *
 * <p>引擎负责命卷的创建、推进和权威状态写入；注册表只负责路由，不得跨引擎修改状态。
 * 任何失败都必须在底层事务提交前抛出，避免返回半份命卷。</p>
 */
public interface StoryEngine {
    EngineDescriptor descriptor();

    boolean supports(String saveId);

    BootstrapResponse bootstrap();

    CharacterRouteResponse characterRoutes(UserIdentity user, CharacterRouteRequest request);

    SaveResponse create(UserIdentity user, CreateSaveRequest request);

    List<SaveSummary> list(UserIdentity user);

    SaveResponse get(UserIdentity user, String saveId);

    SaveResponse takeTurn(UserIdentity user, String saveId, TurnRequest request);

    SaveResponse correct(UserIdentity user, String saveId, CorrectionRequest request);

    void archive(UserIdentity user, String saveId);

    SaveResponse removeEntity(UserIdentity user, String saveId, String entityId, long expectedRevision);
}
