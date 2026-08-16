package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.credit.PlatformTurnAccessService;
import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.domain.StoryCharacterIdentityNames;
import com.wenxian.game.story.domain.StoryActionContract.EntityReference;
import com.wenxian.game.story.domain.StoryActionContract.ReferenceResolution;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.diagnostic.StoryTurnDiagnosticService;
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
import java.util.Objects;
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
    private static final String PROMPT_VERSION = StoryTurnDiagnosticService.PROMPT_VERSION;

    private final StorySaveRepository saves;
    private final StoryTurnRepository turns;
    private final StoryEntityRepository entities;
    private final StoryMemoryRepository memories;
    private final StoryConversationRepository conversations;
    private final StoryProjectionPersistenceMapper projections;
    private final GameVersionService releases;
    private final PlatformTurnAccessService turnAccess;
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
            PlatformTurnAccessService turnAccess,
            TransactionTemplate transactions,
            ObjectMapper mapper) {
        this.saves = saves;
        this.turns = turns;
        this.entities = entities;
        this.memories = memories;
        this.conversations = conversations;
        this.projections = projections;
        this.releases = releases;
        this.turnAccess = turnAccess;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    /** 提交回合；调用方必须在调用模型前已经完成额度检查。 */
    public Committed commit(String userId, StoredSave saved, String requestId, String playerAction,
                            StoryTurnPreparationService.Prepared prepared, StoryModelRoute route,
                            PlatformTurnAccessService.Permit turnPermit) {
        Committed committed = transactions.execute(status -> commitInTransaction(
                userId, saved, requestId, playerAction, prepared, route, turnPermit));
        if (committed == null) throw new IllegalStateException("可信回合事务没有返回结果");
        if (prepared.diagnostic() != null) {
            prepared.diagnostic().committed(committed.revision(), committed.turnNumber(),
                    committed.acceptedPatch(), prepared.applied().projection().worldTimeMinutes());
        }
        return committed;
    }

    private Committed commitInTransaction(String userId, StoredSave saved, String requestId, String playerAction,
                                          StoryTurnPreparationService.Prepared prepared, StoryModelRoute route,
                                          PlatformTurnAccessService.Permit turnPermit) {
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
        persistEntityAliases(saved.id(), nextTurn, prepared, Instant.now());
        persistNewMemories(saved.id(), nextTurn, prepared.before(), after);
        String turnId = "story-turn-" + UUID.randomUUID();
        turns.insert(new NewTurn(turnId, saved.id(), requestId, nextTurn,
                playerAction, accepted.narrative(), mapper.valueToTree(choices), storedPatch(prepared, accepted),
                prepared.before().worldTimeMinutes(), after.worldTimeMinutes(), saved.engineCode(),
                saved.engineVersion(), PROMPT_VERSION, route.provider(),
                prepared.generation().finalResult().model(), route.credentialSource(),
                prepared.generation().inputTokens(), prepared.generation().outputTokens(), 0, 0,
                prepared.generation().latencyMs(), releases.current(), Instant.now()));
        if (accepted.rollingSummary() != null && !accepted.rollingSummary().isBlank()) {
            conversations.updateSummary(saved.id(), accepted.rollingSummary().strip(), nextTurn);
        }
        turnAccess.consume(turnPermit, userId, turnId);
        return new Committed(revision, nextTurn, state, accepted);
    }

    /**
     * 回合补丁继续保留原有兼容结构，同时附带 Reducer 接受后编译出的可信事件。
     * 事件只作为已提交事实的结构化记录，不允许反向覆盖 Reducer 的权威投影。
     */
    private ObjectNode storedPatch(StoryTurnPreparationService.Prepared prepared, StoryPatch accepted) {
        ObjectNode stored = mapper.valueToTree(accepted);
        stored.set("trustedEvents", mapper.valueToTree(prepared.trustedEvents()));
        return stored;
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

    /** 改名别名与实体和回合处于同一事务，避免当前名称已变化但历史称谓无法解析。 */
    private void persistEntityAliases(String saveId, int turnNumber,
                                      StoryTurnPreparationService.Prepared prepared, Instant now) {
        prepared.applied().aliasChanges().forEach(change -> entities.saveAlias(saveId, change.entityId(),
                change.aliasName(), change.aliasType(), turnNumber, now));
        if (prepared.actionContract() == null) return;
        Set<String> referencedCharacters = new HashSet<>();
        prepared.actionContract().references().stream()
                .filter(reference -> reference != null
                        && reference.resolution() == ReferenceResolution.EXISTING
                        && "CHARACTER".equalsIgnoreCase(reference.entityType())
                        && reference.entityId() != null && !reference.entityId().isBlank())
                .map(EntityReference::entityId)
                .filter(referencedCharacters::add)
                .map(prepared.applied().projection().entities()::get)
                .filter(Objects::nonNull)
                .forEach(character -> StoryCharacterIdentityNames.from(character)
                        .forEach(alias -> entities.saveAlias(saveId, character.id(), alias,
                                "TITLE", turnNumber, now)));
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /** 已完整落库的普通回合结果。 */
    public record Committed(long revision, int turnNumber, ObjectNode state, StoryPatch acceptedPatch) {}
}
