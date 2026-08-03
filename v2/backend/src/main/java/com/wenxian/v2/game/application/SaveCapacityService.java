package com.wenxian.v2.game.application;

import com.wenxian.v2.config.V2Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SaveCapacityService {
    private final JdbcTemplate jdbc;
    private final V2Properties properties;

    public SaveCapacityService(JdbcTemplate jdbc, V2Properties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public int limit() {
        String raw = jdbc.query("select config_value from gameplay_config where config_key='v2_max_active_saves'",
                (row, index) -> row.getString(1)).stream().findFirst()
                .orElse(String.valueOf(properties.maxActiveSaves()));
        try {
            return Math.max(1, Math.min(8, Integer.parseInt(raw)));
        } catch (NumberFormatException ignored) {
            return properties.maxActiveSaves();
        }
    }
}
