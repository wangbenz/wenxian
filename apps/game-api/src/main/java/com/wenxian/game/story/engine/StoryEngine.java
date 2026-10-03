package com.wenxian.game.story.engine;

import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.story.model.StoryApiModels.BootstrapResponse;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteRequest;
import com.wenxian.game.story.model.StoryApiModels.CharacterRouteResponse;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.story.model.StoryApiModels.EngineDescriptor;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.SaveSummary;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;

import java.util.List;

/**
 * 中立故事运行时的应用边界。
 *
 * <p>运行时负责命卷的创建、推进和权威状态写入。世界、Prompt、模型和产品发布都复用这条边界，
 * 不因版本变化增加第二套在线引擎。任何失败都必须在底层事务提交前抛出，避免返回半份命卷。</p>
 */
public interface StoryEngine {
    EngineDescriptor descriptor();

    BootstrapResponse bootstrap();

    CharacterRouteResponse characterRoutes(UserIdentity user, CharacterRouteRequest request);

    SaveResponse create(UserIdentity user, CreateSaveRequest request);

    List<SaveSummary> list(UserIdentity user);

    SaveResponse get(UserIdentity user, String saveId);

    SaveResponse takeTurn(UserIdentity user, String saveId, TurnRequest request);

    /** 逻辑删除玩家命卷；实现必须保留审计数据，并让玩家列表立即停止返回该命卷。 */
    void deleteSave(UserIdentity user, String saveId);

    SaveResponse removeEntity(UserIdentity user, String saveId, String entityId, long expectedRevision);
}
