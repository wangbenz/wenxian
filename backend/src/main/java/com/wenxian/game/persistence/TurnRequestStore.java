package com.wenxian.game.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class TurnRequestStore {
    private final JdbcTemplate jdbcTemplate;

    public TurnRequestStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean exists(String requestId, String saveId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from game_turn_request where request_id = ? and save_id = ?",
                Integer.class, requestId, saveId);
        return count != null && count > 0;
    }

    public void record(String requestId, String saveId) {
        jdbcTemplate.update("insert into game_turn_request(request_id, save_id, created_at) values (?, ?, ?)",
                requestId, saveId, Timestamp.from(Instant.now()));
    }
}
