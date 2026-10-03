package com.wenxian.admin.save;

import com.wenxian.admin.audit.AdminOperationLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 同账号可用命卷上限的管理用例。
 *
 * <p>调低上限前锁定配置行并检查现有账号，任何超限都会拒绝保存；本服务不会自动归档、删除或选择
 * 玩家的命卷。该规则属于运营玩法配置，与推演点价格和玩家自备服务无关。</p>
 */
@Service
public class ActiveSaveRuleApplicationService {
    private static final String CONFIG_KEY = "max_active_saves";

    private final JdbcTemplate jdbc;
    private final AdminOperationLogRepository auditLogs;

    public ActiveSaveRuleApplicationService(JdbcTemplate jdbc, AdminOperationLogRepository auditLogs) {
        this.jdbc = jdbc;
        this.auditLogs = auditLogs;
    }

    public ActiveSaveRuleView rule() {
        int maximum = configInt(CONFIG_KEY, 4);
        long activeSaveCount = number("""
                select count(*) from story_save where deleted_at is null and active_slot_no is not null
                """);
        long usersWithMultipleSaves = number("""
                select count(*) from (
                    select user_id from story_save
                    where deleted_at is null and active_slot_no is not null
                    group by user_id having count(*)>1
                ) multi_save_users
                """);
        return new ActiveSaveRuleView(maximum, activeSaveCount, usersWithMultipleSaves);
    }

    @Transactional
    public ActiveSaveRuleView update(int maximumActiveSaves, String actor) {
        if (maximumActiveSaves < 1 || maximumActiveSaves > 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同账号可用命卷上限只能设置为 1 至 4");
        }
        lockConfig();
        long overLimitUsers = number("""
                select count(*) from (
                    select user_id from story_save
                    where deleted_at is null and active_slot_no is not null
                    group by user_id having count(*)>?
                ) over_limit_users
                """, maximumActiveSaves);
        if (overLimitUsers > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "当前有 " + overLimitUsers + " 个账号的可用命卷超过新上限，请先处理后再调低");
        }
        putConfig(maximumActiveSaves);
        auditLogs.append("UPDATE_ACTIVE_SAVE_RULE", "管理员 " + safeActor(actor)
                + " 将同账号可用命卷上限调整为 " + maximumActiveSaves + "；未修改玩家现有命卷");
        return rule();
    }

    private void lockConfig() {
        if (jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, index) -> row.getString(1), CONFIG_KEY).isEmpty()) {
            jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                    CONFIG_KEY, "4", Timestamp.from(Instant.now()));
        }
        jdbc.queryForObject("select config_value from gameplay_config where config_key=? for update",
                String.class, CONFIG_KEY);
    }

    private int configInt(String key, int fallback) {
        String value = jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, index) -> row.getString(1), key).stream().findFirst().orElse(Integer.toString(fallback));
        try {
            return Math.max(1, Math.min(4, Integer.parseInt(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void putConfig(int maximumActiveSaves) {
        jdbc.update("update gameplay_config set config_value=?,updated_at=? where config_key=?",
                Integer.toString(maximumActiveSaves), Timestamp.from(Instant.now()), CONFIG_KEY);
    }

    private long number(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private String safeActor(String actor) {
        return actor == null || actor.isBlank() ? "admin-console" : actor.strip();
    }

    public record ActiveSaveRuleView(int maximumActiveSaves, long activeSaveCount,
                                     long usersWithMultipleSaves) { }
}
