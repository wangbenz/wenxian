package com.wenxian.game.story.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationInternalControllerTest {

    @Test
    void missingServerTokenKeepsMigrationEndpointUnavailable() {
        MigrationInternalController controller = new MigrationInternalController(null, null, "");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.overview("anything"));

        assertEquals(503, error.getStatusCode().value());
    }

    @Test
    void wrongServerTokenIsRejectedBeforeAnyMigrationServiceRuns() {
        MigrationInternalController controller = new MigrationInternalController(null, null, "shared-secret");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.overview("wrong-secret"));

        assertEquals(403, error.getStatusCode().value());
    }
}
