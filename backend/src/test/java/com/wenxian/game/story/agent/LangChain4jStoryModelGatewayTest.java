package com.wenxian.game.story.agent;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LangChain4jStoryModelGatewayTest {
    private final LangChain4jStoryModelGateway gateway = new LangChain4jStoryModelGateway();

    @Test
    void normalizesProviderFailuresWithoutPassingProviderMessagesToBusinessCode() {
        assertKind(StoryModelFailureException.Kind.AUTHENTICATION,
                new AuthenticationException("secret provider response"));
        assertKind(StoryModelFailureException.Kind.CONTENT_FILTERED,
                new ContentFilteredException("secret provider response"));
        assertKind(StoryModelFailureException.Kind.RATE_LIMITED,
                new RateLimitException("secret provider response"));
        assertKind(StoryModelFailureException.Kind.REQUEST_REJECTED,
                new InvalidRequestException("secret provider response"));
        assertKind(StoryModelFailureException.Kind.TEMPORARY_UNAVAILABLE,
                new InternalServerException("secret provider response"));
    }

    @Test
    void leavesNonProviderFailuresAndAlreadyNormalizedFailuresUntouched() {
        IllegalStateException applicationFailure = new IllegalStateException("application failure");
        assertSame(applicationFailure, gateway.normalize(applicationFailure));

        StoryModelFailureException normalized = new StoryModelFailureException(
                StoryModelFailureException.Kind.RATE_LIMITED, "normalized", null);
        assertSame(normalized, gateway.normalize(normalized));
    }

    private void assertKind(StoryModelFailureException.Kind expected, RuntimeException providerFailure) {
        RuntimeException normalized = gateway.normalize(providerFailure);
        StoryModelFailureException failure = (StoryModelFailureException) normalized;
        assertEquals(expected, failure.kind());
        assertSame(providerFailure, failure.getCause());
    }
}
