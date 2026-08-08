package com.wenxian.game.story.migration;

import com.wenxian.game.story.migration.StoryMigrationRepository.MigrationRecord;
import com.wenxian.game.story.migration.V014MigrationBatchModels.ActivateRequest;
import com.wenxian.game.story.migration.V014MigrationBatchModels.BatchOperationResult;
import com.wenxian.game.story.migration.V014MigrationBatchModels.MigrationOverview;
import com.wenxian.game.story.migration.V014MigrationBatchModels.OperationItem;
import com.wenxian.game.story.migration.V014MigrationBatchModels.PreviewItem;
import com.wenxian.game.story.migration.V014MigrationBatchModels.PreviewPage;
import com.wenxian.game.story.migration.V014MigrationExecutionModels.MigrationExecutionResult;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.PreviewReport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * V0.14 命卷的有限批量调度器。预演只读；准备逐份独立事务；激活只允许单份并要求管理台提交双哈希。
 * 该类故意不提供“一键准备全部”或“一键激活全部”。
 */
@Service
public class V014MigrationBatchService {
    private final V014MigrationSourceReader sources;
    private final V014MigrationPreviewService previews;
    private final V014MigrationExecutionService executions;
    private final StoryMigrationRepository migrations;
    private final StoryMigrationOperationPolicy policy;

    public V014MigrationBatchService(V014MigrationSourceReader sources,
                                     V014MigrationPreviewService previews,
                                     V014MigrationExecutionService executions,
                                     StoryMigrationRepository migrations,
                                     StoryMigrationOperationPolicy policy) {
        this.sources = sources;
        this.previews = previews;
        this.executions = executions;
        this.migrations = migrations;
        this.policy = policy;
    }

    /** 返回源数据总量、迁移状态和当前开关，不执行预演或写入。 */
    public MigrationOverview overview() {
        long sourceTotal = sources.countAll();
        Map<String, Long> statusCounts = migrations.countByStatus();
        long processed = statusCounts.values().stream().mapToLong(Long::longValue).sum();
        return new MigrationOverview(sourceTotal, Math.max(0, sourceTotal - processed), statusCounts, policy.current());
    }

    /** 对一小页旧命卷执行只读预演；单份损坏会转换为该行错误，不中断整页观察。 */
    public PreviewPage previewPage(String cursor, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, policy.current().batchLimit()));
        List<String> ids = sources.listSaveIdsAfter(cursor, limit);
        List<PreviewItem> items = ids.stream().map(this::previewOne).toList();
        String nextCursor = ids.size() < limit ? null : ids.getLast();
        return new PreviewPage(cursor == null ? "" : cursor, nextCursor, limit, items);
    }

    /**
     * 准备管理台明确提交的源命卷列表。每份命卷由执行器开启独立事务，失败项不会回滚已成功项；
     * 重复 ID 会去重，数量不得超过动态批量上限。
     */
    public BatchOperationResult prepareBatch(List<String> sourceSaveIds) {
        policy.requirePrepareEnabled();
        List<String> ids = sanitizeIds(sourceSaveIds);
        int limit = policy.current().batchLimit();
        if (ids.isEmpty()) throw new IllegalArgumentException("至少选择一份待准备的旧命卷");
        if (ids.size() > limit) throw new IllegalArgumentException("单次最多准备 " + limit + " 份命卷");

        List<OperationItem> items = new ArrayList<>();
        for (String id : ids) {
            try {
                items.add(success(executions.prepare(id)));
            } catch (RuntimeException error) {
                items.add(failure(id, error));
            }
        }
        int succeeded = (int) items.stream().filter(OperationItem::success).count();
        return new BatchOperationResult(ids.size(), succeeded, ids.size() - succeeded, List.copyOf(items));
    }

    /** 单份激活已准备候选，双哈希不等于审计表当前值时立即拒绝。 */
    public OperationItem activateOne(ActivateRequest request) {
        policy.requireActivationEnabled();
        if (request == null || blank(request.sourceSaveId()) || blank(request.expectedBeforeHash())
                || blank(request.expectedAfterHash())) {
            throw new IllegalArgumentException("激活命卷必须提供源命卷 ID 和迁移双哈希");
        }
        MigrationRecord task = migrations.findBySource(request.sourceSaveId().strip())
                .orElseThrow(() -> new IllegalStateException("没有找到该命卷的迁移任务"));
        if (!Objects.equals(task.beforeHash(), request.expectedBeforeHash().strip())
                || !Objects.equals(task.afterHash(), request.expectedAfterHash().strip())) {
            throw new IllegalStateException("管理台提交的哈希已过期，请重新预演并核对");
        }
        return success(executions.activate(request.sourceSaveId().strip()));
    }

    public List<V014MigrationBatchModels.MigrationTaskItem> recentTasks(int limit) {
        return migrations.recentTasks(limit);
    }

    private PreviewItem previewOne(String sourceSaveId) {
        String migrationStatus = migrations.findBySource(sourceSaveId).map(MigrationRecord::status).orElse("UNPROCESSED");
        try {
            PreviewReport report = previews.preview(sourceSaveId);
            int entities = report.target() == null ? 0 : report.target().entities().size();
            List<String> issueCodes = report.issues().stream().map(V014MigrationPreviewModels.MigrationIssue::code)
                    .distinct().sorted().toList();
            return new PreviewItem(sourceSaveId, report.targetSaveId(), report.status().name(), migrationStatus,
                    report.sourceTurnCount(), entities, report.memoryCount(), issueCodes,
                    report.beforeHash(), report.afterHash(), null);
        } catch (RuntimeException error) {
            return new PreviewItem(sourceSaveId, null, "ERROR", migrationStatus, 0, 0, 0, List.of(),
                    null, null, safeMessage(error));
        }
    }

    private List<String> sanitizeIds(List<String> values) {
        if (values == null) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.stream().filter(Objects::nonNull).map(String::strip).filter(value -> !value.isBlank())
                .forEach(result::add);
        return List.copyOf(result);
    }

    private OperationItem success(MigrationExecutionResult result) {
        return new OperationItem(result.sourceSaveId(), true, result.stage().name(), result.targetSaveId(),
                result.beforeHash(), result.afterHash(), result.message());
    }

    private OperationItem failure(String sourceSaveId, RuntimeException error) {
        return new OperationItem(sourceSaveId, false, "FAILED", null, null, null, safeMessage(error));
    }

    private String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "迁移操作失败，请查看服务日志" : message;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
