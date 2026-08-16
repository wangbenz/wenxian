package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextRequest;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextSelection;
import com.wenxian.game.story.agent.PromptContextAssembler.KnowledgeRef;
import com.wenxian.game.story.agent.StoryModelGateway;
import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryPromptContext.PendingDecision;
import com.wenxian.game.story.agent.StoryTurnAgent;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryActionPreconditionException;
import com.wenxian.game.story.domain.StoryActionContractValidator;
import com.wenxian.game.story.domain.StoryEntityReferenceCompiler;
import com.wenxian.game.story.domain.StoryActionContractPatchValidator;
import com.wenxian.game.story.domain.ExplicitDurationParser;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.SemanticDurationParser;
import com.wenxian.game.story.domain.TrustedStoryEvent;
import com.wenxian.game.story.domain.TrustedStoryEventCompiler;
import com.wenxian.game.story.domain.StoryNarrativeFreshnessValidator;
import com.wenxian.game.story.domain.StoryNarrativeFreshnessException;
import com.wenxian.game.story.domain.StoryOutcomeConsistencyValidator;
import com.wenxian.game.story.domain.StoryActionOutcomeCompiler;
import com.wenxian.game.story.diagnostic.StoryTurnDiagnosticService;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredTurn;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import com.wenxian.game.story.reducer.StoryStateReducer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在数据库事务外准备一次可信普通回合。
 *
 * <p>本服务读取权威投影并装配分层上下文，然后调用模型和 Reducer；它不写数据库、不扣回合。全部实体
 * 以轻量索引进入上下文，最近变化的可见实体才携带详情，避免按名字猜测旧实体，也避免重复发送所有描述。
 * 最终提交仍由独立事务服务核对旧 revision。</p>
 */
@Service
public class StoryTurnPreparationService {
    private static final int RECENT_MEMORY_LIMIT = 20;
    private static final int RELATED_ENTITY_DETAIL_LIMIT = 12;

    private final StoryEntityRepository entities;
    private final StoryMemoryRepository memories;
    private final StoryConversationRepository conversations;
    private final StoryTurnRepository turns;
    private final StoryProjectionPersistenceMapper projections;
    private final PromptContextAssembler contexts;
    private final StoryTurnAgent agent;
    private final StoryStateReducer reducer;
    private final StoryTurnDiagnosticService diagnostics;
    private final StoryActionContractValidator actionContracts = new StoryActionContractValidator();
    private final StoryEntityReferenceCompiler referenceCompiler = new StoryEntityReferenceCompiler();
    private final StoryActionContractPatchValidator contractPatches = new StoryActionContractPatchValidator();
    private final StoryActionOutcomeCompiler actionOutcomeCompiler = new StoryActionOutcomeCompiler();
    private final StoryOutcomeConsistencyValidator outcomeConsistency = new StoryOutcomeConsistencyValidator();
    private final StoryNarrativeFreshnessValidator narrativeFreshness = new StoryNarrativeFreshnessValidator();
    private final TrustedStoryEventCompiler eventCompiler = new TrustedStoryEventCompiler();

    public StoryTurnPreparationService(
            StoryEntityRepository entities,
            StoryMemoryRepository memories,
            StoryConversationRepository conversations,
            StoryTurnRepository turns,
            StoryProjectionPersistenceMapper projections,
            PromptContextAssembler contexts,
            StoryTurnAgent agent,
            StoryStateReducer reducer,
            StoryTurnDiagnosticService diagnostics) {
        this.entities = entities;
        this.memories = memories;
        this.conversations = conversations;
        this.turns = turns;
        this.projections = projections;
        this.contexts = contexts;
        this.agent = agent;
        this.reducer = reducer;
        this.diagnostics = diagnostics;
    }

    /** 生成并归并一份尚未提交的回合结果。 */
    public Prepared prepare(StoredSave saved, String requestId, String playerAction, StoryModelRoute route) {
        return prepare(saved, requestId, playerAction, null, route);
    }

