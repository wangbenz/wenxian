package com.wenxian.game.story.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.wenxian.game.story.agent.StoryPromptContext.ActionContext;
import com.wenxian.game.story.agent.StoryPromptContext.CognitionContext;
import com.wenxian.game.story.agent.StoryPromptContext.EntityDetail;
import com.wenxian.game.story.agent.StoryPromptContext.EntityIndexEntry;
import com.wenxian.game.story.agent.StoryPromptContext.KnowledgeView;
import com.wenxian.game.story.agent.StoryPromptContext.InventoryView;
import com.wenxian.game.story.agent.StoryPromptContext.InitialContractView;
import com.wenxian.game.story.agent.StoryPromptContext.MemoryContext;
import com.wenxian.game.story.agent.StoryPromptContext.MemoryView;
import com.wenxian.game.story.agent.StoryPromptContext.NarrativeContext;
import com.wenxian.game.story.agent.StoryPromptContext.ObjectiveContext;
import com.wenxian.game.story.agent.StoryPromptContext.PreviousTurn;
import com.wenxian.game.story.agent.StoryPromptContext.SystemContext;
import com.wenxian.game.story.agent.StoryPromptContext.SourceExcerpt;
import com.wenxian.game.story.agent.StoryPromptContext.WorldFactView;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryProjection.InitialContract;
import com.wenxian.game.story.domain.StoryProjection.KnowledgeState;
import com.wenxian.game.story.domain.StoryProjection.MemoryState;
import com.wenxian.game.story.domain.StoryProjection.WorldFactState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 根据稳定引用装配故事模型上下文。
 *
 * <p>本类不根据姓名关键词猜测实体，不执行向量检索，也不设置固定 Token 截断。上游相关性选择器
 * 负责从当前场景、近期补丁、玩家明确引用或语义检索中生成 {@link ContextSelection}；本类只验证这些
 * 引用仍属于当前事实投影，并将客观事实、人物认知、记忆和叙事材料严格分区。</p>
 */
public final class PromptContextAssembler {
    private static final List<String> TRUST_INVARIANTS = List.of(
            "玩家已经经历的已提交事件不能被摘要或新正文静默抹除。",
            "只执行玩家本轮授权的行动及其直接后果，并在下一项重大不可逆决定前停止。",
            "客观事实、人物认知、历史叙事和玩家输入属于不同层级，不能相互冒充。",
            "currentInventory 是当前行囊的完整权威清单；历史正文和隐藏实体不能证明仍然持有物品。",
            "已有实体只能通过上下文提供的稳定ID更新；名称相似不能作为覆盖依据。",
            "模型输出只是变化提案，只有服务端归并并提交后的结果才成为事实。"
    );

    /** 装配失败发生在调用模型之前，不消耗玩家回合，也不允许降级到错误实体。 */
    public StoryPromptContext assemble(StoryProjection projection, ContextRequest request) {
        if (projection == null) throw new IllegalArgumentException("当前事实投影不能为空");
        if (request == null) throw new IllegalArgumentException("上下文请求不能为空");
        if (!present(request.playerAction())) throw new IllegalArgumentException("玩家行动不能为空");

        ContextSelection selection = request.selection() == null ? ContextSelection.empty() : request.selection();
        Set<String> indexIds = clean(selection.entityIndexIds());
        Set<String> detailIds = clean(selection.relatedEntityIds());
        indexIds.addAll(detailIds);

        List<EntityIndexEntry> index = indexIds.stream()
                .map(id -> requireEntity(projection, id))
                .sorted(Comparator.comparing(EntityState::id))
                .map(this::toIndex)
                .toList();
        List<EntityDetail> details = detailIds.stream()
                .map(id -> requireEntity(projection, id))
                .sorted(Comparator.comparing(EntityState::id))
                .map(this::toDetail)
                .toList();
        List<EntityIndexEntry> currentInventory = projection.entities().values().stream()
                .filter(this::isCurrentInventoryItem)
                .sorted(Comparator.comparing(EntityState::id))
                .map(this::toIndex)
                .toList();
        List<EntityIndexEntry> historicalInventory = projection.entities().values().stream()
                .filter(value -> value != null && "ITEM".equalsIgnoreCase(value.entityType()))
                .filter(value -> !isCurrentInventoryItem(value))
                .sorted(Comparator.comparing(EntityState::id))
                .map(this::toIndex)
                .toList();
        List<WorldFactView> worldFacts = clean(selection.worldFactIds()).stream()
                .map(id -> requireWorldFact(projection, id))
                .sorted(Comparator.comparing(WorldFactState::id))
                .map(value -> new WorldFactView(value.id(), value.statement(), value.revision()))
                .toList();
        List<KnowledgeView> cognition = safe(selection.knowledgeRefs()).stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(ref -> requireKnowledge(projection, ref))
                .sorted(Comparator.comparing(KnowledgeState::subjectRef)
                        .thenComparing(KnowledgeState::topicRef))
                .map(value -> new KnowledgeView(value.subjectRef(), value.topicRef(), value.summary(),
                        value.evidenceRefs()))
                .toList();
        List<MemoryView> memories = clean(selection.memoryIds()).stream()
                .map(id -> requireMemory(projection, id))
                .sorted(Comparator.comparing(MemoryState::id))
                .map(value -> new MemoryView(value.id(), value.memoryType(), value.title(), value.summary(),
                        value.importance(), value.evidenceRefs()))
                .toList();

        return new StoryPromptContext(
                new SystemContext(TRUST_INVARIANTS, cleanRules(request.engineRules())),
                new ObjectiveContext(projection.worldTimeMinutes(), StoryPromptContext.WorldClock.from(projection.worldTimeMinutes()),
                        projection.coreState(), worldFacts, index, details,
                        new InventoryView(true, currentInventory, historicalInventory), request.pendingDecision(),
                        initialContract(projection.initialContract()), sourceExcerpts(projection, request)),
                new CognitionContext(cognition),
                new MemoryContext(memories),
                new NarrativeContext(blank(request.rollingSummary()), request.previousTurn()),
                new ActionContext(request.playerAction().strip(), request.explicitDurationMinutes(),
                        expectedEndClock(projection.worldTimeMinutes(), request.explicitDurationMinutes()), null,
                        request.selectedChoice())
        );
    }

