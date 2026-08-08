package com.wenxian.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** 管理台统一命卷迁移中心接口；只做管理员身份后的参数边界和内部服务转发。 */
@RestController
@RequestMapping("/api/admin/story-migrations")
public class MigrationAdminController {
    private final MigrationAdminService migrations;
    private final MigrationAdminAuditRepository audit;

    public MigrationAdminController(MigrationAdminService migrations, MigrationAdminAuditRepository audit) {
        this.migrations = migrations;
        this.audit = audit;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return migrations.overview();
    }

    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam(defaultValue = "") String cursor,
                                       @RequestParam(defaultValue = "10") int limit) {
        return migrations.preview(cursor, Math.max(1, Math.min(limit, 50)));
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks(@RequestParam(defaultValue = "30") int limit) {
        return migrations.tasks(Math.max(1, Math.min(limit, 100)));
    }

    @PostMapping("/prepare")
    public Map<String, Object> prepare(@RequestBody Map<String, Object> body) {
        Object raw = body.get("sourceSaveIds");
        if (!(raw instanceof List<?> values)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择需要准备的旧命卷");
        }
        List<String> ids = values.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        Map<String, Object> result = migrations.prepare(ids);
        audit.record("STORY_MIGRATION_PREPARE", "准备 V0.14 候选命卷 " + ids.size() + " 份");
        return result;
    }

    @PostMapping("/activate")
    public Map<String, Object> activate(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = migrations.activate(body);
        audit.record("STORY_MIGRATION_ACTIVATE", "激活 V0.14 源命卷：" + safe(body.get("sourceSaveId")));
        return result;
    }

    @PostMapping("/policy")
    public Map<String, Object> updatePolicy(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = migrations.updatePolicy(body);
        audit.record("STORY_MIGRATION_POLICY", "更新迁移开关与单次批量上限");
        return result;
    }

    private String safe(Object value) {
        String text = value instanceof String string ? string.strip() : "未知";
        return text.length() > 80 ? text.substring(0, 80) : text;
    }
}
