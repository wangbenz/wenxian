package com.wenxian.game.story.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 统一命卷滚动摘要仓储。摘要只用于缩短模型上下文，不是权威游戏事实；
 * 更新失败必须让同一回合事务回滚，避免摘要声称包含尚未提交的剧情。
 */
@Repository
public class StoryConversationRepository {
    private final JdbcTemplate jdbc;

    public StoryConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String summary(String saveId) {
        return jdbc.query("select rolling_summary from story_conversation where save_id=?",
                (row, index) -> row.getString(1), saveId).stream().findFirst().orElse("");
    }

    public void updateSummary(String saveId, String summary, int summarizedThroughTurn) {
        int changed = jdbc.update("""
                update story_conversation set rolling_summary=?,summarized_through_turn=?,updated_at=?
                where save_id=?
                """, summary == null ? "" : summary, summarizedThroughTurn,
                Timestamp.from(Instant.now()), saveId);
        if (changed != 1) throw new IllegalStateException("统一命卷缺少上下文摘要记录");
    }
}
