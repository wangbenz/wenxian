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
    private static final int FULL_HISTORY_CHARACTER_BUDGET = 36_000;
    private static final int RECENT_HISTORY_CHARACTER_BUDGET = 22_000;
    private static final int RECENT_MESSAGE_LIMIT = 16;
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
        String conversationId = ensure(saveId, "deepseek-v4-pro");
        List<MessageRow> rows = jdbc.query("""
                        select role, content from agent_message where conversation_id = ? order by id asc
                        """, (row, index) -> new MessageRow(row.getString("role"), row.getString("content")),
                conversationId);
        int total = rows.stream().mapToInt(row -> row.content().length()).sum();
        List<Map<String, Object>> result = new ArrayList<>();
        if (total <= FULL_HISTORY_CHARACTER_BUDGET) {
            rows.forEach(row -> result.add(Map.of("role", row.role(), "content", row.content())));
            return result;
        }

        String summary = buildSummary(state);
        jdbc.update("update agent_conversation set summary = ?, updated_at = ? where id = ?",
                summary, Timestamp.from(Instant.now()), conversationId);
        result.add(Map.of("role", "user", "content", "【此前命卷摘要】\n" + summary));
        int retained = 0;
        List<MessageRow> recent = new ArrayList<>();
        for (int index = rows.size() - 1;
             index >= 0 && retained < RECENT_HISTORY_CHARACTER_BUDGET && recent.size() < RECENT_MESSAGE_LIMIT;
             index--) {
            MessageRow row = rows.get(index);
            recent.addFirst(row);
            retained += row.content().length();
        }
        recent.forEach(row -> result.add(Map.of("role", row.role(), "content", row.content())));
        return result;
    }

    public void recordTurn(String requestId, String saveId, String conversationId, int turnNumber,
                           String action, GameState before, GameState after, AgentTurn turn, String model) {
        jdbc.update("""
                insert into game_turn(id, save_id, conversation_id, turn_number, player_action,
                                      narrative_json, choices_json, state_delta_json,
                                      world_time_before, world_time_after, model, response_id,
                                      prompt_tokens, completion_tokens, latency_ms, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, requestId, saveId, conversationId, turnNumber, action,
                json(turn.result().narrative()), json(turn.result().choices()), json(turn.result()),
                absoluteTime(before), absoluteTime(after), model, turn.responseId(),
                turn.promptTokens(), turn.completionTokens(), turn.latencyMs(), Timestamp.from(Instant.now()));
    }

    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法保存Agent会话", error);
        }
    }

    private String buildSummary(GameState state) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("player", state.player);
        summary.put("worldTime", Map.of("year", state.worldYear, "day", state.day, "minute", state.minute));
        summary.put("location", state.currentLocation);
        summary.put("abilities", state.abilities);
        summary.put("inventory", state.inventory);
        summary.put("characters", state.characters);
        summary.put("threads", state.threads);
        summary.put("recentEvents", state.events.stream().limit(12).toList());
        return json(summary);
    }

    private long absoluteTime(GameState state) {
        return (long) state.worldYear * 360L * 1440L + (long) Math.max(0, state.day - 1) * 1440L + state.minute;
    }

    private record MessageRow(String role, String content) {}
}
