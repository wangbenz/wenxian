package com.wenxian.game.v014.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LangChain4jModelGatewayTest {
    @Test
    void explicitlyDisablesDeepSeekThinkingForEveryGameplayModel() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"chatcmpl-test","object":"chat.completion","created":1,
                     "model":"deepseek-v4-flash","choices":[{"index":0,
                     "message":{"role":"assistant","content":"{\\"title\\":\\"新页\\",\\"narrative\\":\\"故事\\"}"},
                     "finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            V014ModelGateway.Result result = new LangChain4jModelGateway().generate(new V014ModelGateway.Request(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "DEEPSEEK",
                    "OPENAI_COMPATIBLE", "deepseek-v4-flash", "只返回 JSON", "继续行动", 0.7));

            assertEquals("disabled", new ObjectMapper().readTree(requestBody.get()).path("thinking").path("type").asText());
            assertEquals(10, result.inputTokens());
            assertEquals(20, result.outputTokens());

            new LangChain4jModelGateway().generate(new V014ModelGateway.Request(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "DEEPSEEK",
                    "OPENAI_COMPATIBLE", "deepseek-v4-pro", "只返回 JSON", "继续行动", 0.7));
            assertEquals("disabled", new ObjectMapper().readTree(requestBody.get())
                    .path("thinking").path("type").asText());
        } finally {
            server.stop(0);
        }
    }
}
