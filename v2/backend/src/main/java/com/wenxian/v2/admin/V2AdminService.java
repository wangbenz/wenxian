package com.wenxian.v2.admin;

import com.wenxian.v2.api.V2ApiException;
import com.wenxian.v2.ai.application.SponsoredTurnQuotaService;
import com.wenxian.v2.config.V2Properties;
import com.wenxian.v2.config.AiProperties;
import com.wenxian.v2.game.application.LegacyArchiveService;
import com.wenxian.v2.game.persistence.TurnRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class V2AdminService {
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final V2Properties properties;
    private final AiProperties aiProperties;
    private final LegacyArchiveService legacyArchives;
    private final SponsoredTurnQuotaService quotas;
    private final TurnRepository turns;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(10);
    private final SecureRandom random = new SecureRandom();

    public V2AdminService(JdbcTemplate jdbc, V2Properties properties, AiProperties aiProperties,
                          LegacyArchiveService legacyArchives,
                          SponsoredTurnQuotaService quotas, TurnRepository turns) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.aiProperties = aiProperties;
        this.legacyArchives = legacyArchives;
        this.quotas = quotas;
        this.turns = turns;
    }

    @Transactional
    public Map<String, Object> login(String username, String password) {
        var rows = jdbc.query("select id,password_hash from admin_user where username=? and status='ACTIVE'",
                (row, index) -> Map.of("id", row.getString("id"), "hash", row.getString("password_hash")),
                username == null ? "" : username.strip());
        if (rows.isEmpty() || !matches(password, rows.getFirst().get("hash"))) {
            throw unauthorized("管理员账号或密码错误。");
        }
        String token = randomToken();
        Instant now = Instant.now();
        jdbc.update("insert into admin_session(id,user_id,token_hash,expires_at,created_at) values(?,?,?,?,?)",
                "v2-admin-session-" + UUID.randomUUID(), rows.getFirst().get("id"), sha256(token),
                Timestamp.from(now.plus(Duration.ofDays(7))), Timestamp.from(now));
        return Map.of("token", token, "expiresAt", now.plus(Duration.ofDays(7)));
    }

    public Map<String, Object> overview(String authorization) {
        requireAdmin(authorization);
        Instant dayStart = LocalDate.now(REPORTING_ZONE).atStartOfDay(REPORTING_ZONE).toInstant();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accounts", count("select count(*) from app_user where status='ACTIVE'"));
        result.put("onlineAccounts", count("""
                select count(distinct user_id) from user_session
                where revoked_at is null and expires_at>? and created_at>=?
                """, Timestamp.from(Instant.now()), Timestamp.from(Instant.now().minus(Duration.ofMinutes(15)))));
        result.put("activeSaves", count("select count(*) from v2_game_save where status='ACTIVE'"));
        result.put("endedSaves", count("select count(*) from v2_game_save where status<>'ACTIVE'"));
        result.put("turns", count("select count(*) from v2_turn"));
        result.put("todayTurns", count("select count(*) from v2_turn where created_at>=?", Timestamp.from(dayStart)));
        result.put("runningOperations", count("""
                select count(*) from v2_turn_operation
                where operation_status in ('QUEUED','INTERPRETING','RESOLVING','WRITING','COMMITTING')
                """));
        result.put("failedOperations", count("select count(*) from v2_turn_operation where operation_status in ('FAILED','REJECTED')"));
        result.put("todayAi", jdbc.query("""
                select count(*) calls,coalesce(sum(prompt_tokens),0) prompt_tokens,
                       coalesce(sum(completion_tokens),0) completion_tokens,
                       coalesce(avg(latency_millis),0) average_latency_millis
                from v2_ai_usage_log where created_at>=?
                """, row -> {
                    row.next();
                    return Map.<String, Object>of(
                            "calls", row.getLong("calls"),
                            "promptTokens", row.getLong("prompt_tokens"),
                            "completionTokens", row.getLong("completion_tokens"),
                            "averageLatencyMillis", row.getLong("average_latency_millis"));
                }, Timestamp.from(dayStart)));
        result.put("engineVersion", properties.engineVersion());
        result.put("contentVersion", properties.contentVersion());
        return result;
    }

    public List<Map<String, Object>> players(String authorization, String query, String status, int limit) {
        requireAdmin(authorization);
        String normalized = query == null ? "" : query.strip().toLowerCase();
        String mode = status == null ? "" : status.strip().toUpperCase();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String statusClause = switch (mode) {
            case "ACTIVE" -> " and coalesce(s.active_saves,0)>0 ";
            case "ENDED" -> " and coalesce(s.active_saves,0)=0 and coalesce(s.total_saves,0)>0 ";
            case "NO_SAVE" -> " and coalesce(s.total_saves,0)=0 ";
            default -> "";
        };
        return jdbc.query("""
                select u.id,u.email,u.status,u.registered_at,u.last_login_at,
                       coalesce(s.active_saves,0) active_saves,coalesce(s.ended_saves,0) ended_saves,
                       coalesce(s.total_turns,0) total_turns,s.last_save_at
                from app_user u left join (
                    select user_id,count(*) total_saves,
                           sum(case when status='ACTIVE' then 1 else 0 end) active_saves,
                           sum(case when status<>'ACTIVE' then 1 else 0 end) ended_saves,
                           sum(turn_number) total_turns,max(updated_at) last_save_at
                    from v2_game_save group by user_id
                ) s on s.user_id=u.id
                where (?='' or lower(u.email) like ? or exists (
                    select 1 from v2_character c join v2_game_save gs on gs.id=c.save_id
                    where gs.user_id=u.id and lower(c.name) like ?
                ))
                """ + statusClause + " order by coalesce(s.last_save_at,u.last_login_at,u.registered_at) desc limit ?",
                (row, index) -> {
                    Map<String, Object> player = new LinkedHashMap<>();
                    player.put("userId", row.getString("id"));
                    player.put("email", row.getString("email"));
                    player.put("status", row.getString("status"));
                    player.put("registeredAt", instant(row.getTimestamp("registered_at")));
                    player.put("lastLoginAt", instant(row.getTimestamp("last_login_at")));
                    player.put("activeSaves", row.getInt("active_saves"));
                    player.put("endedSaves", row.getInt("ended_saves"));
                    player.put("totalTurns", row.getInt("total_turns"));
                    player.put("lastSaveAt", instant(row.getTimestamp("last_save_at")));
                    return player;
                }, normalized, "%" + normalized + "%", "%" + normalized + "%", safeLimit);
    }

    public Map<String, Object> player(String authorization, String userId) {
        requireAdmin(authorization);
        Map<String, Object> account = jdbc.query("select id,email,status,registered_at,last_login_at from app_user where id=?",
                row -> row.next() ? Map.<String, Object>of(
                        "userId", row.getString("id"), "email", row.getString("email"),
                        "status", row.getString("status"), "registeredAt", instant(row.getTimestamp("registered_at")),
                        "lastLoginAt", instant(row.getTimestamp("last_login_at"))) : null, userId);
        if (account == null) throw V2ApiException.notFound("ADMIN_PLAYER_NOT_FOUND", "没有找到这个玩家账号。");
        List<Map<String, Object>> saves = jdbc.query("""
                select gs.id,gs.slot_no,gs.status,gs.difficulty,gs.turn_number,gs.engine_version,
                       gs.content_version,gs.updated_at,gs.end_reason,c.name,c.race,
                       cs.realm_display_name,l.name location_name
                from v2_game_save gs join v2_character c on c.id=gs.protagonist_id
                join v2_character_state cs on cs.character_id=c.id
                join v2_location_definition l on l.id=cs.location_id
                where gs.user_id=? order by gs.updated_at desc
                """, (row, index) -> {
                    Map<String, Object> save = new LinkedHashMap<>();
                    save.put("saveId", row.getString("id"));
                    save.put("slotNo", row.getInt("slot_no"));
                    save.put("status", row.getString("status"));
                    save.put("difficulty", row.getString("difficulty"));
                    save.put("turnNumber", row.getInt("turn_number"));
                    save.put("engineVersion", row.getString("engine_version"));
                    save.put("contentVersion", row.getString("content_version"));
                    save.put("updatedAt", instant(row.getTimestamp("updated_at")));
                    save.put("endReason", row.getString("end_reason"));
                    save.put("characterName", row.getString("name"));
                    save.put("race", row.getString("race"));
                    save.put("realm", row.getString("realm_display_name"));
                    save.put("location", row.getString("location_name"));
                    return save;
                }, userId);
        List<Map<String, Object>> operations = jdbc.query("""
                select id,save_id,operation_status,operation_stage,action_type,action_text,
                       public_error_code,public_error_message,problem_reference,created_at,completed_at
                from v2_turn_operation where user_id=? order by created_at desc limit 30
                """, (row, index) -> operationRow(row), userId);
        Map<String, Object> aiPreference = jdbc.query("""
                select p.credential_source,p.provider_code,p.model_code,p.updated_at,
                       c.credential_status,c.masked_suffix,c.last_verified_at
                from user_ai_preference p left join user_ai_provider_credential c
                  on c.user_id=p.user_id and c.provider_code=p.provider_code
                where p.user_id=?
                """, row -> {
            if (!row.next()) return Map.<String, Object>of("credentialSource", "PLATFORM");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("credentialSource", row.getString("credential_source"));
            value.put("providerCode", row.getString("provider_code"));
            value.put("modelCode", row.getString("model_code"));
            value.put("credentialStatus", row.getString("credential_status"));
            value.put("maskedSuffix", row.getString("masked_suffix"));
            value.put("lastVerifiedAt", instant(row.getTimestamp("last_verified_at")));
            return value;
        }, userId);
        return Map.of("account", account, "saves", saves, "legacyArchives", legacyArchives.list(userId),
                "recentOperations", operations, "aiPreference", aiPreference,
                "sponsoredQuota", quotas.quota(userId));
    }

    public Map<String, Object> content(String authorization) {
        requireAdmin(authorization);
        return Map.of(
                "packages", jdbc.query("select package_code,version,package_type,status,published_at from v2_content_package order by package_type,package_code",
                        (row, index) -> Map.of("code", row.getString(1), "version", row.getString(2),
                                "type", row.getString(3), "status", row.getString(4), "publishedAt", instant(row.getTimestamp(5)))),
                "locations", count("select count(*) from v2_location_definition where active=true"),
                "items", count("select count(*) from v2_item_definition where active=true"),
                "abilities", count("select count(*) from v2_ability_definition where active=true"),
                "events", count("select count(*) from v2_event_template where active=true")
        );
    }

    public Map<String, Object> models(String authorization) {
        requireAdmin(authorization);
        return Map.of(
                "platformCredentialConfigured", aiProperties.platformAvailable(),
                "credentialEncryptionConfigured", aiProperties.credentialEncryptionAvailable(),
                "models", jdbc.query("select model_code,provider_code,display_name,active,updated_at from ai_model_catalog order by provider_code,display_name",
                        (row, index) -> Map.of("modelCode", row.getString(1), "providerCode", row.getString(2),
                                "displayName", row.getString(3), "active", row.getBoolean(4), "updatedAt", instant(row.getTimestamp(5)))),
                "routes", jdbc.query("select task_type,provider_code,primary_model_code,fallback_model_code,updated_at from ai_task_route order by task_type",
                        (row, index) -> {
                            Map<String, Object> route = new LinkedHashMap<>();
                            route.put("taskType", row.getString(1));
                            route.put("providerCode", row.getString(2));
                            route.put("primaryModelCode", row.getString(3));
                            route.put("fallbackModelCode", row.getString(4));
                            route.put("updatedAt", instant(row.getTimestamp(5)));
                            return route;
                        })
        );
    }

    @Transactional
    public Map<String, Object> updateRoute(String authorization, String taskType,
                                           String primaryModelCode, String fallbackModelCode) {
        requireAdmin(authorization);
        Map<String, Object> model = activeModel(primaryModelCode);
        if (model == null) throw V2ApiException.badRequest("ADMIN_MODEL_INVALID", "主模型不存在或未启用。");
        String provider = (String) model.get("provider");
        String fallback = fallbackModelCode == null || fallbackModelCode.isBlank() ? null : fallbackModelCode.strip();
        if (fallback != null) {
            Map<String, Object> fallbackModel = activeModel(fallback);
            if (fallbackModel == null || !provider.equals(fallbackModel.get("provider"))) {
                throw V2ApiException.badRequest("ADMIN_FALLBACK_MODEL_INVALID", "备用模型必须已启用并与主模型属于同一提供方。");
            }
        }
        int updated = jdbc.update("update ai_task_route set provider_code=?,primary_model_code=?,fallback_model_code=?,updated_at=? where task_type=?",
                provider, primaryModelCode, fallback, Timestamp.from(Instant.now()), taskType);
        if (updated != 1) throw V2ApiException.notFound("ADMIN_ROUTE_NOT_FOUND", "没有找到这个推演任务路由。");
        return Map.of("taskType", taskType, "providerCode", provider, "primaryModelCode", primaryModelCode,
                "fallbackModelCode", fallback == null ? "" : fallback);
    }

    public Map<String, Object> runtime(String authorization, String query) {
        requireAdmin(authorization);
        String normalized = query == null ? "" : query.strip().toLowerCase();
        String pattern = "%" + normalized + "%";
        List<Map<String, Object>> recentProblems = jdbc.query("""
                select o.id,o.user_id,o.save_id,o.operation_status,o.operation_stage,o.action_type,o.action_text,
                       o.public_error_code,o.public_error_message,o.problem_reference,o.created_at,o.completed_at,
                       u.email,c.name character_name
                from v2_turn_operation o
                join app_user u on u.id=o.user_id
                left join v2_game_save s on s.id=o.save_id
                left join v2_character c on c.id=s.protagonist_id
                where o.operation_status in ('QUEUED','INTERPRETING','RESOLVING','WRITING','COMMITTING','FAILED','REJECTED')
                  and (?='' or lower(coalesce(o.problem_reference,'')) like ?
                    or lower(coalesce(o.public_error_code,'')) like ?
                    or lower(o.action_text) like ? or lower(u.email) like ?
                    or lower(coalesce(c.name,'')) like ? or lower(o.save_id) like ?)
                order by case when o.operation_status in ('QUEUED','INTERPRETING','RESOLVING','WRITING','COMMITTING')
                         then 0 else 1 end,o.created_at desc limit 50
                """, (row, index) -> {
                    Map<String, Object> operation = operationRow(row);
                    operation.put("userId", row.getString("user_id"));
                    operation.put("email", row.getString("email"));
                    operation.put("characterName", row.getString("character_name"));
                    return operation;
                }, normalized, pattern, pattern, pattern, pattern, pattern, pattern);
        return Map.of(
                "queued", count("select count(*) from v2_turn_operation where operation_status='QUEUED'"),
                "processing", count("""
                        select count(*) from v2_turn_operation
                        where operation_status in ('INTERPRETING','RESOLVING','WRITING','COMMITTING')
                        """),
                "failed", count("select count(*) from v2_turn_operation where operation_status='FAILED'"),
                "rejected", count("select count(*) from v2_turn_operation where operation_status='REJECTED'"),
                "recentProblems", recentProblems
        );
    }

    @Transactional
    public Map<String, Object> cancelOperation(String authorization, String operationId) {
        requireAdmin(authorization);
        if (!turns.cancelBeforeCommit(operationId, Instant.now())) {
            throw V2ApiException.conflict("ADMIN_OPERATION_NOT_CANCELLABLE",
                    "这次行动已经完成、已被终止，或正在保存命卷，不能再次终止。",
                    "刷新问题诊断后查看最新状态");
        }
        return Map.of("operationId", operationId, "cancelled", true);
    }

    public Map<String, Object> release(String authorization) {
        requireAdmin(authorization);
        Map<String, String> config = new LinkedHashMap<>();
        jdbc.query("""
                select config_key,config_value from gameplay_config
                where config_key in ('game_access_mode','game_access_notice_title',
                    'game_access_notice_content','game_access_resume_at')
                """, (row, index) -> Map.entry(row.getString(1), row.getString(2)))
                .forEach(entry -> config.put(entry.getKey(), entry.getValue()));
        List<Map<String, Object>> whitelist = jdbc.query("""
                select w.user_id,u.email,w.note,w.created_at,w.updated_at
                from game_access_whitelist w join app_user u on u.id=w.user_id
                order by w.updated_at desc
                """, (row, index) -> Map.of(
                "userId", row.getString("user_id"), "email", row.getString("email"),
                "note", row.getString("note"), "createdAt", instant(row.getTimestamp("created_at")),
                "updatedAt", instant(row.getTimestamp("updated_at"))));
        List<Map<String, Object>> announcements = jdbc.query("""
                select id,version,title,content,status,announce_all,show_on_login,published_at,updated_at
                from release_note order by coalesce(published_at,created_at) desc limit 30
                """, (row, index) -> {
            Map<String, Object> notice = new LinkedHashMap<>();
            notice.put("id", row.getString("id"));
            notice.put("version", row.getString("version"));
            notice.put("title", row.getString("title"));
            notice.put("content", row.getString("content"));
            notice.put("status", row.getString("status"));
            notice.put("announceAll", row.getBoolean("announce_all"));
            notice.put("showOnLogin", row.getBoolean("show_on_login"));
            notice.put("publishedAt", instant(row.getTimestamp("published_at")));
            notice.put("updatedAt", instant(row.getTimestamp("updated_at")));
            return notice;
        });
        return Map.of(
                "restricted", "WHITELIST".equals(config.get("game_access_mode")),
                "title", config.getOrDefault("game_access_notice_title", "命卷暂时封存"),
                "message", config.getOrDefault("game_access_notice_content", "游戏正在更新中，请稍后再回来。"),
                "resumeAt", config.getOrDefault("game_access_resume_at", ""),
                "whitelist", whitelist,
                "announcements", announcements);
    }

    @Transactional
    public Map<String, Object> updateRelease(String authorization, boolean restricted, String title,
                                              String message, String resumeAt) {
        requireAdmin(authorization);
        String safeTitle = required(title, 80, "请填写灰度提示标题。");
        String safeMessage = required(message, 500, "请填写玩家可理解的灰度提示。");
        Instant now = Instant.now();
        putConfig("game_access_mode", restricted ? "WHITELIST" : "OPEN", now);
        putConfig("game_access_notice_title", safeTitle, now);
        putConfig("game_access_notice_content", safeMessage, now);
        putConfig("game_access_resume_at", resumeAt == null ? "" : resumeAt.strip(), now);
        return release(authorization);
    }

    @Transactional
    public Map<String, Object> addWhitelist(String authorization, String email, String note) {
        requireAdmin(authorization);
        String normalized = email == null ? "" : email.strip().toLowerCase();
        String userId = jdbc.query("select id from app_user where email=? and status='ACTIVE'",
                row -> row.next() ? row.getString(1) : null, normalized);
        if (userId == null) throw V2ApiException.notFound("ADMIN_PLAYER_NOT_FOUND", "没有找到这个玩家邮箱。");
        Instant now = Instant.now();
        int updated = jdbc.update("update game_access_whitelist set note=?,updated_at=? where user_id=?",
                note == null ? "" : note.strip(), Timestamp.from(now), userId);
        if (updated == 0) jdbc.update("""
                insert into game_access_whitelist(id,user_id,note,created_by,created_at,updated_at)
                values (?,?,?,?,?,?)
                """, "v2-whitelist-" + UUID.randomUUID(), userId, note == null ? "" : note.strip(),
                "v2-admin", Timestamp.from(now), Timestamp.from(now));
        return release(authorization);
    }

    @Transactional
    public Map<String, Object> removeWhitelist(String authorization, String userId) {
        requireAdmin(authorization);
        jdbc.update("delete from game_access_whitelist where user_id=?", userId);
        return release(authorization);
    }

    public Map<String, Object> limits(String authorization) {
        requireAdmin(authorization);
        return Map.of(
                "turnLimit", integerConfig("account_public_turn_limit", 20, 1, 1000),
                "maxActiveSaves", integerConfig("v2_max_active_saves", properties.maxActiveSaves(), 1, 8),
                "limitMessage", config("account_public_turn_limit_message",
                        "本账号获赠的推演回合已经用完。你可以在卷外设置中接入自己的推演密钥继续游玩。"),
                "contactText", config("registration_contact_text", ""),
                "customApiEnabled", Boolean.parseBoolean(config("custom_api_enabled", "true")));
    }

    @Transactional
    public Map<String, Object> updateLimits(String authorization, int turnLimit, int maxActiveSaves,
                                             String limitMessage, String contactText, boolean customApiEnabled) {
        requireAdmin(authorization);
        if (turnLimit < 1 || turnLimit > 1000) {
            throw V2ApiException.badRequest("ADMIN_TURN_LIMIT_INVALID", "赠送回合需要设置为一至一千回合。");
        }
        if (maxActiveSaves < 1 || maxActiveSaves > 8) {
            throw V2ApiException.badRequest("ADMIN_SAVE_LIMIT_INVALID", "同账号存活角色上限需要设置为一至八个。");
        }
        int highestExisting = jdbc.query("""
                select count(*) active_count from v2_game_save where status='ACTIVE' group by user_id
                """, (row, index) -> row.getInt(1)).stream().mapToInt(Integer::intValue).max().orElse(0);
        if (maxActiveSaves < highestExisting) {
            throw V2ApiException.conflict("ADMIN_SAVE_LIMIT_CONFLICT",
                    "当前已有账号拥有 " + highestExisting + " 名存活角色，不能把上限调低到 " + maxActiveSaves + "。",
                    "先让超出上限的玩家封存角色，或保持现有上限");
        }
        Instant now = Instant.now();
        putConfig("account_public_turn_limit", String.valueOf(turnLimit), now);
        putConfig("account_public_turn_limit_message",
                required(limitMessage, 255, "请填写额度用完后的玩家提示。"), now);
        putConfig("v2_max_active_saves", String.valueOf(maxActiveSaves), now);
        putConfig("registration_contact_text", contactText == null ? "" : contactText.strip(), now);
        putConfig("custom_api_enabled", String.valueOf(customApiEnabled), now);
        return limits(authorization);
    }

    @Transactional
    public Map<String, Object> saveAnnouncement(String authorization, String id, String version,
                                                 String title, String content, String status,
                                                 boolean announceAll, boolean showOnLogin) {
        requireAdmin(authorization);
        String noticeId = id == null || id.isBlank() ? "release-" + UUID.randomUUID() : id.strip();
        String safeVersion = required(version, 32, "请填写公告版本。该版本只用于公告标识。");
        String safeTitle = required(title, 120, "请填写公告标题。");
        String safeContent = required(content, 10000, "请填写公告正文。");
        String safeStatus = status == null ? "DRAFT" : status.strip().toUpperCase();
        if (!List.of("DRAFT", "PUBLISHED").contains(safeStatus)) {
            throw V2ApiException.badRequest("ADMIN_ANNOUNCEMENT_STATUS_INVALID", "公告状态只能是草稿或已发布。");
        }
        Instant now = Instant.now();
        int updated = jdbc.update("""
                update release_note set version=?,title=?,content=?,status=?,announce_all=?,show_on_login=?,
                    published_at=case when ?='PUBLISHED' then coalesce(published_at,?) else null end,updated_at=?
                where id=?
                """, safeVersion, safeTitle, safeContent, safeStatus, announceAll, showOnLogin,
                safeStatus, Timestamp.from(now), Timestamp.from(now), noticeId);
        if (updated == 0) jdbc.update("""
                insert into release_note(id,version,title,content,status,announce_all,show_on_login,
                    published_at,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?)
                """, noticeId, safeVersion, safeTitle, safeContent, safeStatus, announceAll, showOnLogin,
                "PUBLISHED".equals(safeStatus) ? Timestamp.from(now) : null,
                Timestamp.from(now), Timestamp.from(now));
        return release(authorization);
    }

    private Map<String, Object> operationRow(java.sql.ResultSet row) throws java.sql.SQLException {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", row.getString("id"));
        operation.put("saveId", row.getString("save_id"));
        operation.put("status", row.getString("operation_status"));
        operation.put("stage", row.getString("operation_stage"));
        operation.put("actionType", row.getString("action_type"));
        operation.put("actionText", row.getString("action_text"));
        operation.put("errorCode", row.getString("public_error_code"));
        operation.put("errorMessage", row.getString("public_error_message"));
        operation.put("problemReference", row.getString("problem_reference"));
        operation.put("createdAt", instant(row.getTimestamp("created_at")));
        operation.put("completedAt", instant(row.getTimestamp("completed_at")));
        return operation;
    }

    private Map<String, Object> activeModel(String modelCode) {
        if (modelCode == null || modelCode.isBlank()) return null;
        return jdbc.query("select provider_code from ai_model_catalog where model_code=? and active=true",
                row -> row.next() ? Map.<String, Object>of("provider", row.getString(1)) : null, modelCode.strip());
    }

    private void putConfig(String key, String value, Instant now) {
        int updated = jdbc.update("update gameplay_config set config_value=?,updated_at=? where config_key=?",
                value, Timestamp.from(now), key);
        if (updated == 0) jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                key, value, Timestamp.from(now));
    }

    private String required(String value, int max, String message) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > max) {
            throw V2ApiException.badRequest("ADMIN_RELEASE_TEXT_INVALID", message);
        }
        return normalized;
    }

    private String config(String key, String fallback) {
        return jdbc.query("select config_value from gameplay_config where config_key=?",
                (row, index) -> row.getString(1), key).stream().findFirst().orElse(fallback);
    }

    private int integerConfig(String key, int fallback, int minimum, int maximum) {
        try {
            return Math.max(minimum, Math.min(maximum, Integer.parseInt(config(key, String.valueOf(fallback)))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void requireAdmin(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        if (token.isBlank()) throw unauthorized("未提供管理令牌。");
        int matches = count("""
                select count(*) from admin_session s join admin_user u on u.id=s.user_id
                where s.token_hash=? and s.expires_at>? and u.status='ACTIVE'
                """, sha256(token), Timestamp.from(Instant.now()));
        if (matches == 0) throw unauthorized("管理登录已过期，请重新登录。");
    }

    private V2ApiException unauthorized(String message) {
        return new V2ApiException(HttpStatus.UNAUTHORIZED, "ADMIN_UNAUTHORIZED", "AUTH", message,
                List.of("重新登录管理台"));
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private boolean matches(String password, String storedHash) {
        if (password == null || storedHash == null) return false;
        return storedHash.startsWith("$2") ? passwords.matches(password, storedHash)
                : MessageDigest.isEqual(sha256(password).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String instant(Timestamp timestamp) {
        return timestamp == null ? "" : timestamp.toInstant().toString();
    }
}