    private InitialContractView initialContract(InitialContract value) {
        InitialContract source = value == null ? InitialContract.empty() : value;
        return new InitialContractView(source.protagonistName(), source.race(), source.gender(), source.origin(),
                source.startingRealm(), source.storyPreference(), source.worldCode(), source.worldName(), source.worldPremise(),
                source.cultivationStyle(), source.openingRegion(), source.openingNarrative());
    }

    private List<SourceExcerpt> sourceExcerpts(StoryProjection projection, ContextRequest request) {
        List<SourceExcerpt> sources = new ArrayList<>();
        InitialContract initial = projection.initialContract();
        if (initial != null && present(initial.origin())) {
            sources.add(new SourceExcerpt("initial:origin", "INITIAL_ORIGIN", "此身来处", initial.origin()));
        }
        if (initial != null && present(initial.openingNarrative())) {
            sources.add(new SourceExcerpt("initial:opening", "INITIAL_OPENING", "入世开场",
                    initial.openingNarrative()));
        }
        PreviousTurn previous = request.previousTurn();
        if (previous != null && present(previous.narrative())) {
            sources.add(new SourceExcerpt("turn:" + previous.sequence(), "PREVIOUS_TURN",
                    blank(previous.title()), previous.narrative()));
        }
        StoryPromptContext.SelectedChoice choice = request.selectedChoice();
        if (choice != null) {
            sources.add(new SourceExcerpt("choice:" + choice.id(), "SELECTED_CHOICE",
                    blank(choice.title()), blank(choice.description())));
        }
        return List.copyOf(sources);
    }

    private StoryPromptContext.WorldClock expectedEndClock(long currentMinutes, Long elapsedMinutes) {
        if (elapsedMinutes == null || elapsedMinutes < 0) return null;
        long end;
        try {
            end = Math.addExact(currentMinutes, elapsedMinutes);
        } catch (ArithmeticException ignored) {
            end = Long.MAX_VALUE;
        }
        return StoryPromptContext.WorldClock.from(end);
    }

    private EntityState requireEntity(StoryProjection projection, String id) {
        EntityState value = projection.entities().get(id);
        if (value == null) throw new IllegalArgumentException("上下文引用的实体不存在：" + id);
        return value;
    }

    private WorldFactState requireWorldFact(StoryProjection projection, String id) {
        WorldFactState value = projection.worldFacts().get(id);
        if (value == null || !value.active()) {
            throw new IllegalArgumentException("上下文引用的当前世界事实不存在：" + id);
        }
        return value;
    }

    private KnowledgeState requireKnowledge(StoryProjection projection, KnowledgeRef ref) {
        if (!present(ref.subjectRef()) || !present(ref.topicRef())) {
            throw new IllegalArgumentException("人物认知引用必须包含主体与对象");
        }
        return projection.knowledge().values().stream()
                .filter(value -> ref.subjectRef().strip().equals(value.subjectRef())
                        && ref.topicRef().strip().equals(value.topicRef()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "上下文引用的人物认知不存在：" + ref.subjectRef() + "/" + ref.topicRef()));
    }

