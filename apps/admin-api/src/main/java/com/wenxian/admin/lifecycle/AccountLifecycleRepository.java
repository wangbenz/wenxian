package com.wenxian.admin.lifecycle;

import com.wenxian.admin.lifecycle.AccountLifecycleViews.DeletionAuditItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.DeletionJobItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.LifecycleEventItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.NoticeItem;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.Overview;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.Page;
import com.wenxian.admin.lifecycle.AccountLifecycleViews.RetentionRun;

import java.time.Instant;
import java.util.Optional;

/** 数据生命周期只读持久化端口；不包含重试、删除或策略开关写操作。 */
public interface AccountLifecycleRepository {
    Overview overview(Instant now);

    Page<NoticeItem> notices(String status, int page, int size);

    Page<DeletionJobItem> deletionJobs(String status, String reason, int page, int size);

    Page<DeletionAuditItem> deletionAudits(String reason, Instant from, Instant to, int page, int size);

    Page<LifecycleEventItem> lifecycleEvents(
            String eventType, String reason, Instant from, Instant to, int page, int size);

    Optional<RetentionRun> latestRun();
}
