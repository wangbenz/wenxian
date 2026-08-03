package com.wenxian.v2.game.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.ai.domain.TurnProposal.TurnChoice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class TurnRepository {
    private static final List<String> ACTIVE_STATUSES = List.of(
            "QUEUED", "INTERPRETING", "RESOLVING", "WRITING", "COMMITTING");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TurnRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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

    public Operation findActiveOwned(String userId) {
        String placeholders = String.join(",", ACTIVE_STATUSES.stream().map(ignored -> "?").toList());
        Object[] args = new Object[ACTIVE_STATUSES.size() + 1];
        args[0] = userId;
        for (int i = 0; i < ACTIVE_STATUSES.size(); i++) args[i + 1] = ACTIVE_STATUSES.get(i);
        return jdbc.query("select * from v2_turn_operation where user_id=? and operation_status in ("
                        + placeholders + ") order by created_at desc limit 1",
                (row, index) -> operation(row), args).stream().findFirst().orElse(null);
    }

    public List<String> queuedOperationIds() {
        return jdbc.query("select id from v2_turn_operation where operation_status='QUEUED' order by created_at",
                (row, index) -> row.getString(1));
    }

    public void failInterruptedOperations(Instant completedAt) {
        jdbc.update("""
                update v2_turn_operation set operation_status='FAILED',operation_stage='DONE',
                  public_error_code='TURN_INTERRUPTED',
                  public_error_message='服务更新中断了本次推演，命卷状态没有改变，可以重新提交行动。',
                  public_error_suggestions='["重新提交刚才的行动"]',
                  problem_reference='SERVICE-RESTART',completed_at=?
                where operation_status in ('INTERPRETING','RESOLVING','WRITING','COMMITTING')
                """, Timestamp.from(completedAt));
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

    public void reject(String operationId, String code, String message, List<String> suggestions,
                       Instant completedAt) {
        jdbc.update("""
                update v2_turn_operation set operation_status='REJECTED',operation_stage='DONE',
                  public_error_code=?,public_error_message=?,public_error_suggestions=?,completed_at=? where id=?
                  and operation_status not in ('SUCCEEDED','REJECTED','FAILED')
                """, code, message, encode(suggestions == null ? List.of() : suggestions),
                Timestamp.from(completedAt), operationId);
    }

    public void fail(String operationId, String reference, Instant completedAt) {
        jdbc.update("""
                update v2_turn_operation set operation_status='FAILED',operation_stage='DONE',
                  public_error_code='TURN_FAILED',public_error_message='命卷暂时无法继续处理，现有状态没有改变。',
                  problem_reference=?,completed_at=? where id=?
                  and operation_status not in ('SUCCEEDED','REJECTED','FAILED')
                """, reference, Timestamp.from(completedAt), operationId);
    }

    public boolean cancelBeforeCommit(String operationId, Instant completedAt) {
        return jdbc.update("""
                update v2_turn_operation set operation_status='FAILED',operation_stage='DONE',
                  public_error_code='TURN_CANCELLED',
                  public_error_message='管理员终止了本次尚未提交的推演，命卷状态没有改变。',
                  public_error_suggestions='["重新提交刚才的行动"]',
                  problem_reference='ADMIN-CANCELLED',completed_at=?
                where id=? and operation_status in ('QUEUED','INTERPRETING','RESOLVING','WRITING')
                """, Timestamp.from(completedAt), operationId) == 1;
    }

    public void insertTurn(Turn turn) {
        jdbc.update("""
                insert into v2_turn(id,operation_id,save_id,turn_number,player_action,action_intent,
                                    outcome_code,outcome_summary,narrative_text,choices_payload,
                                    world_minute_before,world_minute_after,state_version_before,
                                    state_version_after,model_provider,model_code,created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, turn.id(), turn.operationId(), turn.saveId(), turn.turnNumber(), turn.playerAction(),
                turn.actionIntent(), turn.outcomeCode(), turn.outcomeSummary(), turn.narrativeText(),
                turn.choicesPayload(), turn.worldMinuteBefore(), turn.worldMinuteAfter(),
                turn.stateVersionBefore(), turn.stateVersionAfter(), turn.modelProvider(), turn.modelCode(),
                Timestamp.from(turn.createdAt()));
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
        return recentTurns(saveId, null, limit);
    }

    public List<TurnSummary> recentTurns(String saveId, Integer beforeTurn, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int cursor = beforeTurn == null ? Integer.MAX_VALUE : Math.max(1, beforeTurn);
        List<TurnSummary> summaries = jdbc.query("""
                        select id,turn_number,player_action,outcome_code,outcome_summary,narrative_text,
                               choices_payload,world_minute_before,world_minute_after,state_version_after,created_at
                        from v2_turn where save_id=? and turn_number<? order by turn_number desc limit ?
                        """, (row, index) -> new TurnSummary(row.getString("id"), row.getInt("turn_number"),
                        row.getString("player_action"), row.getString("outcome_code"),
                        row.getString("outcome_summary"), row.getString("narrative_text"),
                        parseChoices(row.getString("choices_payload")),
                        row.getLong("world_minute_before"), row.getLong("world_minute_after"),
                        row.getLong("state_version_after"), List.of(),
                        row.getTimestamp("created_at").toInstant()),
                saveId, cursor, safeLimit);
        Map<String, List<VisibleChange>> changes = visibleChanges(
                summaries.stream().map(TurnSummary::id).toList());
        return summaries.stream().map(summary -> new TurnSummary(summary.id(), summary.turnNumber(),
                summary.playerAction(), summary.outcomeCode(), summary.outcomeSummary(), summary.narrativeText(),
                summary.choices(), summary.worldMinuteBefore(), summary.worldMinuteAfter(),
                summary.stateVersionAfter(), changes.getOrDefault(summary.id(), List.of()),
                summary.createdAt())).toList();
    }

    private Map<String, List<VisibleChange>> visibleChanges(List<String> turnIds) {
        if (turnIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", turnIds.stream().map(ignored -> "?").toList());
        List<VisibleChangeRow> rows = jdbc.query("""
                select turn_id,domain_type,change_type,before_payload,after_payload,reason
                from v2_domain_change where turn_id in (%s) order by turn_id,change_order
                """.formatted(placeholders), (row, index) -> new VisibleChangeRow(row.getString("turn_id"),
                new VisibleChange(row.getString("domain_type"), row.getString("change_type"),
                        row.getString("before_payload"), row.getString("after_payload"),
                        row.getString("reason"))), turnIds.toArray());
        Map<String, List<VisibleChange>> grouped = new HashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.turnId(), ignored -> new ArrayList<>()).add(row.change()));
        return grouped;
    }

    private List<TurnChoice> parseChoices(String payload) {
        if (payload == null || payload.isBlank()) return List.of();
        try {
            return objectMapper.readValue(payload, new TypeReference<>() {
            });
        } catch (JsonProcessingException invalidStoredPayload) {
            return List.of();
        }
    }

    private Operation operation(java.sql.ResultSet row) throws java.sql.SQLException {
        Timestamp started = row.getTimestamp("started_at");
        Timestamp completed = row.getTimestamp("completed_at");
        return new Operation(row.getString("id"), row.getString("request_id"), row.getString("save_id"),
                row.getString("user_id"), row.getLong("expected_state_version"),
                row.getString("operation_status"), row.getString("operation_stage"),
                row.getString("action_type"), row.getString("action_text"), row.getString("action_payload"),
                row.getString("result_turn_id"), row.getString("public_error_code"),
                row.getString("public_error_message"), parseStrings(row.getString("public_error_suggestions")),
                row.getString("problem_reference"),
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
            List<String> errorSuggestions,
            String problemReference,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    private List<String> parseStrings(String payload) {
        if (payload == null || payload.isBlank()) return List.of();
        try {
            return objectMapper.readValue(payload, new TypeReference<>() {
            });
        } catch (JsonProcessingException invalidStoredPayload) {
            return List.of();
        }
    }

    private String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Unable to encode operation data", impossible);
        }
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
            String modelProvider,
            String modelCode,
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
            List<TurnChoice> choices,
            long worldMinuteBefore,
            long worldMinuteAfter,
            long stateVersionAfter,
            List<VisibleChange> changes,
            Instant createdAt
    ) {
    }

    public record VisibleChange(
            String domainType,
            String changeType,
            String before,
            String after,
            String reason
    ) {
    }

    private record VisibleChangeRow(String turnId, VisibleChange change) {
    }
}
