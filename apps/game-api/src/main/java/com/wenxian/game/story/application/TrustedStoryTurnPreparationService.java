package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextRequest;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextSelection;
import com.wenxian.game.story.agent.PromptContextAssembler.KnowledgeRef;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryPromptContext.PendingDecision;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.InvocationContext;
import com.wenxian.game.story.agent.ai.StoryCandidateGenerator;
import com.wenxian.game.story.agent.ai.StoryCandidateGenerator.Session;
import com.wenxian.game.story.domain.StoryRecords;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.persistence.StoryRecordHistoryReader;
import com.wenxian.game.story.domain.SemanticDurationParser;
import com.wenxian.game.story.domain.StoryActionPreconditionException;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
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
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 类型化完整回合的唯一事务外准备服务。
 *
 * <p>读取当前状态和不可变历史，模型同次生成完整正文、变化与持续记录；确定性规则预演后交给原回合事务。
 * 不调用第二个正文模型、不重写旧历史、不从正文补造状态。必要合同错误只完整修复一次，网络和程序异常直接返回。</p>
 */
@Service
public class TrustedStoryTurnPreparationService {
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
    private final WorldTurnContextService worldContexts;
    private final StoryTurnDiagnosticService diagnostics;
    private final StoryCandidateGenerator narratives;
    private final StoryCandidatePreparation candidates = new StoryCandidatePreparation();

    public TrustedStoryTurnPreparationService(
            StoryEntityRepository entities,
            StoryMemoryRepository memories,
            StoryConversationRepository conversations,
            StoryTurnRepository turns,
            StoryProjectionPersistenceMapper projections,
            PromptContextAssembler contexts,
            WorldTurnContextService worldContexts,
            StoryTurnDiagnosticService diagnostics,
            StoryCandidateGenerator narratives) {
        this.entities = entities;
        this.memories = memories;
        this.conversations = conversations;
        this.turns = turns;
        this.projections = projections;
        this.contexts = contexts;
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
                : diagnostics.begin(saved.id(), requestId.strip(), saved.turnNumber() + 1, saved.revision(), StoryRecords.PROMPT_VERSION);
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
        List<StoryRecords.Envelope> recordHistory = recentTurns.stream()
                .map(StoryPromptContext.PreviousTurn::storyRecords).filter(java.util.Objects::nonNull).toList();
        return candidates.prepare(before, context, existingEntities, session, recordHistory, diagnostic);
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
        var history = StoryRecordHistoryReader.read(turn);
        return new StoryPromptContext.PreviousTurn(turn.turnNumber(), turn.playerAction(),
                patch == null ? "" : patch.path("title").asText(""), turn.narrative(), outcome,
                history.acceptedResult(), history.storyRecords());
    }

    private String preview(String value, int maximum) {
        if (value == null || value.isBlank()) return "";
        String cleaned = value.strip();
        return cleaned.length() <= maximum ? cleaned : cleaned.substring(0, maximum) + "…";
    }
}
