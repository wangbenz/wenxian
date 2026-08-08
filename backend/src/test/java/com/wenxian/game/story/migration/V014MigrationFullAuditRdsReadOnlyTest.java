package com.wenxian.game.story.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享 RDS 的全量只读迁移体检。默认禁用；显式开启后在一致性只读事务中扫描全部 V0.14 命卷，
 * 报告只写本机构建目录，不写数据库。
 */
@EnabledIfEnvironmentVariable(named = "WENXIAN_RDS_FULL_AUDIT", matches = "(?i:true|1|yes)")
class V014MigrationFullAuditRdsReadOnlyTest {

    @Test
    void writesOneSanitizedLocalReportWithoutChangingRds() throws Exception {
        String url = required("WENXIAN_RDS_URL");
        String username = required("WENXIAN_RDS_USER");
        String password = required("WENXIAN_RDS_PASSWORD");
        Path reportPath = Path.of(optional("WENXIAN_RDS_AUDIT_REPORT",
                "target/v014-migration-audit.json")).toAbsolutePath().normalize();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                statement.execute("SET TRANSACTION READ ONLY");
            }
            assertTrue(connection.isReadOnly(), "RDS 全量体检必须运行在 JDBC 只读连接中");
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            V014MigrationSourceReader sources = new V014MigrationSourceReader(jdbc);
            V014MigrationPreviewService previews = new V014MigrationPreviewService(sources, mapper);

            var report = new V014MigrationAuditService(sources, previews).auditAll();

            assertEquals(report.sourceTotal(), report.scanned(), "一致性快照中的源命卷必须全部扫描");
            if (reportPath.getParent() != null) Files.createDirectories(reportPath.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
            System.out.printf("RDS_FULL_AUDIT sources=%d scanned=%d statuses=%s issues=%s turns=%d entities=%d durationMs=%d report=%s%n",
                    report.sourceTotal(), report.scanned(), report.statusCounts(), report.issueCounts(),
                    report.totalTurns(), report.totalEntities(), report.durationMillis(), reportPath);
            connection.rollback();
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少环境变量：" + name);
        return value;
    }

    private String optional(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
