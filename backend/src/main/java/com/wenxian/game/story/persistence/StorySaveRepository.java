package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 统一命卷主表仓储。它是核心快照、引擎选择、生命周期和 revision 的数据库边界；
 * 多表原子提交由上层应用服务开启事务，本仓储不会静默重试乐观锁冲突。
 */
@Repository
public class StorySaveRepository {
    private final JdbcTemplate jdbc;
    private final StoryJsonCodec json;

    public StorySaveRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.json = new StoryJsonCodec(mapper);
    }

    public void insert(NewSave save) {
        Instant createdAt = save.createdAt() == null ? Instant.now() : save.createdAt();
        jdbc.update("""
                insert into story_save(
                    id, user_id, slot_no, active_slot_no, status, world_code, engine_code, engine_version,
                    state_schema_version, source_type, source_edition, source_save_id, parent_save_id,
                    state_json, revision, turn_number, created_release, last_played_release, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
                """, save.id(), save.userId(), save.slotNo(), save.activeSlotNo(), save.status(), save.worldCode(),
                save.engineCode(), save.engineVersion(), save.stateSchemaVersion(), save.sourceType(),
                save.sourceEdition(), save.sourceSaveId(), save.parentSaveId(), json.write(save.state()),
                save.turnNumber(), save.createdRelease(), save.lastPlayedRelease(),
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    public Optional<StoredSave> findOwned(String userId, String saveId) {
        return jdbc.query("""
                select * from story_save
                where id=? and user_id=? and deleted_at is null
                  and status not in ('MIGRATING','MIGRATION_FAILED')
                """, (row, index) -> map(row), saveId, userId).stream().findFirst();
    }

    public List<StoredSave> listOwned(String userId) {
        return jdbc.query("""
                select * from story_save
                where user_id=? and deleted_at is null and active_slot_no is not null
                  and status in ('ACTIVE','ENDED')
                order by active_slot_no asc, updated_at desc
                """, (row, index) -> map(row), userId);
    }

    /** 统计账号当前仍可游玩的命卷；逻辑删除记录不占存活上限。 */
    public int countActive(String userId) {
        Integer value = jdbc.queryForObject("""
                select count(*) from story_save where user_id=? and deleted_at is null and active_slot_no is not null
                """, Integer.class, userId);
        return value == null ? 0 : value;
    }

    /**
     * 为新命卷分配只增不复用的展示序号。调用方必须先锁定全局命卷上限配置并处于同一事务，
     * 防止并发创建拿到相同序号；已封存命卷保留原序号，不能被新角色覆盖。
     */
    public int nextSlotNo(String userId) {
        Integer value = jdbc.queryForObject("""
                select coalesce(max(slot_no), 0) + 1 from story_save where user_id=?
                """, Integer.class, userId);
        return value == null ? 1 : value;
    }

    /**
     * 返回当前账号最小的空闲可玩槽位。槽位是玩家界面的固定位置，删除后允许复用；
     * slot_no 仍保留为只增历史序号，二者不能混用。
     */
    public int nextActiveSlotNo(String userId, int maximum) {
        List<Integer> occupied = jdbc.queryForList("""
                select active_slot_no from story_save
                where user_id=? and deleted_at is null and active_slot_no is not null
                """, Integer.class, userId);
        for (int candidate = 1; candidate <= maximum; candidate++) {
            if (!occupied.contains(candidate)) return candidate;
        }
        throw new OptimisticLockingFailureException("当前账号没有可用命卷槽位");
    }

    /** 将可玩命卷标记为玩家删除；保留审计数据，但玩家接口从此不再返回。 */
    public void logicalDelete(String userId, String saveId) {
        int changed = jdbc.update("""
                update story_save
                set active_slot_no=null, deleted_at=?, deleted_by='PLAYER', delete_reason='PLAYER_DELETE',
                    revision=revision+1, updated_at=?
                where id=? and user_id=? and status in ('ACTIVE','ENDED') and deleted_at is null
                """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), saveId, userId);
        if (changed != 1) {
            throw new OptimisticLockingFailureException("统一命卷不存在、已删除或归属不符");
        }
    }

    /**
     * 使用 revision 更新核心快照。返回新的 revision；若命卷已变化则抛出冲突，
     * 调用方必须重新读取，不能覆盖或自动套用旧补丁。
     */
    public long updateState(String userId, String saveId, long expectedRevision, JsonNode state,
                            int stateSchemaVersion, int turnNumber, String worldCode,
                            String status, String lastPlayedRelease) {
        int changed = jdbc.update("""
                update story_save
                set state_json=?, state_schema_version=?, turn_number=?, world_code=?, status=?,
                    last_played_release=?, revision=revision+1, updated_at=?
                where id=? and user_id=? and revision=? and deleted_at is null
                """, json.write(state), stateSchemaVersion, turnNumber, worldCode, status,
                lastPlayedRelease, Timestamp.from(Instant.now()), saveId, userId, expectedRevision);
        if (changed != 1) {
            throw new OptimisticLockingFailureException("统一命卷已经在其他请求中变化");
        }
        return expectedRevision + 1;
    }

    private StoredSave map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new StoredSave(
                row.getString("id"), row.getString("user_id"), row.getInt("slot_no"),
                row.getInt("active_slot_no"), row.getString("status"), row.getString("world_code"), row.getString("engine_code"),
                row.getInt("engine_version"), row.getInt("state_schema_version"),
                row.getString("source_type"), row.getString("source_edition"), row.getString("source_save_id"),
                row.getString("parent_save_id"), json.read(row.getString("state_json")),
                row.getLong("revision"), row.getInt("turn_number"), row.getString("created_release"),
                row.getString("last_played_release"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                instant(row, "deleted_at"), row.getString("deleted_by"), row.getString("delete_reason"));
    }

    private Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
