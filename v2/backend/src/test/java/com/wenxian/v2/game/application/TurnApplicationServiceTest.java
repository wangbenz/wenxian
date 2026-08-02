package com.wenxian.v2.game.application;

import com.wenxian.v2.game.application.SaveApplicationService.CreateSaveCommand;
import com.wenxian.v2.game.application.TurnApplicationService.SubmitActionCommand;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.persistence.TurnRepository.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TurnApplicationServiceTest {
    @Autowired
    SaveApplicationService saves;

    @Autowired
    TurnApplicationService turns;

    @Autowired
    JdbcTemplate jdbc;

    private String userId;

    @BeforeEach
    void createUser() {
        userId = "turn-user-" + UUID.randomUUID();
        jdbc.update("""
                insert into app_user(id,email,status,registered_at) values (?,?,'ACTIVE',?)
                """, userId, userId + "@example.invalid", Timestamp.from(Instant.now()));
    }

    @Test
    void advancesExplicitTimeAndPersistsAuditableTurn() throws Exception {
        SaveAggregate created = saves.create(userId, new CreateSaveCommand(
                "陆青", "男", "HUMAN", "流民", "因灾年进入青岐州",
                "在青木镇安身", "NORMAL"));
        String requestId = "request-" + UUID.randomUUID();

        Operation submitted = turns.submit(userId, created.save().id(), new SubmitActionCommand(
                requestId, created.save().stateVersion(), "WAIT", "在东门休整一个月",
                30L * 24 * 60, null));
        Operation completed = await(submitted.id());
        SaveAggregate after = saves.requireAggregate(created.save().id(), userId);

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(after.worldMinute() - created.worldMinute()).isEqualTo(30L * 24 * 60);
        assertThat(after.save().turnNumber()).isEqualTo(1);
        assertThat(after.save().stateVersion()).isEqualTo(1);
        assertThat(saves.chronicle(created.save().id(), userId, 10)).singleElement()
                .satisfies(turn -> assertThat(turn.playerAction()).contains("一个月"));
        assertThat(turns.submit(userId, created.save().id(), new SubmitActionCommand(
                requestId, 0, "WAIT", "重复提交", 1L, null)).id()).isEqualTo(submitted.id());
    }

    @Test
    void travelsOnlyAlongAnExistingEdgeAndRejectsAStalePage() throws Exception {
        SaveAggregate created = saves.create(userId, new CreateSaveCommand(
                "沈照", "女", "HUMAN", "采药人", "自幼在山间辨药",
                "在青岐州寻找失踪亲人", "NORMAL"));

        Operation travel = turns.submit(userId, created.save().id(), new SubmitActionCommand(
                "travel-" + UUID.randomUUID(), 0, "TRAVEL", "进入青木镇",
                null, "loc-qingmu-town"));
        Operation completed = await(travel.id());
        SaveAggregate after = saves.requireAggregate(created.save().id(), userId);

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(after.characterState().locationId()).isEqualTo("loc-qingmu-town");
        assertThat(after.worldMinute() - created.worldMinute()).isEqualTo(20);
        assertThat(after.save().turnNumber()).isEqualTo(1);

        assertThatThrownBy(() -> turns.submit(userId, created.save().id(), new SubmitActionCommand(
                "stale-" + UUID.randomUUID(), 0, "WAIT", "使用旧页面继续等待", 60L, null)))
                .hasMessageContaining("其他页面发生变化");
    }

    @Test
    void rejectsImpossibleTravelWithoutChangingTheSave() throws Exception {
        SaveAggregate created = saves.create(userId, new CreateSaveCommand(
                "石生", "无", "SPIRIT", "矿石灵", "照骨矿深处的一块青金石生出灵识",
                "离开矿脉看看天地", "HARD"));

        Operation travel = turns.submit(userId, created.save().id(), new SubmitActionCommand(
                "travel-" + UUID.randomUUID(), 0, "TRAVEL", "一步抵达回春堂",
                null, "loc-huichun-hall"));
        Operation completed = await(travel.id());
        SaveAggregate after = saves.requireAggregate(created.save().id(), userId);

        assertThat(completed.status()).isEqualTo("REJECTED");
        assertThat(completed.errorCode()).isEqualTo("TRAVEL_ROUTE_UNAVAILABLE");
        assertThat(after.characterState().locationId()).isEqualTo("loc-zhaogu-mine");
        assertThat(after.worldMinute()).isEqualTo(created.worldMinute());
        assertThat(after.save().turnNumber()).isZero();
        assertThat(after.save().stateVersion()).isZero();
    }

    private Operation await(String operationId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        Operation current;
        do {
            current = turns.requireOperation(userId, operationId);
            if ("SUCCEEDED".equals(current.status()) || "REJECTED".equals(current.status())
                    || "FAILED".equals(current.status())) return current;
            Thread.sleep(20);
        } while (Instant.now().isBefore(deadline));
        return current;
    }
}
