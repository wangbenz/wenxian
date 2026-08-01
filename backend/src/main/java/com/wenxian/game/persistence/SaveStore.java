package com.wenxian.game.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.GameState;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class SaveStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SaveStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void ensureArchiveEndReasonColumn() {
        try {
            jdbcTemplate.query("select end_reason from game_life_archive where 1=0",
                    (row, index) -> row.getString(1));
        } catch (DataAccessException missing) {
            jdbcTemplate.execute("alter table game_life_archive add column end_reason varchar(24) not null default 'LEGACY'");
        }
        ensureSaveSlotSchema();
    }

    private void ensureSaveSlotSchema() {
        try {
            jdbcTemplate.query("select slot_no from game_save where 1=0", (row, index) -> row.getInt(1));
        } catch (DataAccessException missing) {
            jdbcTemplate.execute("alter table game_save add column slot_no smallint not null default 1");
        }
        IndexMetadata metadata = jdbcTemplate.execute((ConnectionCallback<IndexMetadata>) connection -> {
            String tableName = "game_save";
            try (var tables = connection.getMetaData().getTables(
                    connection.getCatalog(), connection.getSchema(), null, new String[]{"TABLE"})) {
                while (tables.next()) {
                    if ("game_save".equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                        tableName = tables.getString("TABLE_NAME");
                        break;
                    }
                }
            }
            Map<String, List<String>> indexesByName = new LinkedHashMap<>();
            try (var indexes = connection.getMetaData().getIndexInfo(
                    connection.getCatalog(), connection.getSchema(), tableName, true, false)) {
                while (indexes.next()) {
                    String name = indexes.getString("INDEX_NAME");
                    String column = indexes.getString("COLUMN_NAME");
                    if (name != null && column != null) {
                        indexesByName.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column.toLowerCase());
                    }
                }
            }
            return new IndexMetadata(connection.getMetaData().getDatabaseProductName(), indexesByName);
        });
        Map<String, List<String>> uniqueIndexes = metadata == null ? Map.of() : metadata.indexes();
        uniqueIndexes.forEach((name, columns) -> {
            if (columns.equals(List.of("user_id")) && name.matches("[A-Za-z0-9_$-]+")) {
                dropLegacyUserUniqueIndex(metadata.product(), name);
            }
        });
        boolean hasSlotIndex = uniqueIndexes.values().stream()
                .anyMatch(columns -> columns.size() == 2 && columns.contains("user_id") && columns.contains("slot_no"));
        if (!hasSlotIndex) {
            try {
                jdbcTemplate.execute("create unique index uk_game_save_user_slot on game_save(user_id, slot_no)");
            } catch (DataAccessException concurrentOrExisting) {
                Integer duplicates = jdbcTemplate.queryForObject("""
                        select count(*) from (
                          select user_id, slot_no from game_save group by user_id, slot_no having count(*) > 1
                        ) duplicated
                        """, Integer.class);
                if (duplicates != null && duplicates > 0) throw concurrentOrExisting;
            }
        }
    }

    private void dropLegacyUserUniqueIndex(String databaseProduct, String indexName) {
        if (databaseProduct != null && databaseProduct.toLowerCase().contains("h2")) {
            List<String> constraints = jdbcTemplate.query("""
                    select distinct tc.constraint_name
                    from information_schema.table_constraints tc
                    join information_schema.constraint_column_usage ccu
                      on ccu.constraint_catalog=tc.constraint_catalog
                     and ccu.constraint_schema=tc.constraint_schema
                     and ccu.constraint_name=tc.constraint_name
                    where upper(tc.table_name)='GAME_SAVE'
                      and tc.constraint_type='UNIQUE'
                    group by tc.constraint_name
                    having count(*)=1 and max(upper(ccu.column_name))='USER_ID'
                    """, (row, ignored) -> row.getString(1));
            for (String constraint : constraints) {
                if (constraint.matches("[A-Za-z0-9_$-]+")) {
                    jdbcTemplate.execute("alter table game_save drop constraint " + constraint);
                }
            }
            return;
        }
        jdbcTemplate.execute("drop index " + indexName + " on game_save");
    }

    private record IndexMetadata(String product, Map<String, List<String>> indexes) {}

    public StoredSave find(String saveId) {
        return jdbcTemplate.query("select id, user_id, slot_no, state_json, revision, updated_at from game_save where id = ?",
                (result, row) -> map(result.getString("id"), result.getString("user_id"),
                        result.getInt("slot_no"),
                        result.getString("state_json"), result.getLong("revision"),
                        result.getTimestamp("updated_at").toInstant()), saveId)
                .stream().findFirst().orElse(null);
    }

    public StoredSave findOwned(String saveId, String userId) {
        StoredSave save = find(saveId);
        return save != null && save.userId.equals(userId) ? save : null;
    }

    public StoredSave findCurrentForUser(String userId) {
        return jdbcTemplate.query("""
                        select id, user_id, slot_no, state_json, revision, updated_at
                        from game_save where user_id = ? order by updated_at desc limit 1
                        """,
                (result, row) -> map(result.getString("id"), result.getString("user_id"),
                        result.getInt("slot_no"),
                        result.getString("state_json"), result.getLong("revision"),
                        result.getTimestamp("updated_at").toInstant()), userId)
                .stream().findFirst().orElse(null);
    }

    public List<StoredSave> findAllCurrentForUser(String userId) {
        return jdbcTemplate.query("""
                        select id, user_id, slot_no, state_json, revision, updated_at
                        from game_save where user_id = ? order by slot_no
                        """,
                (result, row) -> map(result.getString("id"), result.getString("user_id"),
                        result.getInt("slot_no"), result.getString("state_json"),
                        result.getLong("revision"), result.getTimestamp("updated_at").toInstant()), userId);
    }

    public int countCurrentForUser(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from game_save where user_id=?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    public int nextAvailableSlot(String userId, int maximum) {
        List<Integer> used = jdbcTemplate.query(
                "select slot_no from game_save where user_id=? order by slot_no",
                (row, index) -> row.getInt(1), userId);
        for (int candidate = 1; candidate <= maximum; candidate++) {
            if (!used.contains(candidate)) return candidate;
        }
        return 0;
    }

    public synchronized StoredSave put(String saveId, String userId, GameState state, long revision) {
        return put(saveId, userId, 1, state, revision);
    }

    public synchronized StoredSave put(String saveId, String userId, int slotNo, GameState state, long revision) {
        Instant now = Instant.now();
        String json = write(state);
        Integer count = jdbcTemplate.queryForObject("select count(*) from game_save where id = ?", Integer.class, saveId);
        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    update game_save set state_json = ?, revision = ?, updated_at = ?
                    where id = ? and user_id = ?
                    """, json, revision, Timestamp.from(now), saveId, userId);
        } else {
            jdbcTemplate.update("""
                    insert into game_save(id, user_id, slot_no, state_json, revision, updated_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, saveId, userId, slotNo, json, revision, Timestamp.from(now));
        }
        return new StoredSave(saveId, userId, slotNo, state, revision, now);
    }

    public synchronized void delete(String saveId, String userId) {
        jdbcTemplate.update("delete from game_save where id = ? and user_id = ?", saveId, userId);
    }

    @Transactional
    public synchronized void archiveAndDelete(StoredSave save) {
        archiveAndDelete(save, "RESTARTED");
    }

    @Transactional
    public synchronized void archiveAndDelete(StoredSave save, String endReason) {
        String archiveId = "life-" + java.util.UUID.randomUUID();
        String normalizedEndReason = switch (endReason == null ? "" : endReason.toUpperCase()) {
            case "DEAD" -> "DEAD";
            case "ABANDONED" -> "ABANDONED";
            default -> "RESTARTED";
        };
        jdbcTemplate.update("""
                insert into game_life_archive(id, user_id, original_save_id, state_json, end_reason, ended_at)
                values (?, ?, ?, ?, ?, ?)
                """, archiveId, save.userId, save.id,
                write(save.state), normalizedEndReason, Timestamp.from(Instant.now()));
        jdbcTemplate.update("""
                insert into game_life_turn_archive(
                    id, life_archive_id, original_save_id, turn_number, player_action,
                    narrative_json, choices_json, state_delta_json, model, response_id,
                    prompt_tokens, completion_tokens, latency_ms, created_at)
                select concat('life-turn-', id), ?, save_id, turn_number, player_action,
                    narrative_json, choices_json, state_delta_json, model, response_id,
                    prompt_tokens, completion_tokens, latency_ms, created_at
                from game_turn where save_id = ?
                """, archiveId, save.id);
        delete(save.id, save.userId);
    }

    private StoredSave map(String id, String userId, int slotNo, String json, long revision, Instant updatedAt) {
        try {
            return new StoredSave(id, userId, slotNo, objectMapper.readValue(json, GameState.class), revision, updatedAt);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("游戏存档数据损坏", error);
        }
    }

    private String write(GameState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法序列化游戏存档", error);
        }
    }

    public static class StoredSave {
        public String id;
        public String userId;
        public int slotNo;
        public GameState state;
        public long revision;
        public Instant updatedAt;

        public StoredSave() {}

        public StoredSave(String id, String userId, GameState state, long revision, Instant updatedAt) {
            this(id, userId, 1, state, revision, updatedAt);
        }

        public StoredSave(String id, String userId, int slotNo, GameState state, long revision, Instant updatedAt) {
            this.id = id;
            this.userId = userId;
            this.slotNo = slotNo;
            this.state = state;
            this.revision = revision;
            this.updatedAt = updatedAt;
        }
    }
}
