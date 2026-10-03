package com.wenxian.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdminServiceReleaseNoteTest {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private AdminService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("admin-release-" + UUID.randomUUID()).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("""
                create table release_note(
                  id varchar(80) primary key, version varchar(40) not null, title varchar(200) not null,
                  content clob not null, status varchar(24) not null, announce_all boolean not null,
                  show_on_login boolean not null, published_at timestamp null,
                  created_at timestamp not null, updated_at timestamp not null)
                """);
        jdbc.execute("create table user_release_receipt(user_id varchar(64), release_note_id varchar(80))");
        service = new AdminService(jdbc);
    }

    @AfterEach
    void tearDown() { database.shutdown(); }

    @Test
    void publishingANonLoginNoticeDoesNotSilentlyReplaceTheExistingPinnedNotice() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into release_note values ('old','V0.10','旧公告','内容','PUBLISHED',true,true,?,?,?)",
                now, now, now);

        service.saveReleaseNote(null, "V0.11", "新公告", "新内容", "PUBLISHED", true, false);

        assertEquals(1, countPinned());
        assertEquals("old", jdbc.queryForObject(
                "select id from release_note where show_on_login=true", String.class));
    }

    @Test
    void clearLoginAnnouncementRemovesEveryLegacyPinnedFlag() {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into release_note values ('one','V0.10','公告一','内容','PUBLISHED',true,true,?,?,?)",
                now, now, now);
        jdbc.update("insert into release_note values ('two','V0.11','公告二','内容','PUBLISHED',true,true,?,?,?)",
                now, now, now);

        Map<String, Object> result = service.clearLoginAnnouncement();

        assertEquals(2, result.get("updated"));
        assertEquals(0, countPinned());
    }

    private int countPinned() {
        return jdbc.queryForObject("select count(*) from release_note where show_on_login=true", Integer.class);
    }
}
