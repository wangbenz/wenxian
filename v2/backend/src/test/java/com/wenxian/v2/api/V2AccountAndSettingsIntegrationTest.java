package com.wenxian.v2.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.v2.ai.application.V2SecretCipher;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class V2AccountAndSettingsIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void openAccess() {
        Instant now = Instant.now();
        putConfig("game_access_mode", "OPEN", now);
        putConfig("game_access_notice_title", "更新中", now);
        putConfig("game_access_notice_content", "正在校对新世界，请稍后回来。", now);
        putConfig("game_access_resume_at", "", now);
    }

    @Test
    void registersAndUsesTheV2SessionWithoutTheLegacyBackend() throws Exception {
        String email = "v2-register-" + UUID.randomUUID() + "@example.invalid";
        MvcResult registered = http.perform(post("/api/v2/auth/register")
                        .header("X-Forwarded-For", "198.51.100." + (System.nanoTime() % 200 + 1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"strong-pass-020\"}".formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.passwordConfigured").value(true))
                .andReturn();
        Cookie session = sessionCookie(registered);
        http.perform(get("/api/v2/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
        http.perform(get("/api/v2/saves").cookie(session)).andExpect(status().isOk());
    }

    @Test
    void upgradesALegacyEmailPasswordBeforeEnteringTheGame() throws Exception {
        String userId = "legacy-" + UUID.randomUUID();
        String email = userId + "@example.invalid";
        jdbc.update("insert into app_user(id,email,status,registered_at) values (?,?,'ACTIVE',?)",
                userId, email, Timestamp.from(Instant.now()));
        MvcResult login = http.perform(post("/api/v2/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordConfigured").value(false))
                .andReturn();
        Cookie session = sessionCookie(login);
        http.perform(put("/api/v2/auth/password").cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"%s\",\"newPassword\":\"new-password-020\"}".formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordConfigured").value(true));
        assertThat(jdbc.queryForObject("select password_hash is not null from app_user where id=?",
                Boolean.class, userId)).isTrue();
    }

    @Test
    void blocksNonWhitelistedAccountsAndAllowsWhitelistedAccounts() throws Exception {
        String userId = "grey-" + UUID.randomUUID();
        String email = userId + "@example.invalid";
        jdbc.update("insert into app_user(id,email,status,registered_at) values (?,?,'ACTIVE',?)",
                userId, email, Timestamp.from(Instant.now()));
        putConfig("game_access_mode", "WHITELIST", Instant.now());
        http.perform(post("/api/v2/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, email)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GAME_UPDATING"));
        Instant now = Instant.now();
        jdbc.update("""
                insert into game_access_whitelist(id,user_id,note,created_by,created_at,updated_at)
                values (?,?,?,'test',?,?)
                """, "white-" + UUID.randomUUID(), userId, "自动测试", Timestamp.from(now), Timestamp.from(now));
        http.perform(post("/api/v2/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, email)))
                .andExpect(status().isOk());
    }

    @Test
    void exposesOnlyMaskedPlayerCredentialAndUsesTheSelectedSource() throws Exception {
        String email = "ai-settings-" + UUID.randomUUID() + "@example.invalid";
        MvcResult registered = http.perform(post("/api/v2/auth/register")
                        .header("X-Forwarded-For", "203.0.113." + (System.nanoTime() % 200 + 1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"strong-pass-020\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn();
        Cookie session = sessionCookie(registered);
        JsonNode body = json.readTree(registered.getResponse().getContentAsByteArray());
        String userId = body.path("user").path("userId").asText();
        Instant now = Instant.now();
        String encrypted = V2SecretCipher.encrypt("sk-player-secret-1234", "v2-test-credential-master-key");
        jdbc.update("""
                insert into user_ai_provider_credential(user_id,provider_code,api_key_ciphertext,masked_suffix,
                    credential_status,last_verified_at,created_at,updated_at)
                values (?,'DEEPSEEK',?,'1234','ACTIVE',?,?,?)
                """, userId, encrypted, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        http.perform(put("/api/v2/ai/preference").cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credentialSource\":\"USER\",\"modelCode\":\"deepseek-v4-flash\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialSource").value("USER"))
                .andExpect(jsonPath("$.maskedSuffix").value("1234"))
                .andExpect(jsonPath("$.apiKeyCiphertext").doesNotExist());
    }

    @Test
    void reportsSponsoredUsageAndKeepsAPlayersOldLifeReadable() throws Exception {
        String email = "v2-archive-" + UUID.randomUUID() + "@example.invalid";
        MvcResult registered = http.perform(post("/api/v2/auth/register")
                        .header("X-Forwarded-For", "192.0.2." + (System.nanoTime() % 200 + 1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"strong-pass-020\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn();
        Cookie session = sessionCookie(registered);
        String userId = json.readTree(registered.getResponse().getContentAsByteArray())
                .path("user").path("userId").asText();
        Instant now = Instant.now();
        jdbc.update("insert into user_daily_quota(user_id,usage_date,used_count,updated_at) values (?,?,7,?)",
                userId, LocalDate.now(), Timestamp.from(now));
        String archiveId = "legacy-save-" + UUID.randomUUID();
        jdbc.update("insert into game_save(id,user_id,slot_no,state_json,revision,updated_at) values (?,?,1,?,1,?)",
                archiveId, userId,
                "{\"player\":{\"name\":\"旧卷照微\",\"race\":\"灵族\",\"realm\":\"启灵\"},\"currentLocation\":\"照骨矿\",\"turn\":88,\"worldYear\":3003,\"day\":12,\"minute\":600}",
                Timestamp.from(now));
        jdbc.update("insert into game_turn(id,save_id,turn_number,player_action,narrative_json,created_at) values (?,?,?,?,?,?)",
                "legacy-turn-" + UUID.randomUUID(), archiveId, 88, "离开矿洞", "[\"你最后回望矿灯。\"]",
                Timestamp.from(now));

        http.perform(get("/api/v2/ai/preference").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedTurns").value(7))
                .andExpect(jsonPath("$.remainingTurns").value(13));
        http.perform(get("/api/v2/legacy-archives").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].characterName").value("旧卷照微"))
                .andExpect(jsonPath("$[0].turnNumber").value(88));
        http.perform(get("/api/v2/legacy-archives/{archiveId}/chapters", archiveId).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].narrative").value("你最后回望矿灯。"));
    }

    @Test
    void separatesLoginVisibilityFromTheUnreadInGameAnnouncement() throws Exception {
        String email = "v2-notice-" + UUID.randomUUID() + "@example.invalid";
        MvcResult registered = http.perform(post("/api/v2/auth/register")
                        .header("X-Forwarded-For", "198.18.0." + (System.nanoTime() % 200 + 1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"strong-pass-020\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn();
        Cookie session = sessionCookie(registered);
        Instant now = Instant.now();
        String hiddenId = "hidden-notice-" + UUID.randomUUID();
        jdbc.update("""
                insert into release_note(id,version,title,content,status,announce_all,show_on_login,
                  published_at,created_at,updated_at) values (?,'0.2.0','进入后公告','只在游戏内弹出',
                  'PUBLISHED',true,false,?,?,?)
                """, hiddenId, Timestamp.from(now.plusSeconds(30)), Timestamp.from(now), Timestamp.from(now));

        http.perform(get("/api/v2/notices/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice.id").doesNotExist());
        http.perform(get("/api/v2/notices/pending").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notice.id").value(hiddenId));
        http.perform(post("/api/v2/notices/{noticeId}/read", hiddenId).cookie(session))
                .andExpect(status().isOk());
        MvcResult nextPending = http.perform(get("/api/v2/notices/pending").cookie(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(nextPending.getResponse().getContentAsByteArray())
                .path("notice").path("id").asText()).isNotEqualTo(hiddenId);
    }

    private Cookie sessionCookie(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).isNotBlank();
        String raw = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        return new Cookie("WENXIAN_SESSION", raw);
    }

    private void putConfig(String key, String value, Instant now) {
        int updated = jdbc.update("update gameplay_config set config_value=?,updated_at=? where config_key=?",
                value, Timestamp.from(now), key);
        if (updated == 0) jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                key, value, Timestamp.from(now));
    }
}
