package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextRequest;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextSelection;
import com.wenxian.game.story.agent.PromptContextAssembler.KnowledgeRef;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryPromptContext.PendingDecision;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator;
import com.wenxian.game.story.agent.ai.DirectorProtocolException;
import com.wenxian.game.story.agent.ai.NarrativeEndState;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.DirectedPlan;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.InvocationContext;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.ModelCall;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.RenderedNarrative;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.Session;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.AssetProcess;
import com.wenxian.game.story.domain.OrderedTurnResult;
import com.wenxian.game.story.persistence.StoredTurnResultReader;
import com.wenxian.game.story.domain.SemanticDurationParser;
import com.wenxian.game.story.domain.StoryActionPreconditionException;
import com.wenxian.game.story.domain.StoryFactIntentCompiler;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StorySemanticTimeResolver;
import com.wenxian.game.story.domain.TrustedStoryEvent;
import com.wenxian.game.story.domain.TrustedStoryEventCompiler;
import com.wenxian.game.story.diagnostic.StoryTurnDiagnosticService;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.EntityAlias;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredTurn;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import com.wenxian.game.story.reducer.StoryStateReducer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 已退出正式运行的旧两阶段准备器，仅用于保留历史回放和对照测试。
 *
 * <p>固定流程为：读取权威投影、装配分层上下文、轻量导演生成事实意图、Java 编译并用 Reducer
 * 归并一次、正文模型演绎结束事实、仅合并表现字段。正常路径不再进入旧行动契约、结果审阅或正文反推事实链路。</p>
 */
public class LegacyDirectedTurnPreparationService {
    private static final int RECENT_MEMORY_LIMIT = 20;
    private static final int RECENT_MEMORY_CONTEXT_LIMIT = 6;
    private static final int RECENT_TURN_CONTEXT_LIMIT = 6;
    private static final int RELATED_ENTITY_DETAIL_LIMIT = 4;

    private final StoryEntityRepository entities;
    private final StoryMemoryRepository memories;
    private final StoryConversationRepository conversations;
    private final StoryTurnRepository turns;
    private final StoryProjectionPersistenceMapper projections;
    private final PromptContextAssembler contexts;
    private final StoryStateReducer reducer;
    private final WorldTurnContextService worldContexts;
    private final StoryTurnDiagnosticService diagnostics;
    private final DirectedNarrativeGenerator narratives;
    private final StoryFactIntentCompiler factIntents = new StoryFactIntentCompiler();
    private final StorySemanticTimeResolver semanticTimes = new StorySemanticTimeResolver();
    private final TrustedStoryEventCompiler eventCompiler = new TrustedStoryEventCompiler();

    public LegacyDirectedTurnPreparationService(
            StoryEntityRepository entities,
            StoryMemoryRepository memories,
            StoryConversationRepository conversations,
            StoryTurnRepository turns,
            StoryProjectionPersistenceMapper projections,
            PromptContextAssembler contexts,
            StoryStateReducer reducer,
            WorldTurnContextService worldContexts,
            StoryTurnDiagnosticService diagnostics,
            DirectedNarrativeGenerator narratives) {
        this.entities = entities;
        this.memories = memories;
        this.conversations = conversations;
        this.turns = turns;
        this.projections = projections;
        this.contexts = contexts;
        this.reducer = reducer;
        this.worldContexts = worldContexts;
        this.diagnostics = diagnostics;
        this.narratives = narratives;
    }

    /** 冻结本轮模型任务路由；仅解析配置与凭据，不调用供应商、不写数据库、不扣额度。 */
    public Session openSession(StoredSave saved, String requestId) {
        if (saved == null) throw new IllegalArgumentException("模型会话缺少命卷");
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("模型会话缺少请求号");
        return narratives.open(new InvocationContext(saved.userId(), requestId.strip(), saved.id(),
                saved.turnNumber() + 1));
    }

    /** 准备一轮完整回合；该方法不写数据库、不扣额度。 */
    public PreparedStoryTurn prepare(StoredSave saved, String requestId, String playerAction,
                                     String choiceId, Session session) {
        if (saved == null) throw new IllegalArgumentException("回合准备缺少命卷");
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("回合准备缺少请求号");
        if (playerAction == null || playerAction.isBlank()) throw new IllegalArgumentException("玩家行动不能为空");
        StoryTurnDiagnosticService.Session diagnostic = diagnostics == null ? null
                : diagnostics.begin(saved.id(), requestId.strip(), saved.turnNumber() + 1, saved.revision());
        try {
            return prepareObserved(saved, requestId.strip(), playerAction, choiceId, session, diagnostic);
        } catch (RuntimeException error) {
            if (diagnostic != null) diagnostic.failed("PREPARATION_FAILED", error);
            throw error;
        }
    }

