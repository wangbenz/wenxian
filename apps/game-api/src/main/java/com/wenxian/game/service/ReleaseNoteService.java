package com.wenxian.game.service;

import com.wenxian.game.api.PlatformApiModels.ReleaseNoteView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

@Service
public class ReleaseNoteService {
    private final JdbcTemplate jdbc;

    public ReleaseNoteService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReleaseNoteView> published(String userId) {
        return jdbc.query("""
                select r.id, r.version, r.title, r.content, r.published_at,
                       case when ur.user_id is null then true else false end as unread
                from release_note r
                left join user_release_receipt ur on ur.release_note_id = r.id and ur.user_id = ?
                where r.status = 'PUBLISHED'
                order by r.published_at desc, r.created_at desc
                limit 30
                """, (row, index) -> new ReleaseNoteView(
                row.getString("id"), row.getString("version"), row.getString("title"),
                row.getString("content"), row.getTimestamp("published_at").toInstant(),
                row.getBoolean("unread")), userId);
    }

    public ReleaseNoteView pendingAnnouncement(String userId) {
        return jdbc.query("""
                select r.id, r.version, r.title, r.content, r.published_at
                from release_note r
                left join user_release_receipt ur on ur.release_note_id = r.id and ur.user_id = ?
                where r.status = 'PUBLISHED' and r.announce_all = true and ur.user_id is null
                order by r.published_at desc, r.created_at desc
                limit 1
                """, (row, index) -> new ReleaseNoteView(
                row.getString("id"), row.getString("version"), row.getString("title"),
                row.getString("content"), row.getTimestamp("published_at").toInstant(), true), userId)
                .stream().findFirst().orElse(null);
    }

    public ReleaseNoteView latestPublicAnnouncement() {
        return jdbc.query("""
                select r.id, r.version, r.title, r.content, r.published_at
                from release_note r
                where r.status = 'PUBLISHED' and r.announce_all = true and r.show_on_login = true
                order by r.published_at desc, r.created_at desc
                limit 1
                """, (row, index) -> new ReleaseNoteView(
                row.getString("id"), row.getString("version"), row.getString("title"),
                row.getString("content"), row.getTimestamp("published_at").toInstant(), true))
                .stream().findFirst().orElse(null);
    }

    @Transactional
    public void markRead(String userId, String releaseId) {
        Integer exists = jdbc.queryForObject(
                "select count(*) from release_note where id = ? and status = 'PUBLISHED'", Integer.class, releaseId);
        if (exists == null || exists == 0) return;
        jdbc.update("""
                insert into user_release_receipt(user_id, release_note_id, read_at)
                values (?, ?, ?)
                on duplicate key update read_at = values(read_at)
                """, userId, releaseId, Timestamp.from(java.time.Instant.now()));
    }
}
