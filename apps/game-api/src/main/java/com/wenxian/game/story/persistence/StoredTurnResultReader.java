package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.OrderedTurnResult;

/**
 * 读取不可变回合补丁中的已接受结果，供下一轮和修正预览直接引用。
 *
 * <p>旧记录缺字段保持为空，不从正文或摘要推断。已有字段损坏或来自未知协议时停止依赖该结果的新准备，
 * 不静默丢失已确认内容，也不修改原始回合；其他不读取回合上下文的查询不受此读取器影响。</p>
 */
public final class StoredTurnResultReader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StoredTurnResultReader() {}

    public static OrderedTurnResult.Accepted read(JsonNode patch) {
        if (patch == null || !patch.hasNonNull("acceptedResult")) return null;
        JsonNode value = patch.get("acceptedResult");
        if (!value.isObject() || !value.path("protocolVersion").isIntegralNumber()
                || !value.path("protocolVersion").canConvertToInt()
                || (value.path("protocolVersion").intValue() != 1
                    && value.path("protocolVersion").intValue() != OrderedTurnResult.VERSION)
                || !value.path("steps").isArray() || value.path("steps").isEmpty()
                || !value.path("stop").isObject()) {
            throw new IllegalStateException("已保存回合结果协议不受支持或结构损坏");
        }
        for (JsonNode step : value.path("steps")) {
            if (!step.isObject() || !step.path("stepIndex").isIntegralNumber()
                    || !step.path("cultivation").isBoolean() || !step.path("action").isTextual()
                    || !step.path("result").isTextual() || !step.path("resolution").isTextual()
                    || !step.path("assetStepIndexes").isArray() || !step.path("dependsOn").isArray()
                    || !step.path("statements").isArray()) {
                throw new IllegalStateException("已保存回合结果缺少完整步骤字段");
            }
            JsonNode realm = step.path("realmResult");
            if (step.hasNonNull("realmResult") && (!realm.isObject()
                    || !realm.path("resolution").isTextual() || !realm.path("result").isTextual())) {
                throw new IllegalStateException("已保存独立境界结果缺少完整文本和类型");
            }
        }
        try {
            OrderedTurnResult.Accepted result = MAPPER.treeToValue(value, OrderedTurnResult.Accepted.class);
            // 构造器及领域读取校验只检查已保存合同，不重新裁决历史事件。
            OrderedTurnResult.validateAccepted(result);
            return result;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalStateException("已保存回合结果无法按受支持协议读取", error);
        }
    }
}