    private PreparedStoryTurn prepareObserved(
            StoredSave saved,
            String requestId,
            String playerAction,
            String choiceId,
            Session session,
            StoryTurnDiagnosticService.Session diagnostic) {
        List<Entity> existingEntities = entities.listAll(saved.id());
        List<EntityAlias> aliases = entities.listActiveAliases(saved.id());
        StoryProjection before = projections.load(saved, existingEntities,
                memories.recentActive(saved.id(), RECENT_MEMORY_LIMIT));
        var checkpoint = projections.loadDecisionCheckpoint(saved.state());
        List<StoryPromptContext.PreviousTurn> recentTurns = recentTurns(saved.id());
        StoryPromptContext.PreviousTurn previous = recentTurns.isEmpty() ? null : recentTurns.getLast();
        StoryPromptContext.SelectedChoice selectedChoice = selectedChoice(saved.state(), choiceId);
        String authorizedAction = authoritativeAction(playerAction, selectedChoice);
        SemanticDuration requestedDuration = SemanticDurationParser.parse(authorizedAction).orElse(null);
        StoryPromptContext context = contexts.assemble(before, new ContextRequest(
                authorizedAction,
                List.of(),
                selection(before, existingEntities, aliases, authorizedAction),
                conversations.summary(saved.id()),
                previous,
                checkpoint == null ? null : new PendingDecision(checkpoint.prompt()),
                selectedChoice,
                requestedDuration,
                recentTurns)).withWorldContext(worldContexts.select(before, authorizedAction));
        if (diagnostic != null) diagnostic.context(context);

        if (session == null) throw new IllegalArgumentException("回合准备缺少已冻结的模型会话");
        List<ModelCall> calls = new ArrayList<>();
        AcceptedDirector directed = prepareDirector(before, requestedDuration, context, session, calls, diagnostic);
        StoryPatch plan = directed.plan();
        StoryStateReducer.Applied applied = directed.applied();
        if (diagnostic != null) diagnostic.proposal("DIRECTED_ACCEPTED_PLAN", plan);

        RenderedNarrative rendered = session.render(context, applied.acceptedPatch(),
                NarrativeEndState.from(applied.projection(), applied.acceptedPatch())
                        .withProcess(directed.process(), directed.understanding())
                        .withAcceptedResult(directed.acceptedResult()));
        calls.addAll(rendered.modelCalls());
        // RenderedNarrative 已校验表现层必填项；不再执行 CREATE 或重新分配实体/记忆 ID。
        StoryPatch candidate = compose(applied.acceptedPatch(), rendered);
        applied = new StoryStateReducer.Applied(applied.projection(), candidate, applied.aliasChanges());
        if (diagnostic != null) {
            diagnostic.proposal("DIRECTED_NARRATIVE", candidate);
            diagnostic.reduced(applied);
        }
        StoryPatch accepted = applied.acceptedPatch();
        List<TrustedStoryEvent> trustedEvents = new ArrayList<>(eventCompiler
                .compile(before, accepted, requestedDuration).events());
        if (directed.process() != null) {
            trustedEvents.removeIf(event -> event instanceof TrustedStoryEvent.ItemChanged);
            directed.process().steps().forEach(step -> trustedEvents.add(new TrustedStoryEvent.AssetStepResolved(step)));
        }
        return new PreparedStoryTurn(before, applied, existingEntities,
                new PreparedStoryTurn.Generation(accepted, calls), trustedEvents, diagnostic, directed.process(), directed.acceptedResult());
    }

