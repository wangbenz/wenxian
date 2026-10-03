package com.wenxian.game.model.persistence;

import com.wenxian.game.config.SecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCatalogInitializerTest {
    @Test
    void newCatalogLeavesStoryAndCharacterOutputUncappedWithoutChangingOtherTaskDefaults() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            new ModelCatalogInitializer(jdbc, "https://api.deepseek.com", "",
                    "deepseek-v4-flash", "master-key", false).initialize();

            assertThat(outputLimit(jdbc, "TURN_DIRECTOR")).isNull();
            assertThat(outputLimit(jdbc, "NARRATIVE_WRITER")).isEqualTo(3200);
            assertThat(outputLimit(jdbc, "STORY_CORRECTION")).isEqualTo(2200);
            assertThat(outputLimit(jdbc, "CONTEXT_SUMMARY")).isEqualTo(1800);
            assertThat(outputLimit(jdbc, "CHARACTER_CREATION")).isNull();
            var director = jdbc.queryForMap("""
                    select timeout_seconds, maximum_retries from ai_model_task_route where task_code='TURN_DIRECTOR'
                    """);
            assertThat(director.get("timeout_seconds")).isEqualTo(90);
            assertThat(director.get("maximum_retries")).isEqualTo(0);
        } finally {
            database.shutdown();
        }
    }

    @Test
    void restartingDoesNotRemoveExistingDirectorLimitOrRewriteCustomRoute() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            var initializer = new ModelCatalogInitializer(jdbc, "https://api.deepseek.com", "",
                    "deepseek-v4-flash", "master-key", false);
            initializer.initialize();
            for (int existingLimit : new int[]{2200, 5000}) {
                jdbc.update("""
                        update ai_model_task_route set maximum_output_tokens=?, temperature=0.45,
                            timeout_seconds=120 where task_code='TURN_DIRECTOR'
                        """, existingLimit);
                var before = jdbc.queryForMap("select * from ai_model_task_route where task_code='TURN_DIRECTOR'");

                initializer.initialize();

                assertThat(outputLimit(jdbc, "TURN_DIRECTOR")).isEqualTo(existingLimit);
                assertThat(jdbc.queryForMap("select * from ai_model_task_route where task_code='TURN_DIRECTOR'"))
                        .isEqualTo(before);
            }
        } finally {
            database.shutdown();
        }
    }

    @Test
    void explicitMigrationIsScopedRepeatableAndCanRollBack() throws Exception {
        String migration = Files.readString(Path.of("../../database/migrations/model-director-output-default.sql"));
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            new ModelCatalogInitializer(jdbc, "https://api.deepseek.com", "",
                    "deepseek-v4-flash", "master-key", false).initialize();
            jdbc.update("""
                    update ai_model_task_route set maximum_output_tokens=2200,
                        updated_at=timestamp '2000-01-01 00:00:00' where task_code='TURN_DIRECTOR'
                    """);
            jdbc.update("""
                    insert into ai_service_plan_version(id, version_number, status, created_by, created_at,
                        published_by, published_at)
                    values ('published-plan', 1, 'ACTIVE', 'offline', current_timestamp, 'offline', current_timestamp)
                    """);
            jdbc.update("""
                    insert into ai_service_plan_tier(plan_version_id, tier_code, display_name, point_cost)
                    values ('published-plan', 'BALANCED', '离线已发布档位', 1)
                    """);
            jdbc.update("""
                    insert into ai_service_plan_task_route(plan_version_id, tier_code, task_code,
                        primary_provider_code, primary_model_code, maximum_output_tokens)
                    values ('published-plan', 'BALANCED', 'TURN_DIRECTOR', 'DEEPSEEK', 'deepseek-v4-flash', 2200)
                    """);
            var publishedBefore = jdbc.queryForMap("select * from ai_service_plan_task_route");
            var otherTasksBefore = jdbc.queryForList("select * from ai_model_task_route where task_code<>'TURN_DIRECTOR' order by task_code");
            var before = jdbc.queryForMap("select * from ai_model_task_route where task_code='TURN_DIRECTOR'");

            assertThat(jdbc.update(migration)).isEqualTo(1);
            var migrated = jdbc.queryForMap("select * from ai_model_task_route where task_code='TURN_DIRECTOR'");
            assertThat(migrated.get("maximum_output_tokens")).isNull();
            assertThat(migrated.get("updated_at")).isNotEqualTo(before.get("updated_at"));
            assertThat(jdbc.update(migration)).isZero();
            assertThat(jdbc.queryForMap("select * from ai_model_task_route where task_code='TURN_DIRECTOR'"))
                    .isEqualTo(migrated);

            jdbc.update("update ai_model_task_route set maximum_output_tokens=5000 where task_code='TURN_DIRECTOR'");
            var custom = jdbc.queryForMap("select * from ai_model_task_route where task_code='TURN_DIRECTOR'");
            assertThat(jdbc.update(migration)).isZero();
            assertThat(jdbc.queryForMap("select * from ai_model_task_route where task_code='TURN_DIRECTOR'"))
                    .isEqualTo(custom);
            assertThat(jdbc.queryForList("select * from ai_model_task_route where task_code<>'TURN_DIRECTOR' order by task_code"))
                    .isEqualTo(otherTasksBefore);
            assertThat(jdbc.queryForMap("select * from ai_service_plan_task_route")).isEqualTo(publishedBefore);

            jdbc.update("""
                    update ai_model_task_route set maximum_output_tokens=2200,
                        updated_at=timestamp '2000-01-01 00:00:00' where task_code='TURN_DIRECTOR'
                    """);
            var rollbackBefore = jdbc.queryForMap("select * from ai_model_task_route where task_code='TURN_DIRECTOR'");
            new TransactionTemplate(new DataSourceTransactionManager(database)).executeWithoutResult(status -> {
                assertThat(jdbc.update(migration)).isEqualTo(1);
                assertThat(outputLimit(jdbc, "TURN_DIRECTOR")).isNull();
                status.setRollbackOnly();
            });
            assertThat(jdbc.queryForMap("select * from ai_model_task_route where task_code='TURN_DIRECTOR'"))
                    .isEqualTo(rollbackBefore);
        } finally {
            database.shutdown();
        }
    }

    private Integer outputLimit(JdbcTemplate jdbc, String task) {
        return jdbc.queryForObject("select maximum_output_tokens from ai_model_task_route where task_code=?",
                Integer.class, task);
    }

    @Test
    void seedsNeutralCatalogAndOnlyOverridesCredentialWhenExplicitlyForced() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            String firstKey = "sk-platform-first-secret-123456789";
            String secondKey = "sk-platform-second-secret-123456789";

            new ModelCatalogInitializer(jdbc, "https://api.deepseek.com", firstKey,
                    "deepseek-v4-pro", "master-key", false).initialize();

            String initialCiphertext = jdbc.queryForObject("""
                    select platform_secret_ciphertext from ai_provider_config where provider_code='DEEPSEEK'
                    """, String.class);
            assertThat(SecretCipher.decrypt(initialCiphertext, "master-key")).isEqualTo(firstKey);
            assertThat(jdbc.queryForObject("""
                    select primary_model_code from ai_model_task_route where task_code='NARRATIVE_WRITER'
                    """, String.class)).isEqualTo("deepseek-v4-pro");

            new ModelCatalogInitializer(jdbc, "https://api.deepseek.com", secondKey,
                    "deepseek-v4-flash", "master-key", false).initialize();
            assertThat(SecretCipher.decrypt(jdbc.queryForObject("""
                    select platform_secret_ciphertext from ai_provider_config where provider_code='DEEPSEEK'
                    """, String.class), "master-key")).isEqualTo(firstKey);

            new ModelCatalogInitializer(jdbc, "https://api.deepseek.com", secondKey,
                    "deepseek-v4-flash", "master-key", true).initialize();
            assertThat(SecretCipher.decrypt(jdbc.queryForObject("""
                    select platform_secret_ciphertext from ai_provider_config where provider_code='DEEPSEEK'
                    """, String.class), "master-key")).isEqualTo(secondKey);
        } finally {
            database.shutdown();
        }
    }
}