    /**
     * 生成并归并一份尚未提交的回合结果；choiceId 非空时必须属于当前命卷快照。
     */
    public Prepared prepare(StoredSave saved, String requestId, String playerAction, String choiceId,
                            StoryModelRoute route) {
        if (saved == null) throw new IllegalArgumentException("回合准备缺少命卷");
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("回合准备缺少请求号");
        if (playerAction == null || playerAction.isBlank()) throw new IllegalArgumentException("玩家行动不能为空");
        StoryTurnDiagnosticService.Session diagnostic = diagnostics == null ? null
                : diagnostics.begin(saved.id(), requestId.strip(), saved.turnNumber() + 1, saved.revision());
        try {
            return prepareObserved(saved, playerAction, choiceId, route, diagnostic);
        } catch (RuntimeException error) {
            if (diagnostic != null) diagnostic.failed("PREPARATION_FAILED", error);
            throw error;
        }
    }

    private Prepared prepareObserved(StoredSave saved, String playerAction, String choiceId, StoryModelRoute route,
                                     StoryTurnDiagnosticService.Session diagnostic) {
        List<Entity> existingEntities = entities.listAll(saved.id());
        var entityAliases = entities.listActiveAliases(saved.id());
        StoryProjection before = projections.load(saved, existingEntities,
                memories.recentActive(saved.id(), RECENT_MEMORY_LIMIT));
        var checkpoint = projections.loadDecisionCheckpoint(saved.state());
        StoryPromptContext.PreviousTurn previous = previousTurn(saved.id());
        StoryPromptContext.SelectedChoice selectedChoice = selectedChoice(saved.state(), choiceId);
        SemanticDuration semanticDuration = SemanticDurationParser.parse(playerAction).orElse(null);
        Long explicitDurationMinutes = semanticDuration == null
                ? explicitDuration(playerAction, before.worldTimeMinutes())
                : Long.valueOf(semanticDuration.toCompatibilityMinutes(before.worldTimeMinutes()));
        StoryPromptContext context = contexts.assemble(before, new ContextRequest(playerAction,
                worldSeedRules(saved.state()), selection(before, existingEntities), conversations.summary(saved.id()),
                previous, checkpoint == null ? null : new PendingDecision(checkpoint.prompt()),
                explicitDurationMinutes, selectedChoice, semanticDuration));
        if (diagnostic != null) diagnostic.context(context);
        StoryTurnAgent.InvocationContext invocation = new StoryTurnAgent.InvocationContext(
                saved.id(), saved.turnNumber() + 1);
        StoryTurnAgent.ResolvedAction resolvedAction = agent.resolveAction(context, route, invocation);
        Set<String> availableSourceRefs = context.objective().sourceExcerpts().stream()
                .map(StoryPromptContext.SourceExcerpt::ref)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        com.wenxian.game.story.domain.StoryActionContract actionContract;
        try {
            actionContract = referenceCompiler.compile(before, resolvedAction.contract(), entityAliases,
                    explicitDurationMinutes);
            if (diagnostic != null) diagnostic.actionResolved(actionContract);
            actionContracts.validate(before, actionContract, availableSourceRefs);
        } catch (StoryActionPreconditionException conflict) {
            // 已确认事实冲突是业务结论，不能交给模型“修复”后再次尝试。
            throw conflict;
        } catch (IllegalArgumentException structuralProblem) {
            StoryTurnAgent.ResolvedAction repaired = agent.repairActionContract(
                    context, route, invocation, resolvedAction.contract(), structuralProblem.getMessage());
            resolvedAction = combine(resolvedAction, repaired);
            try {
                actionContract = referenceCompiler.compile(before, repaired.contract(), entityAliases,
                        explicitDurationMinutes);
                if (diagnostic != null) diagnostic.actionResolved(actionContract);
                actionContracts.validate(before, actionContract, availableSourceRefs);
            } catch (IllegalArgumentException failedAgain) {
                failedAgain.addSuppressed(structuralProblem);
                throw new StoryTurnProtocolException("ACTION_CONTRACT",
                        "行动理解结果在自动整理后仍不符合可信回合协议", failedAgain);
            }
        }
        context = context.withActionContract(actionContract);
        if (agent.supportsOutcomeFirst()) {
            return prepareOutcomeFirst(before, existingEntities, previous, semanticDuration,
                    explicitDurationMinutes, actionContract, resolvedAction, context, route, invocation, diagnostic);
        }
        StoryTurnAgent.Generated generation = agent.generate(context, route, invocation);
        generation = prependModelResults(resolvedAction.modelResults(), generation);
        if (diagnostic != null) diagnostic.proposal("INITIAL_NARRATIVE_DRAFT", generation.patch());
        generation = projectFacts(context, route, invocation, generation);
        generation = compileTrustedEvents(before, semanticDuration, generation);
        if (diagnostic != null) diagnostic.proposal("INITIAL_FACT_PROJECTION", generation.patch());
        StoryStateReducer.Applied applied;
        try {
            rejectRepeatedNarrative(previous, generation.patch());
            StoryTurnAgent.Review review = agent.review(context, route, invocation, generation.patch());
            if (diagnostic != null) diagnostic.review("INITIAL_REVIEW", review);
            generation = appendReview(generation, review);
            rejectReview(review);
            contractPatches.validate(before, actionContract, generation.patch());
            applied = reducer.reduce(before, generation.patch(), explicitDurationMinutes);
        } catch (IllegalArgumentException rejected) {
            StoryTurnAgent.Generated repairedDraft = rejected instanceof StoryNarrativeFreshnessException
                    || rejected instanceof ReviewRejectedException review && review.requiresFreshNarrative
                    ? agent.regenerateFreshNarrative(context, route, invocation,
                            generation.patch(), rejected.getMessage())
                    : agent.repairRejected(context, route, invocation,
                            generation.patch(), rejected.getMessage());
            if (diagnostic != null) {
                diagnostic.rejected("INITIAL_VALIDATION", rejected,
                        rejected instanceof StoryNarrativeFreshnessException
                                || rejected instanceof ReviewRejectedException review && review.requiresFreshNarrative);
                diagnostic.proposal("REPAIRED_NARRATIVE_DRAFT", repairedDraft.patch());
            }
            StoryTurnAgent.Generated repaired = projectFacts(context, route, invocation, repairedDraft);
            repaired = compileTrustedEvents(before, semanticDuration, repaired);
            if (diagnostic != null) diagnostic.proposal("REPAIRED_FACT_PROJECTION", repaired.patch());
            generation = combine(generation, repaired);
            try {
                rejectRepeatedNarrative(previous, repaired.patch());
                StoryTurnAgent.Review review = agent.review(context, route, invocation, repaired.patch());
                if (diagnostic != null) diagnostic.review("REPAIRED_REVIEW", review);
                generation = appendReview(generation, review);
                rejectReview(review);
                contractPatches.validate(before, actionContract, repaired.patch());
                applied = reducer.reduce(before, repaired.patch(), explicitDurationMinutes);
            } catch (IllegalArgumentException failedAgain) {
                failedAgain.addSuppressed(rejected);
                throw new StoryTurnProtocolException("TURN_PROPOSAL",
                        "剧情与事实变化在自动整理后仍未形成一致结果", failedAgain);
            }
        }
        if (diagnostic != null) diagnostic.reduced(applied);
        List<TrustedStoryEvent> trustedEvents = eventCompiler
                .compile(before, applied.acceptedPatch(), semanticDuration).events();
        return new Prepared(before, applied, existingEntities, generation, trustedEvents, diagnostic);
    }

