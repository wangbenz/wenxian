package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.*;
import com.wenxian.game.story.worldpackage.persistence.WorldPackageRevisionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;

/** 从注册表加载并复核修订；容量有限的缓存只保存内容，每次查询重新检查数据库治理与访问权限。 */
@Service
public class WorldPackageQueryService {
    private final WorldPackageRevisionRepository repository;
    private final CompiledWorldPackageSnapshotReader snapshots;
    // 按序列化 UTF-8 字节预算控制内容缓存；不是 JVM 对象堆大小的精确估算。
    private static final int DEFAULT_MAX_ENTRIES = 128;
    private static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;
    private final int maximumEntries;
    private final long maximumBytes;
    private long cachedBytes;
    private final Map<CacheKey, CachedSnapshot> cache = new LinkedHashMap<>(32, .75f, true);
    private final ConcurrentHashMap<CacheKey, CompletableFuture<CachedSnapshot>> loading = new ConcurrentHashMap<>();
    private record CacheKey(String id, String hash, String contract, String compiledHash) {}
    private record CachedSnapshot(CompiledWorldPackage value, long bytes) {}

    @Autowired
    public WorldPackageQueryService(WorldPackageRevisionRepository repository, ObjectMapper mapper) {
        this(repository, mapper, DEFAULT_MAX_ENTRIES, DEFAULT_MAX_BYTES);
    }

    WorldPackageQueryService(WorldPackageRevisionRepository repository, ObjectMapper mapper,
                             int maximumEntries, long maximumBytes) {
        if (maximumEntries < 1 || maximumBytes < 1) throw new IllegalArgumentException("世界包缓存上限无效");
        this.repository = Objects.requireNonNull(repository);
        this.snapshots = new CompiledWorldPackageSnapshotReader(mapper);
        this.maximumEntries = maximumEntries;
        this.maximumBytes = maximumBytes;
    }

    /** 系统内部按精确绑定读取，供已有命卷使用；调用前仍需命卷归属检查。普通撤回不破坏历史。 */
    public ResolvedRevision boundRevision(String id) {
        var r = repository.revision(id);
        var p = repository.packageById(r.packageId(), false);
        if ("SUSPENDED".equals(p.status()) || "SUSPENDED".equals(r.status())) throw new IllegalStateException("该世界包因安全处置暂停使用");
        return new ResolvedRevision(p, r, compiled(p, r));
    }

    public ResolvedRevision accessible(String userId, String id) {
        var r = repository.revision(id);
        var p = repository.packageById(r.packageId(), false);
        if (!mayRead(userId, p, id)) throw new SecurityException("没有访问该世界包的权限");
        if ("SUSPENDED".equals(p.status()) || "SUSPENDED".equals(r.status())) throw new IllegalStateException("该世界包因安全处置暂停使用");
        return new ResolvedRevision(p, r, compiled(p, r));
    }

    public boolean mayRead(String userId, PackageRow p, String revisionId) {
        return "PUBLIC".equals(p.visibility()) || (userId != null && (userId.equals(p.ownerUserId()) || repository.installed(userId, revisionId)));
    }

    public ResolvedRevision forCreation(String userId, String id) {
        var r = accessible(userId, id);
        if (!"ACTIVE".equals(r.worldPackage().status()) || !"PUBLISHED".equals(r.revision().status())) throw new IllegalStateException("该世界修订已停止创角");
        boolean enabled = repository.enabled("GLOBAL").contains(id) || (userId != null && repository.enabled("USER:" + userId).contains(id));
        if (!enabled) throw new IllegalStateException("该世界修订尚未启用创角");
        return r;
    }

