package com.wenxian.game.story.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 统一命卷迁移的运行开关。准备和激活默认均关闭，只有管理台明确开启后才允许写操作；
 * 批量上限用于避免一次请求占用过多数据库连接，并不是迁移总量限制。
 */
@Service
public class StoryMigrationOperationPolicy {
    public static final String PREPARE_ENABLED = "story_migration_prepare_enabled";
    public static final String ACTIVATION_ENABLED = "story_migration_activation_enabled";
    public static final String BATCH_LIMIT = "story_migration_batch_limit";
    private static final int DEFAULT_BATCH_LIMIT = 10;
    private static final int MAX_BATCH_LIMIT = 50;

    private final JdbcTemplate jdbc;

    public StoryMigrationOperationPolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取当前安全策略；配置不存在或非法时使用关闭写入的安全默认值。 */
    public PolicySnapshot current() {
        return new PolicySnapshot(booleanValue(PREPARE_ENABLED), booleanValue(ACTIVATION_ENABLED),
                boundedLimit(value(BATCH_LIMIT)));
    }

    /** 由受保护的管理入口更新迁移策略，不在应用启动时自动开启任何写能力。 */
    @Transactional
    public PolicySnapshot update(boolean prepareEnabled, boolean activationEnabled, int batchLimit) {
        int safeLimit = boundedLimit(Integer.toString(batchLimit));
        upsert(PREPARE_ENABLED, Boolean.toString(prepareEnabled));
        upsert(ACTIVATION_ENABLED, Boolean.toString(activationEnabled));
        upsert(BATCH_LIMIT, Integer.toString(safeLimit));
        return new PolicySnapshot(prepareEnabled, activationEnabled, safeLimit);
    }

    public void requirePrepareEnabled() {
        if (!current().prepareEnabled()) {
            throw new IllegalStateException("迁移准备开关尚未开启，当前只能预演");
        }
    }

    public void requireActivationEnabled() {
        if (!current().activationEnabled()) {
            throw new IllegalStateException("迁移激活开关尚未开启，候选命卷不会对玩家生效");
        }
    }

    private boolean booleanValue(String key) {
        return "true".equalsIgnoreCase(value(key));
    }

    private String value(String key) {
        List<String> values = jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, index) -> row.getString(1), key);
        return values.isEmpty() ? "" : values.getFirst();
    }

    private int boundedLimit(String raw) {
        try {
            return Math.max(1, Math.min(Integer.parseInt(raw), MAX_BATCH_LIMIT));
        } catch (NumberFormatException ignored) {
            return DEFAULT_BATCH_LIMIT;
        }
    }

    private void upsert(String key, String value) {
        int changed = jdbc.update("update gameplay_config set config_value=?,updated_at=? where config_key=?",
                value, Timestamp.from(Instant.now()), key);
        if (changed == 0) {
            jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                    key, value, Timestamp.from(Instant.now()));
        }
    }

    /** 管理台可见的迁移安全策略快照。 */
    public record PolicySnapshot(boolean prepareEnabled, boolean activationEnabled, int batchLimit) {}
}
