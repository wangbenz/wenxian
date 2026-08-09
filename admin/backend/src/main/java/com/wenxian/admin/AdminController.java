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
    private final AdminAnalyticsService analyticsService;
    private final AnalyticsAggregationService aggregationService;

    public AdminController(AdminService adminService,
                           AdminAnalyticsService analyticsService,
                           AnalyticsAggregationService aggregationService) {
        this.adminService = adminService;
        this.analyticsService = analyticsService;
        this.aggregationService = aggregationService;
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

    @GetMapping("/admin-accounts")
    public List<Map<String, Object>> adminAccounts(
            @org.springframework.web.bind.annotation.RequestHeader("Authorization") String auth) {
        return adminService.adminAccounts(bearerToken(auth));
    }

    @PostMapping("/admin-accounts")
    public Map<String, Object> createAdminAccount(
            @RequestBody Map<String, Object> body,
            @org.springframework.web.bind.annotation.RequestHeader("Authorization") String auth) {
        return adminService.createAdminAccount(bearerToken(auth), string(body.get("username")), string(body.get("password")));
    }

    @PostMapping("/admin-accounts/{adminId}/status")
    public Map<String, Object> updateAdminAccountStatus(
            @PathVariable String adminId,
            @RequestBody Map<String, Object> body,
            @org.springframework.web.bind.annotation.RequestHeader("Authorization") String auth) {
        adminService.updateAdminAccountStatus(bearerToken(auth), adminId, Boolean.TRUE.equals(body.get("active")));
        return Map.of("ok", true);
    }

    @PostMapping("/admin-accounts/{adminId}/temporary-password")
    public Map<String, Object> resetAdminAccountPassword(
            @PathVariable String adminId,
            @org.springframework.web.bind.annotation.RequestHeader("Authorization") String auth) {
        return Map.of("temporaryPassword", adminService.resetAdminAccountPassword(bearerToken(auth), adminId));
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return adminService.dashboard();
    }

    @GetMapping("/analytics/overview")
    public Map<String, Object> analyticsOverview(@RequestParam(defaultValue = "14") int days) {
        return analyticsService.overview(days);
    }

    @GetMapping("/analytics/growth")
    public Map<String, Object> analyticsGrowth(@RequestParam(defaultValue = "30") int days) {
        return analyticsService.growth(days);
    }

    @GetMapping("/analytics/gameplay")
    public Map<String, Object> analyticsGameplay(@RequestParam(defaultValue = "30") int days) {
        return analyticsService.gameplay(days);
    }

    @GetMapping("/analytics/ai-usage")
    public Map<String, Object> analyticsAiUsage(@RequestParam(defaultValue = "14") int days) {
        return analyticsService.aiUsage(days);
    }

    @PostMapping("/analytics/refresh")
    public Map<String, Object> refreshAnalytics() {
        aggregationService.refreshRecentHistory();
        return Map.of("ok", true, "message", "统计数据已重新汇总");
    }

    @GetMapping("/v014/overview")
    public Map<String, Object> v014Overview() {
        return adminService.v014Overview();
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
            @RequestParam(defaultValue = "") String registeredDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return adminService.users(search, characterState, difficulty, realm, registeredDate, page, size);
    }

    @GetMapping("/users/turn-ranking")
    public List<Map<String, Object>> currentTurnRanking(
            @RequestParam(defaultValue = "10") int limit) {
        return adminService.currentTurnRanking(limit);
    }

    @GetMapping("/users/{userId}")
    public Map<String, Object> userDetail(@PathVariable String userId) {
        Map<String, Object> detail = adminService.userDetail(userId);
        if (detail.containsKey("error")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, (String) detail.get("error"));
        }
        return detail;
    }

    @PostMapping("/users/{userId}/temporary-password")
    public Map<String, Object> resetPlayerPassword(@PathVariable String userId) {
        return Map.of("temporaryPassword", adminService.resetPlayerPassword(userId));
    }

    @PostMapping("/users/{userId}/unlock-auth")
    public Map<String, Object> unlockPlayerAuth(@PathVariable String userId) {
        adminService.unlockPlayerAuth(userId);
        return Map.of("ok", true, "message", "登录与注册限制已解除");
    }

    @GetMapping("/users/{userId}/lives/{saveId}/turns")
    public Map<String, Object> userTurns(@PathVariable String userId, @PathVariable String saveId,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(required = false) Integer turn) {
        return adminService.userTurns(userId, saveId, page, size, turn);
    }

    @GetMapping("/users/{userId}/lives/{saveId}/adjudications")
    public List<Map<String, Object>> userAdjudications(@PathVariable String userId,
                                                        @PathVariable String saveId,
                                                        @RequestParam(defaultValue = "20") int limit) {
        return adminService.userAdjudications(userId, saveId, limit);
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

    @GetMapping("/app-version")
    public Map<String, Object> appVersion() {
        return adminService.appVersion();
    }

    @PostMapping("/app-version")
    public Map<String, Object> updateAppVersion(@RequestBody Map<String, Object> body) {
        return adminService.updateAppVersion(string(body.get("version")));
    }

    @PostMapping("/release-notes")
    public Map<String, Object> saveReleaseNote(@RequestBody Map<String, Object> body) {
        return adminService.saveReleaseNote(string(body.get("id")), string(body.get("version")),
                string(body.get("title")), string(body.get("content")),
                string(body.get("status")).toUpperCase(), Boolean.TRUE.equals(body.get("announceAll")),
                Boolean.TRUE.equals(body.get("showOnLogin")));
    }

    @DeleteMapping("/release-notes/login-announcement")
    public Map<String, Object> clearLoginAnnouncement() {
        return adminService.clearLoginAnnouncement();
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

    @GetMapping("/generation-providers")
    public List<Map<String, Object>> generationProviders() {
        return adminService.generationProviders();
    }

    @PostMapping("/generation-providers/{providerCode}")
    public Map<String, Object> updateGenerationProvider(@PathVariable String providerCode,
                                                        @RequestBody Map<String, Object> body) {
        adminService.updateGenerationProvider(providerCode, string(body.get("displayName")),
                string(body.get("baseUrl")), Boolean.TRUE.equals(body.get("enabled")),
                Boolean.TRUE.equals(body.get("userKeyEnabled")), string(body.get("apiKey")));
        return Map.of("ok", true);
    }

    @PostMapping("/generation-providers/{providerCode}/models/{modelCode}")
    public Map<String, Object> updateGenerationModel(@PathVariable String providerCode,
                                                     @PathVariable String modelCode,
                                                     @RequestBody Map<String, Object> body) {
        adminService.updateGenerationModel(providerCode, modelCode, string(body.get("displayName")),
                string(body.get("apiModelName")), Boolean.TRUE.equals(body.get("platformEnabled")),
                Boolean.TRUE.equals(body.get("userSelectable")), Boolean.TRUE.equals(body.get("activePlatform")));
        return Map.of("ok", true);
    }

    @GetMapping("/gameplay-config")
    public Map<String, Object> gameplayConfig() {
        return adminService.gameplayConfig();
    }

    @PostMapping("/gameplay-config")
    public Map<String, Object> updateGameplayConfig(@RequestBody Map<String, Object> body) {
        return adminService.updateGameplayConfig(parseInt(body.get("accountPublicTurnLimit"), 20),
                Boolean.TRUE.equals(body.get("customApiEnabled")), string(body.get("limitMessage")),
                parseInt(body.get("maxActiveSaves"), 2));
    }

    @GetMapping("/registration-config")
    public Map<String, Object> registrationConfig() {
        return adminService.registrationConfig();
    }

    @PostMapping("/registration-config")
    public Map<String, Object> updateRegistrationConfig(@RequestBody Map<String, Object> body) {
        return adminService.updateRegistrationConfig(parseInt(body.get("maxUsers"), 1000),
                string(body.get("limitMessage")), string(body.get("contactText")));
    }

    @GetMapping("/operation-logs")
    public List<Map<String, Object>> operationLogs(@RequestParam(defaultValue = "100") int limit) {
        return adminService.operationLogs(limit);
    }

    @GetMapping("/operation-errors")
    public List<Map<String, Object>> operationErrors(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "100") int limit) {
        return adminService.operationErrors(search, category, limit);
    }

    @GetMapping("/operation-errors/summary")
    public List<Map<String, Object>> operationErrorSummary(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "12") int limit) {
        return adminService.operationErrorSummary(days, limit);
    }

    @GetMapping("/access-control")
    public Map<String, Object> accessControl() {
        return adminService.accessControl();
    }

    @PostMapping("/access-control")
    public Map<String, Object> updateAccessControl(@RequestBody Map<String, Object> body) {
        return adminService.updateAccessControl(string(body.get("mode")), string(body.get("title")),
                string(body.get("message")), string(body.get("resumeAt")));
    }

    @PostMapping("/access-control/whitelist")
    public Map<String, Object> addAccessWhitelist(@RequestBody Map<String, Object> body) {
        return adminService.addAccessWhitelist(string(body.get("email")), string(body.get("note")));
    }

    @DeleteMapping("/access-control/whitelist/{userId}")
    public Map<String, Object> removeAccessWhitelist(@PathVariable String userId) {
        return adminService.removeAccessWhitelist(userId);
    }

    private static String string(Object value) {
        return value instanceof String s ? s.strip() : "";
    }

    private static int parseInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { }
        return fallback;
    }

    private static String bearerToken(String auth) {
        return auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
    }
}
