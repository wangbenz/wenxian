package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 统一命卷当前实体仓储。实体写入必须和命卷 revision 更新处于同一上层事务；
 * 玩家整理记录只改变 visible 和现状，不物理删除历史身份。
 */
@Repository
public class StoryEntityRepository {
    private final JdbcTemplate jdbc;
    private final StoryJsonCodec json;

    public StoryEntityRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.json = new StoryJsonCodec(mapper);
    }

    public void insert(Entity entity) {
        Instant createdAt = entity.createdAt() == null ? Instant.now() : entity.createdAt();
        Instant updatedAt = entity.updatedAt() == null ? createdAt : entity.updatedAt();
        jdbc.update("""
                insert into story_entity(
                    id, save_id, entity_type, name, status, visible, summary, attributes_json,
                    first_turn, last_changed_turn, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entity.id(), entity.saveId(), entity.type(), entity.name(), entity.status(), entity.visible(),
                entity.summary(), json.write(entity.attributes()), entity.firstTurn(), entity.lastChangedTurn(),
                Timestamp.from(createdAt), Timestamp.from(updatedAt));
    }

    public List<Entity> listVisible(String saveId) {
        return jdbc.query("""
                select * from story_entity where save_id=? and visible=true
                order by entity_type, last_changed_turn desc, created_at
                """, (row, index) -> map(row), saveId);
    }

    public int update(Entity entity) {
        return jdbc.update("""
                update story_entity set entity_type=?, name=?, status=?, visible=?, summary=?, attributes_json=?,
                    last_changed_turn=?, updated_at=? where id=? and save_id=?
                """, entity.type(), entity.name(), entity.status(), entity.visible(), entity.summary(),
                json.write(entity.attributes()), entity.lastChangedTurn(), Timestamp.from(Instant.now()),
                entity.id(), entity.saveId());
    }

    public int hide(String saveId, String entityId, int changedTurn, String status) {
        return jdbc.update("""
                update story_entity set visible=false, status=?, last_changed_turn=?, updated_at=?
                where id=? and save_id=? and visible=true
                """, status, changedTurn, Timestamp.from(Instant.now()), entityId, saveId);
    }

    private Entity map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Entity(
                row.getString("id"), row.getString("save_id"), row.getString("entity_type"),
                row.getString("name"), row.getString("status"), row.getBoolean("visible"),
                row.getString("summary"), json.read(row.getString("attributes_json")),
                row.getInt("first_turn"), row.getInt("last_changed_turn"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
}
