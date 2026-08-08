package com.wenxian.admin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationAdminServiceTest {

    @Test
    void proxiesOverviewWithDedicatedInternalToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/story-migrations/v014/overview", this::overview);
        server.start();
        try {
            MigrationAdminService service = new MigrationAdminService(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "migration-secret");

            var result = service.overview();

            assertEquals(12, ((Number) result.get("sourceTotal")).intValue());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesToCallGameBackendWhenTokenIsNotConfigured() {
        MigrationAdminService service = new MigrationAdminService("http://127.0.0.1:1", "");

        ResponseStatusException error = assertThrows(ResponseStatusException.class, service::overview);

        assertEquals(503, error.getStatusCode().value());
    }

    private void overview(HttpExchange exchange) throws IOException {
        String token = exchange.getRequestHeaders().getFirst("X-Migration-Admin-Token");
        if (!"migration-secret".equals(token)) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }
        byte[] body = "{\"sourceTotal\":12,\"unprocessed\":12,\"statusCounts\":{},\"policy\":{\"prepareEnabled\":false,\"activationEnabled\":false,\"batchLimit\":10}}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
