package com.wenxian.game.story.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * V0.14 旧表的只读数据入口。该类只声明 SELECT，不依赖任何 story 写仓储，
 * 用于正式迁移前核对源命卷、回合、上下文摘要和修正记录。
 */
@Repository
public class V014MigrationSourceReader {
    private final JdbcTemplate jdbc;

    public V014MigrationSourceReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取单份旧命卷及其关联事实；未找到时直接终止预演。 */
    public SourceSave read(String saveId) {
        SourceSaveHeader header = jdbc.query("""
                        select id, user_id, slot_no, world_code, state_schema_version, state_json,
                               revision, status, created_at, updated_at
                        from v014_game_save where id=?
                        """, (row, index) -> new SourceSaveHeader(
                        row.getString("id"), row.getString("user_id"), row.getInt("slot_no"),
                        row.getString("world_code"), row.getInt("state_schema_version"),
                        row.getString("state_json"), row.getLong("revision"), row.getString("status"),
                        row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant()),
                saveId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("没有找到 V0.14 命卷：" + saveId));

        List<SourceTurn> turns = jdbc.query("""
                        select id, request_id, turn_number, player_action, narrative_text, choices_json,
                               memory_patch_json, world_time_before, world_time_after, provider, model,
                               credential_source, prompt_tokens, completion_tokens, latency_ms, created_at
                        from v014_game_turn where save_id=? order by turn_number, created_at, id
                        """, (row, index) -> new SourceTurn(
                        row.getString("id"), row.getString("request_id"), row.getInt("turn_number"),
                        row.getString("player_action"), row.getString("narrative_text"),
                        row.getString("choices_json"), row.getString("memory_patch_json"),
                        row.getLong("world_time_before"), row.getLong("world_time_after"),
                        row.getString("provider"), row.getString("model"), row.getString("credential_source"),
                        row.getInt("prompt_tokens"), row.getInt("completion_tokens"),
                        row.getLong("latency_ms"), row.getTimestamp("created_at").toInstant()), saveId);

        SourceConversation conversation = jdbc.query("""
                        select rolling_summary, summarized_through_turn, updated_at
                        from v014_conversation where save_id=?
                        """, (row, index) -> new SourceConversation(
                        row.getString("rolling_summary"), row.getInt("summarized_through_turn"),
                        row.getTimestamp("updated_at").toInstant()), saveId)
                .stream().findFirst().orElse(null);
        List<SourceCorrection> corrections = jdbc.query("""
                        select id, user_id, instruction_text, before_json, after_json, created_at
                        from v014_state_correction where save_id=? order by created_at, id
                        """, (row, index) -> new SourceCorrection(
                        row.getString("id"), row.getString("user_id"), row.getString("instruction_text"),
                        row.getString("before_json"), row.getString("after_json"),
                        row.getTimestamp("created_at").toInstant()), saveId);
        return new SourceSave(header, turns, conversation, corrections);
    }

    /** 旧命卷主表中不含关联集合的权威行。 */
    public record SourceSaveHeader(
            String id,
            String userId,
            int slotNo,
            String worldCode,
            int stateSchemaVersion,
            String stateJson,
            long revision,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /** 旧回合事实，用于校验顺序、时间和目标回合哈希。 */
    public record SourceTurn(
            String id,
            String requestId,
            int turnNumber,
            String playerAction,
            String narrative,
            String choicesJson,
            String statePatchJson,
            long worldTimeBefore,
            long worldTimeAfter,
            String provider,
            String model,
            String credentialSource,
            int promptTokens,
            int completionTokens,
            long latencyMs,
            Instant createdAt
    ) {}

    /** 旧滚动摘要；它是可重建上下文，不覆盖命卷核心事实。 */
    public record SourceConversation(String rollingSummary, int summarizedThroughTurn, Instant updatedAt) {}

    /** 旧命卷修正记录，预演时保留原始前后 JSON 并验证其可读性。 */
    public record SourceCorrection(
            String id,
            String userId,
            String instruction,
            String beforeJson,
            String afterJson,
            Instant createdAt
    ) {}

    /** 一次预演所需的全部只读源数据。 */
    public record SourceSave(
            SourceSaveHeader header,
            List<SourceTurn> turns,
            SourceConversation conversation,
            List<SourceCorrection> corrections
    ) {}
}
