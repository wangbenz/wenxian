package com.wenxian.game.v014.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.v014.model.V014State;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.v014.model.V014ApiModels.ModelPreference;
import com.wenxian.game.v014.service.V014GameService;
import com.wenxian.game.v014.service.V014ModelPreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V014SaveStoreTest {
    @Test
    void v014SaveAndTurnsAreIsolatedFromLegacyGameSave() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values ('u14','v14@test.local','ACTIVE',?,?)",
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            V014SaveStore saves = new V014SaveStore(jdbc, new ObjectMapper().findAndRegisterModules());
            V014State state = state();
            saves.insert("u14", 1, state);

            assertEquals(1, saves.list("u14").size());
            assertEquals(0, jdbc.queryForObject("select count(*) from game_save", Integer.class));
            assertEquals("陆沉", saves.requireOwned("u14", state.saveId()).state().protagonist().name());

            V014State changed = new V014State(14, state.saveId(), 1, "ACTIVE", state.world(), state.protagonist(),
                    60, "青岐城", "无恙", "入城", "城门已开。", List.of(), List.of(), List.of(), List.of(), List.of());
            assertEquals(2, saves.update(state.saveId(), 1, changed));
            saves.recordCorrection("u14", state.saveId(), "地点应为青岐城", state, changed);
            assertEquals(1, jdbc.queryForObject("select count(*) from v014_state_correction", Integer.class));

            saves.archive("u14", state.saveId());
            assertEquals(2, saves.nextSlot("u14", 2), "封存档案保留原槽位，但不占用存活命卷名额");
        } finally { database.shutdown(); }
    }

    @Test
    void recordCleanupUsesOptimisticSoftRemovalWithoutAdvancingTheStory() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values ('u14','v14@test.local','ACTIVE',?,?)",
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            V014SaveStore saves = new V014SaveStore(jdbc, new ObjectMapper().findAndRegisterModules());
            V014State state = stateWithRecords();
            saves.insert("u14", 1, state);
            V014ModelPreferenceService models = new V014ModelPreferenceService(jdbc, null) {
                @Override public ModelPreference get(String userId) {
                    return new ModelPreference("DEEPSEEK", "deepseek-v4-flash", "PUBLIC");
                }
            };
            V014GameService game = new V014GameService(saves, null, null, null, null, models,
                    null, null, new TransactionTemplate(new DataSourceTransactionManager(database)), "https://example.invalid");
            UserIdentity user = new UserIdentity("u14", "v14@test.local", true);

            var itemRemoved = game.removeItem(user, state.saveId(), "item-jade", 1);
            assertEquals(0, itemRemoved.state().turnNumber());
            assertEquals(0, itemRemoved.state().worldTimeMinutes());
            assertEquals(false, itemRemoved.state().inventory().getFirst().inPossession());

            var characterRemoved = game.untrackCharacter(user, state.saveId(), "character-shen", 2);
            assertEquals(0, characterRemoved.state().turnNumber());
            assertEquals(false, characterRemoved.state().characters().getFirst().tracked());
            assertEquals(2, jdbc.queryForObject("select count(*) from v014_state_correction", Integer.class));
        } finally { database.shutdown(); }
    }

    @Test
    void naturalCorrectionLabelsUpdateSeveralFieldsWithoutAdvancingStoryTime() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values ('u14','v14@test.local','ACTIVE',?,?)",
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
            V014SaveStore saves = new V014SaveStore(jdbc, new ObjectMapper().findAndRegisterModules());
            V014State state = state();
            saves.insert("u14", 1, state);
            V014ModelPreferenceService models = new V014ModelPreferenceService(jdbc, null) {
                @Override public ModelPreference get(String userId) {
                    return new ModelPreference("DEEPSEEK", "deepseek-v4-flash", "PUBLIC");
                }
            };
            V014GameService game = new V014GameService(saves, null, null, null, null, models,
                    null, null, new TransactionTemplate(new DataSourceTransactionManager(database)), "https://example.invalid");

            var corrected = game.correct(new UserIdentity("u14", "v14@test.local", true), state.saveId(),
                    "当前位置：云京城外·山脚草屋；状态：已经结束闭关，精神平稳");

            assertEquals("云京城外·山脚草屋", corrected.state().currentLocation());
            assertEquals("已经结束闭关，精神平稳", corrected.state().condition());
            assertEquals(0, corrected.state().turnNumber());
            assertEquals(0, corrected.state().worldTimeMinutes());
        } finally { database.shutdown(); }
    }

    @Test
    void legacyEntityJsonDerivesLightFlagsWithoutADataMigration() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        V014State.Item held = mapper.readValue("""
                {"id":"item-old","name":"旧符","type":"符箓","description":"旧物",
                 "status":"贴身收好","source":"家传","quantity":1}
                """, V014State.Item.class);
        V014State.Character known = mapper.readValue("""
                {"id":"character-old","name":"旧识","tier":"KNOWN","identity":"故人",
                 "relationship":"旧交","status":"远游","lastKnownLocation":"北地"}
                """, V014State.Character.class);

        assertTrue(held.inPossession());
        assertTrue(known.tracked());
    }

    private V014State state() {
        return new V014State(14, "v014-save-test", 0, "ACTIVE",
                new V014State.WorldProfile("XUANXU", "玄墟界", "OFFICIAL", "仙路未明", "古典修仙", "青岐州"),
                new V014State.Protagonist("陆沉", "人族", "男", "凡俗出身", "凡境", "问道", "自由探索"),
                0, "青岐州", "无恙", "命卷初开", "山雨初歇。", List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private V014State stateWithRecords() {
        V014State base = state();
        return new V014State(base.schemaVersion(), base.saveId(), base.turnNumber(), base.status(), base.world(),
                base.protagonist(), base.worldTimeMinutes(), base.currentLocation(), base.condition(), base.sceneTitle(),
                base.narrative(), base.choices(), List.of(new V014State.Item("item-jade", "青玉", "信物",
                "温润旧玉", "贴身收好", "家传", 1, true)), base.abilities(), List.of(new V014State.Character(
                "character-shen", "沈知微", "IMPORTANT", "商会执事", "互有信任", "仍在青岐州", "青岐州", true)), base.memories());
    }
}
