package com.wenxian.game.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.GameState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class SaveStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SaveStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public StoredSave find(String saveId) {
        return jdbcTemplate.query("select id, user_id, state_json, revision, updated_at from game_save where id = ?",
                (result, row) -> map(result.getString("id"), result.getString("user_id"),
                        result.getString("state_json"), result.getLong("revision"),
                        result.getTimestamp("updated_at").toInstant()), saveId)
                .stream().findFirst().orElse(null);
    }

    public StoredSave findOwned(String saveId, String userId) {
        StoredSave save = find(saveId);
        return save != null && save.userId.equals(userId) ? save : null;
    }

    public StoredSave findCurrentForUser(String userId) {
        return jdbcTemplate.query("""
                        select id, user_id, state_json, revision, updated_at
                        from game_save where user_id = ? order by updated_at desc limit 1
                        """,
                (result, row) -> map(result.getString("id"), result.getString("user_id"),
                        result.getString("state_json"), result.getLong("revision"),
                        result.getTimestamp("updated_at").toInstant()), userId)
                .stream().findFirst().orElse(null);
    }

    public synchronized StoredSave put(String saveId, String userId, GameState state, long revision) {
        Instant now = Instant.now();
        String json = write(state);
        Integer count = jdbcTemplate.queryForObject("select count(*) from game_save where id = ?", Integer.class, saveId);
        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    update game_save set state_json = ?, revision = ?, updated_at = ?
                    where id = ? and user_id = ?
                    """, json, revision, Timestamp.from(now), saveId, userId);
        } else {
            jdbcTemplate.update("""
                    insert into game_save(id, user_id, state_json, revision, updated_at)
                    values (?, ?, ?, ?, ?)
                    """, saveId, userId, json, revision, Timestamp.from(now));
        }
        return new StoredSave(saveId, userId, state, revision, now);
    }

    public synchronized void delete(String saveId, String userId) {
        jdbcTemplate.update("delete from game_save where id = ? and user_id = ?", saveId, userId);
    }

    @Transactional
    public synchronized void archiveAndDelete(StoredSave save) {
        archiveAndDelete(save, "RESTARTED");
    }

    @Transactional
    public synchronized void archiveAndDelete(StoredSave save, String endReason) {
        String archiveId = "life-" + java.util.UUID.randomUUID();
        jdbcTemplate.update("""
                insert into game_life_archive(id, user_id, original_save_id, state_json, ended_at)
                values (?, ?, ?, ?, ?)
                """, archiveId, save.userId, save.id,
                write(save.state), Timestamp.from(Instant.now()));
        jdbcTemplate.update("""
                insert into game_life_turn_archive(
                    id, life_archive_id, original_save_id, turn_number, player_action,
                    narrative_json, choices_json, state_delta_json, model, response_id,
                    prompt_tokens, completion_tokens, latency_ms, created_at)
                select concat('life-turn-', id), ?, save_id, turn_number, player_action,
                    narrative_json, choices_json, state_delta_json, model, response_id,
                    prompt_tokens, completion_tokens, latency_ms, created_at
                from game_turn where save_id = ?
                """, archiveId, save.id);
        delete(save.id, save.userId);
    }

    private StoredSave map(String id, String userId, String json, long revision, Instant updatedAt) {
        try {
            return new StoredSave(id, userId, objectMapper.readValue(json, GameState.class), revision, updatedAt);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("游戏存档数据损坏", error);
        }
    }

    private String write(GameState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化游戏存档", error);
        }
    }

    public static class StoredSave {
        public String id;
        public String userId;
        public GameState state;
        public long revision;
        public Instant updatedAt;

        public StoredSave() {}

        public StoredSave(String id, String userId, GameState state, long revision, Instant updatedAt) {
            this.id = id;
            this.userId = userId;
            this.state = state;
            this.revision = revision;
            this.updatedAt = updatedAt;
        }
    }
}