    /** 调用方必须已经开启命卷写事务；锁序与启用服务一致，锁保持到命卷提交。 */
    public void lockForCreation(String userId, String id) {
        var header = repository.revision(id);
        var p = repository.packageById(header.packageId(), true);
        var r = repository.lockRevision(id);
        boolean access = "PUBLIC".equals(p.visibility()) || (userId != null
                && (userId.equals(p.ownerUserId()) || repository.lockInstalled(userId, id)));
        if (!access) throw new SecurityException("没有访问该世界包的权限");
        if (!"ACTIVE".equals(p.status()) || !"PUBLISHED".equals(r.status())) throw new IllegalStateException("该世界修订已停止创角");
        repository.scope("GLOBAL", true);
        boolean userScope = userId != null && repository.lockScopeIfPresent("USER:" + userId).isPresent();
        boolean enabled = repository.lockEnabled("GLOBAL").contains(id)
                || (userScope && repository.lockEnabled("USER:" + userId).contains(id));
        if (!enabled) throw new IllegalStateException("该世界修订尚未启用创角");
    }

    public ResolvedRevision defaultForCreation(String userId) {
        Optional<String> selected = userId == null ? Optional.empty() : repository.defaultRevision("USER:" + userId);
        String id = selected.or(() -> repository.defaultRevision("GLOBAL")).orElseThrow(() -> new IllegalStateException("未配置创角默认世界"));
        return forCreation(userId, id);
    }

    public List<ResolvedRevision> listForCreation(String userId) {
        Set<String> ids = new LinkedHashSet<>(repository.enabled("GLOBAL"));
        if (userId != null) ids.addAll(repository.enabled("USER:" + userId));
        List<ResolvedRevision> result = new ArrayList<>();
        for (String id : ids) {
            var r = repository.revision(id); var p = repository.packageById(r.packageId(), false);
            if ("ACTIVE".equals(p.status()) && "PUBLISHED".equals(r.status()) && mayRead(userId, p, id))
                result.add(new ResolvedRevision(p, r, compiled(p, r)));
        }
        return List.copyOf(result);
    }

    public ResolvedRevision portable(String namespace, String packageKey, long revision) {
        var p = repository.findPackage(namespace, packageKey).orElseThrow(() -> new IllegalArgumentException("世界包未发布"));
        var r = repository.findRevision(p.id(), revision).orElseThrow(() -> new IllegalArgumentException("世界修订未发布"));
        return boundRevision(r.id());
    }

    private CompiledWorldPackage compiled(PackageRow p, RevisionRow r) {
        CacheKey key = new CacheKey(r.id(), r.contentHash(), r.runtimeContract(), r.compiledHash());
        CachedSnapshot found = cached(key);
        if (found == null) found = loadSnapshot(key, p, r);
        // 缓存不能掩盖数据库头部身份或能力声明的不一致。
        snapshots.validateBinding(p, r, found.value());
        return found.value();
    }

    private CachedSnapshot loadSnapshot(CacheKey key, PackageRow p, RevisionRow r) {
        CompletableFuture<CachedSnapshot> mine = new CompletableFuture<>();
        CompletableFuture<CachedSnapshot> existing = loading.putIfAbsent(key, mine);
        if (existing != null) {
            try { return existing.join(); }
            catch (CompletionException failure) { throw propagate(failure.getCause()); }
        }
        try {
            // 避免刚完成的并发加载已经入缓存后再次取库。
            CachedSnapshot result = cached(key);
            if (result == null) {
                String json = repository.compiledSnapshot(r.id());
                result = new CachedSnapshot(snapshots.read(p, r, json), json.getBytes(StandardCharsets.UTF_8).length);
                remember(key, result);
            }
            mine.complete(result);
            return result;
        } catch (RuntimeException | Error failure) {
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            loading.remove(key, mine);
        }
    }

    private CachedSnapshot cached(CacheKey key) {
        synchronized (cache) { return cache.get(key); }
    }

    private void remember(CacheKey key, CachedSnapshot snapshot) {
        // 合法的大快照可以使用，但不挤占超过预算的缓存。
        if (snapshot.bytes() > maximumBytes) return;
        synchronized (cache) {
            CachedSnapshot replaced = cache.put(key, snapshot);
            cachedBytes += snapshot.bytes() - (replaced == null ? 0 : replaced.bytes());
            while (cache.size() > maximumEntries || cachedBytes > maximumBytes) {
                var iterator = cache.entrySet().iterator();
                cachedBytes -= iterator.next().getValue().bytes();
                iterator.remove();
            }
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("世界包快照加载失败", failure);
    }
}
