package com.wenxian.game.story.worldpackage.application;

import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.Principal;
import com.wenxian.game.story.worldpackage.persistence.WorldPackageRevisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 安装精确修订，仅记录持有关系；授权或数据库失败不产生安装，绝不启用或改默认。 */
@Service
public class WorldPackageInstallationService {
    private final WorldPackageRevisionRepository repository;
    private final TransactionTemplate transactions;
    public WorldPackageInstallationService(WorldPackageRevisionRepository repository, TransactionTemplate transactions) { this.repository = repository; this.transactions = transactions; }
    public void installForUser(String revisionId, Principal principal) {
        if (principal.system()) throw new IllegalArgumentException("安装需要玩家主体");
        transactions.executeWithoutResult(tx -> {
            var header = repository.revision(revisionId);
            var p = repository.packageById(header.packageId(), true);
            var r = repository.lockRevision(revisionId);
            if (!"ACTIVE".equals(p.status()) || !"PUBLISHED".equals(r.status())) throw new IllegalStateException("该修订不可安装");
            if (!principal.userId().equals(p.ownerUserId()) && !"PUBLIC".equals(p.visibility())) throw new SecurityException("没有安装权限");
            if (!repository.lockInstalled(principal.userId(), revisionId)) repository.install(principal.userId(), revisionId);
        });
    }
}