    /**
     * 结果优先协议：先预校验最小事实计划，再演绎正文，最后独立检查正文覆盖；正文永远不能反向取得事实写权限。
     */
    private Prepared prepareOutcomeFirst(
            StoryProjection before,
            List<Entity> existingEntities,
            StoryPromptContext.PreviousTurn previous,
            SemanticDuration semanticDuration,
            Long explicitDurationMinutes,
            com.wenxian.game.story.domain.StoryActionContract actionContract,
            StoryTurnAgent.ResolvedAction resolvedAction,
            StoryPromptContext context,
            StoryModelRoute route,
            StoryTurnAgent.InvocationContext invocation,
            StoryTurnDiagnosticService.Session diagnostic) {
        StoryTurnAgent.PlannedOutcome planned = agent.planOutcome(context, route, invocation);
        List<StoryModelGateway.Result> attempts = new ArrayList<>(resolvedAction.modelResults());
        attempts.addAll(planned.modelResults());
        StoryPatch plan = normalizedPlan(before, semanticDuration, actionContract, planned.patch());
        if (diagnostic != null) diagnostic.proposal("OUTCOME_PLAN", plan);

        try {
            preflightOutcome(before, actionContract, plan, explicitDurationMinutes);
        } catch (IllegalArgumentException rejected) {
            if (diagnostic != null) diagnostic.rejected("OUTCOME_PLAN_VALIDATION", rejected, false);
            StoryTurnAgent.PlannedOutcome repaired = agent.repairOutcomePlan(
                    context, route, invocation, plan, rejected.getMessage());
            attempts.addAll(repaired.modelResults());
            plan = normalizedPlan(before, semanticDuration, actionContract, repaired.patch());
            if (diagnostic != null) diagnostic.proposal("REPAIRED_OUTCOME_PLAN", plan);
            try {
                preflightOutcome(before, actionContract, plan, explicitDurationMinutes);
            } catch (IllegalArgumentException failedAgain) {
                failedAgain.addSuppressed(rejected);
                throw new StoryTurnProtocolException("OUTCOME_PLAN",
                        "本轮结果计划在自动整理后仍未通过可信事实边界", failedAgain);
            }
        }

        StoryTurnAgent.RenderedNarrative rendered = agent.renderNarrative(context, route, invocation, plan);
        attempts.addAll(rendered.modelResults());
        StoryPatch candidate = composeOutcome(plan, rendered);
        if (diagnostic != null) diagnostic.proposal("OUTCOME_NARRATIVE", candidate);
        IllegalArgumentException narrativeFreshnessProblem = null;
        try {
            rejectRepeatedNarrative(previous, candidate);
        } catch (IllegalArgumentException repeated) {
            narrativeFreshnessProblem = repeated;
        }

        StoryTurnAgent.Review review = narrativeFreshnessProblem == null
                ? agent.reviewOutcomeCoverage(context, route, invocation, plan, rendered)
                : null;
        if (review != null) {
            if (diagnostic != null) diagnostic.review("OUTCOME_COVERAGE_REVIEW", review);
            attempts.addAll(review.modelResults());
        }

        if (narrativeFreshnessProblem != null || review != null && !review.accepted()) {
            String problem = narrativeFreshnessProblem != null
                    ? narrativeFreshnessProblem.getMessage() : review.problem();
            boolean repairPlan = narrativeFreshnessProblem == null
                    && review.outcome() == StoryTurnAgent.ReviewOutcome.REPAIR_FACTS;
            if (diagnostic != null) {
                diagnostic.rejected(repairPlan ? "OUTCOME_COVERAGE_PLAN_GAP" : "OUTCOME_NARRATIVE_VALIDATION",
                        new IllegalArgumentException(problem), !repairPlan);
            }
            if (repairPlan) {
                StoryTurnAgent.PlannedOutcome repairedPlan = agent.repairOutcomePlan(
                        context, route, invocation, plan, problem);
                attempts.addAll(repairedPlan.modelResults());
                plan = normalizedPlan(before, semanticDuration, actionContract, repairedPlan.patch());
                if (diagnostic != null) diagnostic.proposal("COVERAGE_REPAIRED_OUTCOME_PLAN", plan);
                try {
                    preflightOutcome(before, actionContract, plan, explicitDurationMinutes);
                } catch (IllegalArgumentException failedPlan) {
                    throw new StoryTurnProtocolException("OUTCOME_PLAN_COVERAGE_REPAIR",
                            "结果覆盖审阅发现计划缺项，但自动整理后仍未通过可信事实边界", failedPlan);
                }
                rendered = agent.renderNarrative(context, route, invocation, plan);
                attempts.addAll(rendered.modelResults());
            } else {
                rendered = agent.repairNarrative(context, route, invocation, plan, rendered, problem);
                attempts.addAll(rendered.modelResults());
            }
            candidate = composeOutcome(plan, rendered);
            if (diagnostic != null) diagnostic.proposal(
                    repairPlan ? "PLAN_REPAIRED_OUTCOME_NARRATIVE" : "REPAIRED_OUTCOME_NARRATIVE", candidate);
            try {
                rejectRepeatedNarrative(previous, candidate);
                StoryTurnAgent.Review repairedReview = agent.reviewOutcomeCoverage(
                        context, route, invocation, plan, rendered);
                if (diagnostic != null) diagnostic.review("REPAIRED_OUTCOME_COVERAGE_REVIEW", repairedReview);
                attempts.addAll(repairedReview.modelResults());
                rejectReview(repairedReview);
            } catch (IllegalArgumentException failedAgain) {
                throw new StoryTurnProtocolException("OUTCOME_NARRATIVE",
                        "结果计划或正文在一次自动整理后仍未形成一致回合", failedAgain);
            }
        }

        StoryStateReducer.Applied applied;
        try {
            contractPatches.validate(before, actionContract, candidate);
            applied = reducer.reduce(before, candidate, explicitDurationMinutes);
        } catch (IllegalArgumentException unexpected) {
            // 事实计划已经通过相同边界预校验；此处失败通常意味着非确定性 ID 或协议实现错误，不能改写正文绕过。
            throw new StoryTurnProtocolException("OUTCOME_COMMIT_PREPARATION",
                    "已校验结果在最终归并时出现不一致", unexpected);
        }
        if (diagnostic != null) diagnostic.reduced(applied);
        StoryTurnAgent.Generated generation = new StoryTurnAgent.Generated(candidate, attempts);
        List<TrustedStoryEvent> trustedEvents = eventCompiler
                .compile(before, applied.acceptedPatch(), semanticDuration).events();
        return new Prepared(before, applied, existingEntities, generation, trustedEvents, diagnostic);
    }

