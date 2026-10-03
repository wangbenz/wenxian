package com.wenxian.game.story.worldpackage.application;

import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage;

/**
 * 通过摄取、校验和确定性编译但尚未发布的世界包候选。
 *
 * <p>候选不代表已经取得包所有权、修订号发布权或创角可用性。后续发布服务必须重新检查主体权限、
 * 幂等键和并发修订边界，不能把本对象直接视为数据库中的已发布修订。</p>
 */
public record WorldPackageCandidate(
        WorldPackageDocument document,
        CompiledWorldPackage compiled,
        String canonicalJson,
        String contentHash,
        String compiledJson,
        String compiledHash,
        String compilerVersion,
        java.util.List<WorldPackageDocument.BundleAsset> assets
) {
    public WorldPackageCandidate { assets = java.util.List.copyOf(assets); }
}
