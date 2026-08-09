package com.wenxian.game.story.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 统一命卷关键记忆仓储。记忆是可追加的长期事实；运行时只读取最近的有效窗口，
 * 更早记录仍保留在数据库中，不因 Prompt 窗口缩小而删除。
 */
@Repository
public class StoryMemoryRepository {
    private final JdbcTemplate jdbc;

    public StoryMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<StoredMemory> recentActive(String saveId, int maximum) {
        int limit = Math.max(1, Math.min(maximum, 80));
        return jdbc.query("""
                select id,memory_type,title,summary,occurred_turn,world_time,importance
                from story_memory where save_id=? and status='ACTIVE'
                order by occurred_turn desc,created_at desc limit ?
                """, (row, index) -> new StoredMemory(row.getString("id"), row.getString("memory_type"),
                row.getString("title"), row.getString("summary"), row.getInt("occurred_turn"),
                row.getLong("world_time"), row.getString("importance")), saveId, limit).reversed();
    }

    public boolean exists(String saveId, String memoryId) {
        Integer count = jdbc.queryForObject("select count(*) from story_memory where save_id=? and id=?",
                Integer.class, saveId, memoryId);
        return count != null && count > 0;
    }

    public void insert(String saveId, StoredMemory memory) {
        jdbc.update("""
                insert into story_memory(
                    id,save_id,memory_type,importance,title,summary,occurred_turn,world_time,status,created_at
                ) values (?,?,?,?,?,?,?,?,'ACTIVE',?)
                """, memory.id(), saveId, memory.memoryType(), memory.importance(), memory.title(), memory.summary(),
                memory.occurredTurn(), memory.worldTime(), Timestamp.from(Instant.now()));
    }

    /** 运行时读取的有效记忆事实。 */
    public record StoredMemory(String id, String memoryType, String title, String summary, int occurredTurn,
                               long worldTime, String importance) {}
}
