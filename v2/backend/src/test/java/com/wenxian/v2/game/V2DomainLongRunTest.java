package com.wenxian.v2.game;

import com.wenxian.v2.game.application.SaveApplicationService;
import com.wenxian.v2.game.application.SaveApplicationService.CreateSaveCommand;
import com.wenxian.v2.game.application.TurnApplicationService;
import com.wenxian.v2.game.application.TurnApplicationService.SubmitActionCommand;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import com.wenxian.v2.game.domain.WorldCalendar;
import com.wenxian.v2.game.persistence.TurnRepository.Operation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("long-run")
class V2DomainLongRunTest {
    @Autowired
    SaveApplicationService saves;

    @Autowired
    TurnApplicationService turns;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void survivesOneThousandDeterministicWaitTurnsAcrossMoreThanTenWorldYears() throws InterruptedException {
        String userId = "v2-long-run-" + UUID.randomUUID();
        jdbc.update("insert into app_user(id,email,status,registered_at) values (?,?,'ACTIVE',?)",
                userId, userId + "@wenxian.invalid", Timestamp.from(Instant.now()));
        SaveAggregate current = saves.create(userId, new CreateSaveCommand("长川", "不设定", "SPIRIT",
                "河石初灵", "青岐河中记得历年水声的一块河石", "看遍青岐州十年变迁", "NORMAL"));
        long initialMinute = current.worldMinute();
        long step = 6L * WorldCalendar.MINUTES_PER_DAY;

        for (int turn = 1; turn <= 1_000; turn++) {
            Operation operation = turns.submit(userId, current.save().id(), new SubmitActionCommand(
                    "long-run-" + turn, current.save().stateVersion(), "WAIT",
                    "静观水流六日，记下青岐州的变化", step, null));
            operation = await(userId, operation);
            assertThat(operation.status()).as("turn %s", turn).isEqualTo("SUCCEEDED");
            current = saves.requireAggregate(current.save().id(), userId);
            assertThat(current.save().turnNumber()).isEqualTo(turn);
            assertThat(current.save().stateVersion()).isEqualTo(turn);
            assertThat(current.protagonist().name()).isEqualTo("长川");
            assertThat(current.protagonist().race().name()).isEqualTo("SPIRIT");
        }

        assertThat(current.worldMinute() - initialMinute).isEqualTo(1_000L * step);
        assertThat(current.worldDate().year()).isGreaterThanOrEqualTo(3017);
        assertThat(saves.chronicle(current.save().id(), userId, 100).size()).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from v2_turn where save_id=?", Integer.class,
                current.save().id())).isEqualTo(1_000);
        assertThat(jdbc.queryForObject("""
                select count(*) from v2_domain_change
                where save_id=? and change_type='WORLD_TIME_ADVANCED'
                """, Integer.class, current.save().id())).isEqualTo(1_000);
    }

    private Operation await(String userId, Operation operation) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
        while (!operation.status().matches("SUCCEEDED|REJECTED|FAILED") && Instant.now().isBefore(deadline)) {
            Thread.sleep(2);
            operation = turns.requireOperation(userId, operation.id());
        }
        return operation;
    }
}
