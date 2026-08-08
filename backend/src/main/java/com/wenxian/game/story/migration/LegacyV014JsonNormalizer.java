package com.wenxian.game.story.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 只处理 V0.14 已落库 JSON 的确定性格式缺陷。它不会补写业务字段或调用模型：
 * 仅允许未转义控制字符、解析器明确指出的冒号/逗号误写，以及唯一可解析的末尾闭合符。
 */
final class LegacyV014JsonNormalizer {
    private static final int MAX_DELIMITER_REPAIRS = 16;
    private final ObjectMapper strictMapper;
    private final ObjectMapper tolerantMapper;

    LegacyV014JsonNormalizer(ObjectMapper mapper) {
        this.strictMapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.tolerantMapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
    }

    /** 返回规范化结果；无法唯一恢复时保留失败原因，不返回局部 JSON。 */
    Result normalize(String raw) {
        String source = raw == null || raw.isBlank() ? "{}" : raw;
        JsonNode strict = read(strictMapper, source);
        if (strict != null) return new Result(strict, false, List.of(), null);

        JsonNode tolerant = read(tolerantMapper, source);
        if (tolerant != null) {
            return new Result(tolerant, true, List.of("UNESCAPED_CONTROL_CHARACTER"), null);
        }

        String candidate = source;
        List<String> repairs = new ArrayList<>();
        for (int attempt = 0; attempt < MAX_DELIMITER_REPAIRS; attempt++) {
            try {
                JsonNode parsed = tolerantMapper.readTree(candidate);
                return new Result(parsed, true, List.copyOf(repairs), null);
            } catch (JsonProcessingException error) {
                int offset = offset(error, candidate);
                String message = safeMessage(error);
                if (replaceable(message, "colon", candidate, offset, ',')) {
                    candidate = replace(candidate, offset, ':');
                    repairs.add("COMMA_REPLACED_BY_COLON");
                    continue;
                }
                if (replaceable(message, "comma", candidate, offset, ':')) {
                    candidate = replace(candidate, offset, ',');
                    repairs.add("COLON_REPLACED_BY_COMMA");
                    continue;
                }
                if (offset >= candidate.length() || message.toLowerCase(Locale.ROOT).contains("end-of-input")) {
                    String suffix = uniqueClosingSuffix(candidate);
                    if (suffix != null) {
                        candidate += suffix;
                        repairs.add("UNIQUE_CLOSING_SUFFIX_APPENDED");
                        continue;
                    }
                }
                return new Result(null, false, List.copyOf(repairs), compact(message));
            }
        }
        return new Result(null, false, List.copyOf(repairs), "确定性修复次数超过安全上限");
    }

    private JsonNode read(ObjectMapper mapper, String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private boolean replaceable(String message, String expected, String value, int offset, char actual) {
        return message.toLowerCase(Locale.ROOT).contains(expected)
                && offset >= 0 && offset < value.length() && value.charAt(offset) == actual;
    }

    private int offset(JsonProcessingException error, String value) {
        long offset = error.getLocation() == null ? -1 : error.getLocation().getCharOffset();
        if (offset < 0) return -1;
        if (offset > value.length() && error.getLocation().getByteOffset() >= 0) {
            offset = error.getLocation().getByteOffset();
        }
        return (int) Math.min(offset, Integer.MAX_VALUE);
    }

    private String replace(String value, int offset, char replacement) {
        return value.substring(0, offset) + replacement + value.substring(offset + 1);
    }

    private String uniqueClosingSuffix(String value) {
        List<String> matches = new ArrayList<>();
        char[] closers = {'}', ']'};
        for (int length = 1; length <= 3; length++) {
            int combinations = 1 << length;
            for (int bits = 0; bits < combinations; bits++) {
                StringBuilder suffix = new StringBuilder(length);
                for (int index = 0; index < length; index++) {
                    suffix.append(closers[(bits >> index) & 1]);
                }
                if (read(tolerantMapper, value + suffix) != null) matches.add(suffix.toString());
            }
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private String safeMessage(JsonProcessingException error) {
        String message = error.getOriginalMessage();
        return message == null ? "JSON 无法解析" : message;
    }

    private String compact(String value) {
        String compact = value.replace('\n', ' ').replace('\r', ' ').strip();
        return compact.length() <= 180 ? compact : compact.substring(0, 180);
    }

    /** JSON 规范化结果，repairCodes 只记录格式修复类型，不包含玩家剧情内容。 */
    record Result(JsonNode value, boolean repaired, List<String> repairCodes, String error) {
        boolean valid() {
            return value != null;
        }
    }
}