    private MemoryState requireMemory(StoryProjection projection, String id) {
        return projection.memories().stream()
                .filter(value -> id.equals(value.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("上下文引用的关键记忆不存在：" + id));
    }

    private EntityIndexEntry toIndex(EntityState value) {
        return new EntityIndexEntry(value.id(), value.entityType(), value.name(), objectiveStatus(value), value.visible(),
                value.quantity(), value.inPossession(), value.custody(), value.abilityState(), value.lifecycle(),
                value.relationship());
    }

    private EntityDetail toDetail(EntityState value) {
        JsonNode attributes = value.attributes() == null ? null : value.attributes().deepCopy();
        return new EntityDetail(value.id(), value.entityType(), value.name(), objectiveStatus(value),
                objectiveSummary(value),
                attributes, value.visible(), value.quantity(), value.inPossession(), value.custody(),
                value.abilityState(), value.lifecycle(), value.relationship());
    }

    /**
     * 人物自由文本状态可能包含旧版本重复写入的物品保管描述，不能再作为客观事实发送给模型。
     * 人物客观状态只由结构化生命周期、关系、身份与最后地点组成；物品保管始终从 ITEM.custody 读取。
     */
    private String objectiveStatus(EntityState value) {
        if (!"CHARACTER".equalsIgnoreCase(value.entityType())) return value.status();
        return value.lifecycle() == null ? "UNKNOWN" : value.lifecycle().name();
    }

    private String objectiveSummary(EntityState value) {
        if (!"CHARACTER".equalsIgnoreCase(value.entityType())) return value.summary();
        List<String> facts = new ArrayList<>();
        JsonNode attributes = value.attributes();
        if (attributes != null) {
            addFact(facts, "身份", attributes.path("identity").asText(""));
            addFact(facts, "最后确认地点", attributes.path("lastKnownLocation").asText(""));
        }
        if (value.relationship() != null) addFact(facts, "与主角关系", value.relationship().label());
        return String.join("；", facts);
    }

    private void addFact(List<String> facts, String label, String value) {
        if (present(value)) facts.add(label + "：" + value.strip());
    }

    private boolean isCurrentInventoryItem(EntityState value) {
        if (value == null || !"ITEM".equalsIgnoreCase(value.entityType())) return false;
        if (!value.visible() || !Boolean.TRUE.equals(value.inPossession())) return false;
        return value.quantity() == null || !value.quantity().known() || value.quantity().value() == null
                || value.quantity().value() > 0;
    }

    private Set<String> clean(Set<String> values) {
        Set<String> cleaned = new LinkedHashSet<>();
        if (values == null) return cleaned;
        values.stream().filter(this::present).map(String::strip).forEach(cleaned::add);
        return cleaned;
    }

    private List<String> cleanRules(List<String> values) {
        List<String> cleaned = new ArrayList<>();
        for (String value : safe(values)) {
            if (present(value)) cleaned.add(value.strip());
        }
        return List.copyOf(cleaned);
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private String blank(String value) {
        return value == null ? "" : value;
    }

    public record ContextRequest(
            String playerAction,
            List<String> engineRules,
            ContextSelection selection,
            String rollingSummary,
            PreviousTurn previousTurn,
            StoryPromptContext.PendingDecision pendingDecision,
            Long explicitDurationMinutes,
            StoryPromptContext.SelectedChoice selectedChoice
    ) {
        public ContextRequest(String playerAction, List<String> engineRules, ContextSelection selection,
                              String rollingSummary, PreviousTurn previousTurn) {
            this(playerAction, engineRules, selection, rollingSummary, previousTurn, null, null, null);
        }

        public ContextRequest(String playerAction, List<String> engineRules, ContextSelection selection,
                              String rollingSummary, PreviousTurn previousTurn,
                              StoryPromptContext.PendingDecision pendingDecision) {
            this(playerAction, engineRules, selection, rollingSummary, previousTurn, pendingDecision, null, null);
        }

        /** 兼容系统选项来源加入前的调用方。 */
        public ContextRequest(String playerAction, List<String> engineRules, ContextSelection selection,
                              String rollingSummary, PreviousTurn previousTurn,
                              StoryPromptContext.PendingDecision pendingDecision,
                              Long explicitDurationMinutes) {
            this(playerAction, engineRules, selection, rollingSummary, previousTurn, pendingDecision,
                    explicitDurationMinutes, null);
        }

        public ContextRequest {
            engineRules = engineRules == null ? List.of() : List.copyOf(engineRules);
        }
    }

    /**
     * 上游相关性选择结果。空集合表示本轮不需要该类详情，不代表删除或遗忘数据库中的事实。
     */
    public record ContextSelection(
            Set<String> entityIndexIds,
            Set<String> relatedEntityIds,
            Set<String> worldFactIds,
            List<KnowledgeRef> knowledgeRefs,
            Set<String> memoryIds
    ) {
        public ContextSelection {
            entityIndexIds = immutable(entityIndexIds);
            relatedEntityIds = immutable(relatedEntityIds);
            worldFactIds = immutable(worldFactIds);
            knowledgeRefs = knowledgeRefs == null ? List.of() : List.copyOf(knowledgeRefs);
            memoryIds = immutable(memoryIds);
        }

        public static ContextSelection empty() {
            return new ContextSelection(Set.of(), Set.of(), Set.of(), List.of(), Set.of());
        }

        private static Set<String> immutable(Set<String> values) {
            return values == null ? Set.of() : Set.copyOf(values);
        }
    }

    public record KnowledgeRef(String subjectRef, String topicRef) {}
}
