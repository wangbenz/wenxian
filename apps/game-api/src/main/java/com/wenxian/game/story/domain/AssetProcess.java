package com.wenxian.game.story.domain;

import com.wenxian.game.story.agent.StoryTurnDecision.CustodyIntent;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 单回合资产过程协议及只读的已接受过程。
 *
 * <p>操作描述持久资产的变化，不分类玩家可以尝试的小说行动。输入、产出和盘点由模型提出，
 * 服务端计算余额；已接受记录仅供正文和回合追溯，不是第二份可独立执行的库存命令。
 * 旧回合没有本协议时保持缺失，不能从旧正文自动补账。</p>
 */
public final class AssetProcess {
    public static final int VERSION = 1;

    private AssetProcess() {}

    public record Proposal(int protocolVersion, List<Step> steps) {
        public Proposal { steps = steps == null ? null : List.copyOf(steps); }
    }

    /** EXCHANGE 只表示对价已经结清；欠款和未完成交易不能作为已收到的资产。 */
    public enum Kind { ACQUIRE, PRODUCE, CONSUME, TRANSFER, EXCHANGE, INVENTORY }

    public record Step(String stepId, Kind kind, String summary, List<Flow> inputs,
                       List<Flow> outputs, List<Observation> observations) {
        public Step {
            inputs = safe(inputs);
            outputs = safe(outputs);
            observations = safe(observations);
        }
    }

    /**
     * 输入表示从一个明确栈取出，输出表示加入一个明确栈。all 仅用于输入且 amount 必须为空，
     * 表示明确取出全部余量；未知数量不等于零。custody 是本步骤取出前或加入后的实际保管者，不证明所有权。
     */
    public record Flow(String entityRef, QuantityValue amount, boolean all, String unit,
                       CustodyIntent custody) {}

    /** 盘点只核对已知余额或确定已有物品的未知余额；不能创建新栈、覆盖已知余额或替代真实增减。 */
    public record Observation(String entityRef, QuantityValue quantity, String unit,
                              CustodyIntent custody, String evidence) {}

    public record Accepted(int protocolVersion, List<AcceptedStep> steps) {
        public Accepted { steps = safe(steps); }
    }

    public record AcceptedStep(String stepId, Kind kind, String summary, List<Change> inputs,
                               List<Change> outputs, List<Change> observations) {
        public AcceptedStep {
            inputs = safe(inputs);
            outputs = safe(outputs);
            observations = safe(observations);
        }
    }

    /** 单步计算证据；amount 是实际变化量，盘点时为空，前后余额可以保持未知。 */
    public record Change(String entityRef, String entityName, QuantityValue amount, boolean all,
                         String unit, QuantityValue beforeQuantity, QuantityValue afterQuantity,
                         CustodyValue beforeCustody, CustodyValue afterCustody, String evidence) {}

    /** 带稳定错误码及字段路径的可修复协议错误，不包装数据库、权限或程序异常。 */
    public static final class ProtocolException extends IllegalArgumentException {
        private final String path;
        private final String code;

        public ProtocolException(String path, String code, String message) {
            super(path + " [" + code + "] " + message);
            this.path = path;
            this.code = code;
        }

        public String path() { return path; }
        public String code() { return code; }
    }

    /**
     * Reducer 已为新增实体分配正式 ID 后，统一解析过程中的物品与人物局部引用。
     * 缺失映射必须失败，不能把临时引用保存为已提交事实；旧回合的空过程仍返回空。
     */
    public static Accepted resolveRefs(Accepted process, List<EntityPatch> acceptedEntities) {
        if (process == null) return null;
        Map<String, String> ids = new HashMap<>();
        for (EntityPatch patch : acceptedEntities) {
            if (patch.referenceKey() != null && !patch.referenceKey().isBlank()) {
                String prior = ids.putIfAbsent(patch.referenceKey(), patch.entityId());
                if (prior != null && !prior.equals(patch.entityId())) {
                    throw new IllegalStateException("资产过程引用映射重复");
                }
            }
        }
        UnaryOperator<String> resolver = ref -> {
            if (!StoryReferenceAlias.isAlias(ref)) return ref;
            String id = ids.get(StoryReferenceAlias.keyOf(ref));
            if (id == null || id.isBlank()) throw new IllegalStateException("资产过程缺少正式实体引用：" + ref);
            return id;
        };
        return new Accepted(process.protocolVersion(), process.steps().stream().map(step ->
                new AcceptedStep(step.stepId(), step.kind(), step.summary(),
                        resolve(step.inputs(), resolver), resolve(step.outputs(), resolver),
                        resolve(step.observations(), resolver))).toList());
    }

    private static List<Change> resolve(List<Change> changes, UnaryOperator<String> resolver) {
        return changes.stream().map(change -> new Change(resolver.apply(change.entityRef()),
                change.entityName(), change.amount(), change.all(), change.unit(), change.beforeQuantity(),
                change.afterQuantity(), resolveCustody(change.beforeCustody(), resolver),
                resolveCustody(change.afterCustody(), resolver), change.evidence())).toList();
    }

    private static CustodyValue resolveCustody(CustodyValue custody, UnaryOperator<String> resolver) {
        if (custody == null || custody.holderType() != StoryPatch.HolderType.CHARACTER) return custody;
        return new CustodyValue(custody.holderType(), resolver.apply(custody.holderRef()));
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
