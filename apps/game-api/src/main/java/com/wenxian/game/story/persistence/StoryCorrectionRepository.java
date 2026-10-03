package com.wenxian.game.story.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * 统一命卷玩家修正审计仓储。修正记录与命卷 revision 必须在同一上层事务写入，
 * 这里只保存修正前后事实，不解释玩家指令。
 */
@Repository
public class StoryCorrectionRepository {
    private final JdbcTemplate jdbc;
    private final StoryJsonCodec json;

    public StoryCorrectionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.json = new StoryJsonCodec(mapper);
    }

    public void insert(String userId, String saveId, String instruction, String type, int stateSchemaVersion,
                       JsonNode before, JsonNode after) {
        jdbc.update("""
                insert into story_state_correction(
                    id,save_id,user_id,instruction_text,correction_type,state_schema_version,
                    before_json,after_json,created_at
                ) values (?,?,?,?,?,?,?,?,?)
                """, "story-correction-" + UUID.randomUUID(), saveId, userId, instruction, type,
                stateSchemaVersion, json.write(before), json.write(after), Timestamp.from(Instant.now()));
    }
}
