package com.wenxian.game.story.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.agent.StoryTurnDecision.CustodyIntent;
import com.wenxian.game.story.domain.AssetProcess.*;
import com.wenxian.game.story.domain.StoryPatch.*;
import com.wenxian.game.story.domain.StoryProjection.EntityState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 从冻结前态顺序计算资产过程，生成唯一数量与保管末态。
 *
 * <p>本编译器不读取中文决定成交、配方或所有权。它验证已声明的过程、明确引用和保管来源；
 * 未知余额仍未知，不以零替代。不支持隐式合栈或单位换算，部分移交必须使用单独目标栈。
 * 过程首次明确的单位由服务端写入 quantityUnit，后续回合必须沿用；旧缺失单位保持未知。
 * 所有计算仅修改本次调用的局部对象，任一步失败不会产生可提交的部分结果。</p>
 */
public final class AssetProcessCompiler {
    private static final QuantityValue UNKNOWN = new QuantityValue(false, null);
    private static final QuantityValue ZERO = new QuantityValue(true, 0);
    private static final CustodyValue NONE = new CustodyValue(HolderType.NONE, null);
    private static final Set<String> RESERVED = Set.of("quantity", "inPossession", "custody", "_trusted",
            "holderType", "holderRef", "balance", "quantityKnown");

    /** 元数据按输入顺序保留；过程引用但没有元数据变化的旧物品会获得一个 UPDATE 补丁。 */
    public record Result(List<EntityPatch> entities, Accepted process) {}

    public Result compile(StoryProjection before, List<EntityPatch> metadata, Proposal proposal,
                          Map<String, String> localAliases) {
        validateShape(proposal);
        Context context = new Context(before, metadata, localAliases);
        List<AcceptedStep> accepted = new ArrayList<>();
        for (int index = 0; index < proposal.steps().size(); index++) {
            Step step = proposal.steps().get(index);
            String path = "assetProcess.steps[" + index + "]";
            if (step.kind() == Kind.TRANSFER) context.validateTransfer(step, path);
            List<Change> inputs = context.flows(step.inputs(), true, path + ".inputs");
            List<Change> outputs = context.flows(step.outputs(), false, path + ".outputs");
            List<Change> observations = context.observations(step.observations(), path + ".observations");
            accepted.add(new AcceptedStep(step.stepId().strip(), step.kind(), step.summary().strip(),
                    inputs, outputs, observations));
        }
        return new Result(context.patches(), new Accepted(AssetProcess.VERSION, accepted));
    }

    /**
     * 无需前态的只读形状预检，供适配层在另一字段失败时同时反馈可独立发现的资产协议错误。
     *
     * <p>只复用正式编译的版本、步骤形状及数量字段约束；不解析实体、不计算余额、不证明保管或行动语义。
     * 调用方只能合并 {@link ProtocolException} 的诊断，不能把预检通过当作过程已接受。
     * 本方法不修改提案、不访问外部资源，也不增加模型修复次数。</p>
     */
    public void validateShape(Proposal proposal) {
        require(proposal != null, "assetProcess", "REQUIRED", "新协议必须明确资产过程，无变化时使用空步骤");
        require(proposal.protocolVersion() == AssetProcess.VERSION, "assetProcess.protocolVersion",
                "VERSION", "不支持的资产过程协议版本");
        require(proposal.steps() != null, "assetProcess.steps", "REQUIRED", "缺少步骤不能降级成没有资产变化，请明确提交空列表或过程");
        require(proposal.steps().size() <= 64, "assetProcess.steps", "LIMIT", "单轮资产步骤不能超过64项");
        validateSteps(proposal.steps());
    }

