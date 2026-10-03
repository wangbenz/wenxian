package com.wenxian.game.model.domain;

import java.util.Set;

/** 模型任务使用稳定业务编码，不随产品版本或供应商变化。 */
public enum ModelTask {
    CHARACTER_CREATION(true),
    STORY_CORRECTION(true),
    TURN_DIRECTOR(true),
    NARRATIVE_WRITER(true),
    CONTEXT_SUMMARY(true),
    FORMAT_REPAIR(true);

    private final boolean structuredOutput;

    ModelTask(boolean structuredOutput) {
        this.structuredOutput = structuredOutput;
    }

    /** 当前首批任务都返回类型化结构，因此至少要求普通对话和 JSON 模式。 */
    public Set<ModelCapability> requiredCapabilities() {
        return structuredOutput
                ? Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE)
                : Set.of(ModelCapability.CHAT);
    }

    /** 支持严格 JSON Schema 时优先使用，但不能因此排除只支持 JSON 模式的低成本模型。 */
    public Set<ModelCapability> preferredCapabilities() {
        return structuredOutput ? Set.of(ModelCapability.JSON_SCHEMA) : Set.of();
    }

    public boolean structuredOutput() {
        return structuredOutput;
    }
}
