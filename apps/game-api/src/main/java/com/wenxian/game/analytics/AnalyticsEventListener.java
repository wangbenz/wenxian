package com.wenxian.game.analytics;

import com.wenxian.game.service.GameVersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 命卷事务之后异步持久化统计事实。任何异常只进入服务日志，不会反馈给 H5 或回滚玩家数据。
 */
@Component
public class AnalyticsEventListener {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventListener.class);
    private final AnalyticsRepository analytics;
    private final GameVersionService releases;

    public AnalyticsEventListener(AnalyticsRepository analytics, GameVersionService releases) {
        this.analytics = analytics;
        this.releases = releases;
    }

    @Async("analyticsExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void persist(AnalyticsEvent event) {
        try {
            AnalyticsEvent enriched = new AnalyticsEvent(event.eventKey(), event.eventType(), event.userId(),
                    event.saveId(), releases.current(), event.engineCode(), event.dataOrigin(),
                    event.metadata(), event.occurredAt());
            analytics.append(enriched);
        } catch (Exception error) {
            log.warn("统计事件写入失败，已忽略且不影响玩家操作: {} {}", event.eventType(), event.eventKey(), error);
        }
    }
}
