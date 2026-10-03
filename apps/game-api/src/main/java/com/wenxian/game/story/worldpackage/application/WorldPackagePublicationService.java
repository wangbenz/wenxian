package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.PackageIdentity;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.*;
import com.wenxian.game.story.worldpackage.persistence.WorldPackageRevisionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

/** 发布不可变修订；包行锁内分配编号、写素材与幂等结果，任一步异常整体回滚，不启用创角。 */
@Service
public class WorldPackagePublicationService {
    private final WorldPackageRevisionRepository repository;
    private final TransactionTemplate transactions;
    private final WorldPackageCanonicalizer canonicalizer;

    public WorldPackagePublicationService(WorldPackageRevisionRepository repository, TransactionTemplate transactions, ObjectMapper mapper) {
        this.repository = repository;
        // 发布是独立用例，不继承调用方已经建立的旧一致性快照或在外部事务中留下半个幂等结果。
        this.transactions = new TransactionTemplate(transactions.getTransactionManager(), transactions);
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.canonicalizer = new WorldPackageCanonicalizer(mapper);
    }

    /** 受控官方身份登记；独立于发布，失败最多留下无内容身份，不信任包内所有者声明。 */
    public PackageRow registerOfficial(Principal principal, String namespace, String key) {
        if (!principal.system() || !"wenxian.official".equals(namespace)) throw new SecurityException("无权登记官方身份");
        return register(new PackageRow(UUID.randomUUID().toString(), namespace, key, null, "OFFICIAL", "PUBLIC", "ACTIVE", 0));
    }

    /** 玩家发布身份由服务器签发，不接收玩家指定 namespace 或冒用现存包名。 */
    public PackageRow registerPlayer(Principal principal) {
        if (principal.system()) throw new IllegalArgumentException("玩家包需要账号主体");
        return register(new PackageRow(UUID.randomUUID().toString(), "player." + UUID.randomUUID(),
                "world-" + UUID.randomUUID(), principal.userId(), "USER", "PRIVATE", "ACTIVE", 0));
    }

    private PackageRow register(PackageRow row) {
        try { transactions.executeWithoutResult(s -> repository.insertPackage(row)); }
        catch (DuplicateKeyException duplicate) {
            var existing = repository.findPackage(row.namespace(), row.packageKey()).orElseThrow(() -> duplicate);
            if (!existing.sourceType().equals(row.sourceType()) || !java.util.Objects.equals(existing.ownerUserId(), row.ownerUserId()))
                throw new SecurityException("包身份已由其他主体占用");
            return existing;
        }
        return row;
    }

    /** 精确官方制品保留已验证的修订号；普通作者发布由服务端分配，不能自签号。 */
    public RevisionRow publish(String packageId, WorldPackageCandidate input, Principal principal, String key, boolean exact) {
        if (key == null || key.isBlank() || key.length() > 128) throw new IllegalArgumentException("发布幂等键无效");
        if (exact && !principal.system()) throw new SecurityException("精确发布需要受控来源证明");
        WorldPackageCandidate verified = rebuild(input, input.document().identity());
        if (!verified.contentHash().equals(input.contentHash()) || !verified.compiledHash().equals(input.compiledHash())
                || !verified.canonicalJson().equals(input.canonicalJson()) || !verified.compiledJson().equals(input.compiledJson())
                || !verified.compilerVersion().equals(input.compilerVersion())) throw new IllegalArgumentException("候选内容或编译证明不一致");
        String requestHash = WorldPackageCanonicalizer.sha256((exact ? "EXACT:" + input.document().identity().revision() : "NEXT") + ":" + verified.contentHash());
        return transactions.execute(tx -> {
            PackageRow p = repository.packageById(packageId, true);
            mayPublish(principal, p);
            var identity = verified.document().identity();
            if (!p.namespace().equals(identity.publisherNamespace()) || !p.packageKey().equals(identity.packageId()))
                throw new IllegalArgumentException("候选包身份与授权目标不符");
            var prior = repository.publication(packageId, principal.key(), key);
            if (prior.isPresent()) {
                if (!prior.get().requestHash().equals(requestHash)) throw new IllegalArgumentException("幂等键已用于不同发布请求");
                return repository.revision(prior.get().revisionId());
            }
            var sameNumber = exact ? repository.findRevision(packageId, identity.revision()) : java.util.Optional.<RevisionRow>empty();
            var sameContent = repository.findContent(packageId, verified.contentHash());
            RevisionRow result;
            if (sameNumber.isPresent()) {
                result = sameNumber.get();
                if (!result.contentHash().equals(verified.contentHash())) throw new IllegalArgumentException("同一修订号内容不同，禁止覆盖");
            } else if (sameContent.isPresent()) {
                if (exact) throw new IllegalArgumentException("相同内容已发布为另一修订");
                result = sameContent.get();
            } else {
                long number = exact ? identity.revision() : Math.addExact(p.latestRevision(), 1);
                // 精确历史制品允许补齐较小的缺失号；下一个作者号仍按已发布上界分配。
                var assigned = new PackageIdentity(p.namespace(), p.packageKey(), number, identity.formatVersion(), identity.runtimeContract(), identity.requiredCapabilities());
                WorldPackageCandidate candidate = rebuild(verified, assigned);
                String id = UUID.randomUUID().toString();
                repository.insertRevision(id, packageId, candidate, principal.key());
                repository.advanceRevision(packageId, Math.max(p.latestRevision(), number));
                result = repository.revision(id);
            }
            repository.savePublication(packageId, principal.key(), key, requestHash, result.id());
            return result;
        });
    }

    private static void mayPublish(Principal principal, PackageRow p) {
        if (!"ACTIVE".equals(p.status())) throw new IllegalStateException("世界包当前不可发布");
        if (principal.system()) return;
        if (!"USER".equals(p.sourceType()) || !principal.userId().equals(p.ownerUserId())) throw new SecurityException("没有世界包发布权限");
    }

    private WorldPackageCandidate rebuild(WorldPackageCandidate c, PackageIdentity identity) {
        var d = c.document();
        var assigned = new WorldPackageDocument(identity, d.metadata(), d.world(), d.creation(), d.progression(), d.loreEntries(), d.assets());
        new WorldPackageValidator().validate(assigned);
        if (c.assets().size() != d.assets().size()) throw new IllegalArgumentException("素材原件缺失");
        var descriptors = c.assets().stream().map(WorldPackageDocument.BundleAsset::descriptor).toList();
        if (new java.util.HashSet<>(descriptors).size() != descriptors.size() || !new java.util.HashSet<>(descriptors).equals(new java.util.HashSet<>(d.assets())))
            throw new IllegalArgumentException("素材清单与候选不符");
        for (var a : c.assets()) if (a.content().length != a.descriptor().byteSize() || !WorldPackageCanonicalizer.sha256(a.content()).equals(a.descriptor().sha256()))
            throw new IllegalArgumentException("素材原件哈希不符");
        var compiled = new WorldPackageCompiler().compile(assigned);
        var canonical = canonicalizer.canonicalize(assigned, compiled);
        return new WorldPackageCandidate(assigned, compiled, canonical.canonicalJson(), canonical.contentHash(), canonical.compiledJson(), canonical.compiledHash(), WorldPackageValidator.COMPILER_VERSION, c.assets());
    }
}
