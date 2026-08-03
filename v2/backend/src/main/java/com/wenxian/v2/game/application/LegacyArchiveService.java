package com.wenxian.v2.game.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.api.V2ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class LegacyArchiveService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public LegacyArchiveService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<LegacyLife> list(String userId) {
        List<LegacyLife> result = new ArrayList<>();
        result.addAll(jdbc.query("select id,state_json,updated_at from game_save where user_id=? order by updated_at desc",
                (row, index) -> toLife(row.getString("id"), "V0_13_ACTIVE", "LEGACY",
                        row.getString("state_json"), row.getTimestamp("updated_at")), userId));
        result.addAll(jdbc.query("""
                select id,state_json,end_reason,ended_at from game_life_archive
                where user_id=? order by ended_at desc
                """, (row, index) -> toLife(row.getString("id"), "V0_13_ARCHIVE",
                        row.getString("end_reason"), row.getString("state_json"), row.getTimestamp("ended_at")), userId));
        result.sort((left, right) -> right.recordedAt().compareTo(left.recordedAt()));
        return List.copyOf(result);
    }

    public List<LegacyChapter> chapters(String userId, String archiveId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Integer archived = jdbc.queryForObject(
                "select count(*) from game_life_archive where id=? and user_id=?", Integer.class, archiveId, userId);
        if (archived != null && archived > 0) {
            return jdbc.query("""
                    select turn_number,player_action,narrative_json,created_at from game_life_turn_archive
                    where life_archive_id=? order by turn_number desc limit ?
                    """, (row, index) -> new LegacyChapter(row.getInt("turn_number"),
                    row.getString("player_action"), narrative(row.getString("narrative_json")),
                    row.getTimestamp("created_at").toInstant()), archiveId, safeLimit);
        }
        Integer active = jdbc.queryForObject("select count(*) from game_save where id=? and user_id=?",
                Integer.class, archiveId, userId);
        if (active == null || active == 0) {
            throw V2ApiException.notFound("LEGACY_ARCHIVE_NOT_FOUND", "没有找到这份旧命卷档案。");
        }
        return jdbc.query("""
                select turn_number,player_action,narrative_json,created_at from game_turn
                where save_id=? order by turn_number desc limit ?
                """, (row, index) -> new LegacyChapter(row.getInt("turn_number"),
                row.getString("player_action"), narrative(row.getString("narrative_json")),
                row.getTimestamp("created_at").toInstant()), archiveId, safeLimit);
    }

    private LegacyLife toLife(String id, String source, String endReason, String stateJson, Timestamp at) {
        try {
            JsonNode state = json.readTree(stateJson);
            JsonNode player = state.path("player");
            return new LegacyLife(id, source, endReason == null ? "LEGACY" : endReason,
                    player.path("name").asText("无名旧卷"), player.path("race").asText("未知"),
                    player.path("realm").asText("境界未载"), state.path("currentLocation").asText("去向未载"),
                    state.path("turn").asInt(0), state.path("worldYear").asInt(3001),
                    state.path("day").asInt(1), state.path("minute").asInt(0),
                    at == null ? Instant.EPOCH : at.toInstant());
        } catch (Exception invalidLegacyState) {
            return new LegacyLife(id, source, endReason == null ? "LEGACY" : endReason,
                    "旧命卷", "未知", "境界未载", "去向未载", 0, 3001, 1, 0,
                    at == null ? Instant.EPOCH : at.toInstant());
        }
    }

    private String narrative(String payload) {
        try {
            JsonNode node = json.readTree(payload);
            if (node.isArray()) {
                List<String> paragraphs = new ArrayList<>();
                node.forEach(value -> {
                    if (!value.asText("").isBlank()) paragraphs.add(value.asText());
                });
                return String.join("\n\n", paragraphs);
            }
            return node.asText(payload == null ? "" : payload);
        } catch (Exception ignored) {
            return payload == null ? "" : payload;
        }
    }

    public record LegacyLife(String id, String source, String endReason, String characterName, String race,
                             String realm, String location, int turnNumber, int worldYear, int day,
                             int minuteOfDay, Instant recordedAt) {
    }

    public record LegacyChapter(int turnNumber, String playerAction, String narrative, Instant createdAt) {
    }
}