    private StoryPatch normalizedPlan(StoryProjection before, SemanticDuration semanticDuration,
                                      com.wenxian.game.story.domain.StoryActionContract actionContract,
                                      StoryPatch plan) {
        StoryPatch normalized = eventCompiler.compile(before, plan, semanticDuration).normalizedPatch();
        return actionOutcomeCompiler.compile(actionContract, normalized);
    }

    private void preflightOutcome(StoryProjection before,
                                  com.wenxian.game.story.domain.StoryActionContract actionContract,
                                  StoryPatch plan, Long explicitDurationMinutes) {
        if (plan.outcome() == null) {
            throw new IllegalArgumentException("结果计划缺少成功、部分成功或失败结论");
        }
        contractPatches.validate(before, actionContract, plan);
        outcomeConsistency.validate(actionContract, plan);
        reducer.reduce(before, plan, explicitDurationMinutes);
    }

    private StoryPatch composeOutcome(StoryPatch plan, StoryTurnAgent.RenderedNarrative rendered) {
        return new StoryPatch(rendered.title(), rendered.narrative(), plan.time(), plan.coreState(),
                plan.entities(), plan.worldFacts(), plan.knowledge(), plan.memories(),
                plan.decisionCheckpoint(), rendered.choices(), rendered.rollingSummary(), plan.outcome());
    }

