package com.wenxian.v2.game.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class TurnRepository {
    private static final List<String> ACTIVE_STATUSES = List.of(
            "QUEUED", "INTERPRETING", "RESOLVING", "WRITING", "COMMITTING");

    private final JdbcTemplate jdbc;

    public TurnRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Operation findByRequest(String saveId, String requestId) {
        return jdbc.query("""
                        select * from v2_turn_operation where save_id=? and request_id=?
                        """, (row, index) -> operation(row), saveId, requestId)
                .stream().findFirst().orElse(null);
    }

    public Operation findOwned(String operationId, String userId) {
        return jdbc.query("""
                        select * from v2_turn_operation where id=? and user_id=?
                        """, (row, index) -> operation(row), operationId, userId)
                .stream().findFirst().orElse(null);
    }

    public Operation lock(String operationId) {
        return jdbc.query("select * from v2_turn_operation where id=? for update",
                (row, index) -> operation(row), operationId).stream().findFirst().orElse(null);
    }

    public int activeForUser(String userId) {
        String placeholders = String.join(",", ACTIVE_STATUSES.stream().map(ignored -> "?").toList());
        Object[] args = new Object[ACTIVE_STATUSES.size() + 1];
        args[0] = userId;
        for (int i = 0; i < ACTIVE_STATUSES.size(); i++) args[i + 1] = ACTIVE_STATUSES.get(i);
        Integer count = jdbc.queryForObject(
                "select count(*) from v2_turn_operation where user_id=? and operation_status in (" + placeholders + ")",
                Integer.class, args);
        return count == null ? 0 : count;
    }

    public void insert(Operation operation) {
        jdbc.update("""
                insert into v2_turn_operation(id,request_id,save_id,user_id,expected_state_version,
                                              operation_status,operation_stage,action_type,action_text,
                                              action_payload,created_at)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, operation.id(), operation.requestId(), operation.saveId(), operation.userId(),
                operation.expectedStateVersion(), operation.status(), operation.stage(), operation.actionType(),
                operation.actionText(), operation.actionPayload(), Timestamp.from(operation.createdAt()));
    }

    public void updateStage(String operationId, String status, String stage, Instant startedAt) {
        jdbc.update("""
                update v2_turn_operation set operation_status=?,operation_stage=?,
                  started_at=coalesce(started_at,?) where id=?
                """, status, stage, Timestamp.from(startedAt), operationId);
    }

    public void succeed(String operationId, String turnId, Instant completedAt) {
        jdbc.update("""
                update v2_turn_operation set operation_status='SUCCEEDED',operation_stage='DONE',
                  result_turn_id=?,completed_at=? where id=?
                """, turnId, Timestamp.from(completedAt), operationId);
    }

    public void reject(String operationId, String code, String message, Instant completedAt) {
        jdbc.update("""
                update v2_turn_operation set operation_status='REJECTED',operation_stage='DONE',
                  public_error_code=?,public_error_message=?,completed_at=? where id=?
                """, code, message, Timestamp.from(completedAt), operationId);
    }

    public void fail(String operationId, String reference, Instant completedAt) {
        jdbc.update("""
                update v2_turn_operation set operation_status='FAILED',operation_stage='DONE',
                  public_error_code='TURN_FAILED',public_error_message='命卷暂时无法继续处理，现有状态没有改变。',
                  problem_reference=?,completed_at=? where id=?
                """, reference, Timestamp.from(completedAt), operationId);
    }

    public void insertTurn(Turn turn) {
        jdbc.update("""
                insert into v2_turn(id,operation_id,save_id,turn_number,player_action,action_intent,
                                    outcome_code,outcome_summary,narrative_text,choices_payload,
                                    world_minute_before,world_minute_after,state_version_before,
                                    state_version_after,created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, turn.id(), turn.operationId(), turn.saveId(), turn.turnNumber(), turn.playerAction(),
                turn.actionIntent(), turn.outcomeCode(), turn.outcomeSummary(), turn.narrativeText(),
                turn.choicesPayload(), turn.worldMinuteBefore(), turn.worldMinuteAfter(),
                turn.stateVersionBefore(), turn.stateVersionAfter(), Timestamp.from(turn.createdAt()));
    }

    public void insertChange(DomainChange change) {
        jdbc.update("""
                insert into v2_domain_change(id,turn_id,save_id,change_order,domain_type,entity_id,
                                             change_type,before_payload,after_payload,reason,created_at)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, change.id(), change.turnId(), change.saveId(), change.order(), change.domainType(),
                change.entityId(), change.changeType(), change.beforePayload(), change.afterPayload(),
                change.reason(), Timestamp.from(change.createdAt()));
    }

    public List<TurnSummary> recentTurns(String saveId, int limit) {
        return jdbc.query("""
                        select id,turn_number,player_action,outcome_code,outcome_summary,narrative_text,
                               world_minute_before,world_minute_after,state_version_after,created_at
                        from v2_turn where save_id=? order by turn_number desc limit ?
                        """, (row, index) -> new TurnSummary(row.getString("id"), row.getInt("turn_number"),
                        row.getString("player_action"), row.getString("outcome_code"),
                        row.getString("outcome_summary"), row.getString("narrative_text"),
                        row.getLong("world_minute_before"), row.getLong("world_minute_after"),
                        row.getLong("state_version_after"), row.getTimestamp("created_at").toInstant()),
                saveId, Math.max(1, Math.min(limit, 100)));
    }

    private Operation operation(java.sql.ResultSet row) throws java.sql.SQLException {
        Timestamp started = row.getTimestamp("started_at");
        Timestamp completed = row.getTimestamp("completed_at");
        return new Operation(row.getString("id"), row.getString("request_id"), row.getString("save_id"),
                row.getString("user_id"), row.getLong("expected_state_version"),
                row.getString("operation_status"), row.getString("operation_stage"),
                row.getString("action_type"), row.getString("action_text"), row.getString("action_payload"),
                row.getString("result_turn_id"), row.getString("public_error_code"),
                row.getString("public_error_message"), row.getString("problem_reference"),
                row.getTimestamp("created_at").toInstant(), started == null ? null : started.toInstant(),
                completed == null ? null : completed.toInstant());
    }

    public record Operation(
            String id,
            String requestId,
            String saveId,
            String userId,
            long expectedStateVersion,
            String status,
            String stage,
            String actionType,
            String actionText,
            String actionPayload,
            String resultTurnId,
            String errorCode,
            String errorMessage,
            String problemReference,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    public record Turn(
            String id,
            String operationId,
            String saveId,
            int turnNumber,
            String playerAction,
            String actionIntent,
            String outcomeCode,
            String outcomeSummary,
            String narrativeText,
            String choicesPayload,
            long worldMinuteBefore,
            long worldMinuteAfter,
            long stateVersionBefore,
            long stateVersionAfter,
            Instant createdAt
    ) {
    }

    public record DomainChange(
            String id,
            String turnId,
            String saveId,
            int order,
            String domainType,
            String entityId,
            String changeType,
            String beforePayload,
            String afterPayload,
            String reason,
            Instant createdAt
    ) {
    }

    public record TurnSummary(
            String id,
            int turnNumber,
            String playerAction,
            String outcomeCode,
            String outcomeSummary,
            String narrativeText,
            long worldMinuteBefore,
            long worldMinuteAfter,
            long stateVersionAfter,
            Instant createdAt
    ) {
    }
}
