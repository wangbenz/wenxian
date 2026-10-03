package com.wenxian.game.story.application;

import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextRequest;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextSelection;
import com.wenxian.game.story.agent.PromptContextAssembler.KnowledgeRef;
import com.wenxian.game.story.agent.StoryCorrectionNoChangeException;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredTurn;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

/**
 * 从权威命卷事实生成不落库的修正预览。
 *
 * <p>数据库读取先在短事务中形成一致快照，模型调用发生在事务之外。预览为 Agent 提供全部实体的稳定 ID
 * 与轻量现状、全部当前世界事实、人物认知和近期关键记忆；不会用姓名关键词选择或覆盖实体。模型返回后
 * 只签发绑定旧 revision 的提案，期间若命卷变化，确认阶段会拒绝旧令牌。</p>
 */
@Service
public class StoryCorrectionPreviewService {
    private static final int RECENT_MEMORY_LIMIT = 20;

    private final StorySaveRepository saves;
    private final StoryEntityRepository entities;
    private final StoryMemoryRepository memories;
    private final StoryConversationRepository conversations;
    private final StoryTurnRepository turns;
    private final StoryProjectionPersistenceMapper projections;
    private final PromptContextAssembler contexts;
    private final StoryCorrectionWorkflow workflow;
    private final TransactionTemplate transactions;

    public StoryCorrectionPreviewService(
            StorySaveRepository saves,
            StoryEntityRepository entities,
            StoryMemoryRepository memories,
            StoryConversationRepository conversations,
            StoryTurnRepository turns,
            StoryProjectionPersistenceMapper projections,
            PromptContextAssembler contexts,
            StoryCorrectionWorkflow workflow,
            TransactionTemplate transactions) {
        this.saves = saves;
        this.entities = entities;
        this.memories = memories;
        this.conversations = conversations;
        this.turns = turns;
        this.projections = projections;
        this.contexts = contexts;
        this.workflow = workflow;
        this.transactions = transactions;
    }

    /** 解析玩家修正说明并返回认证提案；不会修改命卷或消耗正式回合。 */
    public StoryCorrectionWorkflow.Preview preview(String userId, String saveId, long expectedRevision,
                                                    String instruction) {
        Snapshot snapshot = transactions.execute(status -> snapshot(userId, saveId, expectedRevision, instruction));
        if (snapshot == null) throw new IllegalStateException("命卷修正预览没有读取到一致快照");
        try {
            return workflow.preview(userId, saveId, expectedRevision, instruction, snapshot.context());
        } catch (StoryCorrectionNoChangeException error) {
            throw new StoryCorrectionException(StoryCorrectionException.Reason.NO_STATE_CHANGE,
                    error.getMessage(), error);
        } catch (StoryCorrectionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new StoryCorrectionException(StoryCorrectionException.Reason.PREVIEW_FAILED,
                    "当前修正说明未能形成可确认的变化", error);
        }
    }

    private Snapshot snapshot(String userId, String saveId, long expectedRevision, String instruction) {
        StoredSave saved = saves.findOwned(userId, saveId)
                .orElseThrow(() -> new StoryCorrectionException(StoryCorrectionException.Reason.SAVE_NOT_FOUND,
                        "没有找到可修正的命卷"));
        if (!"ACTIVE".equals(saved.status())) {
            throw new StoryCorrectionException(StoryCorrectionException.Reason.SAVE_NOT_ACTIVE,
                    "封存命卷不能继续修正");
        }
        if (saved.revision() != expectedRevision) {
            throw new StoryCorrectionException(StoryCorrectionException.Reason.REVISION_CONFLICT,
                    "命卷已经发生变化，请刷新后重新发起修正");
        }
        StoryProjection projection = projections.load(saved, entities.listAll(saveId),
                memories.recentActive(saveId, RECENT_MEMORY_LIMIT));
        ContextSelection selection = new ContextSelection(
                projection.entities().keySet(), Set.of(), projection.worldFacts().keySet(),
                projection.knowledge().values().stream()
                        .map(value -> new KnowledgeRef(value.subjectRef(), value.topicRef())).toList(),
                projection.memories().stream().map(StoryProjection.MemoryState::id)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        StoryPromptContext context = contexts.assemble(projection, new ContextRequest(instruction, List.of(),
                selection, conversations.summary(saveId), previousTurn(saveId)));
        return new Snapshot(context);
    }

    private StoryPromptContext.PreviousTurn previousTurn(String saveId) {
        return turns.recent(saveId, 1).stream().findFirst().map(this::previousTurn).orElse(null);
    }

    private StoryPromptContext.PreviousTurn previousTurn(StoredTurn turn) {
        String title = turn.statePatch() == null ? "" : turn.statePatch().path("title").asText("");
        var history = com.wenxian.game.story.persistence.StoryRecordHistoryReader.read(turn);
        return new StoryPromptContext.PreviousTurn(turn.turnNumber(), turn.playerAction(), title,
                turn.narrative(), "",
                history.acceptedResult(), history.storyRecords());
    }

    private record Snapshot(StoryPromptContext context) {}
}
