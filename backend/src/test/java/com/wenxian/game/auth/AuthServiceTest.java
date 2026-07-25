package com.wenxian.game.auth;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {
    @Test
    void inviteIsRequiredOnlyForFirstLogin() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql")
                .build();
        try {
            AuthService service = new AuthService(new JdbcTemplate(database), "TEST-INVITE", 30);
            service.ensureBootstrapInvite();

            AuthService.LoginOutcome first = service.login("Friend@Example.com", "TEST-INVITE");
            assertTrue(first.newUser());
            assertNotNull(service.currentUser(first.rawToken()));

            AuthService.LoginOutcome second = service.login("friend@example.com", null);
            assertFalse(second.newUser());
            assertNotNull(service.currentUser(second.rawToken()));
        } finally {
            database.shutdown();
        }
    }
}
