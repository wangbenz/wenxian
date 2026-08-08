package com.wenxian.game.story.api;

import com.wenxian.game.story.migration.StoryMigrationOperationPolicy;
import com.wenxian.game.story.migration.V014MigrationBatchModels.ActivateRequest;
import com.wenxian.game.story.migration.V014MigrationBatchModels.PolicyUpdateRequest;
import com.wenxian.game.story.migration.V014MigrationBatchModels.PrepareRequest;
import com.wenxian.game.story.migration.V014MigrationBatchService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理台调用的迁移内部入口。它与玩家 API 分离，并要求独立服务令牌；令牌未配置时全部返回不可用，
 * 防止迁移写能力因部署遗漏而暴露。
 */
@RestController
@RequestMapping("/internal/story-migrations/v014")
public class MigrationInternalController {
    public static final String TOKEN_HEADER = "X-Migration-Admin-Token";

    private final V014MigrationBatchService batches;
    private final StoryMigrationOperationPolicy policy;
    private final String expectedToken;

    public MigrationInternalController(V014MigrationBatchService batches,
                                       StoryMigrationOperationPolicy policy,
                                       @Value("${game.story-migration.admin-token:}") String expectedToken) {
        this.batches = batches;
        this.policy = policy;
        this.expectedToken = expectedToken == null ? "" : expectedToken;
    }

    @GetMapping("/overview")
    public Object overview(@RequestHeader(name = TOKEN_HEADER, required = false) String token) {
        authorize(token);
        return batches.overview();
    }

    @GetMapping("/preview")
    public Object preview(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                          @RequestParam(defaultValue = "") String cursor,
                          @RequestParam(defaultValue = "10") int limit) {
        authorize(token);
        return batches.previewPage(cursor, limit);
    }

    @GetMapping("/tasks")
    public Object tasks(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                        @RequestParam(defaultValue = "30") int limit) {
        authorize(token);
        return batches.recentTasks(limit);
    }

    @PostMapping("/prepare")
    public Object prepare(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                          @RequestBody PrepareRequest request) {
        authorize(token);
        return batches.prepareBatch(request == null ? null : request.sourceSaveIds());
    }

    @PostMapping("/activate")
    public Object activate(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                           @RequestBody ActivateRequest request) {
        authorize(token);
        return batches.activateOne(request);
    }

    @PostMapping("/policy")
    public Object updatePolicy(@RequestHeader(name = TOKEN_HEADER, required = false) String token,
                               @RequestBody PolicyUpdateRequest request) {
        authorize(token);
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少迁移策略");
        return policy.update(request.prepareEnabled(), request.activationEnabled(), request.batchLimit());
    }

    private void authorize(String actualToken) {
        if (expectedToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "迁移内部令牌尚未配置");
        }
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = (actualToken == null ? "" : actualToken).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "迁移内部令牌无效");
        }
    }
}
