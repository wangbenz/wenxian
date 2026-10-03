package com.wenxian.game.story.application;

import com.wenxian.game.api.GameApiException;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.story.agent.StoryModelFailureException;
import com.wenxian.game.story.agent.StoryModelSelectionGateway;
import com.wenxian.game.story.domain.StoryActionPreconditionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 核心回合只依赖稳定错误码，不把供应商异常或协议细节直接交给 H5。 */
class StoryTurnFailureMapperTest {

    @Test
    void invalidatesRejectedPersonalCredentialWithoutConsumingTurn() {
        StubModelSelections selections = new StubModelSelections();
        StoryTurnFailureMapper mapper = new StoryTurnFailureMapper(selections);
        RuntimeException mapped = mapper.map("user-1", CredentialSource.USER,
                new StoryModelFailureException(StoryModelFailureException.Kind.AUTHENTICATION,
                        "provider returned 401", null));

        assertThat(mapped).isInstanceOf(GameApiException.class);
        GameApiException failure = (GameApiException) mapped;
        assertThat(failure.code()).isEqualTo("PERSONAL_KEY_REJECTED");
        assertThat(failure.turnConsumed()).isFalse();
        assertThat(failure.stateChanged()).isFalse();
        assertThat(selections.invalidated).isTrue();
    }

    @Test
    void keepsPlayerActionConflictDistinctFromInternalProtocolFailure() {
        StoryTurnFailureMapper mapper = new StoryTurnFailureMapper(new StubModelSelections());
        GameApiException conflict = (GameApiException) mapper.map("user-1", CredentialSource.PLATFORM,
                new StoryActionPreconditionException("尚未学会御剑术", "先寻找御剑传承并学习"));
        GameApiException protocol = (GameApiException) mapper.map("user-1", CredentialSource.PLATFORM,
                new StoryTurnProtocolException("DIRECTOR_OUTPUT", "结构不完整", null));

        assertThat(conflict.code()).isEqualTo("ACTION_PRECONDITION_FAILED");
        assertThat(conflict.suggestions()).containsExactly("先寻找御剑传承并学习");
        assertThat(conflict.retryable()).isFalse();
        assertThat(protocol.code()).isEqualTo("STORY_INTERNAL_RECOVERY_FAILED");
        assertThat(protocol.retryable()).isTrue();
        assertThat(protocol.details()).containsEntry("stage", "DIRECTOR_OUTPUT");
    }

    @Test
    void leavesUnknownRuntimeFailureForGlobalProblemTracking() {
        StoryTurnFailureMapper mapper = new StoryTurnFailureMapper(new StubModelSelections());
        IllegalStateException original = new IllegalStateException("database unavailable");
        assertThat(mapper.map("user-1", CredentialSource.PLATFORM, original)).isSameAs(original);
    }

    private static final class StubModelSelections implements StoryModelSelectionGateway {
        private boolean invalidated;

        @Override
        public StoryModelSelection currentSelection(String userId) {
            return new StoryModelSelection("DEEPSEEK", "test-model", "PERSONAL");
        }

        @Override
        public void markCredentialInvalid(String userId, String provider, String reason) {
            invalidated = true;
        }
    }
}