    /**
     * 完整复用上一回正文表示模型没有处理本轮行动，不能作为一个新回合提交。
     *
     * <p>这里只比较文本新鲜度，不根据修炼、战斗等业务词汇判断剧情结果。首次命中后仍允许模型按本轮
     * 行动做一次精简语义修复；修复结果再次重复时整轮失败，不推进回合或时间。</p>
     */
    private void rejectRepeatedNarrative(StoryPromptContext.PreviousTurn previous, com.wenxian.game.story.domain.StoryPatch patch) {
        if (previous == null || patch == null) return;
        narrativeFreshness.validate(previous.narrative(), patch.narrative());
    }

    private Long explicitDuration(String playerAction, long worldTimeMinutes) {
        Integer minutes = ExplicitDurationParser.parseMinutes(playerAction, worldTimeMinutes);
        return minutes == null ? null : minutes.longValue();
    }

    private StoryPromptContext.SelectedChoice selectedChoice(JsonNode state, String choiceId) {
        if (choiceId == null || choiceId.isBlank()) return null;
        JsonNode choices = state == null ? null : state.path("choices");
        if (choices != null && choices.isArray()) {
            for (JsonNode choice : choices) {
                if (choiceId.strip().equals(choice.path("id").asText(""))) {
                    String title = choice.path("title").asText("").strip();
                    String description = choice.path("description").asText("").strip();
                    if (title.isBlank()) throw new IllegalStateException("当前系统选项缺少行动标题");
                    return new StoryPromptContext.SelectedChoice(choiceId.strip(), title, description);
                }
            }
        }
        throw new com.wenxian.game.story.domain.StoryActionPreconditionException(
                "这个选项已经不属于当前场景。", "请刷新命卷后选择当前显示的行动，或直接输入新的行动。");
    }

