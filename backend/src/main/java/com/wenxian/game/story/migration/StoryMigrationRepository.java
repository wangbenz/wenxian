package com.wenxian.game.story.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.PreviewReport;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.TargetCorrection;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.TargetEntity;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.TargetMemory;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.TargetSnapshot;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.TargetTurn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.wenxian.game.story.migration.V014MigrationBatchModels.MigrationTaskItem;

/**
 * 统一命卷迁移的专用写入与回读边界。它只写入 story 候选表和迁移审计表，
 * 不修改 V0.14 旧表；所有方法必须由上层迁移服务置于同一事务内。
 */
@Repository
public class StoryMigrationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public StoryMigrationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<MigrationRecord> findBySource(String sourceSaveId) {
        return jdbc.query("""
                select id, source_save_id, target_save_id, status, before_hash, after_hash, error_message
                from story_save_migration where source_engine='V014' and source_save_id=?
                """, (row, index) -> new MigrationRecord(row.getString("id"), row.getString("source_save_id"),
                row.getString("target_save_id"), row.getString("status"), row.getString("before_hash"),
                row.getString("after_hash"), row.getString("error_message")), sourceSaveId).stream().findFirst();
    }

    /**
     * 查询指定账号已经切换到通用表的 V0.14 源命卷。兼容适配器用它隐藏旧表副本，
     * 防止稳定命卷列表同时出现迁移前后两份角色。
     */
    public Set<String> activatedSourceIdsForUser(String userId) {
        return jdbc.queryForList("""
                select migration.source_save_id
                from story_save_migration migration
                join v014_game_save source_save on source_save.id=migration.source_save_id
                where migration.source_engine='V014' and migration.status='SUCCESS'
                  and source_save.user_id=?
                """, String.class, userId).stream().collect(Collectors.toUnmodifiableSet());
    }

    /** 按状态汇总 V0.14 迁移任务，未知状态也会原样展示，避免管理台把异常数据吞掉。 */
    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.queryForList("""
                select status,count(*) as total from story_save_migration
                where source_engine='V014' group by status order by status
                """).forEach(row -> counts.put(String.valueOf(row.get("status")),
                ((Number) row.get("total")).longValue()));
        return Map.copyOf(counts);
    }

    /** 读取最近迁移任务供管理台复核，不包含剧情正文和完整状态 JSON。 */
    public List<MigrationTaskItem> recentTasks(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                select source_save_id,target_save_id,status,before_hash,after_hash,error_message,
                       started_at,completed_at
                from story_save_migration where source_engine='V014'
                order by coalesce(completed_at,started_at) desc,id desc limit ?
                """, (row, index) -> new MigrationTaskItem(
                row.getString("source_save_id"), row.getString("target_save_id"), row.getString("status"),
                row.getString("before_hash"), row.getString("after_hash"), row.getString("error_message"),
                row.getTimestamp("started_at") == null ? null : row.getTimestamp("started_at").toInstant(),
                row.getTimestamp("completed_at") == null ? null : row.getTimestamp("completed_at").toInstant()),
                safeLimit);
    }

    public void insertRunning(String migrationId, PreviewReport report) {
        jdbc.update("""
                insert into story_save_migration(
                    id,source_engine,source_save_id,target_engine,target_save_id,source_schema_version,
                    target_schema_version,status,before_hash,after_hash,error_message,started_at,completed_at
                ) values (?,'V014',?,'LIGHT_NARRATIVE',null,?,?,'RUNNING',?,null,null,?,null)
                """, migrationId, report.sourceSaveId(), report.sourceSchemaVersion(), report.targetSchemaVersion(),
                report.beforeHash(), Timestamp.from(Instant.now()));
    }

    /** 将预演生成的完整候选快照写入不可玩的 MIGRATING 命卷。 */
    public void insertCandidate(PreviewReport report) {
        TargetSnapshot target = report.target();
        Instant createdAt = report.sourceCreatedAt();
        Instant updatedAt = report.sourceUpdatedAt();
        String worldCode = target.coreState().path("world").path("code").asText("UNKNOWN");
        jdbc.update("""
                insert into story_save(
                    id,user_id,slot_no,active_slot_no,status,world_code,engine_code,engine_version,state_schema_version,
                    source_type,source_edition,source_save_id,parent_save_id,state_json,revision,turn_number,
                    created_release,last_played_release,created_at,updated_at
                ) values (?,?,?,null,'MIGRATING',?,'LIGHT_NARRATIVE',1,?,'MIGRATED_V014','V0.14',?,null,?,1,?,
                    'V0.14','V0.14',?,?)
                """, report.targetSaveId(), report.userId(), report.slotNo(), worldCode,
                report.targetSchemaVersion(), report.sourceSaveId(), write(target.coreState()),
                report.stateTurnNumber(), Timestamp.from(createdAt), Timestamp.from(updatedAt));

        for (TargetTurn turn : target.turns()) insertTurn(report.targetSaveId(), turn);
        for (TargetEntity entity : target.entities()) insertEntity(report.targetSaveId(), entity, updatedAt);
        for (TargetMemory memory : target.memories()) insertMemory(report.targetSaveId(), memory, updatedAt);
        jdbc.update("""
                insert into story_conversation(
                    save_id,rolling_summary,summarized_through_turn,summary_schema_version,updated_at
                ) values (?,?,?,1,?)
                """, report.targetSaveId(), target.rollingSummary(), target.summarizedThroughTurn(),
                Timestamp.from(updatedAt));
        for (TargetCorrection correction : target.corrections()) insertCorrection(report.targetSaveId(), correction);
    }

    public TargetSnapshot readCandidate(String targetSaveId) {
        JsonNode coreState = jdbc.queryForObject("select state_json from story_save where id=?",
                (row, index) -> read(row.getString(1)), targetSaveId);
        List<TargetEntity> entities = jdbc.query("""
                select * from story_entity where save_id=? order by id
                """, (row, index) -> new TargetEntity(row.getString("id"), row.getString("entity_type"),
                row.getString("name"), row.getString("status"), row.getBoolean("visible"),
                row.getString("summary"), read(row.getString("attributes_json")), row.getInt("first_turn"),
                row.getInt("last_changed_turn")), targetSaveId);
        List<TargetMemory> memories = jdbc.query("""
                select * from story_memory where save_id=? order by id
                """, (row, index) -> new TargetMemory(row.getString("id"), row.getString("memory_type"),
                row.getString("importance"), row.getString("title"), row.getString("summary"),
                row.getInt("occurred_turn"), row.getLong("world_time"), row.getString("status")), targetSaveId);
        List<TargetTurn> turns = jdbc.query("""
                select * from story_turn where save_id=? order by turn_number,id
                """, (row, index) -> new TargetTurn(row.getString("id"), row.getString("request_id"),
                row.getInt("turn_number"), row.getString("player_action"), row.getString("narrative_text"),
                read(row.getString("choices_json")), read(row.getString("state_patch_json")),
                row.getLong("world_time_before"), row.getLong("world_time_after"), row.getString("engine_code"),
                row.getInt("engine_version"), row.getString("prompt_version"), row.getString("provider"),
                row.getString("model"), row.getString("credential_source"), row.getInt("prompt_tokens"),
                row.getInt("completion_tokens"), row.getInt("cache_hit_tokens"), row.getInt("cache_miss_tokens"),
                row.getLong("latency_ms"), row.getString("release_version"),
                row.getTimestamp("created_at").toInstant()), targetSaveId);
        List<TargetCorrection> corrections = jdbc.query("""
                select * from story_state_correction where save_id=? order by created_at,id
                """, (row, index) -> new TargetCorrection(row.getString("id"), row.getString("user_id"),
                row.getString("instruction_text"), row.getString("correction_type"),
                read(row.getString("before_json")), read(row.getString("after_json")),
                row.getTimestamp("created_at").toInstant()), targetSaveId);
        Conversation conversation = jdbc.query("""
                select rolling_summary,summarized_through_turn from story_conversation where save_id=?
                """, (row, index) -> new Conversation(row.getString("rolling_summary"),
                row.getInt("summarized_through_turn")), targetSaveId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("候选命卷缺少上下文摘要"));
        return new TargetSnapshot(coreState, List.copyOf(entities), List.copyOf(memories), List.copyOf(turns),
                List.copyOf(corrections), conversation.summary(), conversation.summarizedThroughTurn());
    }

    public void markReady(String migrationId, String targetSaveId, String afterHash) {
        int changed = jdbc.update("""
                update story_save_migration set target_save_id=?,status='READY',after_hash=?,error_message=null
                where id=? and status='RUNNING'
                """, targetSaveId, afterHash, migrationId);
        if (changed != 1) throw new IllegalStateException("迁移任务无法进入待激活状态");
    }

    public void markHashMismatch(String migrationId, String reason) {
        jdbc.update("""
                update story_save_migration set status='HASH_MISMATCH',error_message=?,completed_at=? where id=?
                """, reason, Timestamp.from(Instant.now()), migrationId);
    }

    public void activate(String migrationId, String targetSaveId, String targetStatus) {
        Integer activeSlotNo = jdbc.queryForObject("""
                select min(candidate.slot_no) from (
                    select 1 slot_no union all select 2 union all select 3 union all select 4
                ) candidate
                where not exists (
                    select 1 from story_save occupied
                    where occupied.user_id=(select user_id from story_save where id=?)
                      and occupied.deleted_at is null and occupied.active_slot_no=candidate.slot_no
                )
                """, Integer.class, targetSaveId);
        if (activeSlotNo == null) {
            throw new IllegalStateException("当前账号没有可用命卷槽位，迁移候选无法激活");
        }
        int saveChanged = jdbc.update("""
                update story_save set status=?,active_slot_no=?,updated_at=?
                where id=? and status='MIGRATING' and deleted_at is null
                """, targetStatus, activeSlotNo, Timestamp.from(Instant.now()), targetSaveId);
        int auditChanged = jdbc.update("""
                update story_save_migration set status='SUCCESS',error_message=null,completed_at=?
                where id=? and status='READY'
                """, Timestamp.from(Instant.now()), migrationId);
        if (saveChanged != 1 || auditChanged != 1) {
            throw new IllegalStateException("候选命卷状态已变化，本次激活已取消");
        }
    }

    private void insertTurn(String saveId, TargetTurn turn) {
        jdbc.update("""
                insert into story_turn(
                    id,save_id,request_id,turn_number,player_action,narrative_text,choices_json,state_patch_json,
                    world_time_before,world_time_after,engine_code,engine_version,prompt_version,provider,model,
                    credential_source,prompt_tokens,completion_tokens,cache_hit_tokens,cache_miss_tokens,latency_ms,
                    release_version,created_at
                ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, turn.id(), saveId, turn.requestId(), turn.turnNumber(), turn.playerAction(), turn.narrative(),
                write(turn.choices()), write(turn.statePatch()), turn.worldTimeBefore(), turn.worldTimeAfter(),
                turn.engineCode(), turn.engineVersion(), turn.promptVersion(), turn.provider(), turn.model(),
                turn.credentialSource(), turn.promptTokens(), turn.completionTokens(), turn.cacheHitTokens(),
                turn.cacheMissTokens(), turn.latencyMs(), turn.releaseVersion(), Timestamp.from(turn.createdAt()));
    }

    private void insertEntity(String saveId, TargetEntity entity, Instant timestamp) {
        jdbc.update("""
                insert into story_entity(
                    id,save_id,entity_type,name,status,visible,summary,attributes_json,first_turn,last_changed_turn,
                    created_at,updated_at
                ) values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, entity.id(), saveId, entity.type(), entity.name(), entity.status(), entity.visible(),
                entity.summary(), write(entity.attributes()), entity.firstTurn(), entity.lastChangedTurn(),
                Timestamp.from(timestamp), Timestamp.from(timestamp));
    }

    private void insertMemory(String saveId, TargetMemory memory, Instant timestamp) {
        jdbc.update("""
                insert into story_memory(
                    id,save_id,memory_type,importance,title,summary,occurred_turn,world_time,status,created_at
                ) values (?,?,?,?,?,?,?,?,?,?)
                """, memory.id(), saveId, memory.type(), memory.importance(), memory.title(), memory.summary(),
                memory.occurredTurn(), memory.worldTime(), memory.status(), Timestamp.from(timestamp));
    }

    private void insertCorrection(String saveId, TargetCorrection correction) {
        jdbc.update("""
                insert into story_state_correction(
                    id,save_id,user_id,instruction_text,correction_type,before_json,after_json,created_at
                ) values (?,?,?,?,?,?,?,?)
                """, correction.id(), saveId, correction.userId(), correction.instruction(),
                correction.correctionType(), write(correction.before()), write(correction.after()),
                Timestamp.from(correction.createdAt()));
    }

    private String write(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("迁移候选 JSON 无法保存", error);
        }
    }

    private JsonNode read(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("迁移候选 JSON 无法回读", error);
        }
    }

    /** 迁移审计表中的当前任务事实。 */
    public record MigrationRecord(
            String id,
            String sourceSaveId,
            String targetSaveId,
            String status,
            String beforeHash,
            String afterHash,
            String errorMessage
    ) {}

    private record Conversation(String summary, int summarizedThroughTurn) {}
}
