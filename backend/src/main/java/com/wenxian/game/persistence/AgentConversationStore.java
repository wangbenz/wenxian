package com.wenxian.game.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.engine.AgentTurn;
import com.wenxian.game.model.GameState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class AgentConversationStore {
    private static final int RECENT_MEMORY_EVENTS = 10;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AgentConversationStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public String ensure(String saveId, String model) {
        List<String> ids = jdbc.query("select id from agent_conversation where save_id = ?",
                (row, index) -> row.getString(1), saveId);
        if (!ids.isEmpty()) return ids.getFirst();
        String id = "conversation-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into agent_conversation(id, save_id, model, summary, message_count, created_at, updated_at)
                values (?, ?, ?, ?, 0, ?, ?)
                """, id, saveId, model, "", Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    public void append(String conversationId, int turn, String role, String content,
                       String responseId, int promptTokens, int completionTokens) {
        jdbc.update("""
                insert into agent_message(conversation_id, turn_number, role, content, response_id,
                                          prompt_tokens, completion_tokens, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, conversationId, turn, role, content, responseId, promptTokens, completionTokens,
                Timestamp.from(Instant.now()));
        jdbc.update("""
                update agent_conversation set message_count = message_count + 1, updated_at = ? where id = ?
                """, Timestamp.from(Instant.now()), conversationId);
    }

    public List<Map<String, Object>> loadHistory(String saveId, GameState state) {
        String conversationId = ensure(saveId, "deepseek-v4-flash");
        String summary = buildStoryMemory(state);
        jdbc.update("update agent_conversation set summary = ?, updated_at = ? where id = ?",
                summary, Timestamp.from(Instant.now()), conversationId);
        if (summary.isBlank()) return List.of();
        return List.of(Map.of("role", "user", "content", "【此前命卷提要】\n" + summary));
    }

    public void recordTurn(String requestId, String saveId, String conversationId, int turnNumber,
                           String action, GameState before, GameState after, AgentTurn turn, String model) {
        String recordedModel = turn.model() == null || turn.model().isBlank() ? model : turn.model();
        if (turn.credentialSource() != null && !turn.credentialSource().isBlank()) {
            recordedModel += "@" + turn.credentialSource();
        }
        jdbc.update("""
                insert into game_turn(id, save_id, conversation_id, turn_number, player_action,
                                      narrative_json, choices_json, state_delta_json,
                                      world_time_before, world_time_after, model, response_id,
                                      prompt_tokens, completion_tokens, latency_ms, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, requestId, saveId, conversationId, turnNumber, action,
                json(turn.result().narrative()), json(turn.result().choices()), json(turn.result()),
                absoluteTime(before), absoluteTime(after), recordedModel, turn.responseId(),
                turn.promptTokens(), turn.completionTokens(), turn.latencyMs(), Timestamp.from(Instant.now()));
    }

    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法保存Agent会话", error);
        }
    }

    private String buildStoryMemory(GameState state) {
        if (state == null || state.events == null || state.events.size() <= 1) return "";
        Map<String, Object> summary = new LinkedHashMap<>();
        // Event 0 describes the current chapter and is already present in currentNarrative.
        List<GameState.ChronicleEvent> older = state.events.subList(1, state.events.size());
        List<GameState.ChronicleEvent> selected = new ArrayList<>();
        if (older.size() > RECENT_MEMORY_EVENTS) {
            GameState.ChronicleEvent origin = older.getLast();
            selected.add(origin);
            selected.addAll(older.subList(0, RECENT_MEMORY_EVENTS));
        } else {
            selected.addAll(older);
        }
        summary.put("pastEvents", selected.stream().map(event -> Map.of(
                "title", safe(event.title),
                "detail", safe(event.detail),
                "at", event.at,
                "kind", safe(event.kind)
        )).toList());
        return json(summary);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private long absoluteTime(GameState state) {
        return (long) state.worldYear * 360L * 1440L + (long) Math.max(0, state.day - 1) * 1440L + state.minute;
    }
}
