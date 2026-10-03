package com.wenxian.game.account;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountDataOwnershipCatalogTest {
    @Test
    void everyGameSchemaTableHasAnExplicitOwnershipDecision() {
        EmbeddedDatabase database = database();
        try {
            Set<String> physicalTables = schema(new JdbcTemplate(database)).keySet();

            assertThat(AccountDataOwnershipCatalog.policies().keySet()).containsAll(physicalTables);
        } finally {
            database.shutdown();
        }
    }

    @Test
    void unclassifiedSubjectTableFailsClosed() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.execute("create table future_player_fact(id varchar(40) primary key,user_id varchar(64) not null)");

            assertThatThrownBy(() -> AccountDataOwnershipCatalog.assertClassifiedSubjectTables(schema(jdbc)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("future_player_fact");
        } finally {
            database.shutdown();
        }
    }

    private Map<String, Set<String>> schema(JdbcTemplate jdbc) {
        return jdbc.execute((ConnectionCallback<Map<String, Set<String>>>) connection -> {
            Map<String, Set<String>> tables = new LinkedHashMap<>();
            try (ResultSet rows = connection.getMetaData().getTables(
                    connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (rows.next()) {
                    if (!"PUBLIC".equalsIgnoreCase(rows.getString("TABLE_SCHEM"))) continue;
                    String physical = rows.getString("TABLE_NAME");
                    String table = physical.toLowerCase(Locale.ROOT);
                    Set<String> columns = new LinkedHashSet<>();
                    try (ResultSet metadata = connection.getMetaData().getColumns(
                            connection.getCatalog(), null, physical, "%")) {
                        while (metadata.next()) {
                            columns.add(metadata.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                        }
                    }
                    tables.put(table, Set.copyOf(columns));
                }
            }
            return Map.copyOf(tables);
        });
    }

    private EmbeddedDatabase database() {
        return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
    }
}
