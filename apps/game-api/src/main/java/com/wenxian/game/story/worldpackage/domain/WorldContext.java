package com.wenxian.game.story.worldpackage.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/**
 * 为一次模型调用链冻结的世界资料及来源，不是命卷事实或玩家知识。
 *
 * <p>选择器不裁决资料公开性，调用层仍须按任务投影。字符预算按每条 Material 的紧凑 JSON 的
 * UTF-16 长度之和计算，包含引用和原因，不含外层来源元数据；它不是模型 token 或实际内存大小。</p>
 */
public record WorldContext(
        Source source,
        String selectorVersion,
        List<Material> materials,
        int candidateCount,
        int omittedCount,
        int usedCharacters,
        int maximumCharacters,
        List<String> unavailableInputs
) {
    public WorldContext {
        Objects.requireNonNull(source, "世界资料缺少来源");
        Objects.requireNonNull(selectorVersion, "世界资料缺少选择器版本");
        materials = List.copyOf(materials);
        unavailableInputs = List.copyOf(unavailableInputs);
        if (candidateCount < 0 || omittedCount < 0 || candidateCount != materials.size() + omittedCount
                || usedCharacters < 0 || maximumCharacters < 1 || usedCharacters > maximumCharacters) {
            throw new IllegalArgumentException("世界资料选择计数或字符预算无效");
        }
    }

    /** 固定标记资料信任层，不能由包作者或查询参数提升为系统指令。 */
    @JsonProperty("trustLevel")
    public String trustLevel() {
        return "UNTRUSTED_WORLD_BACKGROUND";
    }

    /** 本次已验证的不可变修订身份；不包含凭据、所有者信息或访问授权。 */
    public record Source(String revisionId, String publisherNamespace, String packageId, long revision,
                         String contentHash, String compiledHash, String runtimeContract) {}

    /** 单条来源化资料；防御性复制 JSON，调用方不能修改已冻结输入。 */
    public record Material(String ref, String kind, JsonNode data, List<String> reasons) {
        public Material {
            Objects.requireNonNull(ref, "世界资料缺少引用");
            Objects.requireNonNull(kind, "世界资料缺少类型");
            data = Objects.requireNonNull(data, "世界资料缺少内容").deepCopy();
            reasons = List.copyOf(reasons);
        }

        @Override
        public JsonNode data() {
            return data.deepCopy();
        }
    }
}
