package com.wenxian.game.model.infrastructure;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.output.OutputParsingException;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 在一次同步类型化 AI Service 调用内保留响应，供解码失败后的既有修复与用量记录读取。
 *
 * <p>监听器只记录当前线程的调用作用域；不缓存跨请求响应、不解析正文，也不重试模型。
 * 作用域在所有退出路径清理并恢复嵌套调用，不能用于跨线程或流式回调。</p>
 */
public final class ModelResponseCapture {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private ModelResponseCapture() {}

    /** 执行一次同步调用，仅把类型解析失败转换为不泄露正文的异常。 */
    public static <T> T invoke(Supplier<T> invocation) {
        Objects.requireNonNull(invocation, "模型调用不能为空");
        Scope previous = CURRENT.get();
        Scope current = new Scope();
        CURRENT.set(current);
        try {
            return invocation.get();
        } catch (OutputParsingException ignored) {
            // 原解析异常的 message/cause 可能含完整正文，不附加到可打印的异常链。
            throw new DecodingFailure(current.response);
        } finally {
            current.response = null;
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    /** 接收类型解析前的响应事件；没有同步调用作用域时不保留任何内容。 */
    public static void record(ChatResponse response) {
        Scope current = CURRENT.get();
        if (current != null) current.response = response;
    }

    /** 原始响应只供调用方显式读取；异常消息与异常链不携带模型正文。 */
    public static final class DecodingFailure extends RuntimeException {
        private final ChatResponse response;

        private DecodingFailure(ChatResponse response) {
            super("模型响应无法解析为类型协议", null);
            this.response = response;
        }

        /** 返回本次响应；若解析异常前没有收到响应事件则为 null，不能借用其他调用的结果。 */
        public ChatResponse response() {
            return response;
        }
    }

    private static final class Scope {
        private ChatResponse response;
    }
}
