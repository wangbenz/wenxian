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
import com.wenxian.game.story.domain.StoryActionContractValidator;
import com.wenxian.game.story.domain.StoryActionContractCanonicalizer;
import com.wenxian.game.story.domain.StoryActionContractPatchValidator;
import com.wenxian.game.story.domain.ExplicitDurationParser;
import com.wenxian.game.story.domain.StoryNarrativeFreshnessValidator;
import com.wenxian.game.story.domain.StoryNarrativeFreshnessException;
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
    private final StoryActionContractValidator actionContracts = new StoryActionContractValidator();
    private final StoryActionContractCanonicalizer contractCanonicalizer = new StoryActionContractCanonicalizer();
    private final StoryActionContractPatchValidator contractPatches = new StoryActionContractPatchValidator();
    private final StoryNarrativeFreshnessValidator narrativeFreshness = new StoryNarrativeFreshnessValidator();

    public StoryTurnPreparationService(
            StoryEntityRepository entities,
            StoryMemoryRepository memories,
            StoryConversationRepository conversations,
            StoryTurnRepository turns,
            StoryProjectionPersistenceMapper projections,
            PromptContextAssembler contexts,
            StoryTurnAgent agent,
            StoryStateReducer reducer) {
        this.entities = entities;
        this.memories = memories;
        this.conversations = conversations;
        this.turns = turns;
        this.projections = projections;
        this.contexts = contexts;
        this.agent = agent;
        this.reducer = reducer;
    }

    /** 生成并归并一份尚未提交的回合结果。 */
    public Prepared prepare(StoredSave saved, String playerAction, StoryModelRoute route) {
        if (saved == null) throw new IllegalArgumentException("回合准备缺少命卷");
        if (playerAction == null || playerAction.isBlank()) throw new IllegalArgumentException("玩家行动不能为空");
        List<Entity> existingEntities = entities.listAll(saved.id());
        StoryProjection before = projections.load(saved, existingEntities,
                memories.recentActive(saved.id(), RECENT_MEMORY_LIMIT));
        var checkpoint = projections.loadDecisionCheckpoint(saved.state());
        StoryPromptContext.PreviousTurn previous = previousTurn(saved.id());
        Long explicitDurationMinutes = explicitDuration(playerAction, before.worldTimeMinutes());
        StoryPromptContext context = contexts.assemble(before, new ContextRequest(playerAction,
                worldSeedRules(saved.state()), selection(before, existingEntities), conversations.summary(saved.id()),
                previous, checkpoint == null ? null : new PendingDecision(checkpoint.prompt()),
                explicitDurationMinutes));
        StoryTurnAgent.InvocationContext invocation = new StoryTurnAgent.InvocationContext(
                saved.id(), saved.turnNumber() + 1);
        StoryTurnAgent.ResolvedAction resolvedAction = agent.resolveAction(context, route, invocation);
        var actionContract = contractCanonicalizer.canonicalize(before, resolvedAction.contract());
        actionContracts.validate(before, actionContract);
        context = context.withActionContract(actionContract);
        StoryTurnAgent.Generated generation = agent.generate(context, route, invocation);
        generation = prependModelResults(resolvedAction.modelResults(), generation);
        StoryStateReducer.Applied applied;
        try {
            rejectRepeatedNarrative(previous, generation.patch());
            StoryTurnAgent.Review review = agent.review(context, route, invocation, generation.patch());
            generation = appendReview(generation, review);
            rejectReview(review);
            contractPatches.validate(before, actionContract, generation.patch());
            applied = reducer.reduce(before, generation.patch(), explicitDurationMinutes);
        } catch (IllegalArgumentException rejected) {
            StoryTurnAgent.Generated repaired = rejected instanceof StoryNarrativeFreshnessException
                    ? agent.regenerateFreshNarrative(context, route, invocation,
                            generation.patch(), rejected.getMessage())
                    : agent.repairRejected(context, route, invocation,
                            generation.patch(), rejected.getMessage());
            generation = combine(generation, repaired);
            try {
                rejectRepeatedNarrative(previous, repaired.patch());
                StoryTurnAgent.Review review = agent.review(context, route, invocation, repaired.patch());
                generation = appendReview(generation, review);
                rejectReview(review);
                contractPatches.validate(before, actionContract, repaired.patch());
                applied = reducer.reduce(before, repaired.patch(), explicitDurationMinutes);
            } catch (IllegalArgumentException failedAgain) {
                failedAgain.addSuppressed(rejected);
                throw failedAgain;
            }
        }
        return new Prepared(before, applied, existingEntities, generation);
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

    private StoryTurnAgent.Generated combine(StoryTurnAgent.Generated first, StoryTurnAgent.Generated repaired) {
        List<StoryModelGateway.Result> attempts = new ArrayList<>(first.modelResults());
        attempts.addAll(repaired.modelResults());
        return new StoryTurnAgent.Generated(repaired.patch(), attempts);
    }

    private StoryTurnAgent.Generated appendReview(StoryTurnAgent.Generated generated, StoryTurnAgent.Review review) {
        if (review == null || review.modelResults().isEmpty()) return generated;
        List<StoryModelGateway.Result> attempts = new ArrayList<>(generated.modelResults());
        attempts.addAll(review.modelResults());
        return new StoryTurnAgent.Generated(generated.patch(), attempts);
    }

    private StoryTurnAgent.Generated prependModelResults(List<StoryModelGateway.Result> earlier,
                                                          StoryTurnAgent.Generated generated) {
        if (earlier == null || earlier.isEmpty()) return generated;
        List<StoryModelGateway.Result> attempts = new ArrayList<>(earlier);
        attempts.addAll(generated.modelResults());
        return new StoryTurnAgent.Generated(generated.patch(), attempts);
    }

    private void rejectReview(StoryTurnAgent.Review review) {
        if (review != null && !review.accepted()) {
            throw new IllegalArgumentException("独立回合审阅未通过：" + review.problem());
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
            StoryTurnAgent.Generated generation
    ) {
        public Prepared {
            existingEntities = existingEntities == null ? List.of() : List.copyOf(existingEntities);
        }
    }
}
