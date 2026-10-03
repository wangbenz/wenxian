package com.wenxian.admin;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 保护 game-api 与 admin-api 重复声明的共享 MySQL 表保持同形。 */
class SharedSchemaDefinitionContractTest {
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "create\\s+table\\s+if\\s+not\\s+exists\\s+`?(\\w+)`?\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> EXPECTED_SHARED_TABLES = Set.of(
            "ai_model_config",
            "ai_model_task_route",
            "ai_model_validation_run",
            "ai_provider_config",
            "ai_provider_model",
            "ai_service_plan_counter",
            "ai_service_plan_model_snapshot",
            "ai_service_plan_task_route",
            "ai_service_plan_tier",
            "ai_service_plan_version",
            "ai_usage_log",
            "analytics_daily_metric",
            "analytics_event",
            "analytics_retention_cohort",
            "analytics_user_day",
            "game_access_whitelist",
            "gameplay_config",
            "operation_error",
            "release_note",
            "turn_credit_grant",
            "turn_credit_ledger",
            "turn_credit_reservation",
            "turn_credit_reservation_allocation",
            "user_daily_quota",
            "user_release_receipt",
            "user_service_tier_preference");

    @Test
    void sharedTablesHaveTheSameColumnsIndexesAndConstraints() throws IOException {
        Map<String, TableDefinition> game = parse(Path.of("../game-api/src/main/resources/schema.sql"));
        Map<String, TableDefinition> admin = parse(Path.of("src/main/resources/schema.sql"));
        Set<String> actualShared = new TreeSet<>(game.keySet());
        actualShared.retainAll(admin.keySet());

        assertEquals(new TreeSet<>(EXPECTED_SHARED_TABLES), actualShared,
                "共享表清单变化时必须明确评审两个后端是否都应声明该表");
        for (String table : EXPECTED_SHARED_TABLES) {
            assertEquals(game.get(table), admin.get(table),
                    () -> "共享表结构漂移：" + table);
        }
    }

    private Map<String, TableDefinition> parse(Path path) throws IOException {
        String sql = Files.readString(path);
        Map<String, TableDefinition> tables = new LinkedHashMap<>();
        Matcher matcher = CREATE_TABLE.matcher(sql);
        while (matcher.find()) {
            int close = findClosingParenthesis(sql, matcher.end());
            tables.put(matcher.group(1).toLowerCase(Locale.ROOT),
                    parseDefinition(sql.substring(matcher.end(), close)));
        }
        return tables;
    }

    private int findClosingParenthesis(String sql, int start) {
        int depth = 1;
        boolean quoted = false;
        for (int index = start; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (current == '\'' && quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (current == '\'') {
                quoted = !quoted;
            } else if (!quoted && current == '(') {
                depth++;
            } else if (!quoted && current == ')' && --depth == 0) {
                return index;
            }
        }
        throw new IllegalArgumentException("未找到CREATE TABLE结束括号");
    }

    private TableDefinition parseDefinition(String body) {
        Map<String, String> columns = new LinkedHashMap<>();
        List<String> constraints = new ArrayList<>();
        for (String item : splitTopLevel(body)) {
            String normalized = normalize(item);
            if (normalized.isBlank()) continue;
            String first = normalized.split(" ", 2)[0];
            if (Set.of("primary", "unique", "key", "index", "constraint", "foreign", "check")
                    .contains(first)) {
                constraints.add(normalizeConstraint(normalized));
            } else {
                String[] column = normalized.split(" ", 2);
                columns.put(column[0], column.length == 1 ? "" : column[1]);
            }
        }
        constraints.sort(String::compareTo);
        return new TableDefinition(columns, constraints);
    }

    private List<String> splitTopLevel(String body) {
        List<String> items = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            if (current == '\'' && quoted && index + 1 < body.length() && body.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (current == '\'') {
                quoted = !quoted;
            } else if (!quoted && current == '(') {
                depth++;
            } else if (!quoted && current == ')') {
                depth--;
            } else if (!quoted && current == ',' && depth == 0) {
                items.add(body.substring(start, index));
                start = index + 1;
            }
        }
        items.add(body.substring(start));
        return items;
    }

    private String normalize(String sql) {
        return sql.replaceAll("--[^\\n]*", "")
                .replaceAll("(?i)\\s+comment\\s+'(?:''|[^'])*'", "")
                .replace("`", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private String normalizeConstraint(String constraint) {
        return constraint
                .replaceFirst("(?i)^constraint\\s+\\w+\\s+", "")
                .replaceFirst("(?i)^unique\\s+(?:key|index)\\s+\\w+\\s*\\(", "unique(")
                .replaceFirst("(?i)^(?:key|index)\\s+\\w+\\s*\\(", "index(");
    }

    private record TableDefinition(Map<String, String> columns, List<String> constraints) { }
}
