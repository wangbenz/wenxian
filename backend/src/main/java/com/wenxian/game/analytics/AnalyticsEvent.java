package com.wenxian.game.analytics;

import java.time.Instant;
import java.util.Map;

/**
 * 跨线程传递的不可变统计事实。只允许携带稳定 ID 和非敏感维度，禁止保存邮箱、密钥、Prompt 或剧情正文。
 */
public record AnalyticsEvent(
        String eventKey,
        AnalyticsEventType eventType,
        String userId,
        String saveId,
        String releaseVersion,
        String engineCode,
        String dataOrigin,
        Map<String, ?> metadata,
        Instant occurredAt
) {
    public AnalyticsEvent {
        saveId = saveId == null ? "" : saveId;
        releaseVersion = releaseVersion == null || releaseVersion.isBlank() ? "UNKNOWN" : releaseVersion;
        engineCode = engineCode == null ? "" : engineCode;
        dataOrigin = dataOrigin == null || dataOrigin.isBlank() ? "REALTIME" : dataOrigin;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
