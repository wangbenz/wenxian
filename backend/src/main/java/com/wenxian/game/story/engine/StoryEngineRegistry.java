package com.wenxian.game.story.engine;

import com.wenxian.game.api.GameApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 故事引擎注册表，按运行时路由编码或命卷标识选择唯一实现。
 *
 * <p>同一玩法在迁移期可以同时注册旧表适配器和通用表实现，但 routeCode 必须唯一。
 * 找不到命卷引擎时返回稳定的玩家错误，禁止静默落到错误引擎。</p>
 */
@Component
public class StoryEngineRegistry {
    private static final String PERSISTED_ROUTE = PersistedLightNarrativeEngine.ROUTE_CODE;
    private final Map<String, StoryEngine> engines;
    private final StoryEngine defaultEngine;

    public StoryEngineRegistry(List<StoryEngine> registered,
                               @Value("${wenxian.story.default-route:LIGHT_NARRATIVE_V014}") String defaultCode,
                               @Value("${wenxian.story.persisted-runtime-enabled:false}") boolean persistedEnabled) {
        Map<String, StoryEngine> indexed = new LinkedHashMap<>();
        for (StoryEngine engine : registered) {
            String code = engine.routeCode();
            if (indexed.putIfAbsent(code, engine) != null) {
                throw new IllegalStateException("故事引擎路由编码重复：" + code);
            }
        }
        this.engines = Map.copyOf(indexed);
        String effectiveDefault = persistedEnabled && indexed.containsKey(PERSISTED_ROUTE)
                ? PERSISTED_ROUTE : defaultCode;
        this.defaultEngine = indexed.get(effectiveDefault);
        if (defaultEngine == null) {
            throw new IllegalStateException("默认故事引擎尚未注册：" + effectiveDefault);
        }
    }

    public StoryEngine defaultEngine() {
        return defaultEngine;
    }

    public List<StoryEngine> all() {
        return List.copyOf(engines.values());
    }

    public StoryEngine requireForSave(String saveId) {
        return engines.values().stream().filter(engine -> engine.supports(saveId)).findFirst()
                .orElseThrow(() -> GameApiException.of(HttpStatus.NOT_FOUND, "STORY_SAVE_NOT_FOUND", "STATE",
                        "没有找到这份命卷", "命卷可能已被封存到尚未接入的历史档案中。",
                        List.of("刷新我的命卷", "返回命卷列表")));
    }
}
