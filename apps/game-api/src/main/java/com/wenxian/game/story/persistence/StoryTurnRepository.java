package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewTurn;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredTurn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 统一命卷回合仓储。回合只允许追加；`save_id + request_id` 与回合号唯一约束
 * 是网络重试和重复提交的最后防线，已提交正文不得被更新。
 */
@Repository
public class StoryTurnRepository {
    private final JdbcTemplate jdbc;
    private final StoryJsonCodec json;

    public StoryTurnRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.json = new StoryJsonCodec(mapper);
    }

    public void insert(NewTurn turn) {
        jdbc.update("""
                insert into story_turn(
                    id, save_id, request_id, turn_number, player_action, narrative_text,
                    choices_json, state_patch_json, world_time_before, world_time_after,
                    engine_code, engine_version, state_schema_version, prompt_version, provider, model, credential_source,
                    prompt_tokens, completion_tokens, cache_hit_tokens, cache_miss_tokens,
                    latency_ms, release_version, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, turn.id(), turn.saveId(), turn.requestId(), turn.turnNumber(), turn.playerAction(),
                turn.narrative(), json.write(turn.choices()), json.write(turn.statePatch()),
                turn.worldTimeBefore(), turn.worldTimeAfter(), turn.engineCode(), turn.engineVersion(),
                turn.stateSchemaVersion(), turn.promptVersion(), turn.provider(), turn.model(), turn.credentialSource(),
                turn.promptTokens(), turn.completionTokens(), turn.cacheHitTokens(), turn.cacheMissTokens(),
                turn.latencyMs(), turn.releaseVersion(), Timestamp.from(turn.createdAt()));
    }

    public Optional<StoredTurn> findByRequestId(String saveId, String requestId) {
        return jdbc.query("""
                select * from story_turn where save_id=? and request_id=?
                """, (row, index) -> map(row), saveId, requestId).stream().findFirst();
    }

    public List<StoredTurn> recent(String saveId, int maximum) {
        int limit = Math.max(1, Math.min(20, maximum));
        return jdbc.query("""
                select * from story_turn where save_id=? order by turn_number desc limit ?
                """, (row, index) -> map(row), saveId, limit).reversed();
    }

    private StoredTurn map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new StoredTurn(
                row.getString("id"), row.getString("save_id"), row.getString("request_id"),
                row.getInt("turn_number"), row.getString("player_action"), row.getString("narrative_text"),
                json.read(row.getString("choices_json")), json.read(row.getString("state_patch_json")),
                row.getLong("world_time_before"), row.getLong("world_time_after"),
                row.getString("engine_code"), row.getInt("engine_version"),
                row.getObject("state_schema_version", Integer.class), row.getString("prompt_version"),
                row.getString("provider"), row.getString("model"), row.getString("credential_source"),
                row.getInt("prompt_tokens"), row.getInt("completion_tokens"),
                row.getInt("cache_hit_tokens"), row.getInt("cache_miss_tokens"), row.getLong("latency_ms"),
                row.getString("release_version"), row.getTimestamp("created_at").toInstant());
    }
}
