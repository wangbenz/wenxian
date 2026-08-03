package com.wenxian.v2.game.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class OriginProposalRepository {
    private final JdbcTemplate jdbc;

    public OriginProposalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String userId, String fingerprint, String candidatesJson,
                       Instant expiresAt, Instant now) {
        jdbc.update("""
                insert into v2_origin_proposal(id,user_id,request_fingerprint,candidates_json,
                                               expires_at,consumed_at,created_at)
                values (?,?,?,?,?,null,?)
                """, id, userId, fingerprint, candidatesJson, Timestamp.from(expiresAt), Timestamp.from(now));
    }

    public StoredProposal lock(String id, String userId) {
        return jdbc.query("""
                        select request_fingerprint,candidates_json,expires_at,consumed_at
                        from v2_origin_proposal where id=? and user_id=? for update
                        """, (row, index) -> new StoredProposal(row.getString("request_fingerprint"),
                        row.getString("candidates_json"), row.getTimestamp("expires_at").toInstant(),
                        row.getTimestamp("consumed_at") == null ? null : row.getTimestamp("consumed_at").toInstant()),
                id, userId).stream().findFirst().orElse(null);
    }

    public void consume(String id, String userId, Instant now) {
        int updated = jdbc.update("""
                update v2_origin_proposal set consumed_at=?
                where id=? and user_id=? and consumed_at is null
                """, Timestamp.from(now), id, userId);
        if (updated != 1) throw new IllegalStateException("Origin proposal changed before save creation");
    }

    public record StoredProposal(String fingerprint, String candidatesJson,
                                 Instant expiresAt, Instant consumedAt) {
    }
}