    private StoryTurnAgent.Generated combine(StoryTurnAgent.Generated first, StoryTurnAgent.Generated repaired) {
        List<StoryModelGateway.Result> attempts = new ArrayList<>(first.modelResults());
        attempts.addAll(repaired.modelResults());
        return new StoryTurnAgent.Generated(repaired.patch(), attempts);
    }

    private StoryTurnAgent.ResolvedAction combine(StoryTurnAgent.ResolvedAction first,
                                                   StoryTurnAgent.ResolvedAction repaired) {
        List<StoryModelGateway.Result> attempts = new ArrayList<>(first.modelResults());
        attempts.addAll(repaired.modelResults());
        return new StoryTurnAgent.ResolvedAction(repaired.contract(), attempts);
    }

    private StoryTurnAgent.Generated appendReview(StoryTurnAgent.Generated generated, StoryTurnAgent.Review review) {
        if (review == null || review.modelResults().isEmpty()) return generated;
        List<StoryModelGateway.Result> attempts = new ArrayList<>(generated.modelResults());
        attempts.addAll(review.modelResults());
        return new StoryTurnAgent.Generated(generated.patch(), attempts);
    }

    private StoryTurnAgent.Generated projectFacts(StoryPromptContext context, StoryModelRoute route,
                                                   StoryTurnAgent.InvocationContext invocation,
                                                   StoryTurnAgent.Generated narrativeDraft) {
        StoryTurnAgent.ProjectedFacts projected;
        try {
            projected = agent.projectFacts(context, route, invocation, narrativeDraft.patch());
        } catch (StoryTurnProtocolException protocol) {
            throw protocol;
        } catch (IllegalArgumentException structuralProblem) {
            throw new StoryTurnProtocolException("FACT_PROJECTION",
                    "可信事实投影在自动整理后仍不符合回合协议", structuralProblem);
        }
        if (projected.modelResults().isEmpty() && projected.patch() == narrativeDraft.patch()) {
            return narrativeDraft;
        }
        List<StoryModelGateway.Result> attempts = new ArrayList<>(narrativeDraft.modelResults());
        attempts.addAll(projected.modelResults());
        return new StoryTurnAgent.Generated(projected.patch(), attempts);
    }

