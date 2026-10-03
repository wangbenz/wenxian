package com.wenxian.game.model.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 通过本机 HTTP 验证中性模型工厂实际发送的思考控制与可选输出上限，不访问供应商。 */
class OpenAiCompatibleChatModelFactoryTest {

    @Test
    void sendsExplicitDeepSeekThinkingDisabledParameter() throws Exception {
        JsonNode body = captureRequest(2200);
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(2200);
    }

    @Test
    void nullOutputLimitOmitsMaxTokensFromTheActualRequest() throws Exception {
        JsonNode body = captureRequest(null);
        assertThat(body.has("max_tokens")).isFalse();
        assertThat(body.has("max_completion_tokens")).isFalse();
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    private JsonNode captureRequest(Integer outputLimit) throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"chatcmpl-test","object":"chat.completion","created":1,
                     "model":"deepseek-v4-flash","choices":[{"index":0,
                     "message":{"role":"assistant","content":"{\\\"result\\\":\\\"ok\\\"}"},
                     "finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var model = new OpenAiCompatibleChatModelFactory().create(definition(server),
                    ModelCredential.platform("test-key"),
                    new ModelInvocationOptions(0.3, Duration.ofSeconds(10), 0, outputLimit, true));

            model.chat(List.of(UserMessage.from("只返回 JSON")));

            return new ObjectMapper().readTree(requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    private ModelDefinition definition(HttpServer server) {
        return new ModelDefinition("DEEPSEEK", "TEST_MODEL",
                "http://127.0.0.1:" + server.getAddress().getPort(), ModelAdapterType.OPENAI_COMPATIBLE,
                "deepseek-v4-flash", Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE,
                ModelCapability.THINKING_CONTROL), 128_000, 8_000);
    }
}
