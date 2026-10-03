package com.wenxian.game.model.application;

import com.wenxian.game.config.SecretCipher;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelRouteRequest;
import com.wenxian.game.model.domain.ModelTask;
import com.wenxian.game.model.domain.ServicePlanSelection;
import com.wenxian.game.model.persistence.ModelCatalogRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcModelRoutePolicyTest {
    private static final String MASTER_KEY = "test-master-key";

    @Test
    void resolvesPlayerPreferenceWithOnlyPlayerCredential() {
        ModelCatalogRepository catalog = mock(ModelCatalogRepository.class);
        when(catalog.findUserPreference("user-1")).thenReturn(Optional.of(
                new ModelCatalogRepository.UserPreference("DEEPSEEK", "flash", "USER")));
        when(catalog.findUserSelectableModel("DEEPSEEK", "flash")).thenReturn(Optional.of(model("flash")));
        when(catalog.findActiveUserCredential("user-1", "DEEPSEEK")).thenReturn(Optional.of(
                new ModelCatalogRepository.EncryptedCredential(SecretCipher.encrypt("player-secret", MASTER_KEY))));
        when(catalog.findTaskRoute(ModelTask.CHARACTER_CREATION)).thenReturn(Optional.of(taskRoute(false)));

        var resolved = new JdbcModelRoutePolicy(catalog, MASTER_KEY).resolve(request("user-1"));

        assertThat(resolved.credentialSource()).isEqualTo(CredentialSource.USER);
        assertThat(resolved.candidates()).hasSize(1);
        assertThat(resolved.candidates().getFirst().credential().secret()).isEqualTo("player-secret");
    }

    @Test
    void resolvesConfiguredPlatformPrimaryAndFallbackWithoutExposingCiphertext() {
        ModelCatalogRepository catalog = mock(ModelCatalogRepository.class);
        when(catalog.findUserPreference("user-2")).thenReturn(Optional.empty());
        when(catalog.findTaskRoute(ModelTask.CHARACTER_CREATION)).thenReturn(Optional.of(taskRoute(true)));
        when(catalog.findPlatformModel("DEEPSEEK", "flash")).thenReturn(Optional.of(model("flash")));
        when(catalog.findPlatformModel("DEEPSEEK", "pro")).thenReturn(Optional.of(model("pro")));
        when(catalog.findPlatformCredential("DEEPSEEK")).thenReturn(Optional.of(
                new ModelCatalogRepository.EncryptedCredential(SecretCipher.encrypt("platform-secret", MASTER_KEY))));

        var resolved = new JdbcModelRoutePolicy(catalog, MASTER_KEY).resolve(request("user-2"));

        assertThat(resolved.credentialSource()).isEqualTo(CredentialSource.PLATFORM);
        assertThat(resolved.candidates()).hasSize(2)
                .allSatisfy(candidate -> assertThat(candidate.credential().secret()).isEqualTo("platform-secret"));
    }

    @Test
    void resolvesTheFrozenPlanRouteWithoutReadingLegacyTaskConfiguration() {
        ModelCatalogRepository catalog = mock(ModelCatalogRepository.class);
        ServicePlanRuntimeService servicePlans = mock(ServicePlanRuntimeService.class);
        ServicePlanSelection selection = new ServicePlanSelection("plan-v8", "LIGHT", 2);
        when(servicePlans.requireTaskRoute(selection, ModelTask.CHARACTER_CREATION))
                .thenReturn(taskRoute(false));
        when(catalog.findPlatformModel("DEEPSEEK", "flash")).thenReturn(Optional.of(model("flash")));
        when(catalog.findPlatformCredential("DEEPSEEK")).thenReturn(Optional.of(
                new ModelCatalogRepository.EncryptedCredential(SecretCipher.encrypt("platform-secret", MASTER_KEY))));

        var resolved = new JdbcModelRoutePolicy(catalog, servicePlans, MASTER_KEY).resolve(
                new ModelRouteRequest(ModelTask.CHARACTER_CREATION, "request-plan", "user-plan", false,
                        selection));

        assertThat(resolved.credentialSource()).isEqualTo(CredentialSource.PLATFORM);
        assertThat(resolved.candidates()).singleElement()
                .satisfies(candidate -> assertThat(candidate.model().modelCode()).isEqualTo("flash"));
        verify(catalog, never()).findTaskRoute(ModelTask.CHARACTER_CREATION);
    }

    private ModelRouteRequest request(String userId) {
        return new ModelRouteRequest(ModelTask.CHARACTER_CREATION, "request-1", userId, false);
    }

    private ModelCatalogRepository.TaskRoute taskRoute(boolean fallback) {
        return new ModelCatalogRepository.TaskRoute(ModelTask.CHARACTER_CREATION,
                "DEEPSEEK", "flash", fallback ? "DEEPSEEK" : null, fallback ? "pro" : null,
                new ModelInvocationOptions(0.7, Duration.ofSeconds(60), 0, 2400, true), fallback);
    }

    private ModelDefinition model(String code) {
        return new ModelDefinition("DEEPSEEK", code, "https://example.invalid",
                ModelAdapterType.OPENAI_COMPATIBLE, code,
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL),
                128_000, 8_000);
    }
}
