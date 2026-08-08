package com.wenxian.game.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 独立统计事实仓储。
 *
 * <p>事件先以幂等键追加，再增量更新用户日活。表不依赖命卷外键，历史封存和后续业务迁移不会破坏统计事实。</p>
 */
@Repository
public class AnalyticsRepository {
    private static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AnalyticsRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public boolean append(AnalyticsEvent event) {
        Timestamp occurred = Timestamp.from(event.occurredAt());
        try {
            jdbc.update("""
                    insert into analytics_event(
                        id, event_key, event_type, user_id, save_id, release_version, engine_code,
                        data_origin, metadata_json, occurred_at, recorded_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, "analytics-" + UUID.randomUUID(), event.eventKey(), event.eventType().name(),
                    event.userId(), blankToNull(event.saveId()), event.releaseVersion(), event.engineCode(),
                    event.dataOrigin(), metadata(event), occurred, Timestamp.from(java.time.Instant.now()));
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
        updateUserDay(event, occurred);
        return true;
    }

    private void updateUserDay(AnalyticsEvent event, Timestamp occurred) {
        Date date = Date.valueOf(event.occurredAt().atZone(REPORTING_ZONE).toLocalDate());
        int created = event.eventType() == AnalyticsEventType.SAVE_CREATED ? 1 : 0;
        int turns = event.eventType() == AnalyticsEventType.TURN_SUCCEEDED ? 1 : 0;
        int corrections = event.eventType() == AnalyticsEventType.CORRECTION_APPLIED ? 1 : 0;
        int removals = event.eventType() == AnalyticsEventType.ENTITY_REMOVED ? 1 : 0;
        int archived = event.eventType() == AnalyticsEventType.SAVE_ARCHIVED ? 1 : 0;
        if (event.eventType() == AnalyticsEventType.USER_REGISTERED) return;

        int changed = jdbc.update("""
                update analytics_user_day set
                    created_save_count=created_save_count+?, successful_turn_count=successful_turn_count+?,
                    correction_count=correction_count+?, entity_removal_count=entity_removal_count+?,
                    archived_save_count=archived_save_count+?, first_activity_at=least(first_activity_at, ?),
                    last_activity_at=greatest(last_activity_at, ?), last_release_version=?,
                    data_origin=case when data_origin='REALTIME' then data_origin else ? end, updated_at=?
                where activity_date=? and user_id=?
                """, created, turns, corrections, removals, archived, occurred, occurred,
                event.releaseVersion(), event.dataOrigin(), Timestamp.from(java.time.Instant.now()), date, event.userId());
        if (changed > 0) return;
        try {
            jdbc.update("""
                    insert into analytics_user_day(
                        activity_date, user_id, created_save_count, successful_turn_count, correction_count,
                        entity_removal_count, archived_save_count, first_activity_at, last_activity_at,
                        first_release_version, last_release_version, data_origin, updated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, date, event.userId(), created, turns, corrections, removals, archived,
                    occurred, occurred, event.releaseVersion(), event.releaseVersion(), event.dataOrigin(),
                    Timestamp.from(java.time.Instant.now()));
        } catch (DuplicateKeyException race) {
            updateUserDay(event, occurred);
        }
    }

    private String metadata(AnalyticsEvent event) {
        try {
            return mapper.writeValueAsString(event.metadata());
        } catch (JsonProcessingException error) {
            return "{}";
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
