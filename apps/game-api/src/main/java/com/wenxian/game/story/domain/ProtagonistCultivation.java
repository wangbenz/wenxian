package com.wenxian.game.story.domain;

/**
 * 主角在已确认修行体系中的当前权威位置。
 *
 * <p>大境界保存稳定 ID，小阶段与进度标记由服务端维护。玩家节奏只决定进入当前小阶段时固定的
 * {@code requiredProgressMarks}，模型不能直接选择这些权威值。</p>
 */
public record ProtagonistCultivation(
        String systemId,
        String currentStageId,
        WorldPackage.RealmPhase phase,
        int progressMarks,
        int requiredProgressMarks,
        String progressNote
) {
    public ProtagonistCultivation(String systemId, String currentStageId, String progressNote) {
        this(systemId, currentStageId, WorldPackage.RealmPhase.EARLY, 0, 2, progressNote);
    }

    public ProtagonistCultivation {
        if (phase == null) throw new IllegalArgumentException("角色境界小阶段不能为空");
        if (progressMarks < 0) throw new IllegalArgumentException("修行进度标记不能小于零");
        if (requiredProgressMarks < 1) throw new IllegalArgumentException("小阶段所需进度标记必须大于零");
        if (progressMarks >= requiredProgressMarks) {
            throw new IllegalArgumentException("达到阈值的修行进度必须先归并为小阶段变化");
        }
        progressNote = progressNote == null ? "" : progressNote.strip();
    }
}
