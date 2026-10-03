package com.wenxian.game.story.worldpackage.persistence;

import com.wenxian.game.story.worldpackage.application.WorldPackageCandidate;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.*;
import java.util.List;
import java.util.Optional;

/** 世界包注册表持久化端口；只保存/读取及提供行锁，事务和授权由应用服务裁决。 */
public interface WorldPackageRevisionRepository {
    void insertPackage(PackageRow row);
    Optional<PackageRow> findPackage(String namespace, String key);
    PackageRow packageById(String id, boolean lock);
    Optional<RevisionRow> findRevision(String packageId, long revision);
    Optional<RevisionRow> findContent(String packageId, String contentHash);
    RevisionRow revision(String id);
    RevisionRow lockRevision(String id);
    StoredContent content(String revisionId);
    /** 运行时仅取已发布快照，不加载作者原文或素材原件。 */
    String compiledSnapshot(String revisionId);
    List<com.wenxian.game.story.worldpackage.document.WorldPackageDocument.BundleAsset> assets(String revisionId);
    Optional<PublicationResult> publication(String packageId, String principal, String key);
    void insertRevision(String id, String packageId, WorldPackageCandidate candidate, String principal);
    void savePublication(String packageId, String principal, String key, String requestHash, String revisionId);
    void advanceRevision(String packageId, long revision);
    void ensureScope(Scope scope);
    Scope scope(String scopeId, boolean lock);
    Optional<Scope> lockScopeIfPresent(String scopeId);
    List<String> enabled(String scopeId);
    List<String> lockEnabled(String scopeId);
    Optional<String> defaultRevision(String scopeId);
    Optional<String> lockDefault(String scopeId);
    boolean installed(String userId, String revisionId);
    boolean lockInstalled(String userId, String revisionId);
    void install(String userId, String revisionId);
    void enable(String scopeId, String revisionId);
    void disable(String scopeId, String revisionId);
    void setDefault(String scopeId, String revisionId);
    void audit(String actor, String action, String scopeId, String revisionId);
}
