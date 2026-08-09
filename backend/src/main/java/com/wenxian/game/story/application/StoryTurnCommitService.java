package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.service.PlayerAiService;
import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository.StoredMemory;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewTurn;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import com.wenxian.game.v014.model.V014State;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 原子提交一份已经通过 Reducer 的普通回合。
 *
 * <p>该服务是可信普通回合的唯一写边界：使用旧 revision 更新命卷，保存 Reducer 最终接受的补丁、
 * 受影响实体、新关键记忆、滚动摘要、模型用量和额度。任何步骤失败都会回滚，模型原始 JSON 不落库。</p>
 */
@Service
public class StoryTurnCommitService {
    private static final String PROMPT_VERSION = "trusted-story-turn-v1";

    private final StorySaveRepository saves;
    private final StoryTurnRepository turns;
    private final StoryEntityRepository entities;
    private final StoryMemoryRepository memories;
    private final StoryConversationRepository conversations;
    private final StoryProjectionPersistenceMapper projections;
    private final GameVersionService releases;
    private final PlayerAiService playerAi;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public StoryTurnCommitService(
            StorySaveRepository saves,
            StoryTurnRepository turns,
            StoryEntityRepository entities,
            StoryMemoryRepository memories,
            StoryConversationRepository conversations,
            StoryProjectionPersistenceMapper projections,
            GameVersionService releases,
            PlayerAiService playerAi,
            TransactionTemplate transactions,
            ObjectMapper mapper) {
        this.saves = saves;
        this.turns = turns;
        this.entities = entities;
        this.memories = memories;
        this.conversations = conversations;
        this.projections = projections;
        this.releases = releases;
        this.playerAi = playerAi;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    /** 提交回合；调用方必须在调用模型前已经完成额度检查。 */
    public Committed commit(String userId, StoredSave saved, String requestId, String playerAction,
                            StoryTurnPreparationService.Prepared prepared, StoryModelRoute route,
                            PlayerAiService.Route quotaRoute) {
        Committed committed = transactions.execute(status -> commitInTransaction(
                userId, saved, requestId, playerAction, prepared, route, quotaRoute));
        if (committed == null) throw new IllegalStateException("可信回合事务没有返回结果");
        return committed;
    }

    private Committed commitInTransaction(String userId, StoredSave saved, String requestId, String playerAction,
                                          StoryTurnPreparationService.Prepared prepared, StoryModelRoute route,
                                          PlayerAiService.Route quotaRoute) {
        StoryProjection after = prepared.applied().projection();
        StoryPatch accepted = prepared.applied().acceptedPatch();
        int nextTurn = saved.turnNumber() + 1;
        List<V014State.Choice> choices = accepted.choices().stream()
                .map(value -> new V014State.Choice("choice-" + UUID.randomUUID(),
                        value.title(), value.description())).toList();
        ObjectNode state = projections.writeState(saved.state(), after);
        projections.writeDecisionCheckpoint(state, accepted.decisionCheckpoint());
        state.put("schemaVersion", saved.stateSchemaVersion());
        state.put("saveId", saved.id());
        state.put("turnNumber", nextTurn);
        state.put("status", saved.status());
        state.put("sceneTitle", text(accepted.title(), state.path("sceneTitle").asText("命卷新页")));
        state.put("narrative", accepted.narrative());
        state.set("choices", mapper.valueToTree(choices));

        long revision = saves.updateState(userId, saved.id(), saved.revision(), state,
                saved.stateSchemaVersion(), nextTurn, saved.worldCode(), saved.status(), releases.current());
        persistChangedEntities(saved.id(), nextTurn, prepared, after, accepted, Instant.now());
        persistNewMemories(saved.id(), nextTurn, prepared.before(), after);
        turns.insert(new NewTurn("story-turn-" + UUID.randomUUID(), saved.id(), requestId, nextTurn,
                playerAction, accepted.narrative(), mapper.valueToTree(choices), mapper.valueToTree(accepted),
                prepared.before().worldTimeMinutes(), after.worldTimeMinutes(), saved.engineCode(),
                saved.engineVersion(), PROMPT_VERSION, route.provider(),
                prepared.generation().finalResult().model(), route.credentialSource(),
                prepared.generation().inputTokens(), prepared.generation().outputTokens(), 0, 0,
                prepared.generation().latencyMs(), releases.current(), Instant.now()));
        if (accepted.rollingSummary() != null && !accepted.rollingSummary().isBlank()) {
            conversations.updateSummary(saved.id(), accepted.rollingSummary().strip(), nextTurn);
        }
        playerAi.recordSuccessfulInvocation(userId, quotaRoute);
        return new Committed(revision, nextTurn, state, accepted);
    }

    private void persistChangedEntities(String saveId, int turnNumber,
                                        StoryTurnPreparationService.Prepared prepared,
                                        StoryProjection after, StoryPatch accepted, Instant now) {
        Set<String> changedIds = new HashSet<>();
        accepted.entities().forEach(patch -> {
            if (patch.entityId() == null || patch.entityId().isBlank()) {
                throw new IllegalStateException("已接受的普通回合实体变化缺少稳定ID");
            }
            changedIds.add(patch.entityId());
        });
        if (changedIds.isEmpty()) return;
        projections.writeEntities(saveId, after, prepared.existingEntities(), turnNumber, now).stream()
                .filter(value -> changedIds.contains(value.id()))
                .forEach(value -> {
                    if (entities.update(value) == 0) entities.insert(value);
                });
    }

    private void persistNewMemories(String saveId, int turnNumber, StoryProjection before,
                                    StoryProjection after) {
        Set<String> existingIds = before.memories().stream().map(StoryProjection.MemoryState::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        after.memories().stream().filter(value -> !existingIds.contains(value.id()))
                .forEach(value -> memories.insert(saveId, new StoredMemory(value.id(), value.memoryType(),
                        value.title(), value.summary(), turnNumber, after.worldTimeMinutes(), value.importance())));
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** 已完整落库的普通回合结果。 */
    public record Committed(long revision, int turnNumber, ObjectNode state, StoryPatch acceptedPatch) {}
}
