package com.wenxian.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** 记录迁移中心的策略、准备和激活操作；只保存摘要，不保存密钥、完整状态或剧情正文。 */
@Repository
public class MigrationAdminAuditRepository {
    private final JdbcTemplate jdbc;

    public MigrationAdminAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String operation, String detail) {
        jdbc.update("insert into admin_operation_log(id,operated_at,operation,detail) values (?,?,?,?)",
                "admin-log-" + UUID.randomUUID(), Timestamp.from(Instant.now()), operation, detail);
    }
}
