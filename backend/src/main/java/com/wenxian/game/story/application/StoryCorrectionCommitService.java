package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.persistence.StoryCorrectionRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StorySaveRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 将玩家已经确认的命卷修正作为一个原子事务提交。
 *
 * <p>本服务是修正确认的唯一写边界：读取当前 revision 与全部实体，验证加密提案，执行通用 Reducer，
 * 再原子更新命卷快照、受影响实体和修正审计。任一步失败都会回滚；它不调用模型、不推进世界时间、
 * 不增加回合，也不会在冲突后自动套用旧提案。</p>
 */
@Service
public class StoryCorrectionCommitService {
    private final StorySaveRepository saves;
    private final StoryEntityRepository entities;
    private final StoryMemoryRepository memories;
    private final StoryCorrectionRepository corrections;
    private final StoryProjectionPersistenceMapper projections;
    private final StoryCorrectionWorkflow workflow;
    private final GameVersionService releases;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public StoryCorrectionCommitService(
            StorySaveRepository saves,
            StoryEntityRepository entities,
            StoryMemoryRepository memories,
            StoryCorrectionRepository corrections,
            StoryProjectionPersistenceMapper projections,
            StoryCorrectionWorkflow workflow,
            GameVersionService releases,
            TransactionTemplate transactions,
            ObjectMapper mapper) {
        this.saves = saves;
        this.entities = entities;
        this.memories = memories;
        this.corrections = corrections;
        this.projections = projections;
        this.workflow = workflow;
        this.releases = releases;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    /** 确认并提交一份预览令牌；返回值只在整个事务成功后产生。 */
    public Committed confirm(String userId, String saveId, long expectedRevision, String proposalToken) {
        Committed committed = transactions.execute(status -> confirmInTransaction(
                userId, saveId, expectedRevision, proposalToken));
        if (committed == null) throw new IllegalStateException("命卷修正事务没有返回结果");
        return committed;
    }

    private Committed confirmInTransaction(String userId, String saveId, long expectedRevision,
                                             String proposalToken) {
        StoredSave saved = saves.findOwned(userId, saveId)
                .orElseThrow(() -> new StoryCorrectionException(StoryCorrectionException.Reason.SAVE_NOT_FOUND,
                        "没有找到可修正的命卷"));
        if (!"ACTIVE".equals(saved.status())) {
            throw new StoryCorrectionException(StoryCorrectionException.Reason.SAVE_NOT_ACTIVE,
                    "封存命卷不能继续修正");
        }
        if (saved.revision() != expectedRevision) {
            throw new StoryCorrectionException(StoryCorrectionException.Reason.REVISION_CONFLICT,
                    "命卷已经发生变化，请刷新后重新预览修正");
        }

        List<Entity> existingEntities = entities.listAll(saveId);
        StoryProjection before = projections.load(saved, existingEntities, memories.recentActive(saveId, 80));
        StoryCorrectionWorkflow.Confirmed confirmed;
        try {
            confirmed = workflow.confirm(userId, saveId, expectedRevision, proposalToken, before);
        } catch (CorrectionProposalTokenCodec.InvalidProposalTokenException error) {
            String message = "修正预览已经失效，请重新预览后确认";
            throw new StoryCorrectionException(StoryCorrectionException.Reason.PROPOSAL_INVALID, message, error);
        } catch (IllegalArgumentException error) {
            throw new StoryCorrectionException(StoryCorrectionException.Reason.PROPOSAL_REJECTED,
                    "当前事实无法应用这份修正，请刷新后重新说明", error);
        }
        StoryProjection after = confirmed.projection();
        if (after.worldTimeMinutes() != before.worldTimeMinutes()) {
            throw new IllegalStateException("命卷修正不能推进世界时间");
        }

        long nextRevision = saves.updateState(userId, saveId, expectedRevision,
                projections.writeState(saved.state(), after), saved.stateSchemaVersion(), saved.turnNumber(),
                saved.worldCode(), saved.status(), releases.current());
        persistChangedEntities(saveId, saved.turnNumber(), existingEntities, after,
                confirmed.acceptedProposal(), Instant.now());
        confirmed.aliasChanges().forEach(change -> entities.saveAlias(saveId, change.entityId(),
                change.aliasName(), change.aliasType(), saved.turnNumber(), Instant.now()));
        corrections.insert(userId, saveId, confirmed.instruction(),
                confirmed.acceptedProposal().kind().name(), mapper.valueToTree(before), mapper.valueToTree(after));
        return new Committed(nextRevision, saved.turnNumber(), confirmed.instruction(), after,
                confirmed.acceptedProposal());
    }

    private void persistChangedEntities(String saveId, int turnNumber, List<Entity> existing,
                                        StoryProjection after, StoryCorrectionProposal proposal, Instant now) {
        Set<String> changedIds = new HashSet<>();
        proposal.entities().forEach(patch -> {
            if (patch.entityId() == null || patch.entityId().isBlank()) {
                throw new IllegalStateException("已接受的实体修正缺少稳定ID");
            }
            changedIds.add(patch.entityId());
        });
        if (changedIds.isEmpty()) return;

        projections.writeEntities(saveId, after, existing, turnNumber, now).stream()
                .filter(entity -> changedIds.contains(entity.id()))
                .forEach(entity -> {
                    if (entities.update(entity) == 0) entities.insert(entity);
                });
    }

    /** 一次已经落库的修正确认结果。 */
    public record Committed(
            long revision,
            int turnNumber,
            String instruction,
            StoryProjection projection,
            StoryCorrectionProposal acceptedProposal
    ) {}
}
