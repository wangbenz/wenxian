package com.wenxian.game.story.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 可信回合诊断仓储。
 *
 * <p>该表只保存上下文来源清单、结构化提案概要、审阅结果和最终提交概要，不保存完整 Prompt、
 * 密钥或剧情正文。写入使用独立事务，不能跟随命卷事务回滚，也不能反过来阻断玩家回合。</p>
 */
@Repository
public class StoryTurnDiagnosticRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public StoryTurnDiagnosticRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 按命卷和请求号幂等保存当前诊断快照。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(StoryTurnDiagnosticService.Snapshot value) {
        String context = json(value.contextManifest());
        String contract = json(value.actionContract());
        String proposals = json(value.proposalTrace());
        String reviews = json(value.reviewTrace());
        String commit = json(value.commitSummary());
        int updated = jdbc.update("""
                update story_turn_diagnostic set
                    turn_number=?, expected_revision=?, prompt_version=?, status=?, current_stage=?,
                    context_manifest_json=?, action_contract_json=?, proposal_trace_json=?,
                    review_trace_json=?, commit_summary_json=?, failure_code=?, failure_detail=?, updated_at=?
                where save_id=? and request_id=?
                """, value.turnNumber(), value.expectedRevision(), value.promptVersion(), value.status(),
                value.currentStage(), context, contract, proposals, reviews, commit,
                text(value.failureCode(), 64), text(value.failureDetail(), 1000),
                Timestamp.from(value.updatedAt()), value.saveId(), value.requestId());
        if (updated == 1) return;
        jdbc.update("""
                insert into story_turn_diagnostic(
                    id, save_id, request_id, turn_number, expected_revision, prompt_version,
                    status, current_stage, context_manifest_json, action_contract_json,
                    proposal_trace_json, review_trace_json, commit_summary_json,
                    failure_code, failure_detail, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, value.id(), value.saveId(), value.requestId(), value.turnNumber(), value.expectedRevision(),
                value.promptVersion(), value.status(), value.currentStage(), context, contract, proposals,
                reviews, commit, text(value.failureCode(), 64), text(value.failureDetail(), 1000),
                Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()));
    }

    /**
     * 读取一次回合请求的最小诊断状态，供客户端在网关超时后确认后台是否仍在处理。
     * 本方法不返回 Prompt、提案正文或内部审阅内容，不能作为命卷事实来源。
     */
    public Optional<RequestState> findRequestState(String saveId, String requestId) {
        return jdbc.query("""
                select status,current_stage,failure_code,updated_at
                from story_turn_diagnostic where save_id=? and request_id=?
                """, (row, index) -> new RequestState(row.getString("status"),
                row.getString("current_stage"), row.getString("failure_code"),
                row.getTimestamp("updated_at").toInstant()), saveId, requestId).stream().findFirst();
    }

    private String json(JsonNode value) {
        try {
            return mapper.writeValueAsString(value == null ? mapper.createObjectNode() : value);
        } catch (Exception error) {
            throw new IllegalArgumentException("回合诊断无法序列化", error);
        }
    }

    private String text(String value, int limit) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)sk-[a-z0-9_-]{12,}", "<REDACTED_KEY>");
        return safe.substring(0, Math.min(limit, safe.length()));
    }

    /** 只用于恢复查询的脱敏诊断状态。 */
    public record RequestState(String status, String currentStage, String failureCode, Instant updatedAt) {
    }
}
