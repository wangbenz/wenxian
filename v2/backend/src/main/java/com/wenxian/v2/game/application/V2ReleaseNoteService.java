package com.wenxian.v2.game.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Service
public class V2ReleaseNoteService {
    private final JdbcTemplate jdbc;

    public V2ReleaseNoteService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Notice publicLatest() {
        return jdbc.query("""
                select id,version,title,content,published_at from release_note
                where status='PUBLISHED' and show_on_login=true
                order by published_at desc,created_at desc limit 1
                """, (row, index) -> notice(row, true)).stream().findFirst().orElse(null);
    }

    public Notice pending(String userId) {
        return jdbc.query("""
                select r.id,r.version,r.title,r.content,r.published_at
                from release_note r left join user_release_receipt receipt
                  on receipt.release_note_id=r.id and receipt.user_id=?
                where r.status='PUBLISHED' and r.announce_all=true and receipt.user_id is null
                order by r.published_at desc,r.created_at desc limit 1
                """, (row, index) -> notice(row, true), userId).stream().findFirst().orElse(null);
    }

    public List<Notice> published(String userId) {
        return jdbc.query("""
                select r.id,r.version,r.title,r.content,r.published_at,
                       case when receipt.user_id is null then true else false end unread
                from release_note r left join user_release_receipt receipt
                  on receipt.release_note_id=r.id and receipt.user_id=?
                where r.status='PUBLISHED'
                order by r.published_at desc,r.created_at desc limit 30
                """, (row, index) -> notice(row, row.getBoolean("unread")), userId);
    }

    @Transactional
    public void markRead(String userId, String noticeId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from release_note where id=? and status='PUBLISHED'", Integer.class, noticeId);
        if (count == null || count == 0) return;
        int updated = jdbc.update("update user_release_receipt set read_at=? where user_id=? and release_note_id=?",
                Timestamp.from(Instant.now()), userId, noticeId);
        if (updated == 0) jdbc.update(
                "insert into user_release_receipt(user_id,release_note_id,read_at) values (?,?,?)",
                userId, noticeId, Timestamp.from(Instant.now()));
    }

    private Notice notice(java.sql.ResultSet row, boolean unread) throws java.sql.SQLException {
        Timestamp publishedAt = row.getTimestamp("published_at");
        return new Notice(row.getString("id"), row.getString("version"), row.getString("title"),
                row.getString("content"), publishedAt == null ? null : publishedAt.toInstant(), unread);
    }

    public record Notice(String id, String version, String title, String content,
                         Instant publishedAt, boolean unread) {
    }
}
