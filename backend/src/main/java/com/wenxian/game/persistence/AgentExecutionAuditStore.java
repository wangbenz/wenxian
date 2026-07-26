package com.wenxian.game.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class AgentExecutionAuditStore {
    private final JdbcTemplate jdbcTemplate;

    public AgentExecutionAuditStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String saveId, int turn, String model, int attempt, String status,
                       String detail, String responseId, long latencyMs) {
        jdbcTemplate.update("""
                insert into agent_execution
                (id, save_id, turn_number, model, attempt_number, status, detail, response_id, latency_ms, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, "agent-" + UUID.randomUUID(), saveId, turn, model, attempt, status,
                detail == null ? "" : detail.substring(0, Math.min(2000, detail.length())),
                responseId, latencyMs, Timestamp.from(Instant.now()));
    }
}
