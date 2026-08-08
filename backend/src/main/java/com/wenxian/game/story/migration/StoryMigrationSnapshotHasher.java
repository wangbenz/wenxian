package com.wenxian.game.story.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.migration.V014MigrationPreviewModels.TargetSnapshot;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 对已归一化的迁移候选快照生成稳定 SHA-256。预演与落库回读必须共用
 * 该实现，否则不允许将候选命卷切换为可玩状态。
 */
final class StoryMigrationSnapshotHasher {
    private final ObjectMapper mapper;

    StoryMigrationSnapshotHasher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    String hash(TargetSnapshot snapshot) {
        try {
            byte[] source = mapper.writeValueAsBytes(snapshot);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("迁移候选快照哈希无法生成", error);
        }
    }
}
