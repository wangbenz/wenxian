package com.wenxian.v2.api;

import com.wenxian.v2.account.SharedAccountSessionService;
import com.wenxian.v2.game.application.LegacyArchiveService;
import com.wenxian.v2.game.application.LegacyArchiveService.LegacyChapter;
import com.wenxian.v2.game.application.LegacyArchiveService.LegacyLife;
import com.wenxian.v2.game.application.V2ReleaseNoteService;
import com.wenxian.v2.game.application.V2ReleaseNoteService.Notice;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v2")
public class V2ArchiveNoticeController {
    private final SharedAccountSessionService sessions;
    private final LegacyArchiveService archives;
    private final V2ReleaseNoteService notices;

    public V2ArchiveNoticeController(SharedAccountSessionService sessions, LegacyArchiveService archives,
                                     V2ReleaseNoteService notices) {
        this.sessions = sessions;
        this.archives = archives;
        this.notices = notices;
    }

    @GetMapping("/legacy-archives")
    public List<LegacyLife> archives(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token) {
        return archives.list(sessions.requireAccount(token).userId());
    }

    @GetMapping("/legacy-archives/{archiveId}/chapters")
    public List<LegacyChapter> archiveChapters(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @PathVariable String archiveId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        return archives.chapters(sessions.requireAccount(token).userId(), archiveId, limit);
    }

    @GetMapping("/notices/public")
    public NoticeEnvelope publicNotice() {
        return new NoticeEnvelope(notices.publicLatest());
    }

    @GetMapping("/notices")
    public List<Notice> notices(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token) {
        return notices.published(sessions.requireAccount(token).userId());
    }

    @GetMapping("/notices/pending")
    public NoticeEnvelope pendingNotice(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token) {
        return new NoticeEnvelope(notices.pending(sessions.requireAccount(token).userId()));
    }

    @PostMapping("/notices/{noticeId}/read")
    public void markNoticeRead(
            @CookieValue(name = SharedAccountSessionService.COOKIE_NAME, required = false) String token,
            @PathVariable String noticeId) {
        notices.markRead(sessions.requireAccount(token).userId(), noticeId);
    }

    public record NoticeEnvelope(Notice notice) {
    }
}
