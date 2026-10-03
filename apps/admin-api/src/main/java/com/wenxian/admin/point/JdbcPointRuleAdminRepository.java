package com.wenxian.admin.point;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 推演点规则的 JDBC 仓储。
 *
 * <p>迁移期继续写入 game-api 已读取的旧配置键，避免出现管理台显示一套、运行时读取另一套的双配置。
 * 玩家已建立的赠送批次和不可变流水只做汇总查询，更新规则时绝不重算或覆盖这些历史余额。</p>
 */
@Repository
public class JdbcPointRuleAdminRepository implements PointRuleAdminRepository {
    static final String OPENING_GRANT_KEY = "account_public_turn_limit";
    static final String DEPLETED_MESSAGE_KEY = "account_public_turn_limit_message";
    static final String DEFAULT_DEPLETED_MESSAGE =
            "本账号的推演点已经用完。你可以前往「设置」接入自己的推演服务继续游玩。";

    private final JdbcTemplate jdbc;

    public JdbcPointRuleAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PointRuleState state() {
        int openingGrant = configInt(OPENING_GRANT_KEY, 20, 1, 1000);
        String depletedMessage = configValue(DEPLETED_MESSAGE_KEY, DEFAULT_DEPLETED_MESSAGE);
        long totalAccounts = number("select count(*) from app_user");
        long initializedAccounts = number("""
                select count(distinct user_id) from turn_credit_grant where grant_type='LEGACY_OPENING'
                """);
        List<long[]> sums = jdbc.query("""
                select coalesce(sum(granted_count),0),
                       coalesce(sum(case when status='ACTIVE' and (expires_at is null or expires_at>?)
                                         then available_count else 0 end),0),
                       coalesce(sum(reserved_count),0),coalesce(sum(consumed_count),0)
                from turn_credit_grant
                """, (row, index) -> new long[]{row.getLong(1), row.getLong(2), row.getLong(3), row.getLong(4)},
                Timestamp.from(Instant.now()));
        long[] pointSums = sums.isEmpty() ? new long[4] : sums.getFirst();
        return new PointRuleState(openingGrant, depletedMessage, totalAccounts, initializedAccounts,
                pointSums[0], pointSums[1], pointSums[2], pointSums[3]);
    }

    @Override
    public void updateOpeningRule(int openingGrant, String depletedMessage) {
        putConfig(OPENING_GRANT_KEY, Integer.toString(openingGrant));
        putConfig(DEPLETED_MESSAGE_KEY, depletedMessage);
    }

    private String configValue(String key, String fallback) {
        return jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, index) -> row.getString(1), key).stream().findFirst().orElse(fallback);
    }

    private int configInt(String key, int fallback, int minimum, int maximum) {
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(configValue(key, Integer.toString(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private long number(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private void putConfig(String key, String value) {
        Timestamp now = Timestamp.from(Instant.now());
        int updated = jdbc.update("update gameplay_config set config_value=?,updated_at=? where config_key=?",
                value, now, key);
        if (updated > 0) return;
        try {
            jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                    key, value, now);
        } catch (DuplicateKeyException race) {
            jdbc.update("update gameplay_config set config_value=?,updated_at=? where config_key=?", value, now, key);
        }
    }
}
