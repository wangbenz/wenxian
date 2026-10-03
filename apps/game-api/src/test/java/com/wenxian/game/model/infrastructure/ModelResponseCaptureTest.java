package com.wenxian.game.model.infrastructure;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.output.OutputParsingException;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ModelResponseCaptureTest {
    @Test
    void retainsResponseOnlyAsAnExplicitFieldAndNeverPrintsTheRawParsingFailure() {
        String raw = "private-response-must-not-appear-in-logs";
        ChatResponse response = response(raw);
        AtomicInteger calls = new AtomicInteger();

        var failure = catchThrowableOfType(() -> ModelResponseCapture.invoke(() -> {
            calls.incrementAndGet();
            ModelResponseCapture.record(response);
            throw parsingFailure(raw);
        }), ModelResponseCapture.DecodingFailure.class);

        assertThat(failure.response()).isSameAs(response);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        StringWriter printable = new StringWriter();
        failure.printStackTrace(new PrintWriter(printable));
        assertThat(printable.toString()).contains("模型响应无法解析为类型协议")
                .doesNotContain(raw, "OutputParsingException", "private-parser-cause");
        assertThat(calls).hasValue(1);
    }

    @Test
    void clearsFailureAndSuccessScopesAndIgnoresUnscopedEvents() {
        var first = catchThrowableOfType(() -> ModelResponseCapture.invoke(() -> {
            ModelResponseCapture.record(response("failed-response"));
            throw parsingFailure("failed-response");
        }), ModelResponseCapture.DecodingFailure.class);
        assertThat(first.response().aiMessage().text()).isEqualTo("failed-response");

        assertThat(ModelResponseCapture.invoke(() -> {
            ModelResponseCapture.record(response("successful-response"));
            return "ok";
        })).isEqualTo("ok");
        ModelResponseCapture.record(response("outside-an-invocation"));

        assertThat(failureWithoutResponse().response()).isNull();
    }

    @Test
    void doesNotConvertOrRetryOtherFailuresAndClearsTheirResponse() {
        var original = new IllegalStateException("network unavailable");
        AtomicInteger calls = new AtomicInteger();
        var failure = catchThrowableOfType(() -> ModelResponseCapture.invoke(() -> {
            calls.incrementAndGet();
            ModelResponseCapture.record(response("unrelated-response"));
            throw original;
        }), IllegalStateException.class);

        assertThat(failure).isSameAs(original);
        assertThat(calls).hasValue(1);
        assertThat(failureWithoutResponse().response()).isNull();
    }

    @Test
    void restoresTheOuterScopeAfterAnIndependentNestedInvocation() {
        ChatResponse outer = response("outer");
        var failure = catchThrowableOfType(() -> ModelResponseCapture.invoke(() -> {
            ModelResponseCapture.record(outer);
            var inner = catchThrowableOfType(() -> ModelResponseCapture.invoke(() -> {
                ModelResponseCapture.record(response("inner"));
                throw parsingFailure("inner");
            }), ModelResponseCapture.DecodingFailure.class);
            assertThat(inner.response().aiMessage().text()).isEqualTo("inner");
            throw parsingFailure("outer");
        }), ModelResponseCapture.DecodingFailure.class);

        assertThat(failure.response()).isSameAs(outer);
        assertThat(failureWithoutResponse().response()).isNull();
    }

    @Test
    void doesNotInheritTheCurrentScopeOnAnotherThread() {
        ChatResponse outer = response("same-thread-only");
        var failure = catchThrowableOfType(() -> ModelResponseCapture.invoke(() -> {
            ModelResponseCapture.record(outer);
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> {
                    ModelResponseCapture.record(response("foreign-thread"));
                    assertThat(failureWithoutResponse().response()).isNull();
                }).get(5, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            throw parsingFailure("same-thread-only");
        }), ModelResponseCapture.DecodingFailure.class);

        assertThat(failure.response()).isSameAs(outer);
    }

    private ModelResponseCapture.DecodingFailure failureWithoutResponse() {
        return catchThrowableOfType(() -> ModelResponseCapture.invoke(() -> {
            throw parsingFailure("no-response");
        }), ModelResponseCapture.DecodingFailure.class);
    }

    private OutputParsingException parsingFailure(String raw) {
        return new OutputParsingException("Cannot parse: " + raw,
                new IllegalArgumentException("private-parser-cause: " + raw));
    }

    private ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
