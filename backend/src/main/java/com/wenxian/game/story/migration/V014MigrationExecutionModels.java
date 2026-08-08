package com.wenxian.game.story.migration;

import java.util.List;

/**
 * V0.14 命卷正式迁移的阶段结果。结果只表示候选数据和审计状态，
 * 不代表 H5 已经切换到统一 story 运行时。
 */
public final class V014MigrationExecutionModels {
    private V014MigrationExecutionModels() {}

    public enum ExecutionStage {
        PREPARED,
        ALREADY_PREPARED,
        ACTIVATED,
        ALREADY_ACTIVATED,
        HASH_MISMATCH
    }

    /** 单份命卷的准备或激活结果，哈希可用于管理台人工复核。 */
    public record MigrationExecutionResult(
            ExecutionStage stage,
            String sourceSaveId,
            String targetSaveId,
            String beforeHash,
            String afterHash,
            List<String> warningCodes,
            String message
    ) {}
}
