package com.wenxian.v2.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class V2AdminApiIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @AfterEach
    void restoreSharedOperationalState() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("update gameplay_config set config_value='OPEN',updated_at=? where config_key='game_access_mode'", now);
        jdbc.update("update gameplay_config set config_value='命卷暂时封存',updated_at=? where config_key='game_access_notice_title'", now);
        jdbc.update("update gameplay_config set config_value='游戏正在更新中，请稍后再回来。',updated_at=? where config_key='game_access_notice_content'", now);
        jdbc.update("update gameplay_config set config_value='',updated_at=? where config_key='game_access_resume_at'", now);
        jdbc.update("delete from release_note where title='接口验收公告'");
    }

    @Test
    void loginAndOperationalPagesShareTheSameAdminContract() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String username = "v2-operator-" + suffix;
        String password = "admin-password-020";
        jdbc.update("insert into admin_user(id,username,password_hash,status,created_at) values(?,?,?,?,?)",
                "v2-admin-" + suffix, username, new BCryptPasswordEncoder(10).encode(password),
                "ACTIVE", Timestamp.from(Instant.now()));

        MvcResult login = http.perform(post("/api/v2/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        JsonNode body = json.readTree(login.getResponse().getContentAsByteArray());
        String authorization = "Bearer " + body.path("token").asText();

        http.perform(get("/api/v2/admin/overview").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engineVersion").isNotEmpty())
                .andExpect(jsonPath("$.todayAi.promptTokens").isNumber());
        http.perform(get("/api/v2/admin/players").header("Authorization", authorization))
                .andExpect(status().isOk());
        http.perform(get("/api/v2/admin/content").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packages").isArray());
        http.perform(get("/api/v2/admin/models").header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformCredentialConfigured").isBoolean())
                .andExpect(jsonPath("$.credentialEncryptionConfigured").isBoolean())
                .andExpect(jsonPath("$.routes").isArray());
        http.perform(get("/api/v2/admin/runtime").param("query", "not-found-marker")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentProblems").isArray());
        http.perform(put("/api/v2/admin/limits").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"turnLimit":20,"maxActiveSaves":2,
                                 "limitMessage":"赠送回合已用完","contactText":"测试反馈群",
                                 "customApiEnabled":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turnLimit").value(20))
                .andExpect(jsonPath("$.maxActiveSaves").value(2));

        String playerId = "v2-admin-api-player-" + suffix;
        String playerEmail = "admin-api-" + suffix + "@wenxian.invalid";
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values(?,?,?,?,?)",
                playerId, playerEmail, "ACTIVE", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        http.perform(post("/api/v2/admin/release/whitelist").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"note\":\"灰度验收\"}".formatted(playerEmail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.whitelist[0].email").value(playerEmail));
        http.perform(put("/api/v2/admin/release").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"restricted":true,"title":"灰度验证中",
                                 "message":"仅测试账号可进入","resumeAt":"约30分钟"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restricted").value(true));
        http.perform(post("/api/v2/admin/release/announcements").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"0.2.0","title":"接口验收公告","content":"公告正文",
                                 "status":"PUBLISHED","announceAll":true,"showOnLogin":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.announcements[0].title").value("接口验收公告"));
        http.perform(delete("/api/v2/admin/release/whitelist/{userId}", playerId)
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.whitelist").isEmpty());
    }
}
