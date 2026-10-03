package com.wenxian.game.story.replay.records;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewTurn;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试专用回合仓储扩展：把已经预演的记录绑定到真正插入的回合，不另开事务。
 *
 * <p>宿主每轮新建实例，并仅在准备完整后 stage；普通提交服务仍负责状态、回合、记忆和额度的
 * 同一事务。缺少暂存记录、重复覆盖或冲突格式直接失败，SQL 异常不吞掉，不进行独立补写。</p>
 */
public class RecordStoryTurnRepository extends StoryTurnRepository {
    public static final String PROMPT_VERSION = "record-candidate-v1";
    private final ObjectMapper mapper;
    private final Map<String, List<StoryRecords.Value>> staged = new ConcurrentHashMap<>();

    public RecordStoryTurnRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
        this.mapper = mapper;
    }

    public void stage(String requestId, List<StoryRecords.Value> values) {
        if (requestId == null || requestId.isBlank() || values == null) {
            throw new IllegalArgumentException("暂存持续记录必须绑定请求 ID 与明确记录数组");
        }
        List<StoryRecords.Value> copy = List.copyOf(values);
        List<StoryRecords.Value> previous = staged.putIfAbsent(requestId, copy);
        if (previous != null && !previous.equals(copy)) {
            throw new IllegalStateException("同一请求的已接受持续记录不能被覆盖");
        }
    }

    @Override
    public void insert(NewTurn turn) {
        if (turn == null || turn.requestId() == null || turn.requestId().isBlank()) {
            throw new IllegalStateException("持续记录插入缺少真实回合或请求 ID");
        }
        List<StoryRecords.Value> values = staged.get(turn.requestId());
        if (values == null) throw new IllegalStateException("回合提交前没有暂存已接受的持续记录");
        if (!(turn.statePatch() instanceof ObjectNode patch)) {
            throw new IllegalStateException("持续记录需要有效的回合状态补丁");
        }
        if (patch.has("acceptedResult") || patch.has("storyRecords")) {
            throw new IllegalStateException("新记录回合不能同时携带旧步骤结果或预先伪造的记录来源");
        }
        ObjectNode stored = patch.deepCopy();
        stored.set("storyRecords", mapper.valueToTree(StoryRecords.stamp(values, turn)));
        super.insert(new NewTurn(turn.id(), turn.saveId(), turn.requestId(), turn.turnNumber(),
                turn.playerAction(), turn.narrative(), turn.choices(), stored,
                turn.worldTimeBefore(), turn.worldTimeAfter(), turn.engineCode(), turn.engineVersion(),
                turn.stateSchemaVersion(), PROMPT_VERSION, turn.provider(), turn.model(), turn.credentialSource(),
                turn.promptTokens(), turn.completionTokens(), turn.cacheHitTokens(), turn.cacheMissTokens(),
                turn.latencyMs(), turn.releaseVersion(), turn.createdAt()));
        // 暂存留到本轮实例结束；外层事务若在回合插入后回滚，不会丢失同请求的重试材料。
    }
}
