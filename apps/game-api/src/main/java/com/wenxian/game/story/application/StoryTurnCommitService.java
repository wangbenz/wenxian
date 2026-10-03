package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.account.AccountActivityService;
import com.wenxian.game.account.AccountActivitySource;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.credit.PlatformTurnAccessService;
import com.wenxian.game.story.domain.StoryCharacterIdentityNames;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.diagnostic.StoryTurnDiagnosticService;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryRecords;
import com.wenxian.game.story.domain.StoryRuntimeState;
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
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AccountActivityService accountActivity;

    @Autowired
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
            ObjectMapper mapper,
            AccountActivityService accountActivity) {
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
        this.accountActivity = accountActivity;
    }

    /** 兼容不加载账号生命周期表的纯故事单元测试；生产装配必须使用带活动服务的构造器。 */
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
        this(saves, turns, entities, memories, conversations, projections, releases, turnAccess,
                transactions, mapper, null);
    }

    /** 提交回合；调用方必须在调用模型前已经完成额度检查。 */
    public Committed commit(String userId, StoredSave saved, String requestId, String playerAction,
                            PreparedStoryTurn prepared,
                            PlatformTurnAccessService.Permit turnPermit) {
        Committed committed = transactions.execute(status -> commitInTransaction(
                userId, saved, requestId, playerAction, prepared, turnPermit));
        if (committed == null) throw new IllegalStateException("可信回合事务没有返回结果");
        if (prepared.diagnostic() != null) {
            prepared.diagnostic().committed(committed.revision(), committed.turnNumber(),
                    committed.acceptedPatch(), prepared.applied().projection().worldTime());
        }
        return committed;
    }

    private Committed commitInTransaction(String userId, StoredSave saved, String requestId, String playerAction,
                                          PreparedStoryTurn prepared,
                                          PlatformTurnAccessService.Permit turnPermit) {
        StoryProjection after = prepared.applied().projection();
        StoryPatch accepted = prepared.applied().acceptedPatch();
        int nextTurn = saved.turnNumber() + 1;
        List<StoryRuntimeState.Choice> choices = accepted.choices().stream()
                .map(value -> new StoryRuntimeState.Choice("choice-" + UUID.randomUUID(),
                        value.title(), value.description())).toList();
        ObjectNode state = projections.writeState(saved.state(), after);
        projections.writeDecisionCheckpoint(state, accepted.decisionCheckpoint());
        state.put("schemaVersion", saved.stateSchemaVersion());
        state.put("saveId", saved.id());
        state.put("turnNumber", nextTurn);
        state.put("status", saved.status());
        ObjectNode scene = state.path("scene") instanceof ObjectNode object
                ? object.deepCopy() : mapper.createObjectNode();
        scene.put("title", text(accepted.title(), scene.path("title").asText("命卷新页")));
        scene.put("narrative", accepted.narrative());
        scene.put("situation", text(accepted.outcome() == null ? null : accepted.outcome().summary(),
                accepted.narrative()));
        scene.set("choices", mapper.valueToTree(choices));
        state.set("scene", scene);

        long revision = saves.updateState(userId, saved.id(), saved.revision(), state,
                saved.stateSchemaVersion(), nextTurn, saved.worldCode(), saved.status(), releases.current());
        persistChangedEntities(saved.id(), nextTurn, prepared, after, accepted, Instant.now());
        persistEntityAliases(saved.id(), nextTurn, prepared, Instant.now());
        persistNewMemories(saved.id(), nextTurn, prepared.before(), after);
        String turnId = "story-turn-" + UUID.randomUUID();
        turns.insert(new NewTurn(turnId, saved.id(), requestId, nextTurn,
                playerAction, accepted.narrative(), mapper.valueToTree(choices), storedPatch(prepared, accepted, turnId, nextTurn),
                prepared.before().worldTime().toOrder(), after.worldTime().toOrder(), saved.engineCode(),
                saved.engineVersion(), saved.stateSchemaVersion(), prepared.storyRecords() == null ? PROMPT_VERSION : StoryRecords.PROMPT_VERSION,
                prepared.generation().finalCall().providerCode(),
                prepared.generation().finalCall().modelCode(),
                prepared.generation().finalCall().credentialSource(),
                prepared.generation().inputTokens(), prepared.generation().outputTokens(),
                prepared.generation().cacheHitTokens(), prepared.generation().cacheMissTokens(),
                prepared.generation().latencyMs(), releases.current(), Instant.now()));
        if (accepted.rollingSummary() != null && !accepted.rollingSummary().isBlank()) {
            conversations.updateSummary(saved.id(), accepted.rollingSummary().strip(), nextTurn);
        }
        turnAccess.consume(turnPermit, userId, turnId);
        if (accountActivity != null) {
            accountActivity.record(userId, AccountActivitySource.TURN_COMMITTED, Instant.now());
        }
        return new Committed(revision, nextTurn, state, accepted);
    }

    /**
     * 回合补丁附带已接受的资产过程、可信事件与持续记录；记录在同一事务中绑定真实回合来源。
     * 旧固定载体只写旧结果，新合同只写 storyRecords，不允许相互替代或反向覆盖当前投影。
     */
    private ObjectNode storedPatch(PreparedStoryTurn prepared, StoryPatch accepted, String turnId, int turnNumber) {
        ObjectNode stored = mapper.valueToTree(accepted);
        stored.set("trustedEvents", mapper.valueToTree(prepared.trustedEvents()));
        if (prepared.assetProcess() != null) {
            stored.set("assetProcess", mapper.valueToTree(prepared.assetProcess()));
        }
        if (prepared.acceptedResult() != null) {
            stored.set("acceptedResult", mapper.valueToTree(prepared.acceptedResult()));
        }
        if (prepared.storyRecords() != null) {
            stored.set("storyRecords", mapper.valueToTree(StoryRecords.stamp(prepared.storyRecords(), turnId, turnNumber,
                    prepared.before().worldTime().toOrder(), prepared.applied().projection().worldTime().toOrder())));
        }
        return stored;
    }

    private void persistChangedEntities(String saveId, int turnNumber,
                                        PreparedStoryTurn prepared,
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
                        value.title(), value.summary(), turnNumber, after.worldTime().toOrder(), value.importance())));
    }

    /** 改名别名与实体和回合处于同一事务，避免当前名称已变化但历史称谓无法解析。 */
    private void persistEntityAliases(String saveId, int turnNumber,
                                      PreparedStoryTurn prepared, Instant now) {
        prepared.applied().aliasChanges().forEach(change -> entities.saveAlias(saveId, change.entityId(),
                change.aliasName(), change.aliasType(), turnNumber, now));
        Set<String> changedCharacters = new HashSet<>();
        prepared.applied().acceptedPatch().entities().stream()
                .map(StoryPatch.EntityPatch::entityId)
                .filter(Objects::nonNull)
                .filter(changedCharacters::add)
                .map(prepared.applied().projection().entities()::get)
                .filter(Objects::nonNull)
                .filter(character -> "CHARACTER".equalsIgnoreCase(character.entityType()))
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
