package com.wenxian.game.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 读取当前环境对外展示的发布标识。
 *
 * <p>发布版本是运营元数据，不参与故事路由、表选择或领域规则。已有环境始终保留数据库中显式配置的值；
 * 全新环境尚未配置发布版本时使用中性标识，避免代码默认值把历史产品版本带入新架构。</p>
 */
@Service
public class GameVersionService {
    public static final String CONFIG_KEY = "current_game_version";
    public static final String DEFAULT_VERSION = "UNRELEASED";

    private final JdbcTemplate jdbc;
    private final String versionOverride;

    @Autowired
    public GameVersionService(JdbcTemplate jdbc,
                              @Value("${wenxian.game.version-override:}") String versionOverride) {
        this.jdbc = jdbc;
        this.versionOverride = versionOverride == null ? "" : versionOverride.strip();
    }

    public GameVersionService(JdbcTemplate jdbc) {
        this(jdbc, "");
    }

    @PostConstruct
    void ensureDefault() {
        try {
            jdbc.update("""
                    insert into gameplay_config(config_key, config_value, updated_at)
                    values (?, ?, ?)
                    """, CONFIG_KEY, DEFAULT_VERSION, Timestamp.from(Instant.now()));
        } catch (DataAccessException alreadyExists) {
            // Preserve the version explicitly selected for the current environment.
        }
    }

    public String current() {
        if (!versionOverride.isBlank()) return versionOverride;
        return jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, ignored) -> row.getString(1), CONFIG_KEY)
                .stream().findFirst().filter(value -> !value.isBlank()).orElse(DEFAULT_VERSION);
    }

}
