package com.wenxian.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 管理台到游戏后端迁移入口的唯一客户端。管理台不自行解释或写入命卷，所有迁移规则仍由游戏后端执行。
 */
@Service
public class MigrationAdminService {
    private static final String TOKEN_HEADER = "X-Migration-Admin-Token";
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final String token;

    public MigrationAdminService(@Value("${wenxian.game-service.base-url:http://127.0.0.1:18080}") String baseUrl,
                                 @Value("${wenxian.game-service.migration-admin-token:}") String token) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.token = token == null ? "" : token;
    }

    public Map<String, Object> overview() {
        return get("/internal/story-migrations/v014/overview", MAP);
    }

    public Map<String, Object> preview(String cursor, int limit) {
        return get("/internal/story-migrations/v014/preview?cursor=" + encode(cursor) + "&limit=" + limit, MAP);
    }

    public List<Map<String, Object>> tasks(int limit) {
        return get("/internal/story-migrations/v014/tasks?limit=" + limit, LIST);
    }

    public Map<String, Object> prepare(List<String> sourceSaveIds) {
        return post("/internal/story-migrations/v014/prepare", Map.of("sourceSaveIds", sourceSaveIds), MAP);
    }

    public Map<String, Object> activate(Map<String, Object> request) {
        return post("/internal/story-migrations/v014/activate", request, MAP);
    }

    public Map<String, Object> updatePolicy(Map<String, Object> request) {
        return post("/internal/story-migrations/v014/policy", request, MAP);
    }

    private <T> T get(String path, ParameterizedTypeReference<T> type) {
        ensureConfigured();
        try {
            return client.get().uri(path).header(TOKEN_HEADER, token).retrieve().body(type);
        } catch (RestClientResponseException error) {
            throw translate(error);
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "游戏后端迁移服务暂不可用");
        }
    }

    private <T> T post(String path, Object body, ParameterizedTypeReference<T> type) {
        ensureConfigured();
        try {
            return client.post().uri(path).header(TOKEN_HEADER, token).body(body).retrieve().body(type);
        } catch (RestClientResponseException error) {
            throw translate(error);
        } catch (RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "游戏后端迁移服务暂不可用");
        }
    }

    private void ensureConfigured() {
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "管理台迁移令牌尚未配置");
        }
    }

    private ResponseStatusException translate(RestClientResponseException error) {
        HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
        return new ResponseStatusException(status == null ? HttpStatus.BAD_GATEWAY : status,
                "迁移服务请求失败（" + error.getStatusCode().value() + "）");
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
