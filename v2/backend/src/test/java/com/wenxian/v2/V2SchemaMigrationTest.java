package com.wenxian.v2;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class V2SchemaMigrationTest {
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsCoreTablesAndBaselineContent() {
        Integer tables = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema=current_schema() and table_name like 'v2_%'
                """, Integer.class);
        Integer locations = jdbc.queryForObject("select count(*) from v2_location_definition", Integer.class);
        Integer models = jdbc.queryForObject("select count(*) from ai_model_catalog", Integer.class);
        Integer generatedEntities = jdbc.queryForObject("select count(*) from v2_generated_entity", Integer.class);
        Integer usageRows = jdbc.queryForObject("select count(*) from v2_ai_usage_log", Integer.class);

        assertThat(tables).isGreaterThanOrEqualTo(30);
        assertThat(locations).isEqualTo(9);
        assertThat(models).isEqualTo(2);
        assertThat(generatedEntities).isZero();
        assertThat(usageRows).isZero();
    }
}
