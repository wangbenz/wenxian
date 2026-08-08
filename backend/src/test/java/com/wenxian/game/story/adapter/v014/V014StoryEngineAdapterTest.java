package com.wenxian.game.story.adapter.v014;

import com.wenxian.game.api.GameApiException;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.story.model.StoryApiModels.SaveResponse;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;
import com.wenxian.game.v014.model.V014ApiModels;
import com.wenxian.game.v014.model.V014State;
import com.wenxian.game.v014.service.V014GameService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V014StoryEngineAdapterTest {
    private static final UserIdentity USER = new UserIdentity("user-1", "test@example.com", true);

    @Test
    void mapsLegacyStateToStableStoryContract() {
        StubGame game = new StubGame(legacyResponse(7));
        V014StoryEngineAdapter adapter = adapter(game);

        SaveResponse result = adapter.get(USER, "v014-save-12345678");

        assertEquals("LIGHT_NARRATIVE", result.engineCode());
        assertEquals(1, result.engineVersion());
        assertEquals(14, result.stateSchemaVersion());
        assertEquals(7, result.revision());
        assertEquals("PLAYABLE", result.mode());
        assertTrue(result.capabilities().contains("PLAY"));
        assertEquals("陆沉", result.state().protagonist().name());
        assertEquals("青纹令", result.state().inventory().getFirst().name());
        assertTrue(result.state().inventory().getFirst().inPossession());
    }

    @Test
    void refusesStaleTurnBeforeCallingLegacyEngine() {
        StubGame game = new StubGame(legacyResponse(7));
        V014StoryEngineAdapter adapter = adapter(game);

        GameApiException error = assertThrows(GameApiException.class,
                () -> adapter.takeTurn(USER, "v014-save-12345678",
                        new TurnRequest("查看青纹令", "request-12345678", 6)));

        assertEquals("STATE_CONFLICT", error.code());
        assertFalse(game.turnCalled);
    }

    @Test
    void removesReleasePrefixFromLegacyBusinessError() {
        StubGame game = new StubGame(legacyResponse(7));
        game.error = GameApiException.of(HttpStatus.CONFLICT, "V014_SAVE_NOT_ACTIVE", "STATE",
                "命卷已经封存", "不能继续推进。", List.of("选择其他命卷"));
        V014StoryEngineAdapter adapter = adapter(game);

        GameApiException error = assertThrows(GameApiException.class,
                () -> adapter.get(USER, "v014-save-12345678"));

        assertEquals("SAVE_NOT_ACTIVE", error.code());
    }

    private V014StoryEngineAdapter adapter(V014GameService game) {
        return new V014StoryEngineAdapter(game, null, null, null, null);
    }

    private V014ApiModels.SaveResponse legacyResponse(long revision) {
        V014State state = new V014State(14, "v014-save-12345678", 3, "ACTIVE",
                new V014State.WorldProfile("XUANXU", "玄墟界", "OFFICIAL", "仙路难寻", "轻叙事", "青岐州"),
                new V014State.Protagonist("陆沉", "人族", "男", "山村少年", "引气",
                        "随经历形成", "自由探索"),
                180, "青岐城", "神完气足", "雨后入城", "你走入青岐城。",
                List.of(new V014State.Choice("choice-12345678", "查看令牌", "辨认来历")),
                List.of(new V014State.Item("item-12345678", "青纹令", "信物", "来历不明",
                        "随身携带", "旧识所赠", 1, true)),
                List.of(), List.of(), List.of());
        return new V014ApiModels.SaveResponse(state, revision, "PLATFORM", null, null);
    }

    private static final class StubGame extends V014GameService {
        private final V014ApiModels.SaveResponse response;
        private GameApiException error;
        private boolean turnCalled;

        private StubGame(V014ApiModels.SaveResponse response) {
            super(null, null, null, null, null, null, null, null, null, "https://example.com");
            this.response = response;
        }

        @Override
        public V014ApiModels.SaveResponse get(UserIdentity user, String saveId) {
            if (error != null) throw error;
            return response;
        }

        @Override
        public V014ApiModels.SaveResponse takeTurn(UserIdentity user, String saveId,
                                                    String action, String requestId) {
            turnCalled = true;
            return response;
        }
    }
}
