package com.wenxian.game.story.agent.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.model.application.ModelInvocationService;
import com.wenxian.game.model.application.ModelTaskRouter;
import com.wenxian.game.model.application.ServicePlanRuntimeService;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ModelRoute;
import com.wenxian.game.model.domain.ModelRouteRequest;
import com.wenxian.game.model.domain.ModelTask;
import com.wenxian.game.model.domain.ServicePlanSelection;
import com.wenxian.game.model.infrastructure.ModelResponseCapture;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryWorldTime;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 旧双阶段模型实现，仅为历史离线实验对照保留，不注册 Spring Bean。
 *
 * <p>会话在任何供应商调用前一次性冻结导演与正文任务路由，并对密钥来源做一致性校验。
 * 上层只读取脱敏后的密钥来源决定是否预留平台额度，不再额外解析旧版模型路由。</p>
 */
public class LangChain4jDirectedNarrativeGenerator implements DirectedNarrativeGenerator {
    private final ModelTaskRouter router;
    private final ServicePlanRuntimeService servicePlans;
    private final ModelInvocationService invocations;
    private final StoryAiServiceRegistry services;
    private final ObjectMapper mapper;
    private final DirectorAssetDraftAdapter assets = new DirectorAssetDraftAdapter();

    @Autowired
    public LangChain4jDirectedNarrativeGenerator(ModelTaskRouter router, ServicePlanRuntimeService servicePlans,
                                                 ModelInvocationService invocations,
                                                 StoryAiServiceRegistry services, ObjectMapper mapper) {
        this.router = router;
        this.servicePlans = servicePlans;
        this.invocations = invocations;
        this.services = services;
        this.mapper = mapper;
    }

    /** 仅供不加载运行时方案的离线回放与单元测试使用。 */
    public LangChain4jDirectedNarrativeGenerator(ModelTaskRouter router, ModelInvocationService invocations,
                                                 StoryAiServiceRegistry services, ObjectMapper mapper) {
        this(router, null, invocations, services, mapper);
    }

    @Override
    public Session open(InvocationContext invocation) {
        if (invocation == null) throw new IllegalArgumentException("类型化回合调用缺少上下文");
        ServicePlanSelection servicePlan = servicePlans == null
                ? null : servicePlans.freezeForUser(invocation.userId()).orElse(null);
        ModelRoute director = resolve(ModelTask.TURN_DIRECTOR, invocation, servicePlan);
        ModelRoute writer = resolve(ModelTask.NARRATIVE_WRITER, invocation, servicePlan);
        requireSameCredentialSource(director, writer);
        return new ResolvedSession(invocation, director, writer, servicePlan);
    }

    private final class ResolvedSession implements Session {
        private final InvocationContext invocation;
        private final ModelRoute directorRoute;
        private final ModelRoute writerRoute;
        private final ServicePlanSelection servicePlan;

        private ResolvedSession(InvocationContext invocation,
                                ModelRoute directorRoute, ModelRoute writerRoute,
                                ServicePlanSelection servicePlan) {
            this.invocation = invocation;
            this.directorRoute = directorRoute;
            this.writerRoute = writerRoute;
            this.servicePlan = servicePlan;
        }

        @Override
        public CredentialSource credentialSource() {
            return directorRoute.primary().credential().source();
        }

        @Override
        public Optional<ServicePlanSelection> servicePlanSelection() {
            return Optional.ofNullable(servicePlan);
        }

        @Override
        public DirectedPlan plan(StoryPromptContext context) {
            return direct(directorPayload(context));
        }

        @Override
        public DirectedPlan repair(StoryPromptContext context,
                                   StoryTurnDecision rejected, String problem) {
            Map<String, Object> payload = directorPayload(context);
            payload.put("mode", "REPAIR_REJECTED_DECISION");
            payload.put("rejectedDecision", modelDraft(rejected));
            payload.put("validationProblem", problem == null ? "事实意图无法编译" : problem.strip());
            return direct(payload);
        }

        @Override
        public DirectedPlan repairProtocol(StoryPromptContext context, DirectorProtocolException rejected) {
            Map<String, Object> payload = directorPayload(context);
            payload.put("mode", "REPAIR_REJECTED_DECISION");
            payload.put("rejectedDraft", rejected.rejectedDraft() instanceof StoryTurnDecision decision
                    ? modelDraft(decision) : rejected.rejectedDraft());
            payload.put("rejectedPhase", rejected.phase());
            payload.put("validationProblem", rejected.getMessage());
            return direct(payload);
        }

