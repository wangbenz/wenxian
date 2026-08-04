package com.wenxian.game.v014.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.config.RuntimeAiConfigService;
import com.wenxian.game.service.PlayerAiService;
import com.wenxian.game.v014.ai.V014ProviderCredentialVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V014GenerationSettingsServiceTest {
    @Test
    void platformIsAnonymousAndPersonalRouteNeverFallsBackSilently() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values ('u-route','route@test.local','ACTIVE',?,?)",
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            RuntimeAiConfigService runtime = new RuntimeAiConfigService(
                    jdbc, "sk-platform-secret-123456789", "deepseek-v4-flash", "master-key", true);
            PlayerAiService playerAi = new PlayerAiService(jdbc, runtime, RestClient.builder(),
                    "https://api.deepseek.com", "master-key");
            V014ProviderCredentialVerifier verifier = new V014ProviderCredentialVerifier() {
                @Override public void verify(String baseUrl, String adapterType, String apiKey, String model) {
                    assertEquals("OPENAI_COMPATIBLE", adapterType);
                    assertTrue(apiKey.startsWith("sk-personal"));
                }
            };
            V014ModelPreferenceService service = new V014ModelPreferenceService(jdbc, playerAi, runtime,
                    verifier, "master-key", "https://api.deepseek.com");
            service.ensureCatalog();

            var platform = service.settings("u-route");
            assertEquals("PLATFORM", platform.mode());
            assertNull(platform.selectedPersonalProvider());
            assertFalse(new ObjectMapper().valueToTree(platform.platform()).has("provider"));
            assertFalse(new ObjectMapper().valueToTree(platform.platform()).has("model"));
            assertTrue(platform.platform().available());

            var personal = service.saveCredential("u-route", "DEEPSEEK", "deepseek-v4-pro",
                    "sk-personal-secret-123456789");
            assertEquals("PERSONAL", personal.mode());
            var personalRoute = service.resolve("u-route", "ignored");
            assertEquals("USER", personalRoute.credentialSource());
            assertEquals("deepseek-v4-pro", personalRoute.model());
            assertEquals("sk-personal-secret-123456789", personalRoute.apiKey());

            service.deleteCredential("u-route", "DEEPSEEK");
            jdbc.update("""
                    update v014_user_model_preference set credential_source='USER', provider_code='DEEPSEEK',
                        model_code='deepseek-v4-pro' where user_id='u-route'
                    """);
            GameApiException unavailable = assertThrows(GameApiException.class,
                    () -> service.resolve("u-route", "ignored"));
            assertEquals("PERSONAL_KEY_UNAVAILABLE", unavailable.code());

            service.saveSettings("u-route", "PLATFORM", null, null);
            var restoredPlatform = service.settings("u-route");
            assertNull(restoredPlatform.selectedPersonalProvider());
            assertNull(restoredPlatform.selectedPersonalModel());
            var publicRoute = service.resolve("u-route", "ignored");
            assertEquals("PUBLIC", publicRoute.credentialSource());
            assertEquals("sk-platform-secret-123456789", publicRoute.apiKey());
        } finally {
            database.shutdown();
        }
    }
}
