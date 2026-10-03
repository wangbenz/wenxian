package com.wenxian.game.story.agent.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.agent.ai.TurnDirectorAi.*;
import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.AssetProcessCompiler;
import com.wenxian.game.story.domain.StoryPatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把模型的窄资产 DTO 转为既有内部过程合同，并为协议修复反向编码同一模型格式。
 *
 * <p>只解析固定标记和十进制正整数字面量，不解释自然语言、不推算库存、不放宽身份或保管规则。
 * 固定版本和步骤编号由服务端补齐；旧 all/known/value 模型格式没有自动回退路径。
 * 领域编译器仍在冻结前态上执行唯一资产归并。</p>
 */
public final class DirectorAssetDraftAdapter {
    public AssetProcess.Proposal toDomain(AssetProcessDraft draft) {
        require(draft != null && draft.steps() != null, "assetProcess.steps", "REQUIRED",
                "assetProcess 必须显式返回有序 steps；没有资产变化用空数组");
        require(draft.steps().size() <= 64, "assetProcess.steps", "LIMIT", "单轮资产步骤不能超过64项");
        List<AssetProcess.Step> steps = new ArrayList<>();
        for (int i = 0; i < draft.steps().size(); i++) {
            AssetStepDraft step = draft.steps().get(i);
            String path = "assetProcess.steps[" + i + "]";
            require(step != null, path, "REQUIRED", "资产步骤不能为空");
            steps.add(new AssetProcess.Step("step-" + (i + 1), step.kind(), step.summary(),
                    flows(step.inputs(), true, path + ".inputs"), flows(step.outputs(), false, path + ".outputs"),
                    observations(step.observations(), path + ".observations")));
        }
        return new AssetProcess.Proposal(AssetProcess.VERSION, steps);
    }

    /** 不依赖前态的协议预检；只用于合并一次修复反馈，不代表资产已经接受。 */
    public void validateShape(AssetProcessDraft draft) {
        new AssetProcessCompiler().validateShape(toDomain(draft));
    }

    /** 领域候选进入修复提示前重编码，避免把内部 all/known/value 或技术版本重新展示成输出格式。 */
    public AssetProcessDraft fromDomain(AssetProcess.Proposal proposal) {
        if (proposal == null) return null;
        if (proposal.steps() == null) return new AssetProcessDraft(null);
        return new AssetProcessDraft(proposal.steps().stream().map(step -> new AssetStepDraft(
                step.kind(), step.summary(), fromFlows(step.inputs()), fromFlows(step.outputs()),
                step.observations().stream().map(observation -> new ObservationDraft(observation.entityRef(),
                        quantity(observation.quantity()), observation.unit(), custody(observation.custody()),
                        observation.evidence())).toList())).toList());
    }

    private List<AssetProcess.Flow> flows(List<FlowDraft> flows, boolean input, String path) {
        require(flows != null, path, "REQUIRED", "资产步骤必须显式返回 inputs/outputs，无变化用空数组");
        List<AssetProcess.Flow> result = new ArrayList<>();
        for (int i = 0; i < flows.size(); i++) {
            FlowDraft flow = flows.get(i);
            String flowPath = path + "[" + i + "]";
            require(flow != null, flowPath, "REQUIRED", "资产变化不能为空");
            Amount amount = amount(flow.amount(), input, flowPath + ".amount");
            result.add(new AssetProcess.Flow(flow.entityRef(), amount.quantity(), amount.all(), flow.unit(),
                    custody(flow.custody(), flowPath + ".custody")));
        }
        return result;
    }

    private List<AssetProcess.Observation> observations(List<ObservationDraft> observations, String path) {
        require(observations != null, path, "REQUIRED", "资产步骤必须显式返回 observations，非盘点步骤使用空数组");
        List<AssetProcess.Observation> result = new ArrayList<>();
        for (int i = 0; i < observations.size(); i++) {
            ObservationDraft observation = observations.get(i);
            String observationPath = path + "[" + i + "]";
            require(observation != null, observationPath, "REQUIRED", "盘点记录不能为空");
            QuantityDraft quantity = observation.quantity();
            result.add(new AssetProcess.Observation(observation.entityRef(), quantity == null ? null
                    : new StoryPatch.QuantityValue(quantity.known(), quantity.value()), observation.unit(),
                    custody(observation.custody(), observationPath + ".custody"), observation.evidence()));
        }
        return result;
    }

    private Amount amount(String text, boolean input, String path) {
        require(text != null, path, "REQUIRED", "amount 必须为正整数字符串、ALL 或 UNKNOWN");
        if ("ALL".equals(text)) {
            require(input, path, "ALL_DIRECTION", "ALL 只用于 inputs；outputs 必须明确数量或 UNKNOWN");
            return new Amount(null, true);
        }
        if ("UNKNOWN".equals(text)) return new Amount(new StoryPatch.QuantityValue(false, null), false);
        require(text.length() <= 10 && text.matches("[1-9][0-9]*"), path, "AMOUNT_LITERAL",
                "amount 只能是受限十进制正整数字符串、ALL 或 UNKNOWN；不接受中文、空白、零、负数或小数");
        long value = Long.parseLong(text);
        require(value <= Integer.MAX_VALUE, path, "OVERFLOW", "amount 超出物品数量允许范围");
        return new Amount(new StoryPatch.QuantityValue(true, (int) value), false);
    }

    private StoryTurnDecision.CustodyIntent custody(CustodyDraft draft, String path) {
        if (draft == null) return null;
        StoryPatch.HolderType type;
        try {
            type = StoryPatch.HolderType.valueOf(draft.holderType() == null ? ""
                    : draft.holderType().strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new AssetProcess.ProtocolException(path + ".holderType", "CUSTODY_TYPE", "保管类型必须使用明确的协议枚举");
        }
        return new StoryTurnDecision.CustodyIntent(type, draft.holderRef(), draft.localHolderRef());
    }

    private List<FlowDraft> fromFlows(List<AssetProcess.Flow> flows) {
        return flows.stream().map(flow -> new FlowDraft(flow.entityRef(), flow.all() ? "ALL"
                : flow.amount() == null ? null : !flow.amount().known() ? "UNKNOWN"
                : flow.amount().value() == null ? null : flow.amount().value().toString(),
                flow.unit(), custody(flow.custody()))).toList();
    }

    private CustodyDraft custody(StoryTurnDecision.CustodyIntent custody) {
        return custody == null ? null : new CustodyDraft(custody.holderType() == null ? null : custody.holderType().name(),
                custody.holderRef(), custody.localHolderRef());
    }

    private QuantityDraft quantity(StoryPatch.QuantityValue quantity) {
        return quantity == null ? null : new QuantityDraft(quantity.known(), quantity.value());
    }

    private record Amount(StoryPatch.QuantityValue quantity, boolean all) {}

    private void require(boolean valid, String path, String code, String message) {
        if (!valid) throw new AssetProcess.ProtocolException(path, code, message);
    }

    /** 避免 Jackson 默认把裸数字/布尔值强转为字符串，确保实际模型输出使用本次明确的窄合同。 */
    public static final class StrictAmountDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw JsonMappingException.from(parser, "amount 必须是字符串，不能使用旧数量对象、裸数字或布尔值");
            }
            return parser.getText();
        }
    }
}
