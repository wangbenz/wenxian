package com.wenxian.admin.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** 管理台高风险配置用例共用的审计写入边界；审计表故障不应掩盖已经完成的配置事务。 */
@Repository
public class AdminOperationLogRepository {
    private final JdbcTemplate jdbc;

    public AdminOperationLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String operation, String detail) {
        append(operation, detail, null);
    }

    /** 玩家相关操作必须显式传入稳定账号ID，避免后续只能从自由文本猜测个人数据归属。 */
    public void append(String operation, String detail, String subjectUserId) {
        try {
            jdbc.update("""
                    insert into admin_operation_log(id, operated_at, operation, detail, subject_user_id)
                    values (?, ?, ?, ?, ?)
                    """, "admin-op-" + UUID.randomUUID(), Timestamp.from(Instant.now()), operation, detail,
                    subjectUserId);
        } catch (RuntimeException ignored) {
            // 兼容尚未创建审计表的本地旧库；配置本身仍由外层事务保护。
        }
    }
}
