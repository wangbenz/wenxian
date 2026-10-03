package com.wenxian.game.story.application;

import com.wenxian.game.analytics.AnalyticsEvent;
import com.wenxian.game.analytics.AnalyticsEventPublisher;
import com.wenxian.game.analytics.AnalyticsEventType;
import com.wenxian.game.story.model.GenerationApiModels.CredentialRequest;
import com.wenxian.game.story.model.GenerationApiModels.PersonalModel;
import com.wenxian.game.story.model.GenerationApiModels.PersonalProvider;
import com.wenxian.game.story.model.GenerationApiModels.PlatformAccess;
import com.wenxian.game.story.model.GenerationApiModels.SettingsRequest;
import com.wenxian.game.story.model.GenerationApiModels.SettingsResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GenerationApplicationServiceTest {

    @Test
    void returnsStableSettingsWithoutDependingOnVersionModels() {
        StubGateway gateway = new StubGateway(settings("PLATFORM"));
        GenerationApplicationService service = service(gateway, new ArrayList<>());

        SettingsResponse response = service.settings("user-1");

        assertSame(gateway.response, response);
        assertEquals("user-1", gateway.lastUserId);
    }

    @Test
    void delegatesPersonalModeSelectionAsOneStableRequest() {
        StubGateway gateway = new StubGateway(settings("PERSONAL"));
        GenerationApplicationService service = service(gateway, new ArrayList<>());
        SettingsRequest request = new SettingsRequest("PERSONAL", "DEEPSEEK", "deepseek-v4-flash");

        SettingsResponse response = service.saveSettings("user-1", request);

        assertSame(request, gateway.lastSettings);
        assertEquals("DEEPSEEK", response.selectedPersonalProvider());
    }

    @Test
    void recordsOnlySanitizedCredentialConnectionFactAfterSuccessfulSave() {
        StubGateway gateway = new StubGateway(settings("PERSONAL"));
        List<Object> events = new ArrayList<>();
        GenerationApplicationService service = service(gateway, events);

        service.saveCredential("user-1", "deepseek", new CredentialRequest("secret-key-that-never-enters-event", "deepseek-v4-flash"));

        AnalyticsEvent event = (AnalyticsEvent) events.getFirst();
        assertEquals(AnalyticsEventType.BYOK_CREDENTIAL_CONNECTED, event.eventType());
        assertEquals(Map.of("providerCode", "DEEPSEEK"), event.metadata());
    }

    private GenerationApplicationService service(StubGateway gateway, List<Object> events) {
        return new GenerationApplicationService(gateway, new AnalyticsEventPublisher(events::add));
    }

    private SettingsResponse settings(String mode) {
        return new SettingsResponse(mode, new PlatformAccess(20, 8, 12, true),
                List.of(new PersonalProvider("DEEPSEEK", "DeepSeek", true, "ACTIVE", "1234",
                        Instant.parse("2026-08-08T00:00:00Z"),
                        List.of(new PersonalModel("deepseek-v4-flash", "Flash")))),
                "PERSONAL".equals(mode) ? "DEEPSEEK" : null,
                "PERSONAL".equals(mode) ? "deepseek-v4-flash" : null);
    }

    private static final class StubGateway implements GenerationSettingsGateway {
        private final SettingsResponse response;
        private String lastUserId;
        private SettingsRequest lastSettings;

        private StubGateway(SettingsResponse response) {
            this.response = response;
        }

        @Override
        public SettingsResponse settings(String userId) {
            lastUserId = userId;
            return response;
        }

        @Override
        public SettingsResponse saveSettings(String userId, SettingsRequest request) {
            lastUserId = userId;
            lastSettings = request;
            return response;
        }

        @Override
        public SettingsResponse saveCredential(String userId, String provider, CredentialRequest request) {
            lastUserId = userId;
            return response;
        }

        @Override
        public SettingsResponse deleteCredential(String userId, String provider) {
            lastUserId = userId;
            return response;
        }
    }
}
