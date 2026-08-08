package com.wenxian.game.story.application;

import com.wenxian.game.story.model.GenerationApiModels.SettingsRequest;
import com.wenxian.game.v014.model.V014ApiModels.GenerationSettings;
import com.wenxian.game.v014.model.V014ApiModels.PersonalModel;
import com.wenxian.game.v014.model.V014ApiModels.PersonalProvider;
import com.wenxian.game.v014.model.V014ApiModels.PlatformAccess;
import com.wenxian.game.v014.service.V014ModelPreferenceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationApplicationServiceTest {

    @Test
    void mapsPlatformQuotaWithoutExposingPlatformModel() {
        StubPreferences preferences = new StubPreferences(settings("PLATFORM"));
        GenerationApplicationService service = new GenerationApplicationService(preferences);

        var response = service.settings("user-1");

        assertEquals("PLATFORM", response.mode());
        assertEquals(20, response.platform().turnLimit());
        assertEquals(12, response.platform().remainingTurns());
        assertTrue(response.platform().available());
        assertNull(response.selectedPersonalProvider());
        assertNull(response.selectedPersonalModel());
        assertEquals("DEEPSEEK", response.personalProviders().getFirst().code());
        assertEquals("***1234", "***" + response.personalProviders().getFirst().maskedSuffix());
    }

    @Test
    void delegatesPersonalModeSelectionAndReturnsStableContract() {
        StubPreferences preferences = new StubPreferences(settings("PERSONAL"));
        GenerationApplicationService service = new GenerationApplicationService(preferences);

        var response = service.saveSettings("user-1",
                new SettingsRequest("PERSONAL", "DEEPSEEK", "deepseek-v4-flash"));

        assertEquals("PERSONAL", preferences.lastMode);
        assertEquals("DEEPSEEK", preferences.lastProvider);
        assertEquals("deepseek-v4-flash", preferences.lastModel);
        assertEquals("DEEPSEEK", response.selectedPersonalProvider());
    }

    private GenerationSettings settings(String mode) {
        return new GenerationSettings(mode, new PlatformAccess(20, 8, 12, true),
                List.of(new PersonalProvider("DEEPSEEK", "DeepSeek", true, "ACTIVE", "1234",
                        Instant.parse("2026-08-08T00:00:00Z"),
                        List.of(new PersonalModel("deepseek-v4-flash", "Flash")))),
                "PERSONAL".equals(mode) ? "DEEPSEEK" : null,
                "PERSONAL".equals(mode) ? "deepseek-v4-flash" : null);
    }

    private static final class StubPreferences extends V014ModelPreferenceService {
        private final GenerationSettings response;
        private String lastMode;
        private String lastProvider;
        private String lastModel;

        private StubPreferences(GenerationSettings response) {
            super(null, null);
            this.response = response;
        }

        @Override
        public GenerationSettings settings(String userId) {
            return response;
        }

        @Override
        public GenerationSettings saveSettings(String userId, String mode, String provider, String model) {
            this.lastMode = mode;
            this.lastProvider = provider;
            this.lastModel = model;
            return response;
        }
    }
}
