package com.wenxian.game.story.engine;

import com.wenxian.game.story.model.StoryApiModels.EngineDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoryEngineCatalogContractValidatorTest {
    @Test
    void acceptsExactV2CatalogContract() {
        EmbeddedDatabase database = database();
        try {
            assertThatCode(() -> new StoryEngineCatalogContractValidator(
                    new JdbcTemplate(database), descriptor()).validate()).doesNotThrowAnyException();
        } finally {
            database.shutdown();
        }
    }

    @Test
    void rejectsCatalogThatStillClaimsV1() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("""
                    update story_engine_catalog set min_schema_version=1,current_schema_version=1
                    where engine_code='LIGHT_NARRATIVE' and engine_version=1
                    """);
            assertThatThrownBy(() -> new StoryEngineCatalogContractValidator(jdbc, descriptor()).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("目录与Java状态结构不一致");
        } finally {
            database.shutdown();
        }
    }

    private EmbeddedDatabase database() {
        return new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
    }

    private EngineDescriptor descriptor() {
        return new EngineDescriptor("LIGHT_NARRATIVE", 1, 2);
    }
}