        /** 调用完成即保留用量；仅把明确的输出解码/适配错误送入上层共用修复预算。 */
        private DirectedPlan direct(Map<String, Object> payload) {
            ModelInvocationService.Invocation<TurnDirectorAi.DecisionDraft> result;
            try {
                result = invoke(ModelTask.TURN_DIRECTOR, invocation, directorRoute, servicePlan,
                        services::turnDirector, service -> ModelResponseCapture.invoke(
                                () -> service.direct(json(payload, "导演上下文无法编码"))));
            } catch (ModelInvocationService.ModelInvocationException failed) {
                if (!outputDecodingFailure(failed)) throw failed;
                // 原稿由本次同步响应事件取得，只作未接受的修复输入；不能从异常消息拆文本或直接合入状态。
                var captured = capturedDecodingFailure(failed);
                String rawDraft = captured == null || captured.response() == null
                        || captured.response().aiMessage() == null ? null : captured.response().aiMessage().text();
                throw new DirectorProtocolException(DirectorProtocolException.Phase.DECODING, rawDraft,
                        modelCalls(failed.attempts()), "导演响应无法解析为类型协议，请依据同一行动修正草案结构",
                        new IllegalArgumentException("OutputParsingException：类型化响应解析失败"));
            }
            List<ModelCall> calls = modelCalls(result);
            try {
                return new DirectedPlan(toDecision(result.content()), calls);
            } catch (IllegalArgumentException invalidDraft) {
                String problem = invalidDraft.getMessage();
                // 适配已失败时仍检查不依赖前态的过程形状，让唯一一次修复同时看到独立字段错误。
                // 这不是事实归并；引用、保管与余额仍只由应用层在冻结前态上校验。
                if (result.content() != null && result.content().assetProcess() != null) {
                    try {
                        assets.validateShape(result.content().assetProcess());
                    } catch (AssetProcess.ProtocolException invalidShape) {
                        problem += "\n" + invalidShape.getMessage();
                    }
                }
                throw new DirectorProtocolException(DirectorProtocolException.Phase.ADAPTATION,
                        result.content(), calls, problem, invalidDraft);
            }
        }

        @Override
        public RenderedNarrative render(StoryPromptContext context,
                                        StoryPatch acceptedPlan, NarrativeEndState endState) {
            if (acceptedPlan == null) throw new IllegalArgumentException("正文演绎缺少已接受结果计划");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("beforeState", objectivePayload(context));
            if (context.worldContext() != null) payload.put("worldContext", context.worldContext());
            payload.put("action", context.action());
            payload.put("narrativeContext", context.narrative());
            payload.put("acceptedPlan", acceptedPlan);
            if (endState == null) throw new IllegalArgumentException("正文缺少回合结束事实");
            payload.put("endState", endState);
            StoryWorldTime startTime = context.objective().worldTime();
            StoryWorldTime endTime = endState.worldTime();
            if (!endTime.equals(startTime.advance(acceptedPlan.time().duration()))) {
                throw new IllegalArgumentException("正文结束时间与已接受计划不一致");
            }
            Map<String, Object> timeBoundary = new LinkedHashMap<>();
            timeBoundary.put("start", startTime);
            timeBoundary.put("end", endTime);
            timeBoundary.put("duration", acceptedPlan.time().duration());
            timeBoundary.put("resolution", acceptedPlan.time().resolution());
            payload.put("worldTimeBoundary", timeBoundary);
            ModelInvocationService.Invocation<NarrativeWriterAi.NarrativeDraft> result = invoke(
                    ModelTask.NARRATIVE_WRITER, invocation, writerRoute, servicePlan, services::narrativeWriter,
                    service -> service.write(json(payload, "正文演绎上下文无法编码")));
            NarrativeWriterAi.NarrativeDraft draft = result.content();
            List<StoryPatch.ChoiceDraft> choices = draft.choices() == null ? List.of() : draft.choices().stream()
                    .filter(java.util.Objects::nonNull)
                    .map(choice -> new StoryPatch.ChoiceDraft(choice.title(), choice.description()))
                    .toList();
            return new RenderedNarrative(draft.title(), draft.narrative(), choices,
                    draft.summary(), modelCalls(result));
        }
    }

