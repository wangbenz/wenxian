package com.wenxian.game.model.api;

import com.wenxian.game.model.application.ModelValidationApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class InternalModelValidationControllerTest {
    private final ModelValidationApplicationService service = mock(ModelValidationApplicationService.class);

    @Test
    void blankServerTokenKeepsInternalChannelClosed() {
        InternalModelValidationController controller = new InternalModelValidationController(service, "");

        assertThatThrownBy(() -> controller.validate("TEST", "model-a",
                new InternalModelValidationController.ValidationRequest("admin-1"), "anything"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(service, never()).validate("TEST", "model-a", "admin-1");
    }

    @Test
    void mismatchedTokenDoesNotReachValidationService() {
        InternalModelValidationController controller = new InternalModelValidationController(service,
                "internal-validation-token");

        assertThatThrownBy(() -> controller.validate("TEST", "model-a",
                new InternalModelValidationController.ValidationRequest("admin-1"), "wrong-token"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getStatusCode())
                                .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(service, never()).validate("TEST", "model-a", "admin-1");
    }

    @Test
    void matchingTokenDelegatesOnlySafeIdentifiers() {
        InternalModelValidationController controller = new InternalModelValidationController(service,
                "internal-validation-token");

        controller.validate("TEST", "model-a",
                new InternalModelValidationController.ValidationRequest("admin-1"),
                "internal-validation-token");

        verify(service).validate("TEST", "model-a", "admin-1");
    }
}
