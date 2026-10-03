package com.wenxian.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdminAuthFilterTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("admin-auth-filter-" + UUID.randomUUID()).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table admin_user(id varchar(64) primary key, status varchar(16) not null)");
        jdbc.execute("""
                create table admin_session(token_hash varchar(128) primary key, user_id varchar(64) not null,
                    expires_at timestamp not null)
                """);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void exposesAuthenticatedAdminIdForAuditedCommands() throws Exception {
        String token = "valid-admin-token";
        jdbc.update("insert into admin_user values ('admin-42', 'ACTIVE')");
        jdbc.update("insert into admin_session values (?, 'admin-42', ?)", sha256(token),
                Timestamp.from(Instant.now().plusSeconds(600)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/service-plans/drafts");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new AdminAuthFilter(jdbc).doFilter(request, response, chain);

        assertEquals("admin-42", request.getAttribute(AdminAuthFilter.ADMIN_USER_ID_ATTRIBUTE));
        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
