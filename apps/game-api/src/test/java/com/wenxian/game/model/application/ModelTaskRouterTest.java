package com.wenxian.game.model.application;

import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelRoute;
import com.wenxian.game.model.domain.ModelRouteCandidate;
import com.wenxian.game.model.domain.ModelRouteRequest;
import com.wenxian.game.model.domain.ModelTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelTaskRouterTest {
    @Test
    void selectsOnePrimaryAndOneFallbackWithoutChangingRequestIdentity() {
        ModelRouteCandidate primary = candidate("flash", ModelCredential.platform("platform-secret-0001"));
        ModelRouteCandidate fallback = candidate("pro", ModelCredential.platform("platform-secret-0002"));
        ModelTaskRouter router = new ModelTaskRouter(request -> resolved(CredentialSource.PLATFORM, primary, fallback));

        ModelRoute route = router.route(new ModelRouteRequest(ModelTask.NARRATIVE_WRITER,
                "request-1", "user-1", false));

        assertThat(route.task()).isEqualTo(ModelTask.NARRATIVE_WRITER);
        assertThat(route.requestId()).isEqualTo("request-1");
        assertThat(route.primary()).isSameAs(primary);
        assertThat(route.fallbackCandidate()).contains(fallback);
    }

    @Test
    void rejectsModelMissingTaskCapabilities() {
        ModelDefinition chatOnly = definition("chat-only", Set.of(ModelCapability.CHAT));
        ModelRouteCandidate candidate = new ModelRouteCandidate(chatOnly,
                ModelCredential.platform("platform-secret-0003"),
                new ModelInvocationOptions(0.7, java.time.Duration.ofSeconds(30), 0, null, false));
        ModelTaskRouter router = new ModelTaskRouter(request -> resolved(CredentialSource.PLATFORM, candidate));

        assertThatThrownBy(() -> router.route(request(false)))
                .isInstanceOfSatisfying(ModelTaskRouter.ModelRoutingException.class,
                        error -> assertThat(error.code()).isEqualTo("MODEL_CAPABILITY_MISSING"));
    }

    @Test
    void personalKeyCannotSilentlyFallbackToPlatformCredit() {
        ModelRouteCandidate personal = candidate("personal", ModelCredential.user("personal-secret-0001"));
        ModelRouteCandidate platform = candidate("platform", ModelCredential.platform("platform-secret-0004"));
        ModelTaskRouter router = new ModelTaskRouter(request -> resolved(CredentialSource.USER, personal, platform));

        assertThatThrownBy(() -> router.route(request(false)))
                .isInstanceOfSatisfying(ModelTaskRouter.ModelRoutingException.class,
                        error -> assertThat(error.code()).isEqualTo("PLATFORM_FALLBACK_NOT_ALLOWED"));

        assertThat(router.route(request(true)).fallbackCandidate()).contains(platform);
    }

    @Test
    void rejectsMoreThanOneFallback() {
        ModelTaskRouter router = new ModelTaskRouter(request -> resolved(CredentialSource.PLATFORM,
                candidate("one", ModelCredential.platform("platform-secret-0005")),
                candidate("two", ModelCredential.platform("platform-secret-0006")),
                candidate("three", ModelCredential.platform("platform-secret-0007"))));

        assertThatThrownBy(() -> router.route(request(false)))
                .isInstanceOfSatisfying(ModelTaskRouter.ModelRoutingException.class,
                        error -> assertThat(error.code()).isEqualTo("MODEL_ROUTE_TOO_MANY_CANDIDATES"));
    }

    @Test
    void platformModeCannotUsePlayerCredentialAsFallback() {
        ModelRouteCandidate platform = candidate("platform", ModelCredential.platform("platform-secret-0008"));
        ModelRouteCandidate personal = candidate("personal", ModelCredential.user("personal-secret-0002"));
        ModelTaskRouter router = new ModelTaskRouter(request -> resolved(CredentialSource.PLATFORM, platform, personal));

        assertThatThrownBy(() -> router.route(request(false)))
                .isInstanceOfSatisfying(ModelTaskRouter.ModelRoutingException.class,
                        error -> assertThat(error.code()).isEqualTo("MODEL_CREDENTIAL_SOURCE_MISMATCH"));
    }

    private ModelRouteRequest request(boolean allowPlatformFallback) {
        return new ModelRouteRequest(ModelTask.TURN_DIRECTOR, "request-2", "user-2", allowPlatformFallback);
    }

    private ModelRoutePolicy.ResolvedCandidates resolved(CredentialSource source,
                                                         ModelRouteCandidate... candidates) {
        return new ModelRoutePolicy.ResolvedCandidates(source, List.of(candidates));
    }

    private ModelRouteCandidate candidate(String code, ModelCredential credential) {
        return new ModelRouteCandidate(definition(code, Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE,
                ModelCapability.THINKING_CONTROL)), credential, ModelInvocationOptions.defaults());
    }

    private ModelDefinition definition(String code, Set<ModelCapability> capabilities) {
        return new ModelDefinition("TEST", code, "https://example.invalid", ModelAdapterType.OPENAI_COMPATIBLE,
                code, capabilities, 64_000, 8_000);
    }
}
