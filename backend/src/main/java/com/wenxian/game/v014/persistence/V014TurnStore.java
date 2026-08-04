package com.wenxian.game.v014.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.v014.model.V014State.Choice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class V014TurnStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public V014TurnStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insert(String saveId, String requestId, int turnNumber, String action, String narrative,
                       List<Choice> choices, String patchJson, long before, long after,
                       String provider, String model, String source, int inputTokens,
                       int outputTokens, long latencyMs) {
        jdbc.update("""
                insert into v014_game_turn
                (id, save_id, request_id, turn_number, player_action, narrative_text, choices_json,
                 memory_patch_json, world_time_before, world_time_after, provider, model,
                 credential_source, prompt_tokens, completion_tokens, latency_ms, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "v014-turn-" + UUID.randomUUID(), saveId, requestId, turnNumber, action, narrative,
                json(choices), patchJson == null ? "{}" : patchJson, before, after, provider, model,
                source, inputTokens, outputTokens, latencyMs, Timestamp.from(Instant.now()));
    }

    public boolean requestExists(String saveId, String requestId) {
        Integer count = jdbc.queryForObject("select count(*) from v014_game_turn where save_id=? and request_id=?",
                Integer.class, saveId, requestId);
        return count != null && count > 0;
    }

    public List<RecentTurn> recent(String saveId, int count) {
        return jdbc.query("""
                select turn_number, player_action, narrative_text from v014_game_turn
                where save_id=? order by turn_number desc limit ?
                """, (row, index) -> new RecentTurn(row.getInt(1), row.getString(2), row.getString(3)),
                saveId, count).reversed();
    }

    public String summary(String saveId) {
        return jdbc.query("select rolling_summary from v014_conversation where save_id=?",
                (row, index) -> row.getString(1), saveId).stream().findFirst().orElse("");
    }

    public void updateSummary(String saveId, String summary, int turn) {
        jdbc.update("update v014_conversation set rolling_summary=?, summarized_through_turn=?, updated_at=? where save_id=?",
                summary == null ? "" : summary, turn, Timestamp.from(Instant.now()), saveId);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("回合数据无法保存", error); }
    }

    public record RecentTurn(int turnNumber, String action, String narrative) {}
}
