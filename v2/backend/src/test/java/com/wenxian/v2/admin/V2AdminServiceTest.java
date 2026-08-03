package com.wenxian.v2.admin;

import com.wenxian.v2.game.application.SaveApplicationService;
import com.wenxian.v2.game.application.SaveApplicationService.CreateSaveCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class V2AdminServiceTest {
    @Autowired V2AdminService admin;
    @Autowired JdbcTemplate jdbc;
    @Autowired SaveApplicationService saves;

    @Test
    void adminCanInspectV2PlayersAndChangeTheRealModelRoute() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        String adminId = "admin-test-" + suffix;
        String userId = "admin-player-" + suffix;
        Instant now = Instant.now();
        jdbc.update("insert into admin_user(id,username,password_hash,status,created_at) values(?,?,?,?,?)",
                adminId, "operator-" + suffix, sha256("correct-password"), "ACTIVE", Timestamp.from(now));
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values(?,?,?,?,?)",
                userId, "v2-admin-" + suffix + "@wenxian.invalid", "ACTIVE", Timestamp.from(now), Timestamp.from(now));
        var firstSave = saves.create(userId, new CreateSaveCommand("顾青", "女", "HUMAN", "药户遗孤",
                "从青木镇外来，记得母亲留下的药囊。", "找到自己的修行道路", "NORMAL"));

        String token = (String) admin.login("operator-" + suffix, "correct-password").get("token");
        String authorization = "Bearer " + token;
        assertThat(((Number) admin.overview(authorization).get("activeSaves")).intValue()).isPositive();
        assertThat(admin.players(authorization, "顾青", "ACTIVE", 20)).hasSize(1);
        assertThat((java.util.List<?>) admin.player(authorization, userId).get("saves")).hasSize(1);
        assertThat(admin.player(authorization, userId).get("sponsoredQuota")).isNotNull();

        String operationId = "admin-cancel-" + UUID.randomUUID();
        jdbc.update("""
                insert into v2_turn_operation(id,request_id,save_id,user_id,expected_state_version,
                  operation_status,operation_stage,action_type,action_text,action_payload,created_at)
                values (?,?,?,?,?,'QUEUED','QUEUED','FREE','等待推演','{}',?)
                """, operationId, "request-" + UUID.randomUUID(), firstSave.save().id(), userId,
                firstSave.save().stateVersion(), Timestamp.from(now));
        assertThat(admin.cancelOperation(authorization, operationId)).containsEntry("cancelled", true);
        assertThat(jdbc.queryForObject("select public_error_code from v2_turn_operation where id=?",
                String.class, operationId)).isEqualTo("TURN_CANCELLED");
        var searchedProblems = (java.util.List<?>) admin.runtime(authorization, "TURN_CANCELLED")
                .get("recentProblems");
        assertThat(searchedProblems).hasSize(1);
        assertThat(((java.util.Map<?, ?>) searchedProblems.getFirst()).get("email"))
                .isEqualTo("v2-admin-" + suffix + "@wenxian.invalid");

        admin.updateRoute(authorization, "NARRATIVE_WRITING", "deepseek-v4-pro", "deepseek-v4-flash");
        assertThat(jdbc.queryForObject("select primary_model_code from ai_task_route where task_type='NARRATIVE_WRITING'", String.class))
                .isEqualTo("deepseek-v4-pro");
        admin.updateRoute(authorization, "NARRATIVE_WRITING", "deepseek-v4-flash", "deepseek-v4-pro");

        admin.updateLimits(authorization, 20, 2, "额度已用完", "测试群", true);
        saves.create(userId, new CreateSaveCommand("顾迟", "男", "HUMAN", "驿卒之子",
                "从青木镇北侧驿道来，熟悉沿路的旧里程碑。", "走完所有旧驿道", "NORMAL"));
        assertThatThrownBy(() -> admin.updateLimits(authorization, 20, 1,
                "额度已用完", "测试群", true))
                .hasMessageContaining("不能把上限调低");
        admin.saveAnnouncement(authorization, null, "0.2.0", "测试公告", "只在游戏内弹出",
                "PUBLISHED", true, false);
        assertThat((java.util.List<?>) admin.release(authorization).get("announcements")).isNotEmpty();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
