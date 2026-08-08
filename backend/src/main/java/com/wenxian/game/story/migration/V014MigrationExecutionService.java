package com.wenxian.game.story.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.migration.StoryMigrationRepository.MigrationRecord;
import com.wenxian.game.story.migration.V014MigrationExecutionModels.ExecutionStage;
import com.wenxian.game.story.migration.V014MigrationExecutionModels.MigrationExecutionResult;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.PreviewReport;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.PreviewStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * V0.14 命卷迁入统一 story 领域的两阶段执行器。prepare 只写入不可玩候选命卷并回读校验；
 * activate 再次校验旧表哈希和候选哈希后才激活。任一校验失败都不修改 V0.14 旧数据，
 * 也不会让半成品命卷进入玩家可玩列表。
 */
@Service
public class V014MigrationExecutionService {
    private static final Set<String> ACTIVATABLE_STATUSES = Set.of("ACTIVE", "ARCHIVED", "DEAD");

    private final V014MigrationPreviewService previews;
    private final StoryMigrationRepository migrations;
    private final StoryMigrationSnapshotHasher snapshotHasher;

    public V014MigrationExecutionService(V014MigrationPreviewService previews,
                                         StoryMigrationRepository migrations,
                                         ObjectMapper mapper) {
        this.previews = previews;
        this.migrations = migrations;
        this.snapshotHasher = new StoryMigrationSnapshotHasher(mapper);
    }

    /** 写入 MIGRATING 候选命卷，回读哈希相同后将任务标记为 READY。 */
    @Transactional
    public MigrationExecutionResult prepare(String sourceSaveId) {
        PreviewReport report = previews.preview(sourceSaveId);
        if (report.status() == PreviewStatus.INVALID || report.target() == null) {
            throw new IllegalStateException("旧命卷预演未通过，不允许写入候选数据："
                    + String.join(",", allIssueCodes(report)));
        }

        MigrationRecord existing = migrations.findBySource(sourceSaveId).orElse(null);
        if (existing != null) {
            if ("SUCCESS".equals(existing.status())) {
                return result(ExecutionStage.ALREADY_ACTIVATED, report, "该命卷已完成迁移激活");
            }
            if ("READY".equals(existing.status())
                    && report.beforeHash().equals(existing.beforeHash())
                    && report.afterHash().equals(existing.afterHash())) {
                return result(ExecutionStage.ALREADY_PREPARED, report, "候选命卷已准备，等待明确激活");
            }
            throw new IllegalStateException("该命卷已有状态为 " + existing.status()
                    + " 的迁移任务，需要人工复核后再重试");
        }

        String migrationId = "migration-" + report.targetSaveId().substring("story-v014-".length());
        migrations.insertRunning(migrationId, report);
        migrations.insertCandidate(report);
        String storedHash = snapshotHasher.hash(migrations.readCandidate(report.targetSaveId()));
        if (!report.afterHash().equals(storedHash)) {
            throw new IllegalStateException("候选命卷落库回读哈希不一致，本次准备已整体回滚");
        }
        migrations.markReady(migrationId, report.targetSaveId(), storedHash);
        return result(ExecutionStage.PREPARED, report, "候选命卷已落库并通过回读校验，尚未激活");
    }

    /** 只激活已 READY 且源数据、目标数据哈希均未变化的候选命卷。 */
    @Transactional
    public MigrationExecutionResult activate(String sourceSaveId) {
        MigrationRecord task = migrations.findBySource(sourceSaveId)
                .orElseThrow(() -> new IllegalStateException("该命卷尚未准备迁移候选数据"));
        PreviewReport report = previews.preview(sourceSaveId);
        if ("SUCCESS".equals(task.status())) {
            return result(ExecutionStage.ALREADY_ACTIVATED, report, "该命卷已完成迁移激活");
        }
        if (!"READY".equals(task.status())) {
            throw new IllegalStateException("迁移任务当前状态为 " + task.status() + "，不允许激活");
        }
        if (report.status() == PreviewStatus.INVALID || !task.beforeHash().equals(report.beforeHash())) {
            migrations.markHashMismatch(task.id(), "源命卷在准备后发生变化");
            return result(ExecutionStage.HASH_MISMATCH, report, "源命卷已变化，候选命卷保持隔离");
        }
        String storedHash;
        try {
            storedHash = snapshotHasher.hash(migrations.readCandidate(task.targetSaveId()));
        } catch (RuntimeException error) {
            migrations.markHashMismatch(task.id(), "候选命卷结构不完整");
            return result(ExecutionStage.HASH_MISMATCH, report, "候选命卷不完整，已阻止激活");
        }
        if (!task.afterHash().equals(storedHash)) {
            migrations.markHashMismatch(task.id(), "候选命卷在准备后发生变化");
            return result(ExecutionStage.HASH_MISMATCH, report, "候选命卷哈希不一致，已阻止激活");
        }

        String targetStatus = ACTIVATABLE_STATUSES.contains(report.sourceStatus())
                ? report.sourceStatus() : "ARCHIVED";
        migrations.activate(task.id(), task.targetSaveId(), targetStatus);
        return result(ExecutionStage.ACTIVATED, report, "候选命卷已通过双重哈希校验并激活");
    }

    private MigrationExecutionResult result(ExecutionStage stage, PreviewReport report, String message) {
        return new MigrationExecutionResult(stage, report.sourceSaveId(), report.targetSaveId(),
                report.beforeHash(), report.afterHash(), issueCodes(report), message);
    }

    private List<String> issueCodes(PreviewReport report) {
        return report.issues().stream().filter(issue -> "WARNING".equals(issue.severity()))
                .map(V014MigrationPreviewModels.MigrationIssue::code).distinct().toList();
    }

    private List<String> allIssueCodes(PreviewReport report) {
        return report.issues().stream().map(V014MigrationPreviewModels.MigrationIssue::code).distinct().toList();
    }
}
