package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 统一处理 story_* 表中的 JSON 序列化，解析失败时终止本次读写而不返回残缺状态。 */
final class StoryJsonCodec {
    private final ObjectMapper mapper;

    StoryJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String write(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("统一命卷 JSON 无法保存", error);
        }
    }

    JsonNode read(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("统一命卷 JSON 无法读取", error);
        }
    }
}
