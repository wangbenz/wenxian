package com.wenxian.game.analytics;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 业务侧统计事件出口。
 *
 * <p>调用方只能在业务已经成功后发布事件；真正写库由异步监听器完成，统计失败不得改变玩家操作结果。</p>
 */
@Service
public class AnalyticsEventPublisher {
    private final ApplicationEventPublisher events;

    public AnalyticsEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void publish(String eventKey, AnalyticsEventType type, String userId, String saveId,
                        String engineCode, Map<String, ?> metadata) {
        // 发布版本由异步监听器读取，避免为统计给玩家请求增加一次远程数据库往返。
        events.publishEvent(new AnalyticsEvent(eventKey, type, userId, saveId, "UNKNOWN",
                engineCode, "REALTIME", metadata, Instant.now()));
    }
}