    private StoryTurnAgent.Generated prependModelResults(List<StoryModelGateway.Result> earlier,
                                                          StoryTurnAgent.Generated generated) {
        if (earlier == null || earlier.isEmpty()) return generated;
        List<StoryModelGateway.Result> attempts = new ArrayList<>(earlier);
        attempts.addAll(generated.modelResults());
        return new StoryTurnAgent.Generated(generated.patch(), attempts);
    }

    /** 类型化事件编译只清理与实体类型不相容的可选字段，不改变正文、选项或重大事实。 */
    private StoryTurnAgent.Generated compileTrustedEvents(StoryProjection before, SemanticDuration planned,
                                                           StoryTurnAgent.Generated generated) {
        var compiled = eventCompiler.compile(before, generated.patch(), planned);
        if (compiled.normalizedPatch().equals(generated.patch())) return generated;
        return new StoryTurnAgent.Generated(compiled.normalizedPatch(), generated.modelResults());
    }

    private void rejectReview(StoryTurnAgent.Review review) {
        if (review != null && !review.accepted()) {
            throw new ReviewRejectedException("独立回合审阅未通过：" + review.problem(),
                    review.requiresFreshNarrative());
        }
    }

    /** 审阅类别决定恢复方式，避免通过解析中文错误文本选择修复流程。 */
    private static final class ReviewRejectedException extends IllegalArgumentException {
        private final boolean requiresFreshNarrative;

        private ReviewRejectedException(String message, boolean requiresFreshNarrative) {
            super(message);
            this.requiresFreshNarrative = requiresFreshNarrative;
        }
    }

    private ContextSelection selection(StoryProjection projection, List<Entity> existing) {
        Set<String> details = existing.stream()
                .filter(Entity::visible)
                .sorted(Comparator.comparingInt(Entity::lastChangedTurn).reversed()
                        .thenComparing(Entity::id))
                .limit(RELATED_ENTITY_DETAIL_LIMIT)
                .map(Entity::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new ContextSelection(projection.entities().keySet(), details, projection.worldFacts().keySet(),
                projection.knowledge().values().stream()
                        .map(value -> new KnowledgeRef(value.subjectRef(), value.topicRef())).toList(),
                projection.memories().stream().map(StoryProjection.MemoryState::id)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private StoryPromptContext.PreviousTurn previousTurn(String saveId) {
        return turns.recent(saveId, 1).stream().findFirst().map(this::previousTurn).orElse(null);
    }

    private StoryPromptContext.PreviousTurn previousTurn(StoredTurn turn) {
        JsonNode patch = turn.statePatch();
        return new StoryPromptContext.PreviousTurn(turn.turnNumber(), turn.playerAction(),
                patch == null ? "" : patch.path("title").asText(""), turn.narrative(),
                patch == null ? "" : patch.path("rollingSummary").asText(""));
    }

    private List<String> worldSeedRules(JsonNode state) {
        JsonNode world = state == null ? null : state.path("world");
        if (world == null || world.isMissingNode()) return List.of();
        String name = world.path("name").asText("");
        String premise = world.path("premise").asText("");
        String cultivation = world.path("cultivationStyle").asText("");
        String region = world.path("openingRegion").asText("");
        return List.of(
                "当前世界模板是软起点，玩家可明确扩展或修订，不得把模板空白误当成禁止内容。",
                "世界模板：" + name + "；起点简介：" + premise,
                "修行风格：" + cultivation + "；开场地域：" + region
        );
    }

    /** 模型完成但尚未落库的回合准备结果。 */
    public record Prepared(
            StoryProjection before,
            StoryStateReducer.Applied applied,
            List<Entity> existingEntities,
            StoryTurnAgent.Generated generation,
            List<TrustedStoryEvent> trustedEvents,
            StoryTurnDiagnosticService.Session diagnostic
    ) {
        public Prepared {
            existingEntities = existingEntities == null ? List.of() : List.copyOf(existingEntities);
            trustedEvents = trustedEvents == null ? List.of() : List.copyOf(trustedEvents);
        }
    }
}
