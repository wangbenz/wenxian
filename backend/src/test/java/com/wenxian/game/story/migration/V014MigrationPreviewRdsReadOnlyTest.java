package com.wenxian.game.story.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享 RDS 的显式只读冒烟测试。默认不会运行；只有维护者主动提供连接环境变量和命卷 ID 时才启用。
 * 数据库连接会开启服务端只读事务，即使预演代码未来误加写操作也会被数据库拒绝。
 */
@EnabledIfEnvironmentVariable(named = "WENXIAN_RDS_PREVIEW_SAVE_IDS", matches = ".+")
class V014MigrationPreviewRdsReadOnlyTest {

    @Test
    void previewsSelectedProductionSavesInsideReadOnlyTransaction() throws Exception {
        String url = required("WENXIAN_RDS_URL");
        String username = required("WENXIAN_RDS_USER");
        String password = required("WENXIAN_RDS_PASSWORD");
        String[] saveIds = Arrays.stream(required("WENXIAN_RDS_PREVIEW_SAVE_IDS").split(","))
                .map(String::strip).filter(value -> !value.isBlank()).toArray(String[]::new);

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION READ ONLY");
            }
            assertTrue(connection.isReadOnly(), "RDS 抽样必须运行在 JDBC 只读连接中");
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            V014MigrationPreviewService service = new V014MigrationPreviewService(
                    new V014MigrationSourceReader(jdbc), new ObjectMapper().findAndRegisterModules());

            for (String saveId : saveIds) {
                var report = service.preview(saveId);
                assertNotNull(report.status());
                String issueCodes = report.issues().stream().map(V014MigrationPreviewModels.MigrationIssue::code)
                        .distinct().sorted().collect(Collectors.joining(","));
                System.out.printf("RDS_PREVIEW save=%s status=%s turns=%d entities=%d memories=%d issues=%s before=%s after=%s%n",
                        report.sourceSaveId(), report.status(), report.sourceTurnCount(),
                        report.target() == null ? 0 : report.target().entities().size(), report.memoryCount(), issueCodes,
                        prefix(report.beforeHash()), prefix(report.afterHash()));
            }
            connection.rollback();
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少环境变量：" + name);
        return value;
    }

    private String prefix(String hash) {
        return hash == null ? "-" : hash.substring(0, Math.min(hash.length(), 12));
    }
}
