package com.wenxian.game.service;

import com.wenxian.game.api.PlatformApiModels.ReleaseNoteView;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReleaseNoteServiceTest {
    @Test
    void publicAnnouncementReturnsLatestPublishedGlobalNote() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            Instant now = Instant.now();
            insert(jdbc, "release-old", "PUBLISHED", true, true, now.minusSeconds(120));
            insert(jdbc, "release-private", "PUBLISHED", false, true, now.minusSeconds(30));
            insert(jdbc, "release-draft", "DRAFT", true, true, now);
            insert(jdbc, "release-newer", "PUBLISHED", true, false, now);
            insert(jdbc, "release-latest", "PUBLISHED", true, true, now.minusSeconds(60));

            ReleaseNoteView note = new ReleaseNoteService(jdbc).latestPublicAnnouncement();

            assertNotNull(note);
            assertEquals("release-latest", note.id());
        } finally {
            database.shutdown();
        }
    }

    private void insert(JdbcTemplate jdbc, String id, String status, boolean announceAll,
                        boolean showOnLogin, Instant publishedAt) {
        Timestamp at = Timestamp.from(publishedAt);
        jdbc.update("""
                insert into release_note(id,version,title,content,status,announce_all,show_on_login,published_at,created_at,updated_at)
                values (?,'V0.11','公告','内容',?,?,?,?,?,?)
                """, id, status, announceAll, showOnLogin, at, at, at);
    }
}
