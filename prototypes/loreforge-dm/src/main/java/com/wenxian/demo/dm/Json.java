package com.wenxian.demo.dm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static String write(Object value) { try { return MAPPER.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("数据编码失败", e); } }
    static <T> T read(String text, Class<T> type) { try { return MAPPER.readValue(text, type); } catch (Exception e) { throw new IllegalArgumentException("数据格式不正确", e); } }
    static JsonNode tree(String text) { return read(text, JsonNode.class); }
    static String resource(String path) {
        try (var stream = Json.class.getResourceAsStream("/" + path)) {
            if (stream == null) throw new IllegalStateException("缺少资源: " + path);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new IllegalStateException("读取资源失败", e); }
    }
}
