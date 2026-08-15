package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.EntityAlias;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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

    /**
     * 读取命卷的全部实体事实，包括已移出行囊、停止关注和暂时不可见的记录。
     *
     * <p>该方法用于权威状态归并与事实追查；玩家当前页面仍应使用 {@link #listVisible(String)}。
     * 不得用可见列表代替权威读取，否则同一稳定实体再次进入剧情时可能被错误地当作新实体插入。</p>
     */
    public List<Entity> listAll(String saveId) {
        return jdbc.query("""
                select * from story_entity where save_id=?
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
        Entity current = jdbc.query("select * from story_entity where id=? and save_id=? and visible=true",
                (row, index) -> map(row), entityId, saveId).stream().findFirst().orElse(null);
        if (current == null) return 0;
        ObjectNode attributes = current.attributes() instanceof ObjectNode object
                ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
        ObjectNode trusted = attributes.path(StoryProjectionPersistenceMapper.TRUSTED_ENTITY) instanceof ObjectNode object
                ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
        if ("ITEM".equalsIgnoreCase(current.type())) {
            attributes.put("inPossession", false);
            trusted.put("inPossession", false);
        } else if ("CHARACTER".equalsIgnoreCase(current.type())) {
            attributes.put("tracked", false);
        }
        attributes.set(StoryProjectionPersistenceMapper.TRUSTED_ENTITY, trusted);
        return jdbc.update("""
                update story_entity set visible=false, status=?, attributes_json=?, last_changed_turn=?, updated_at=?
                where id=? and save_id=? and visible=true
                """, status, json.write(attributes), changedTurn, Timestamp.from(Instant.now()), entityId, saveId);
    }

    /** 读取当前命卷仍可用于实体解析的全部长期别名。 */
    public List<EntityAlias> listActiveAliases(String saveId) {
        return jdbc.query("""
                select * from story_entity_alias where save_id=? and active=true
                order by entity_id, created_at
                """, (row, index) -> new EntityAlias(
                row.getString("id"), row.getString("save_id"), row.getString("entity_id"),
                row.getString("alias_name"), row.getString("normalized_alias"), row.getString("alias_type"),
                (Integer) row.getObject("source_turn"), row.getBoolean("active"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant()), saveId);
    }

    /**
     * 幂等保存一个已经通过事实归并的长期别名。相同实体的相同别名重复出现时只恢复可用状态，
     * 不生成重复记录；不同实体允许同名，由引用编译器识别为歧义并停止自动绑定。
     */
    public void saveAlias(String saveId, String entityId, String aliasName, String aliasType,
                          Integer sourceTurn, Instant now) {
        String normalized = normalizeAlias(aliasName);
        if (normalized.isBlank()) return;
        Instant timestamp = now == null ? Instant.now() : now;
        try {
            jdbc.update("""
                    insert into story_entity_alias(
                        id, save_id, entity_id, alias_name, normalized_alias, alias_type,
                        source_turn, active, created_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, true, ?, ?)
                    """, "entity-alias-" + UUID.randomUUID(), saveId, entityId, aliasName.strip(), normalized,
                    aliasType == null || aliasType.isBlank() ? "HISTORICAL_NAME" : aliasType.strip(), sourceTurn,
                    Timestamp.from(timestamp), Timestamp.from(timestamp));
        } catch (DuplicateKeyException duplicate) {
            jdbc.update("""
                    update story_entity_alias set alias_name=?, alias_type=?, source_turn=coalesce(source_turn, ?),
                        active=true, updated_at=?
                    where save_id=? and entity_id=? and normalized_alias=?
                    """, aliasName.strip(), aliasType == null || aliasType.isBlank()
                            ? "HISTORICAL_NAME" : aliasType.strip(), sourceTurn, Timestamp.from(timestamp),
                    saveId, entityId, normalized);
        }
    }

    /** 别名只做 Unicode、大小写和空白归一，不使用业务词表或模糊相似度猜测实体身份。 */
    public static String normalizeAlias(String value) {
        if (value == null || value.isBlank()) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
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
