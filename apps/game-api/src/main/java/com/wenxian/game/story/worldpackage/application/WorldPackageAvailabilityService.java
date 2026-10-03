package com.wenxian.game.story.worldpackage.application;

import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.*;
import com.wenxian.game.story.worldpackage.persistence.WorldPackageRevisionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/** 显式创角可用性与默认指针管理；scope 行锁串行修改，授权、审计与变更同事务。 */
@Service
public class WorldPackageAvailabilityService {
    private final WorldPackageRevisionRepository repository;
    private final TransactionTemplate transactions;
    private final TransactionTemplate initializations;
    public WorldPackageAvailabilityService(WorldPackageRevisionRepository repository, TransactionTemplate transactions) {
        this.repository = repository;
        this.transactions = transactions;
        this.initializations = new TransactionTemplate(transactions.getTransactionManager(), transactions);
        this.initializations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.initializations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 仅为系统首次建立 GLOBAL 范围、启用项、默认指针和审计；已有范围即使为空也不接管。
     *
     * <p>发布在调用本方法前独立完成。本事务按包、修订、范围顺序加锁，任一步失败全部回滚，
     * 保留已发布修订供重试；唯一键竞争必须退出失败事务后再重读，不能在已损事务里继续初始化。</p>
     */
    public boolean initializeDefaultIfAbsent(String revisionId, Principal principal) {
        if (!principal.system()) throw new SecurityException("只有系统主体可以初始化全局创角范围");
        if (revisionId == null || revisionId.isBlank()) throw new IllegalArgumentException("缺少初始化修订");
        Scope expected = new Scope("GLOBAL", null);
        try {
            return Boolean.TRUE.equals(initializations.execute(tx -> {
                var header = repository.revision(revisionId);
                var world = repository.packageById(header.packageId(), true);
                var revision = repository.lockRevision(revisionId);
                var existing = repository.lockScopeIfPresent(expected.id());
                if (existing.isPresent()) {
                    requireScopeOwner(existing.get(), expected);
                    return false;
                }
                if (!"ACTIVE".equals(world.status()) || !"PUBLISHED".equals(revision.status()))
                    throw new IllegalStateException("该修订不可初始化创角");
                if (!"PUBLIC".equals(world.visibility())) throw new SecurityException("私有包不能全局启用");
                repository.ensureScope(expected);
                repository.enable(expected.id(), revisionId);
                repository.audit(principal.key(), "ENABLE", expected.id(), revisionId);
                repository.setDefault(expected.id(), revisionId);
                repository.audit(principal.key(), "DEFAULT", expected.id(), revisionId);
                return true;
            }));
        } catch (DuplicateKeyException duplicate) {
            // execute 已完成回滚；新事务只判断并发胜出者，不重用失败事务或猜测空默认的含义。
            var existing = Objects.requireNonNull(initializations.execute(tx -> repository.lockScopeIfPresent(expected.id())));
            if (existing.isEmpty()) throw duplicate;
            requireScopeOwner(existing.get(), expected);
            return false;
        }
    }

    private static void requireScopeOwner(Scope actual, Scope expected) {
        if (!expected.equals(actual)) throw new SecurityException("创角范围归属冲突");
    }

    public boolean prepareScope(Principal principal) {
        Scope scope = new Scope(principal.system() ? "GLOBAL" : "USER:" + principal.userId(), principal.userId());
        var registration = new TransactionTemplate(transactions.getTransactionManager(), transactions);
        registration.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try { registration.executeWithoutResult(tx -> repository.ensureScope(scope)); }
        catch (DuplicateKeyException duplicate) {
            if (!repository.scope(scope.id(), false).equals(scope)) throw new SecurityException("创角范围归属冲突");
            return false;
        }
        return true;
    }

    public void enableForCreation(String revisionId, Principal principal) { change(revisionId, principal, "ENABLE"); }
    public void disableForCreation(String revisionId, Principal principal) { change(revisionId, principal, "DISABLE"); }
    public void setDefault(String revisionId, Principal principal) { change(revisionId, principal, "DEFAULT"); }

    private void change(String revisionId, Principal principal, String action) {
        String scopeId = principal.system() ? "GLOBAL" : "USER:" + principal.userId();
        transactions.executeWithoutResult(tx -> {
            if (revisionId != null) {
                var header = repository.revision(revisionId);
                var p = repository.packageById(header.packageId(), true);
                var r = repository.lockRevision(revisionId);
                if (!action.equals("DISABLE")) {
                    if (!"ACTIVE".equals(p.status()) || !"PUBLISHED".equals(r.status())) throw new IllegalStateException("该修订不可启用");
                    if (principal.system() && !"PUBLIC".equals(p.visibility())) throw new SecurityException("私有包不能全局启用");
                    if (!principal.system() && (!repository.lockInstalled(principal.userId(), revisionId)
                            || (!principal.userId().equals(p.ownerUserId()) && !"PUBLIC".equals(p.visibility())))) throw new SecurityException("需要可访问且已安装的修订");
                }
            } else if (!action.equals("DEFAULT")) throw new IllegalArgumentException("缺少修订");
            Scope scope = repository.scope(scopeId, true);
            if (!java.util.Objects.equals(scope.userId(), principal.userId())) throw new SecurityException("不能修改其他范围");
            // 当前读避开 MySQL REPEATABLE READ 在等待行锁前建立的旧快照。
            var enabled = repository.lockEnabled(scopeId);
            if (action.equals("ENABLE")) {
                if (!enabled.contains(revisionId)) repository.enable(scopeId, revisionId);
            } else if (action.equals("DISABLE")) {
                if (repository.lockDefault(scopeId).filter(revisionId::equals).isPresent()) throw new IllegalStateException("请先显式清空或切换默认项");
                repository.disable(scopeId, revisionId);
            } else {
                if (revisionId != null && !enabled.contains(revisionId)) throw new IllegalStateException("默认项必须已在同一范围启用");
                repository.setDefault(scopeId, revisionId);
            }
            repository.audit(principal.key(), action, scopeId, revisionId);
        });
    }
}
