package com.wenxian.v2.game.application;

import com.wenxian.v2.game.application.SaveApplicationService.CreateSaveCommand;
import com.wenxian.v2.game.domain.GameRecords.SaveAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SaveApplicationServiceTest {
    @Autowired
    SaveApplicationService saves;

    @Autowired
    JdbcTemplate jdbc;

    private String userId;

    @BeforeEach
    void createUser() {
        userId = "test-user-" + UUID.randomUUID();
        jdbc.update("""
                insert into app_user(id,email,status,registered_at) values (?,?,'ACTIVE',?)
                """, userId, userId + "@example.invalid", Timestamp.from(Instant.now()));
    }

    @Test
    void createsSpiritSaveWithIndependentInventoryWalletAndWorldClock() {
        SaveAggregate aggregate = saves.create(userId, new CreateSaveCommand(
                "石生", "无性", "SPIRIT", "玄铁矿灵", "照骨矿玄铁孕育出的灵识",
                "寻找不依附矿脉的存在方式", "NORMAL"));

        assertThat(aggregate.protagonist().race().name()).isEqualTo("SPIRIT");
        assertThat(aggregate.characterState().realmDisplayName()).isEqualTo("萌灵");
        assertThat(aggregate.characterState().locationId()).isEqualTo("loc-zhaogu-mine");
        assertThat(aggregate.inventory()).singleElement().satisfies(item -> {
            assertThat(item.itemCode()).isEqualTo("IDENTITY_TOKEN");
            assertThat(item.provenance()).contains("照骨矿玄铁");
        });
        assertThat(aggregate.wallet()).hasSize(6).allSatisfy(balance ->
                assertThat(balance.balance()).isZero());
        assertThat(aggregate.worldDate().year()).isEqualTo(3001);
        assertThat(saves.knownWorld(aggregate.save().id(), userId)).extracting("id")
                .contains("loc-qingqi", "loc-qingmu-town", "loc-zhaogu-mine");
    }
}
