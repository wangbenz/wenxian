package com.wenxian.game.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.service.GameVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Repository
public class GameAnalyticsStore {
    private static final Logger log = LoggerFactory.getLogger(GameAnalyticsStore.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final GameVersionService gameVersionService;

    public GameAnalyticsStore(JdbcTemplate jdbc, ObjectMapper objectMapper, GameVersionService gameVersionService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.gameVersionService = gameVersionService;
    }

    public void record(String userId, String saveId, String eventType, Map<String, ?> metadata) {
        try {
            jdbc.update("""
                    insert into game_analytics_event
                    (id, user_id, save_id, game_version, event_type, metadata_json, created_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, "event-" + UUID.randomUUID(), userId, saveId, gameVersionService.current(),
                    eventType, metadata(metadata), Timestamp.from(Instant.now()));
        } catch (Exception error) {
            // Analytics must never block or roll back a player's game action.
            log.warn("Failed to record analytics event {} for save {}", eventType, saveId, error);
        }
    }

    private String metadata(Map<String, ?> value) throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(value == null ? Map.of() : value);
        return json.substring(0, Math.min(1000, json.length()));
    }
}
