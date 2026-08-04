package com.wenxian.game.v014.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.v014.model.V014ApiModels.SaveSummary;
import com.wenxian.game.v014.model.V014State;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class V014SaveStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public V014SaveStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public int nextSlot(String userId, int maximum) {
        Integer active = jdbc.queryForObject(
                "select count(*) from v014_game_save where user_id=? and status='ACTIVE'", Integer.class, userId);
        if (active != null && active >= maximum) return 0;
        List<Integer> occupied = jdbc.query("select slot_no from v014_game_save where user_id=? order by slot_no",
                (row, index) -> row.getInt(1), userId);
        int slot = 1;
        while (occupied.contains(slot)) slot++;
        return slot;
    }

    public void insert(String userId, int slotNo, V014State state) {
        Instant now = Instant.now();
        jdbc.update("""
                insert into v014_game_save
                (id, user_id, slot_no, world_code, state_schema_version, state_json, revision, status, created_at, updated_at)
                values (?, ?, ?, ?, 14, ?, 1, 'ACTIVE', ?, ?)
                """, state.saveId(), userId, slotNo, state.world().code(), json(state),
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("insert into v014_conversation(save_id, rolling_summary, summarized_through_turn, updated_at) values (?, '', 0, ?)",
                state.saveId(), Timestamp.from(now));
    }

    public StoredSave requireOwned(String userId, String saveId) {
        return jdbc.query("""
                        select state_json, revision, slot_no, status, updated_at from v014_game_save
                        where id=? and user_id=?
                        """, (row, index) -> new StoredSave(read(row.getString("state_json")),
                        row.getLong("revision"), row.getInt("slot_no"), row.getString("status"),
                        row.getTimestamp("updated_at").toInstant()), saveId, userId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("没有找到这份命卷"));
    }

    public List<SaveSummary> list(String userId) {
        return jdbc.query("""
                select id, slot_no, state_json, status, updated_at from v014_game_save
                where user_id=? order by status='ACTIVE' desc, updated_at desc
                """, (row, index) -> {
            V014State state = read(row.getString("state_json"));
            return new SaveSummary(row.getString("id"), row.getInt("slot_no"), state.world().name(),
                    state.protagonist().name(), state.protagonist().race(), state.protagonist().realm(),
                    state.turnNumber(), row.getString("status"), row.getTimestamp("updated_at").toInstant());
        }, userId);
    }

    public long update(String saveId, long expectedRevision, V014State state) {
        int changed = jdbc.update("""
                update v014_game_save set state_json=?, revision=revision+1, status=?, updated_at=?
                where id=? and revision=?
                """, json(state), state.status(), Timestamp.from(Instant.now()), saveId, expectedRevision);
        if (changed != 1) throw new OptimisticLockingFailureException("命卷已在其他请求中变化");
        return expectedRevision + 1;
    }

    public void archive(String userId, String saveId) {
        int changed = jdbc.update("update v014_game_save set status='ARCHIVED', updated_at=? where id=? and user_id=? and status='ACTIVE'",
                Timestamp.from(Instant.now()), saveId, userId);
        if (changed != 1) throw new IllegalArgumentException("命卷不存在或已经封存");
    }

    public void recordCorrection(String userId, String saveId, String instruction,
                                 V014State before, V014State after) {
        jdbc.update("""
                insert into v014_state_correction
                (id, save_id, user_id, instruction_text, before_json, after_json, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, "v014-fix-" + UUID.randomUUID(), saveId, userId, instruction,
                json(before), json(after), Timestamp.from(Instant.now()));
    }

    private String json(V014State state) {
        try { return mapper.writeValueAsString(state); }
        catch (JsonProcessingException error) { throw new IllegalStateException("命卷无法保存", error); }
    }

    private V014State read(String json) {
        try { return mapper.readValue(json, V014State.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("命卷数据无法读取", error); }
    }

    public record StoredSave(V014State state, long revision, int slotNo, String status, Instant updatedAt) {}
}
