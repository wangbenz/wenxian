package com.wenxian.game.story.engine;

import com.wenxian.game.story.model.StoryApiModels.EngineDescriptor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 在应用开始提供 Story 能力前，核对 Java 引擎描述与数据库引擎目录。
 *
 * <p>当前运行时只读取一个精确的 state schema，因此目录的最小可读版本和当前写入版本都必须与
 * {@link StoryEngine#descriptor()} 一致。目录缺失或漂移时启动失败，避免把 v2 JSON 写进仍声明 v1 的结构。
 * 本校验不把产品发布版本、Prompt 版本或模型版本推断成状态结构版本。</p>
 */
@Component
public class StoryEngineCatalogContractValidator {
    private final JdbcTemplate jdbc;
    private final EngineDescriptor descriptor;

    @Autowired
    public StoryEngineCatalogContractValidator(JdbcTemplate jdbc, StoryEngine engine) {
        this(jdbc, engine.descriptor());
    }

    StoryEngineCatalogContractValidator(JdbcTemplate jdbc, EngineDescriptor descriptor) {
        this.jdbc = jdbc;
        this.descriptor = descriptor;
    }

    @PostConstruct
    public void validate() {
        List<CatalogContract> rows = jdbc.query("""
                        select min_schema_version,current_schema_version
                        from story_engine_catalog where engine_code=? and engine_version=?
                        """, (row, index) -> new CatalogContract(row.getInt(1), row.getInt(2)),
                descriptor.code(), descriptor.version());
        if (rows.size() != 1) {
            throw new IllegalStateException("Story引擎目录缺少唯一运行契约："
                    + descriptor.code() + "/" + descriptor.version());
        }
        CatalogContract catalog = rows.getFirst();
        if (catalog.minimumSchemaVersion() != descriptor.stateSchemaVersion()
                || catalog.currentSchemaVersion() != descriptor.stateSchemaVersion()) {
            throw new IllegalStateException("Story引擎目录与Java状态结构不一致：目录可读/写="
                    + catalog.minimumSchemaVersion() + "/" + catalog.currentSchemaVersion()
                    + "，Java=" + descriptor.stateSchemaVersion());
        }
    }

    private record CatalogContract(int minimumSchemaVersion, int currentSchemaVersion) { }
}
