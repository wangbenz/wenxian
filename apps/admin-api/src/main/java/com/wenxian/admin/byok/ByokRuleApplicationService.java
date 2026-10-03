package com.wenxian.admin.byok;

import com.wenxian.admin.audit.AdminOperationLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 玩家自备推演服务的管理用例。
 *
 * <p>这里只管理平台总开关和脱敏统计，不读取、解密或返回任何玩家凭证。供应商是否允许用户密钥仍由
 * 模型库配置负责，避免一个页面同时改动两份权威配置。</p>
 */
@Service
public class ByokRuleApplicationService {
    private static final String ENABLED_KEY = "custom_api_enabled";
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final AdminOperationLogRepository auditLogs;

    public ByokRuleApplicationService(JdbcTemplate jdbc, AdminOperationLogRepository auditLogs) {
        this.jdbc = jdbc;
        this.auditLogs = auditLogs;
    }

    public ByokRuleView rule() {
        boolean enabled = Boolean.parseBoolean(configValue(ENABLED_KEY, "true"));
        long activeCredentialUsers = number(
                "select count(distinct user_id) from user_ai_provider_credential where status='ACTIVE'");
        long invalidCredentialUsers = number(
                "select count(distinct user_id) from user_ai_provider_credential where status<>'ACTIVE'");
        Instant todayStart = LocalDate.now(REPORTING_ZONE).atStartOfDay(REPORTING_ZONE).toInstant();
        long todayCustomTurns = number(
                "select count(*) from story_turn where credential_source='USER' and created_at>=?",
                Timestamp.from(todayStart));
        return new ByokRuleView(enabled, activeCredentialUsers, invalidCredentialUsers, todayCustomTurns);
    }

    @Transactional
    public ByokRuleView update(boolean enabled, String actor) {
        putConfig(ENABLED_KEY, Boolean.toString(enabled));
        auditLogs.append("UPDATE_BYOK_RULE", "管理员 " + safeActor(actor) + (enabled
                ? " 开启玩家自备推演服务总开关" : " 关闭玩家自备推演服务总开关；已保存凭证不删除"));
        return rule();
    }

    private String configValue(String key, String fallback) {
        return jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, index) -> row.getString(1), key).stream().findFirst().orElse(fallback);
    }

    private void putConfig(String key, String value) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbc.update("update gameplay_config set config_value=?,updated_at=? where config_key=?",
                value, now, key);
        if (updated == 0) {
            jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                    key, value, now);
        }
    }

    private long number(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private String safeActor(String actor) {
        return actor == null || actor.isBlank() ? "admin-console" : actor.strip();
    }

    public record ByokRuleView(boolean enabled, long activeCredentialUsers,
                               long invalidCredentialUsers, long todayCustomTurns) { }
}
