package com.wenxian.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String username = string(body.get("username"));
        String password = string(body.get("password"));
        if (username.isBlank() || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名和密码不能为空");
        }
        return adminService.login(username, password);
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(
            @RequestBody Map<String, Object> body,
            @org.springframework.web.bind.annotation.RequestHeader("Authorization") String auth) {
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
        String oldPassword = string(body.get("oldPassword"));
        String newPassword = string(body.get("newPassword"));
        if (oldPassword.isBlank() || newPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码不能为空");
        }
        if (newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码不能少于8位");
        }
        adminService.changePassword(token, oldPassword, newPassword);
        return Map.of("ok", true, "message", "密码已修改，请重新登录");
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return adminService.dashboard();
    }

    @GetMapping("/invite-codes")
    public List<Map<String, Object>> inviteCodes(@RequestParam(defaultValue = "") String status) {
        return adminService.inviteCodes(status);
    }

    @PostMapping("/invite-codes")
    public Map<String, Object> generateInviteCodes(@RequestBody Map<String, Object> body) {
        int count = parseInt(body.get("count"), 5);
        int maxUses = parseInt(body.get("maxUses"), 20);
        if (count < 1 || count > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数量需在 1-50 之间");
        if (maxUses < 1 || maxUses > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "使用次数需在 1-200 之间");
        List<String> codes = adminService.generateInviteCodes(count, maxUses);
        return Map.of("codes", codes, "count", codes.size());
    }

    @DeleteMapping("/invite-codes/unusable")
    public Map<String, Object> deleteUnusableInviteCodes() {
        int deleted = adminService.deleteUnusableInviteCodes();
        return Map.of("deleted", deleted, "message", "已删除 " + deleted + " 个错误生成且从未使用的内测码");
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String characterState,
            @RequestParam(defaultValue = "") String difficulty,
            @RequestParam(defaultValue = "") String realm,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return adminService.users(search, characterState, difficulty, realm, page, size);
    }

    @GetMapping("/users/{userId}")
    public Map<String, Object> userDetail(@PathVariable String userId) {
        Map<String, Object> detail = adminService.userDetail(userId);
        if (detail.containsKey("error")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, (String) detail.get("error"));
        }
        return detail;
    }

    @GetMapping("/users/{userId}/lives/{saveId}/turns")
    public Map<String, Object> userTurns(@PathVariable String userId, @PathVariable String saveId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) Integer turn) {
        return adminService.userTurns(userId, saveId, page, size, turn);
    }

    @GetMapping("/agent-executions/{saveId}")
    public List<Map<String, Object>> executions(@PathVariable String saveId,
                                                 @RequestParam(defaultValue = "50") int limit,
                                                 @RequestParam(required = false) Integer turn) {
        return adminService.executions(saveId, limit, turn);
    }

    @GetMapping("/release-notes")
    public List<Map<String, Object>> releaseNotes() {
        return adminService.releaseNotes();
    }

    @PostMapping("/release-notes")
    public Map<String, Object> saveReleaseNote(@RequestBody Map<String, Object> body) {
        return adminService.saveReleaseNote(string(body.get("id")), string(body.get("version")),
                string(body.get("title")), string(body.get("content")),
                string(body.get("status")).toUpperCase(), Boolean.TRUE.equals(body.get("announceAll")),
                Boolean.TRUE.equals(body.get("showOnLogin")));
    }

    @GetMapping("/model-configs")
    public List<Map<String, Object>> modelConfigs() {
        return adminService.modelConfigs();
    }

    @PostMapping("/model-configs/{modelCode}")
    public Map<String, Object> updateModelConfig(@PathVariable String modelCode, @RequestBody Map<String, Object> body) {
        adminService.updateModelConfig(modelCode, Boolean.TRUE.equals(body.get("active")), string(body.get("apiKey")));
        return Map.of("ok", true);
    }

    @GetMapping("/operation-logs")
    public List<Map<String, Object>> operationLogs(@RequestParam(defaultValue = "100") int limit) {
        return adminService.operationLogs(limit);
    }

    private static String string(Object value) {
        return value instanceof String s ? s.strip() : "";
    }

    private static int parseInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { }
        return fallback;
    }
}
