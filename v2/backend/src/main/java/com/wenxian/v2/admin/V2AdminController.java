package com.wenxian.v2.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/admin")
public class V2AdminController {
    private final V2AdminService admin;

    public V2AdminController(V2AdminService admin) {
        this.admin = admin;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        return admin.login(request.username(), request.password());
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestHeader("Authorization") String authorization) {
        return admin.overview(authorization);
    }

    @GetMapping("/players")
    public List<Map<String, Object>> players(@RequestHeader("Authorization") String authorization,
                                              @RequestParam(defaultValue = "") String query,
                                              @RequestParam(defaultValue = "") String status,
                                              @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return admin.players(authorization, query, status, limit);
    }

    @GetMapping("/players/{userId}")
    public Map<String, Object> player(@RequestHeader("Authorization") String authorization,
                                      @PathVariable String userId) {
        return admin.player(authorization, userId);
    }

    @GetMapping("/content")
    public Map<String, Object> content(@RequestHeader("Authorization") String authorization) {
        return admin.content(authorization);
    }

    @GetMapping("/models")
    public Map<String, Object> models(@RequestHeader("Authorization") String authorization) {
        return admin.models(authorization);
    }

    @PutMapping("/models/routes/{taskType}")
    public Map<String, Object> updateRoute(@RequestHeader("Authorization") String authorization,
                                           @PathVariable String taskType,
                                           @Valid @RequestBody RouteRequest request) {
        return admin.updateRoute(authorization, taskType, request.primaryModelCode(), request.fallbackModelCode());
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime(@RequestHeader("Authorization") String authorization,
                                       @RequestParam(defaultValue = "") String query) {
        return admin.runtime(authorization, query);
    }

    @PostMapping("/runtime/{operationId}/cancel")
    public Map<String, Object> cancelOperation(@RequestHeader("Authorization") String authorization,
                                               @PathVariable String operationId) {
        return admin.cancelOperation(authorization, operationId);
    }

    @GetMapping("/release")
    public Map<String, Object> release(@RequestHeader("Authorization") String authorization) {
        return admin.release(authorization);
    }

    @PutMapping("/release")
    public Map<String, Object> updateRelease(@RequestHeader("Authorization") String authorization,
                                             @Valid @RequestBody ReleaseRequest request) {
        return admin.updateRelease(authorization, request.restricted(), request.title(), request.message(),
                request.resumeAt());
    }

    @PostMapping("/release/whitelist")
    public Map<String, Object> addWhitelist(@RequestHeader("Authorization") String authorization,
                                            @Valid @RequestBody WhitelistRequest request) {
        return admin.addWhitelist(authorization, request.email(), request.note());
    }

    @DeleteMapping("/release/whitelist/{userId}")
    public Map<String, Object> removeWhitelist(@RequestHeader("Authorization") String authorization,
                                               @PathVariable String userId) {
        return admin.removeWhitelist(authorization, userId);
    }

    @PostMapping("/release/announcements")
    public Map<String, Object> saveAnnouncement(@RequestHeader("Authorization") String authorization,
                                                @Valid @RequestBody AnnouncementRequest request) {
        return admin.saveAnnouncement(authorization, request.id(), request.version(), request.title(),
                request.content(), request.status(), request.announceAll(), request.showOnLogin());
    }

    @GetMapping("/limits")
    public Map<String, Object> limits(@RequestHeader("Authorization") String authorization) {
        return admin.limits(authorization);
    }

    @PutMapping("/limits")
    public Map<String, Object> updateLimits(@RequestHeader("Authorization") String authorization,
                                            @Valid @RequestBody LimitsRequest request) {
        return admin.updateLimits(authorization, request.turnLimit(), request.maxActiveSaves(), request.limitMessage(),
                request.contactText(), request.customApiEnabled());
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RouteRequest(@NotBlank String primaryModelCode, String fallbackModelCode) {
    }

    public record ReleaseRequest(boolean restricted, @NotBlank String title,
                                 @NotBlank String message, String resumeAt) {
    }

    public record WhitelistRequest(@NotBlank String email, String note) {
    }

    public record AnnouncementRequest(String id, @NotBlank String version, @NotBlank String title,
                                      @NotBlank String content, @NotBlank String status,
                                      boolean announceAll, boolean showOnLogin) {
    }

    public record LimitsRequest(@Min(1) @Max(1000) int turnLimit,
                                @Min(1) @Max(8) int maxActiveSaves, @NotBlank String limitMessage,
                                String contactText, boolean customApiEnabled) {
    }
}
