package com.wenxian.game.story.engine;

import com.wenxian.game.api.GameApiException;
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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoryEngineRegistryTest {

    @Test
    void routesSaveToTheOnlySupportingEngine() {
        FakeEngine legacy = new FakeEngine("LEGACY", "old-");
        FakeEngine current = new FakeEngine("LIGHT_NARRATIVE", "v014-save-");
        StoryEngineRegistry registry = new StoryEngineRegistry(List.of(legacy, current), "LIGHT_NARRATIVE");

        assertSame(current, registry.defaultEngine());
        assertSame(current, registry.requireForSave("v014-save-12345678"));
        assertSame(legacy, registry.requireForSave("old-12345678"));
    }

    @Test
    void rejectsDuplicateEngineCodesAtStartup() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new StoryEngineRegistry(List.of(
                        new FakeEngine("LIGHT_NARRATIVE", "a-"),
                        new FakeEngine("LIGHT_NARRATIVE", "b-")), "LIGHT_NARRATIVE"));

        assertEquals("故事引擎路由编码重复：LIGHT_NARRATIVE", error.getMessage());
    }

    @Test
    void unknownSaveNeverFallsBackToDefaultEngine() {
        StoryEngineRegistry registry = new StoryEngineRegistry(
                List.of(new FakeEngine("LIGHT_NARRATIVE", "v014-save-")), "LIGHT_NARRATIVE");

        GameApiException error = assertThrows(GameApiException.class,
                () -> registry.requireForSave("unmapped-save-123"));

        assertEquals("STORY_SAVE_NOT_FOUND", error.code());
    }

    private static final class FakeEngine implements StoryEngine {
        private final EngineDescriptor descriptor;
        private final String prefix;

        private FakeEngine(String code, String prefix) {
            this.descriptor = new EngineDescriptor(code, 1, 1);
            this.prefix = prefix;
        }

        @Override public EngineDescriptor descriptor() { return descriptor; }
        @Override public boolean supports(String saveId) { return saveId.startsWith(prefix); }
        @Override public BootstrapResponse bootstrap() { throw unsupported(); }
        @Override public CharacterRouteResponse characterRoutes(UserIdentity user, CharacterRouteRequest request) { throw unsupported(); }
        @Override public SaveResponse create(UserIdentity user, CreateSaveRequest request) { throw unsupported(); }
        @Override public List<SaveSummary> list(UserIdentity user) { return List.of(); }
        @Override public SaveResponse get(UserIdentity user, String saveId) { throw unsupported(); }
        @Override public SaveResponse takeTurn(UserIdentity user, String saveId, TurnRequest request) { throw unsupported(); }
        @Override public SaveResponse correct(UserIdentity user, String saveId, CorrectionRequest request) { throw unsupported(); }
        @Override public void archive(UserIdentity user, String saveId) { throw unsupported(); }
        @Override public SaveResponse removeEntity(UserIdentity user, String saveId, String entityId, long expectedRevision) { throw unsupported(); }

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("测试未使用该方法");
        }
    }
}