    /**
     * 解码、草案适配和领域预演共享一次修复，不重新装配上下文或启动新的玩家回合。
     *
     * <p>调用边界只接收适配器明确分类的协议异常；权限、网络和程序异常直接交还上层释放预占。
     * 领域编译器及 Reducer 原有的 IllegalArgumentException 校验契约只在其局部调用范围内转换。
     * 修复后无论在哪一层再次拒绝都停止，避免每层各自追加一次供应商调用。</p>
     */
    private AcceptedDirector prepareDirector(StoryProjection before, SemanticDuration requestedDuration,
                                             StoryPromptContext context, Session session, List<ModelCall> calls,
                                             StoryTurnDiagnosticService.Session diagnostic) {
        DirectorProtocolException previous = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                DirectedPlan directed;
                if (previous == null) {
                    directed = session.plan(context);
                } else if (previous.phase() == DirectorProtocolException.Phase.COMPILATION) {
                    directed = session.repair(context, (StoryTurnDecision) previous.rejectedDraft(),
                            previous.getMessage());
                } else {
                    directed = session.repairProtocol(context, previous);
                }
                // 必须先记录供应商用量，再运行可能拒绝的领域编译及预演。
                calls.addAll(directed.modelCalls());
                try {
                    StoryFactIntentCompiler.Compilation compiled = factIntents.compileDetailed(before, directed.decision());
                    StoryPatch timed = semanticTimes.resolve(before, compiled.patch(), requestedDuration);
                    StoryPatch plan = eventCompiler.compile(before, timed, requestedDuration).normalizedPatch();
                    StoryStateReducer.Applied applied = reducer.reduce(before, plan);
                    AssetProcess.Accepted process = AssetProcess.resolveRefs(compiled.process(), applied.acceptedPatch().entities());
                    OrderedTurnResult.Accepted acceptedResult = OrderedTurnResult.resolveRefs(
                            compiled.acceptedResult(), applied.acceptedPatch().entities());
                    return new AcceptedDirector(plan, applied, process, directed.decision().actionUnderstanding(), acceptedResult);
                } catch (IllegalArgumentException rejected) {
                    throw new DirectorProtocolException(DirectorProtocolException.Phase.COMPILATION,
                            directed.decision(), List.of(), rejected.getMessage(), rejected);
                }
            } catch (DirectorProtocolException rejected) {
                calls.addAll(rejected.modelCalls());
                if (diagnostic != null) diagnostic.rejected("DIRECTED_" + rejected.phase(), rejected, false);
                if (previous != null) {
                    DirectorProtocolException exhausted = rejected.withModelCalls(calls);
                    exhausted.addSuppressed(previous);
                    throw new StoryTurnProtocolException("DIRECTED_FACT_REPAIR",
                            "轻量导演结果整理后仍无法编译为可信事实", exhausted);
                }
                previous = rejected;
            }
        }
        throw new IllegalStateException("导演修复预算状态异常");
    }

    private record AcceptedDirector(StoryPatch plan, StoryStateReducer.Applied applied,
                                    AssetProcess.Accepted process,
                                    StoryTurnDecision.ActionUnderstanding understanding,
                                    OrderedTurnResult.Accepted acceptedResult) {}

    private StoryPatch compose(StoryPatch plan, RenderedNarrative rendered) {
        return new StoryPatch(rendered.title(), rendered.narrative(), plan.time(), plan.coreState(),
                plan.entities(), plan.worldFacts(), plan.knowledge(), plan.memories(),
                plan.decisionCheckpoint(), rendered.choices(), rendered.rollingSummary(), plan.outcome(),
                plan.cultivation());
    }

    /** 系统选项以当前命卷快照为准，忽略请求中可伪造的自由文本。 */
    private String authoritativeAction(String playerAction, StoryPromptContext.SelectedChoice selectedChoice) {
        if (selectedChoice == null) return playerAction.strip();
        return selectedChoice.description() == null || selectedChoice.description().isBlank()
                ? selectedChoice.title().strip()
                : selectedChoice.title().strip() + "：" + selectedChoice.description().strip();
    }

    private StoryPromptContext.SelectedChoice selectedChoice(JsonNode state, String choiceId) {
        if (choiceId == null || choiceId.isBlank()) return null;
        JsonNode choices = state == null ? null : state.path("scene").path("choices");
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
        throw new StoryActionPreconditionException(
                "这个选项已经不属于当前场景。", "请刷新命卷后选择当前显示的行动，或直接输入新的行动。");
    }

    private ContextSelection selection(StoryProjection projection, List<Entity> existing,
                                       List<EntityAlias> aliases, String playerAction) {
        Set<String> details = existing.stream()
                .filter(Entity::visible)
                .sorted(Comparator.comparingInt(Entity::lastChangedTurn).reversed().thenComparing(Entity::id))
                .limit(RELATED_ENTITY_DETAIL_LIMIT)
                .map(Entity::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> selected = new LinkedHashSet<>(details);
        String location = projection.coreState() == null ? "" : projection.coreState().location();
        projection.entities().values().stream()
                .filter(value -> alwaysRelevantEntity(value, location))
                .map(StoryProjection.EntityState::id)
                .forEach(id -> {
                    selected.add(id);
                    details.add(id);
                });
        explicitlyReferencedEntityIds(existing, aliases, playerAction).forEach(id -> {
            selected.add(id);
            details.add(id);
        });
        return new ContextSelection(selected, details, projection.worldFacts().keySet(),
                projection.knowledge().values().stream()
                        .filter(value -> "PLAYER".equals(value.subjectRef())
                                || selected.contains(value.subjectRef()) || selected.contains(value.topicRef()))
                        .map(value -> new KnowledgeRef(value.subjectRef(), value.topicRef())).toList(),
                relevantMemoryIds(projection, selected));
    }

    private Set<String> relevantMemoryIds(StoryProjection projection, Set<String> selectedEntityIds) {
        List<StoryProjection.MemoryState> values = projection.memories();
        Set<String> selected = new LinkedHashSet<>();
        int recentStart = Math.max(0, values.size() - RECENT_MEMORY_CONTEXT_LIMIT);
        for (int index = 0; index < values.size(); index++) {
            StoryProjection.MemoryState memory = values.get(index);
            boolean recent = index >= recentStart;
            boolean durable = "KEY".equalsIgnoreCase(memory.importance())
                    || "PROMISE".equalsIgnoreCase(memory.memoryType())
                    || "CLUE".equalsIgnoreCase(memory.memoryType());
            boolean related = memory.evidenceRefs().stream().anyMatch(selectedEntityIds::contains);
            if (recent || durable || related) selected.add(memory.id());
        }
        return Set.copyOf(selected);
    }

    private boolean alwaysRelevantEntity(StoryProjection.EntityState value, String protagonistLocation) {
        if (value == null || !value.visible()) return false;
        if ("ITEM".equalsIgnoreCase(value.entityType())) return Boolean.TRUE.equals(value.inPossession());
        if ("ABILITY".equalsIgnoreCase(value.entityType())) {
            return value.abilityState() != StoryPatch.AbilityState.LOST;
        }
        if (!"CHARACTER".equalsIgnoreCase(value.entityType()) || value.attributes() == null) return false;
        String lastKnownLocation = value.attributes().path("lastKnownLocation").asText("");
        return protagonistLocation != null && !protagonistLocation.isBlank()
                && protagonistLocation.equals(lastKnownLocation);
    }

    /** 名称和长期别名只负责召回稳定实体，不解释玩家行动的修炼或战斗语义。 */
    private Set<String> explicitlyReferencedEntityIds(List<Entity> existing, List<EntityAlias> aliases,
                                                       String playerAction) {
        String action = StoryEntityRepository.normalizeAlias(playerAction);
        if (action.isBlank()) return Set.of();
        Set<String> selected = new LinkedHashSet<>();
        existing.stream().filter(value -> value.name() != null && !value.name().isBlank())
                .filter(value -> {
                    String normalized = StoryEntityRepository.normalizeAlias(value.name());
                    return !normalized.isBlank() && action.contains(normalized);
                })
                .map(Entity::id).forEach(selected::add);
        if (aliases != null) {
            aliases.stream().filter(value -> value.normalizedAlias() != null && !value.normalizedAlias().isBlank())
                    .filter(value -> action.contains(value.normalizedAlias()))
                    .map(EntityAlias::entityId).forEach(selected::add);
        }
        return selected;
    }

    private List<StoryPromptContext.PreviousTurn> recentTurns(String saveId) {
        return turns.recent(saveId, RECENT_TURN_CONTEXT_LIMIT).stream().map(this::previousTurn).toList();
    }

    private StoryPromptContext.PreviousTurn previousTurn(StoredTurn turn) {
        JsonNode patch = turn.statePatch();
        String outcome = patch == null ? "" : patch.path("outcome").path("summary").asText("");
        if (outcome.isBlank() && patch != null) outcome = patch.path("title").asText("");
        if (outcome.isBlank()) outcome = preview(turn.narrative(), 240);
        return new StoryPromptContext.PreviousTurn(turn.turnNumber(), turn.playerAction(),
                patch == null ? "" : patch.path("title").asText(""), turn.narrative(), outcome,
                StoredTurnResultReader.read(patch));
    }

    private String preview(String value, int maximum) {
        if (value == null || value.isBlank()) return "";
        String cleaned = value.strip();
        return cleaned.length() <= maximum ? cleaned : cleaned.substring(0, maximum) + "…";
    }
}