    private Map<String, Object> directorPayload(StoryPromptContext context) {
        if (context == null) throw new IllegalArgumentException("导演上下文不能为空");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "PLAN_CURRENT_ACTION");
        payload.put("objective", objectivePayload(context));
        if (context.worldContext() != null) payload.put("worldContext", context.worldContext());
        payload.put("cognition", context.cognition());
        payload.put("memory", context.memory());
        payload.put("narrative", context.narrative());
        payload.put("action", context.action());
        return payload;
    }

    /** 保留命卷结构化事实，移除重复包描述，防止未选择的世界正文绕过资料分区与预算。 */
    private JsonNode objectivePayload(StoryPromptContext context) {
        ObjectNode objective = mapper.valueToTree(context.objective());
        if (context.worldContext() != null) {
            if (objective.get("creationContract") instanceof ObjectNode creation) {
                creation.remove("worldPremise");
            }
            if (objective.get("cultivation") instanceof ObjectNode cultivation) {
                cultivation.remove("summary");
                cultivation.path("stages").forEach(stage -> {
                    if (stage instanceof ObjectNode value) value.remove("meaning");
                });
            }
        }
        return objective;
    }

    private StoryTurnDecision toDecision(TurnDirectorAi.DecisionDraft draft) {
        if (draft == null || draft.duration() == null) {
            throw new IllegalArgumentException("导演草案缺少时间尺度");
        }
        SemanticDuration.Unit durationUnit = SemanticDuration.Unit.valueOf(
                required(draft.duration().unit(), "时间尺度").name());
        String targetPhase = durationUnit == SemanticDuration.Unit.PHASE
                ? text(draft.duration().targetPhase()) : "";
        SemanticDuration duration = new SemanticDuration(durationUnit,
                Math.max(1, draft.duration().value()), targetPhase);
        StoryTurnDecision.CoreStateIntent core = draft.coreState() == null ? null
                : new StoryTurnDecision.CoreStateIntent(optional(draft.coreState().location()),
                optional(draft.coreState().condition()), optional(draft.coreState().realm()));
        StoryTurnDecision.CultivationIntent cultivation = draft.cultivation() == null ? null
                : new StoryTurnDecision.CultivationIntent(draft.cultivation().progressGained(),
                draft.cultivation().breakthroughAttempted(), draft.cultivation().reason(),
                safe(draft.cultivation().evidenceRefs()));
        List<StoryTurnDecision.EntityIntent> entities = new java.util.ArrayList<>();
        appendItemChanges(entities, draft.itemChanges());
        appendChanges(entities, draft.abilityChanges(), "ABILITY");
        appendChanges(entities, draft.entities(), null);
        List<StoryTurnDecision.WorldFactIntent> worldFacts = safe(draft.worldFacts()).stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> new StoryTurnDecision.WorldFactIntent(value.factId(), value.statement(), value.reason()))
                .toList();
        return new StoryTurnDecision(null, duration,
                StoryPatch.TimeResolution.valueOf(required(draft.timeResolution(), "时间结果").name()),
                draft.interruptionReason(), core, cultivation, entities, worldFacts, List.of(),
                null,
                List.of(), requiredAssetProcess(draft), requiredUnderstanding(draft), requiredOrderedResult(draft));
    }

    /** 模型必须明确提交有序结果与停止原因；不把缺失协议从旧摘要、节拍或人物元数据补造出来。 */
    private OrderedTurnResult.Proposal requiredOrderedResult(TurnDirectorAi.DecisionDraft draft) {
        var result = draft.orderedResult();
        if (result == null || result.steps() == null || result.steps().isEmpty() || result.steps().size() > 16
                || result.stop() == null) {
            throw new IllegalArgumentException("orderedResult 必须有1至16项有序 steps 和明确 stop，不能用旧摘要代替");
        }
        return result;
    }

    private com.wenxian.game.story.domain.AssetProcess.Proposal requiredAssetProcess(TurnDirectorAi.DecisionDraft draft) {
        return assets.toDomain(draft.assetProcess());
    }

    /** 物品在模型格式中只有元数据字段，固定类型与空数量由边界适配补齐，不能另写一份余额。 */
    private void appendItemChanges(List<StoryTurnDecision.EntityIntent> target, List<TurnDirectorAi.ItemDraft> items) {
        if (items == null) throw new IllegalArgumentException("导演必须明确返回 itemChanges，无物品元数据变化用空数组");
        java.util.Set<String> protectedKeys = java.util.Set.of("quantity", "inPossession", "custody", "_trusted",
                "holderType", "holderRef", "balance", "quantityKnown", "quantityUnit");
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (item == null) throw new IllegalArgumentException("itemChanges[" + i + "] 不能包含空项");
            for (var attribute : safe(item.attributes())) {
                if (attribute != null && protectedKeys.contains(text(attribute.key()))) {
                    throw new IllegalArgumentException("itemChanges[" + i + "].attributes." + attribute.key()
                            + " 是受保护的资产字段，不能用普通元数据写入");
                }
            }
            target.add(entity(new TurnDirectorAi.EntityDraft(item.existingEntityId(), item.localRef(), "ITEM", item.name(),
                    item.status(), item.summary(), null, item.attributes(), null, null, null, null, null, item.visible())));
        }
    }

    /** 编译拒绝后仍交付同一种窄模型草案，内部 v1 的 all、版本、步骤编号不进入修复输出示例。 */
    private TurnDirectorAi.DecisionDraft modelDraft(StoryTurnDecision decision) {
        if (decision == null) return null;
        var duration = decision.duration();
        var core = decision.coreState();
        var cultivation = decision.cultivation();
        List<TurnDirectorAi.ItemDraft> items = new java.util.ArrayList<>();
        List<TurnDirectorAi.EntityDraft> abilities = new java.util.ArrayList<>();
        List<TurnDirectorAi.EntityDraft> entities = new java.util.ArrayList<>();
        for (var intent : decision.entities()) {
            if ("ITEM".equalsIgnoreCase(intent.entityType())) {
                items.add(new TurnDirectorAi.ItemDraft(intent.existingEntityId(), intent.localRef(), intent.name(),
                        intent.status(), intent.summary(), modelAttributes(intent.attributes()), intent.visible()));
            } else {
                var draft = modelEntity(intent);
                if ("ABILITY".equalsIgnoreCase(intent.entityType())) abilities.add(draft);
                else entities.add(draft);
            }
        }
        return new TurnDirectorAi.DecisionDraft(duration == null ? null : new TurnDirectorAi.DurationDraft(TurnDirectorAi.DurationUnit.valueOf(duration.unit().name()),
                        duration.value(), duration.targetPhase()),
                decision.timeResolution() == null ? null : TurnDirectorAi.TimeResolution.valueOf(decision.timeResolution().name()),
                decision.interruptionReason(), core == null ? null : new TurnDirectorAi.CoreStateDraft(core.location(), core.condition(), core.realm()),
                cultivation == null ? null : new TurnDirectorAi.CultivationDraft(cultivation.progressGained(), cultivation.breakthroughAttempted(),
                        cultivation.reason(), cultivation.evidenceRefs()), items, abilities, entities,
                decision.worldFacts().stream().map(fact -> new TurnDirectorAi.WorldFactDraft(fact.factId(), fact.statement(), fact.reason())).toList(),
                assets.fromDomain(decision.assetProcess()), decision.actionUnderstanding() == null ? null
                        : new TurnDirectorAi.UnderstandingDraft(decision.actionUnderstanding().purpose(), decision.actionUnderstanding().constraints()),
                decision.orderedResult());
    }

    private TurnDirectorAi.EntityDraft modelEntity(StoryTurnDecision.EntityIntent entity) {
        var custody = entity.custody();
        var lifecycle = entity.lifecycle();
        var relationship = entity.relationship();
        return new TurnDirectorAi.EntityDraft(entity.existingEntityId(), entity.localRef(), entity.entityType(), entity.name(),
                entity.status(), entity.summary(), entity.attributes() == null ? null : entity.attributes().path("scenePresence").asText(null),
                modelAttributes(entity.attributes()), entity.quantity() == null ? null
                        : new TurnDirectorAi.QuantityDraft(entity.quantity().known(), entity.quantity().value()),
                custody == null ? null : new TurnDirectorAi.CustodyDraft(custody.holderType() == null ? null : custody.holderType().name(),
                        custody.holderRef(), custody.localHolderRef()), entity.abilityState() == null ? null : entity.abilityState().name(),
                lifecycle == null ? null : new TurnDirectorAi.LifecycleDraft(lifecycle.state() == null ? null : lifecycle.state().name(), lifecycle.changeReason()),
                relationship == null ? null : new TurnDirectorAi.RelationshipDraft(relationship.label(), relationship.changeReason()), entity.visible());
    }

    private List<TurnDirectorAi.AttributeDraft> modelAttributes(JsonNode attributes) {
        if (attributes == null || !attributes.isObject()) return List.of();
        List<TurnDirectorAi.AttributeDraft> result = new java.util.ArrayList<>();
        attributes.fields().forEachRemaining(entry -> {
            if (!"scenePresence".equals(entry.getKey())) result.add(new TurnDirectorAi.AttributeDraft(entry.getKey(),
                    entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue().toString()));
        });
        return result;
    }

    /** 行动承接只供模型保持任务和审读；不替代原始行动，不作为新增授权或语义正确的证明。 */
    private StoryTurnDecision.ActionUnderstanding requiredUnderstanding(TurnDirectorAi.DecisionDraft draft) {
        var understanding = draft.actionUnderstanding();
        if (understanding == null || text(understanding.purpose()).isBlank()
                || understanding.purpose().length() > 500
                || understanding.constraints() == null || understanding.constraints().size() > 16) {
            throw new IllegalArgumentException("actionUnderstanding 必须简短表达 purpose 与 constraints，步骤结果只放 orderedResult");
        }
        for (var constraint : understanding.constraints()) {
            if (constraint == null || constraint.isBlank() || constraint.length() > 500) {
                throw new IllegalArgumentException("行动限制必须是非空短句，无限制用空数组");
            }
        }
        return new StoryTurnDecision.ActionUnderstanding(understanding.purpose(), understanding.constraints(), List.of());
    }

    private boolean outputDecodingFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelResponseCapture.DecodingFailure
                    || cause instanceof dev.langchain4j.service.output.OutputParsingException) return true;
        }
        return false;
    }

    private ModelResponseCapture.DecodingFailure capturedDecodingFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelResponseCapture.DecodingFailure captured) return captured;
        }
        return null;
    }

    /** 三组变化必须显式返回；分类错误直接拒绝，不能把未填写解释成没有收获。 */
    private void appendChanges(List<StoryTurnDecision.EntityIntent> target,
                               List<TurnDirectorAi.EntityDraft> changes, String expectedType) {
        if (changes == null) throw new IllegalArgumentException("导演必须明确返回 itemChanges、abilityChanges 和 entities，无变化用空数组");
        for (var value : changes) {
            if (value == null) throw new IllegalArgumentException("实体变化不能包含空项");
            String type = text(value.entityType()).toUpperCase(java.util.Locale.ROOT);
            boolean asset = "ITEM".equals(type) || "ABILITY".equals(type);
            if (expectedType == null ? asset : !expectedType.equals(type)) {
                throw new IllegalArgumentException("物品、功法与其他实体必须填写在各自变化分组");
            }
            var intent = entity(value);
            if ("ABILITY".equals(expectedType) && intent.abilityState() == null) {
                throw new IllegalArgumentException("功法变化必须明确学习状态");
            }
            target.add(intent);
        }
    }

    private StoryTurnDecision.EntityIntent entity(TurnDirectorAi.EntityDraft value) {
        com.fasterxml.jackson.databind.node.ObjectNode attributes = null;
        if (value.attributes() != null && !value.attributes().isEmpty()) {
            attributes = mapper.createObjectNode();
            for (TurnDirectorAi.AttributeDraft attribute : value.attributes()) {
                if (attribute != null && !text(attribute.key()).isBlank()) {
                    attributes.put(attribute.key().strip(), text(attribute.value()));
                }
            }
            if (attributes.isEmpty()) attributes = null;
        }
        String scenePresence = optionalEntityScenePresence(value.scenePresence());
        if (!scenePresence.isBlank()) {
            if (attributes == null) attributes = mapper.createObjectNode();
            attributes.put("scenePresence", scenePresence);
        }
        StoryPatch.QuantityValue quantity = value.quantity() == null ? null
                : new StoryPatch.QuantityValue(value.quantity().known(), value.quantity().value());
        TurnDirectorAi.CustodyDraft custodyDraft = value.custody();
        StoryPatch.HolderType holderType = custodyDraft == null ? null
                : optionalEnum(StoryPatch.HolderType.class, custodyDraft.holderType());
        StoryTurnDecision.CustodyIntent custody = holderType == null ? null
                : new StoryTurnDecision.CustodyIntent(
                holderType, custodyDraft.holderRef(), custodyDraft.localHolderRef());
        TurnDirectorAi.LifecycleDraft lifecycleDraft = value.lifecycle();
        StoryPatch.LifecycleState lifecycleState = lifecycleDraft == null ? null
                : optionalEnum(StoryPatch.LifecycleState.class, lifecycleDraft.state());
        StoryPatch.LifecycleValue lifecycle = lifecycleState == null ? null
                : new StoryPatch.LifecycleValue(
                lifecycleState, lifecycleDraft.changeReason());
        TurnDirectorAi.RelationshipDraft relationshipDraft = value.relationship();
        StoryPatch.RelationshipValue relationship = relationshipDraft == null ? null
                : new StoryPatch.RelationshipValue(relationshipDraft.label(), relationshipDraft.changeReason());
        StoryPatch.AbilityState ability = optionalEnum(StoryPatch.AbilityState.class, value.abilityState());
        return new StoryTurnDecision.EntityIntent(value.existingEntityId(), value.localRef(), value.entityType(),
                value.name(), value.status(), value.summary(), attributes, quantity, custody, ability, lifecycle,
                relationship, value.visible());
    }

    /** 在场证据是人物地点归一化的类型化协议，不允许供应商返回任意新枚举。 */
    private String optionalEntityScenePresence(String value) {
        String normalized = text(value).toUpperCase(java.util.Locale.ROOT);
        if (normalized.isBlank() || "PRESENT".equals(normalized) || "MENTIONED".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("不支持的人物在场状态：" + value);
    }

    /** 空串表示模型没有提出该可选事实；非空未知值仍立即拒绝，不能静默改写为另一领域状态。 */
    private <E extends Enum<E>> E optionalEnum(Class<E> type, String value) {
        String normalized = text(value);
        if (normalized.isBlank()) return null;
        try {
            return Enum.valueOf(type, normalized.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("不支持的" + type.getSimpleName() + "：" + value, error);
        }
    }

    private <T> T required(T value, String label) {
        if (value == null) throw new IllegalArgumentException(label + "不能为空");
        return value;
    }

    private String requiredText(String value, String message) {
        String normalized = text(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(message);
        return normalized;
    }

    private String optional(String value) {
        String normalized = text(value);
        return normalized.isBlank() ? null : normalized;
    }

    private String text(String value) {
        return value == null ? "" : value.strip();
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values.stream()
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private ModelRoute resolve(ModelTask task, InvocationContext invocation, ServicePlanSelection servicePlan) {
        return router.route(request(task, invocation, servicePlan));
    }

    private <S, T> ModelInvocationService.Invocation<T> invoke(
            ModelTask task,
            InvocationContext invocation,
            ModelRoute route,
            ServicePlanSelection servicePlan,
            java.util.function.Function<com.wenxian.game.model.infrastructure.ChatModelRegistry.RegisteredChatModel, S>
                    serviceResolver,
            java.util.function.Function<S, dev.langchain4j.service.Result<T>> call) {
        return invocations.invokeResolved(request(task, invocation, servicePlan), route,
                serviceResolver, call);
    }

    private ModelRouteRequest request(ModelTask task, InvocationContext invocation,
                                      ServicePlanSelection servicePlan) {
        return new ModelRouteRequest(task, invocation.requestId(), invocation.userId(), false, servicePlan);
    }

    private void requireSameCredentialSource(ModelRoute director, ModelRoute writer) {
        CredentialSource source = director.primary().credential().source();
        if (writer.primary().credential().source() != source) {
            throw new IllegalStateException("同一回合的导演与正文任务不能混用平台和玩家密钥");
        }
    }

    private List<ModelCall> modelCalls(ModelInvocationService.Invocation<?> invocation) {
        return modelCalls(invocation.attempts());
    }

    private List<ModelCall> modelCalls(List<ModelInvocationService.Attempt> attempts) {
        return attempts.stream().map(attempt -> new ModelCall(
                attempt.providerCode(), attempt.modelCode(), attempt.credentialSource(), attempt.fallback(),
                attempt.status(), attempt.latencyMs(), attempt.inputTokens(), attempt.outputTokens(),
                attempt.cacheHitTokens(), attempt.cacheMissTokens(), attempt.errorCategory())).toList();
    }

    private String json(Object value, String message) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(message, error);
        }
    }
}
