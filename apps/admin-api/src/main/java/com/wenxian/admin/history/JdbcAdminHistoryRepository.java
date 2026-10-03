package com.wenxian.admin.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理台历史查询 JDBC 仓储。
 *
 * <p>分页统计和明细复用同一组条件，避免页面总数与当前结果不一致。问题搜索只关联账号和命卷归属，
 * 不把剧情正文作为事实来源；角色名仅从结构化状态的创角契约中读取用于运营定位。</p>
 */
@Repository
public class JdbcAdminHistoryRepository implements AdminHistoryRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public JdbcAdminHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public HistoryPage<OperationProblemView> findProblems(HistoryFilter filter) {
        SqlFilter sqlFilter = problemFilter(filter);
        Long total = jdbc.queryForObject("""
                select count(*)
                from operation_error e
                left join story_save direct_save on direct_save.id=e.save_id
                left join app_user u on u.id=direct_save.user_id
                %s
                """.formatted(sqlFilter.where()), Long.class, sqlFilter.arguments().toArray());
        List<Object> pageArguments = new ArrayList<>(sqlFilter.arguments());
        pageArguments.add(filter.size());
        pageArguments.add(filter.page() * filter.size());
        List<OperationProblemView> rows = jdbc.query("""
                select e.reference_id,e.save_id,e.operation,e.category,e.error_code,e.http_status,
                       e.public_message,e.diagnostic,e.created_at,coalesce(u.email,'') email,
                       direct_save.state_json
                from operation_error e
                left join story_save direct_save on direct_save.id=e.save_id
                left join app_user u on u.id=direct_save.user_id
                %s
                order by e.created_at desc,e.reference_id desc
                limit ? offset ?
                """.formatted(sqlFilter.where()), (row, index) -> new OperationProblemView(
                row.getString("reference_id"), row.getString("save_id"), row.getString("operation"),
                row.getString("category"), row.getString("error_code"), row.getInt("http_status"),
                row.getString("public_message"), row.getString("diagnostic"), row.getString("email"),
                protagonistName(row.getString("state_json")), row.getTimestamp("created_at").toInstant()
        ), pageArguments.toArray());
        return new HistoryPage<>(total == null ? 0 : total, filter.page(), filter.size(), rows);
    }

    @Override
    public HistoryPage<AdminAuditLogView> findAuditLogs(HistoryFilter filter) {
        SqlFilter sqlFilter = auditFilter(filter);
        Long total = jdbc.queryForObject("select count(*) from admin_operation_log l " + sqlFilter.where(),
                Long.class, sqlFilter.arguments().toArray());
        List<Object> pageArguments = new ArrayList<>(sqlFilter.arguments());
        pageArguments.add(filter.size());
        pageArguments.add(filter.page() * filter.size());
        List<AdminAuditLogView> rows = jdbc.query("""
                select l.id,l.operation,l.detail,l.operated_at
                from admin_operation_log l
                %s
                order by l.operated_at desc,l.id desc
                limit ? offset ?
                """.formatted(sqlFilter.where()), (row, index) -> new AdminAuditLogView(
                row.getString("id"), row.getString("operation"), row.getString("detail"),
                row.getTimestamp("operated_at").toInstant()), pageArguments.toArray());
        return new HistoryPage<>(total == null ? 0 : total, filter.page(), filter.size(), rows);
    }

    @Override
    public List<String> findAuditOperations() {
        return jdbc.query("""
                select operation from admin_operation_log group by operation order by operation
                """, (row, index) -> row.getString(1));
    }

    private SqlFilter problemFilter(HistoryFilter filter) {
        StringBuilder where = new StringBuilder("where 1=1");
        List<Object> arguments = new ArrayList<>();
        if (!filter.type().isBlank()) {
            where.append(" and e.category=?");
            arguments.add(filter.type());
        }
        if (!filter.search().isBlank()) {
            String like = "%" + filter.search() + "%";
            where.append("""
                     and (e.reference_id like ? or e.error_code like ? or e.save_id like ?
                          or coalesce(u.email,'') like ? or coalesce(direct_save.state_json,'') like ?)
                    """);
            arguments.add(like);
            arguments.add(like);
            arguments.add(like);
            arguments.add(like);
            arguments.add(like);
        }
        appendRange(where, arguments, "e.created_at", filter);
        return new SqlFilter(where.toString(), arguments);
    }

    private SqlFilter auditFilter(HistoryFilter filter) {
        StringBuilder where = new StringBuilder("where 1=1");
        List<Object> arguments = new ArrayList<>();
        if (!filter.type().isBlank()) {
            where.append(" and l.operation=?");
            arguments.add(filter.type());
        }
        if (!filter.search().isBlank()) {
            String like = "%" + filter.search() + "%";
            where.append(" and (l.operation like ? or l.detail like ?)");
            arguments.add(like);
            arguments.add(like);
        }
        appendRange(where, arguments, "l.operated_at", filter);
        return new SqlFilter(where.toString(), arguments);
    }

    private void appendRange(StringBuilder where, List<Object> arguments, String column, HistoryFilter filter) {
        if (filter.from() != null) {
            where.append(" and ").append(column).append(">=?");
            arguments.add(Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            where.append(" and ").append(column).append("<=?");
            arguments.add(Timestamp.from(filter.to()));
        }
    }

    private String protagonistName(String stateJson) {
        if (stateJson == null || stateJson.isBlank()) return "";
        try {
            JsonNode root = mapper.readTree(stateJson);
            String name = root.path("trustedProjection").path("creationContract")
                    .path("identity").path("protagonistName").asText("");
            return name.strip();
        } catch (Exception ignored) {
            // 损坏命卷不能阻断问题历史；账号、错误编号和诊断信息仍可用于定位。
            return "";
        }
    }

    private record SqlFilter(String where, List<Object> arguments) { }
}