    /** 先汇总可独立发现的协议形状错误，使一次修复同时收到字段位置与互斥约束，不把生产错误误报成交易失败。 */
    private void validateSteps(List<Step> steps) {
        Set<String> ids = new HashSet<>();
        List<ProtocolException> issues = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            String path = "assetProcess.steps[" + index + "]";
            require(step != null && present(step.stepId()), path + ".stepId", "REQUIRED", "步骤必须有本轮唯一引用");
            require(step.stepId().length() <= 128 && ids.add(step.stepId().strip()),
                    path + ".stepId", "DUPLICATE", "步骤引用重复或过长");
            require(step.kind() != null, path + ".kind", "REQUIRED", "缺少资产过程类型");
            require(present(step.summary()) && step.summary().length() <= 1000,
                    path + ".summary", "REQUIRED", "资产过程须有简短的已发生结果说明");
            validateShape(step, path, issues);
            validateFlowShapes(step.inputs(), true, path + ".inputs", issues);
            validateFlowShapes(step.outputs(), false, path + ".outputs", issues);
        }
        if (!issues.isEmpty()) {
            ProtocolException first = issues.getFirst();
            String detail = String.join("\n", issues.stream().limit(16).map(Throwable::getMessage).toList());
            if (issues.size() > 16) detail += "\n另有 " + (issues.size() - 16) + " 项同类错误，请按相同字段约束检查全部步骤。";
            throw new ProtocolException(first.path(), first.code(), "请同时修正以下资产协议错误：\n" + detail);
        }
    }

    private void validateShape(Step step, String path, List<ProtocolException> issues) {
        require(step.inputs().size() + step.outputs().size() + step.observations().size() <= 64,
                path, "LIMIT", "单步资产引用不能超过64项");
        boolean in = !step.inputs().isEmpty(), out = !step.outputs().isEmpty(), obs = !step.observations().isEmpty();
        if (step.kind() != Kind.INVENTORY) issue(!obs, issues, path + ".observations", "UNEXPECTED_FIELD",
                step.kind() + " 的 observations 必须为空数组；本步骤末态由 inputs/outputs 计算，不能在 observations 重复填写余额");
        switch (step.kind()) {
            case ACQUIRE -> {
                issue(!in, issues, path + ".inputs", "UNEXPECTED_FIELD", "ACQUIRE 只表达取得，inputs 必须为空数组");
                issue(out, issues, path + ".outputs", "INCOMPLETE_PROCESS", "ACQUIRE.outputs 必须列出实际取得的资产");
            }
            case PRODUCE -> issue(out, issues, path + ".outputs", "INCOMPLETE_PROCESS", "PRODUCE.outputs 必须列出实际产出的资产");
            case CONSUME -> {
                issue(in, issues, path + ".inputs", "INCOMPLETE_PROCESS", "CONSUME.inputs 必须列出实际消耗的资产");
                issue(!out, issues, path + ".outputs", "UNEXPECTED_FIELD", "CONSUME 只表达消耗，outputs 必须为空数组");
            }
            case TRANSFER -> {
                issue(step.inputs().size() == 1, issues, path + ".inputs", "FLOW_COUNT", "TRANSFER.inputs 必须且只能包含一个来源栈");
                issue(step.outputs().size() == 1, issues, path + ".outputs", "FLOW_COUNT", "TRANSFER.outputs 必须且只能包含一个目标栈");
            }
            case EXCHANGE -> {
                issue(in, issues, path + ".inputs", "INCOMPLETE_PROCESS", "EXCHANGE 已声明结算，inputs 必须列出实际交付的资产");
                issue(out, issues, path + ".outputs", "INCOMPLETE_PROCESS", "EXCHANGE 已声明结算，outputs 必须列出实际收到的对价，不能只扣交付物");
            }
            case INVENTORY -> {
                issue(!in, issues, path + ".inputs", "UNEXPECTED_FIELD", "INVENTORY 只核对或确定余额，inputs 必须为空数组");
                issue(!out, issues, path + ".outputs", "UNEXPECTED_FIELD", "INVENTORY 不能取得或补发资产，outputs 必须为空数组");
                issue(obs, issues, path + ".observations", "INCOMPLETE_PROCESS", "INVENTORY.observations 必须列出实际核对的余额和观察依据");
            }
        }
    }

    private void validateFlowShapes(List<Flow> flows, boolean input, String path, List<ProtocolException> issues) {
        for (int index = 0; index < flows.size(); index++) {
            Flow flow = flows.get(index);
            String flowPath = path + "[" + index + "]";
            if (flow == null) {
                issue(false, issues, flowPath, "REQUIRED", "资产变化不能为空");
            } else {
                try {
                    validateAmount(flow, input, flowPath);
                } catch (ProtocolException invalid) {
                    issues.add(invalid);
                }
            }
        }
    }

    private void issue(boolean valid, List<ProtocolException> issues, String path, String code, String message) {
        if (!valid) issues.add(new ProtocolException(path, code, message));
    }

    private static final class Context {
        private final StoryProjection before;
        private final Map<String, String> aliases;
        private final Map<String, Stack> stacks = new LinkedHashMap<>();
        private final Map<String, EntityPatch> patches = new LinkedHashMap<>();
        private final Set<String> characterRefs = new HashSet<>();

        private Context(StoryProjection before, List<EntityPatch> metadata, Map<String, String> aliases) {
            this.before = before;
            this.aliases = Map.copyOf(aliases);
            before.entities().values().forEach(entity -> {
                if ("ITEM".equals(entity.entityType())) stacks.put(entity.id(), new Stack(entity));
                if ("CHARACTER".equals(entity.entityType())) characterRefs.add(entity.id());
            });
            for (EntityPatch patch : metadata) {
                String ref = patch.operation() == EntityOperation.CREATE
                        ? StoryReferenceAlias.holderRef(patch.referenceKey()) : patch.entityId();
                require(patches.putIfAbsent(ref, patch) == null, "entities", "DUPLICATE_ENTITY",
                        "同一实体不能有多份独立元数据写入");
                if ("CHARACTER".equals(patch.entityType())) characterRefs.add(ref);
                if (!"ITEM".equals(patch.entityType())) continue;
                require(patch.quantity() == null && patch.custody() == null && patch.inPossession() == null,
                        "entities[" + ref + "]", "FINAL_ASSET_WRITE", "新协议的物品元数据不能另写最终数量或保管关系");
                if (patch.attributes() != null) {
                    require(patch.attributes().isObject(), "entities[" + ref + "].attributes", "ATTRIBUTES_SHAPE",
                            "物品元数据必须是对象，不能用标量覆盖已有属性");
                    for (String key : RESERVED) require(!patch.attributes().has(key), "entities[" + ref + "].attributes." + key,
                            "RESERVED_ASSET_FIELD", "普通属性不能保存另一份数量或保管字段");
                    // 只有过程编译器可以确立计数单位，普通元数据不能覆盖既有计数口径。
                    require(!patch.attributes().has("quantityUnit"), "entities[" + ref + "].attributes.quantityUnit",
                            "UNIT_METADATA", "本轮单位请放在资产过程，不通过普通元数据改写单位");
                }
                if (patch.operation() == EntityOperation.CREATE) stacks.put(ref, new Stack(ref, patch.name()));
            }
        }

        private String ref(String value, String path) {
            require(present(value), path, "REQUIRED", "缺少物品稳定引用或本轮局部引用");
            String ref = value.strip();
            String alias = aliases.get(ref);
            require(alias != null || !StoryReferenceAlias.isAlias(ref), path, "PRIVATE_REFERENCE",
                    "模型只能使用声明过的局部引用，不能猜测服务端临时键");
            require(alias == null || !before.entities().containsKey(ref), path, "AMBIGUOUS_REFERENCE",
                    "局部引用不能与已有稳定ID重名");
            if (alias != null) ref = StoryReferenceAlias.holderRef(alias);
            require(stacks.containsKey(ref), path, "UNKNOWN_ITEM", "引用必须指向已有物品或本轮明确声明的新物品");
            return ref;
        }

        private void validateTransfer(Step step, String path) {
            Flow input = step.inputs().getFirst(), output = step.outputs().getFirst();
            require(input != null && output != null, path, "REQUIRED", "转移必须有来源和目标");
            String sourceRef = ref(input.entityRef(), path + ".inputs[0].entityRef");
            String targetRef = ref(output.entityRef(), path + ".outputs[0].entityRef");
            Stack source = stacks.get(sourceRef), target = stacks.get(targetRef);
            validateAmount(input, true, path + ".inputs[0]");
            validateAmount(output, false, path + ".outputs[0]");
            QuantityValue amount = input.all() ? source.quantity : input.amount();
            require(Objects.equals(amount, output.amount()), path, "TRANSFER_QUANTITY",
                    "同一转移的来源与目标必须表示同一数量，不能凭转移增减总量");
            String sourceUnit = source.unit(input.unit(), path + ".inputs[0]");
            String targetUnit = target.unit(output.unit(), path + ".outputs[0]");
            if (sourceUnit.isBlank() && !targetUnit.isBlank()) sourceUnit = source.unit(targetUnit, path + ".inputs[0]");
            if (targetUnit.isBlank() && !sourceUnit.isBlank()) targetUnit = target.unit(sourceUnit, path + ".outputs[0]");
            require(sourceUnit.equals(targetUnit), path, "UNIT_MISMATCH", "转移两端必须使用相同单位，不自动换算");
            if (sourceRef.equals(targetRef)) {
                require(input.all() || (source.quantity.known() && amount.known()
                                && source.quantity.value().equals(amount.value())),
                        path, "PARTIAL_STACK_TRANSFER", "同一栈只能整体改变保管者，部分移交必须声明新的目标栈");
            } else {
                require(target.created && isZero(target.quantity) && !target.touched,
                        path, "IMPLICIT_STACK_MERGE", "部分移交必须使用尚未承载物品的新目标栈，不能按名称合并既有栈");
            }
        }

        private List<Change> flows(List<Flow> flows, boolean input, String path) {
            List<Change> changes = new ArrayList<>();
            Set<String> refs = new HashSet<>();
            for (int i = 0; i < flows.size(); i++) {
                Flow flow = flows.get(i);
                String currentPath = path + "[" + i + "]";
                require(flow != null, currentPath, "REQUIRED", "资产变化不能为空");
                String ref = ref(flow.entityRef(), currentPath + ".entityRef");
                require(refs.add(ref), currentPath, "DUPLICATE_FLOW", "同一步同一方向不能重复列出一个栈");
                Stack stack = stacks.get(ref);
                validateAmount(flow, input, currentPath);
                String unit = stack.unit(flow.unit(), currentPath);
                CustodyValue declared = custody(flow.custody(), currentPath + ".custody", !input);
                QuantityValue previous = stack.quantity;
                CustodyValue previousCustody = stack.custody;
                QuantityValue amount = flow.all() ? previous : flow.amount();
                if (input) {
                    require(!stack.created || stack.touched, currentPath, "FUTURE_ASSET",
                            "不能使用尚未取得、生产或明确盘点的本轮新物品");
                    require(Objects.equals(previousCustody, declared), currentPath + ".custody", "SOURCE_CUSTODY",
                            "交付或消耗必须匹配当前已确认的保管来源；保管不等于所有权");
                    require(previousCustody.holderType() != HolderType.NONE, currentPath + ".custody", "NONEXISTENT_SOURCE",
                            "已经不存在的历史栈不能再次消耗或出售；须先有真实取得或生产过程");
                    require(!isZero(previous), currentPath, "INSUFFICIENT_QUANTITY", "当前栈已经为空");
                    if (flow.all()) stack.quantity = ZERO;
                    else if (previous.known()) {
                        require(amount.known(), currentPath + ".amount", "UNKNOWN_DEDUCTION",
                                "已知库存的扣除必须有明确数量或声明全部，不能用未知量绕过不足校验");
                        require(previous.value() >= amount.value(), currentPath + ".amount", "INSUFFICIENT_QUANTITY", "当前数量不足");
                        stack.quantity = new QuantityValue(true, previous.value() - amount.value());
                    } else stack.quantity = UNKNOWN;
                    if (isZero(stack.quantity)) stack.custody = NONE;
                } else {
                    require(isZero(previous) || Objects.equals(previousCustody, declared), currentPath + ".custody",
                            "PARTIAL_CUSTODY_CHANGE", "不能把仍有余量的整个栈改交他人；请声明独立目标栈");
                    stack.quantity = add(previous, amount, currentPath);
                    stack.custody = declared;
                }
                stack.touched = true;
                changes.add(new Change(ref, stack.name, amount, flow.all(), unit, previous, stack.quantity,
                        previousCustody, stack.custody, ""));
            }
            return changes;
        }

        private List<Change> observations(List<Observation> observations, String path) {
            List<Change> changes = new ArrayList<>();
            Set<String> refs = new HashSet<>();
            for (int i = 0; i < observations.size(); i++) {
                Observation observation = observations.get(i);
                String currentPath = path + "[" + i + "]";
                require(observation != null, currentPath, "REQUIRED", "盘点不能为空");
                String ref = ref(observation.entityRef(), currentPath + ".entityRef");
                require(refs.add(ref), currentPath, "DUPLICATE_OBSERVATION", "同一次盘点不能对同一栈提交两份余额");
                require(present(observation.evidence()) && observation.evidence().length() <= 1000,
                        currentPath + ".evidence", "OBSERVATION_EVIDENCE", "盘点必须明确本轮实际观察依据，不能由遗漏推算");
                validateQuantity(observation.quantity(), true, currentPath + ".quantity");
                Stack stack = stacks.get(ref);
                require(!stack.created || stack.touched, currentPath, "UNESTABLISHED_INVENTORY",
                        "不能通过盘点凭空建立新物品；先明确取得、生产或移交过程");
                QuantityValue previous = stack.quantity;
                CustodyValue previousCustody = stack.custody;
                require(!previous.known() || previous.equals(observation.quantity()), currentPath + ".quantity",
                        "INVENTORY_OVERWRITE", "盘点只能核对已知余额或确定未知余额；已知数量变化须说明真实增减过程");
                String unit = stack.unit(observation.unit(), currentPath);
                CustodyValue custody = custody(observation.custody(), currentPath + ".custody", false);
                require(previousCustody.holderType() == HolderType.UNKNOWN || previousCustody.equals(custody),
                        currentPath + ".custody", "INVENTORY_CUSTODY_OVERWRITE",
                        "盘点不能改写已确认的保管者；真实移动须通过转移过程");
                require(isZero(observation.quantity()) || custody.holderType() != HolderType.NONE,
                        currentPath, "PRESENT_WITHOUT_CUSTODY", "非空物品不能盘点为已经不存在");
                stack.quantity = observation.quantity();
                stack.custody = isZero(stack.quantity) ? NONE : custody;
                stack.touched = true;
                changes.add(new Change(ref, stack.name, null, false, unit, previous, stack.quantity,
                        previousCustody, stack.custody, observation.evidence().strip()));
            }
            return changes;
        }

        private CustodyValue custody(CustodyIntent intent, String path, boolean target) {
            require(intent != null && intent.holderType() != null, path, "REQUIRED", "资产过程必须明确保管来源或目标");
            HolderType type = intent.holderType();
            require(!target || (type != HolderType.UNKNOWN && type != HolderType.NONE), path,
                    "INVALID_TARGET", "新取得或转移不能交给未知保管者或已经不存在的目标");
            String holderRef = null;
            if (type == HolderType.CHARACTER) {
                require(!(present(intent.holderRef()) && present(intent.localHolderRef())), path,
                        "AMBIGUOUS_HOLDER", "人物保管来源只能选择稳定引用或局部引用之一");
                if (present(intent.localHolderRef())) {
                    String alias = aliases.get(intent.localHolderRef().strip());
                    require(alias != null, path, "UNKNOWN_HOLDER", "保管者局部引用不存在");
                    holderRef = StoryReferenceAlias.holderRef(alias);
                } else holderRef = normal(intent.holderRef());
                require(characterRefs.contains(holderRef), path, "UNKNOWN_HOLDER", "保管者必须引用已有或本轮明确的人物");
            } else if (type == HolderType.LOCATION) {
                require(present(intent.holderRef()) && !present(intent.localHolderRef()), path,
                        "INVALID_HOLDER", "地点保管必须使用明确地点引用");
                holderRef = intent.holderRef().strip();
            } else require(!present(intent.holderRef()) && !present(intent.localHolderRef()), path,
                    "INVALID_HOLDER", "该保管类型不能附带人物或地点引用");
            return new CustodyValue(type, holderRef);
        }

        private List<EntityPatch> patches() {
            for (Stack stack : stacks.values()) {
                EntityPatch metadata = patches.get(stack.ref);
                require(!stack.created || stack.touched, "entities[" + stack.ref + "]", "MISSING_ASSET_PROCESS",
                        "新增物品必须有取得、生产或转移过程，不能仅通过元数据或盘点创建余额");
                if (metadata != null && metadata.operation() == EntityOperation.HIDE) {
                    require(isZero(stack.quantity), "entities[" + stack.ref + "].visible", "HIDE_NONEMPTY_STACK",
                            "不能用隐藏物品绕过资产过程清空仍有余量的栈");
                }
                if (!stack.touched) continue;
                if (metadata == null) metadata = new EntityPatch(EntityOperation.UPDATE, stack.ref, "ITEM",
                        null, null, null, null, null, null, null, null, null, null);
                EntityOperation operation = metadata.operation();
                if (!stack.created && !stack.visible && !isZero(stack.quantity)
                        && stack.custody.holderType() != HolderType.NONE) {
                    operation = EntityOperation.RESTORE;
                }
                JsonNode attributes = metadata.attributes();
                if (!stack.unit.isBlank()) {
                    ObjectNode delta = attributes instanceof ObjectNode object
                            ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
                    delta.put("quantityUnit", stack.unit);
                    attributes = delta;
                }
                patches.put(stack.ref, new EntityPatch(operation, metadata.entityId(), "ITEM", metadata.name(),
                        metadata.status(), metadata.summary(), attributes, stack.quantity,
                        stack.custody.holderType() == HolderType.PLAYER, stack.custody, metadata.abilityState(),
                        metadata.lifecycle(), metadata.relationship(), metadata.nameChange(), metadata.referenceKey()));
            }
            return List.copyOf(patches.values());
        }
    }

    private static final class Stack {
        private final String ref;
        private final String name;
        private final boolean created;
        private final boolean visible;
        private QuantityValue quantity;
        private CustodyValue custody;
        private String unit;
        private boolean touched;

        private Stack(EntityState entity) {
            ref = entity.id(); name = entity.name(); created = false; visible = entity.visible();
            quantity = entity.quantity() == null ? UNKNOWN : entity.quantity();
            validateQuantity(quantity, true, "before.entities[" + ref + "].quantity");
            custody = entity.custody() == null
                    ? new CustodyValue(Boolean.TRUE.equals(entity.inPossession()) ? HolderType.PLAYER : HolderType.UNKNOWN, null)
                    : entity.custody();
            JsonNode attributes = entity.attributes();
            unit = attributes != null && attributes.path("quantityUnit").isTextual()
                    ? normal(attributes.path("quantityUnit").asText()) : "";
        }

        private Stack(String ref, String name) {
            this.ref = ref; this.name = name; created = true; visible = true;
            quantity = ZERO; custody = NONE; unit = "";
        }

        private String unit(String requested, String path) {
            String next = normal(requested);
            require(next.length() <= 40, path + ".unit", "UNIT_LENGTH", "数量单位过长");
            require(unit.isBlank() || next.isBlank() || unit.equals(next), path + ".unit", "UNIT_MISMATCH",
                    "同一栈的计数单位不能隐式改变或换算");
            if (unit.isBlank() && !next.isBlank()) unit = next;
            return unit;
        }
    }

    private static void validateAmount(Flow flow, boolean input, String path) {
        if (flow.all()) {
            require(input, path + ".all", "ALL_DIRECTION", "outputs 不能使用 all=true；请用 amount 明确实际产出数量，all=false");
            require(flow.amount() == null, path + ".amount", "ALL_AMOUNT",
                    "all=true 与 amount 互斥：全部取出时 amount 必须为 null；若填写具体 amount，则 all 必须为 false");
        } else validateQuantity(flow.amount(), false, path + ".amount");
    }

    private static void validateQuantity(QuantityValue quantity, boolean zeroAllowed, String path) {
        require(quantity != null, path, "REQUIRED", "数量必须明确为已知或未知");
        require(quantity.known() ? quantity.value() != null && quantity.value() >= (zeroAllowed ? 0 : 1)
                : quantity.value() == null, path, "QUANTITY", "已知变化量必须为正整数；未知数量不能附带数值");
    }

    private static QuantityValue add(QuantityValue before, QuantityValue amount, String path) {
        if (!before.known() || !amount.known()) return UNKNOWN;
        long sum = (long) before.value() + amount.value();
        require(sum <= Integer.MAX_VALUE, path, "OVERFLOW", "数量相加超出允许范围");
        return new QuantityValue(true, (int) sum);
    }

    private static boolean isZero(QuantityValue value) { return value.known() && value.value() == 0; }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static String normal(String value) { return value == null ? "" : value.strip(); }
    private static void require(boolean valid, String path, String code, String message) {
        if (!valid) throw new ProtocolException(path, code, message);
    }
}
