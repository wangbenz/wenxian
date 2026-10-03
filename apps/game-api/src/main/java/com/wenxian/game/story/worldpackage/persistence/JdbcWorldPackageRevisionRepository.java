package com.wenxian.game.story.worldpackage.persistence;

import com.wenxian.game.story.worldpackage.application.WorldPackageCandidate;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC 注册表；不吞数据库异常，不覆盖修订正文，锁操作必须由应用事务包裹。 */
@Repository
public class JdbcWorldPackageRevisionRepository implements WorldPackageRevisionRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<PackageRow> PACKAGES = (r, n) -> new PackageRow(r.getString("id"),
            r.getString("publisher_namespace"), r.getString("package_key"), r.getString("owner_user_id"),
            r.getString("source_type"), r.getString("visibility"), r.getString("status"), r.getLong("latest_revision"));
    private static final RowMapper<RevisionRow> REVISIONS = (r, n) -> new RevisionRow(r.getString("id"),
            r.getString("package_id"), r.getLong("revision"), r.getInt("format_version"), r.getString("runtime_contract"),
            r.getString("content_hash"), r.getString("compiled_hash"), r.getString("compiler_version"), r.getString("status"), r.getString("required_capabilities_json"));
    private static final String HEAD = "id,package_id,revision,format_version,runtime_contract,content_hash,compiled_hash,compiler_version,status,required_capabilities_json";

    public JdbcWorldPackageRevisionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void insertPackage(PackageRow p) {
        jdbc.update("insert into world_package(id,publisher_namespace,package_key,owner_user_id,source_type,visibility,status,latest_revision) values(?,?,?,?,?,?,?,?)",
                p.id(), p.namespace(), p.packageKey(), p.ownerUserId(), p.sourceType(), p.visibility(), p.status(), p.latestRevision());
    }
    public Optional<PackageRow> findPackage(String namespace, String key) {
        return jdbc.query("select * from world_package where publisher_namespace=? and package_key=?", PACKAGES, namespace, key).stream().findFirst();
    }
    public PackageRow packageById(String id, boolean lock) {
        return jdbc.queryForObject("select * from world_package where id=?" + (lock ? " for update" : ""), PACKAGES, id);
    }
    public Optional<RevisionRow> findRevision(String packageId, long revision) {
        return jdbc.query("select " + HEAD + " from world_package_revision where package_id=? and revision=?", REVISIONS, packageId, revision).stream().findFirst();
    }
    public Optional<RevisionRow> findContent(String packageId, String hash) {
        return jdbc.query("select " + HEAD + " from world_package_revision where package_id=? and content_hash=?", REVISIONS, packageId, hash).stream().findFirst();
    }
    public RevisionRow revision(String id) {
        return jdbc.queryForObject("select " + HEAD + " from world_package_revision where id=?", REVISIONS, id);
    }
    public RevisionRow lockRevision(String id) {
        return jdbc.queryForObject("select " + HEAD + " from world_package_revision where id=? for update", REVISIONS, id);
    }
    public StoredContent content(String id) {
        return jdbc.queryForObject("select canonical_json,compiled_json from world_package_revision where id=?",
                (r, n) -> new StoredContent(r.getString(1), r.getString(2)), id);
    }
    public String compiledSnapshot(String id) {
        return jdbc.queryForObject("select compiled_json from world_package_revision where id=?", String.class, id);
    }
    public List<com.wenxian.game.story.worldpackage.document.WorldPackageDocument.BundleAsset> assets(String id) {
        return jdbc.query("select asset_path,media_type,byte_size,content_hash,asset_bytes from world_package_asset where revision_id=? order by asset_path",
                (r, n) -> new com.wenxian.game.story.worldpackage.document.WorldPackageDocument.BundleAsset(
                        new com.wenxian.game.story.worldpackage.document.WorldPackageDocument.AssetDescriptor(
                                r.getString(1), "", r.getString(2), r.getLong(3), r.getString(4)), r.getBytes(5)), id);
    }
    public Optional<PublicationResult> publication(String packageId, String principal, String key) {
        return jdbc.query("select request_hash,revision_id from world_package_publication_request where package_id=? and principal_key=? and idempotency_key=?",
                (r, n) -> new PublicationResult(r.getString(1), r.getString(2)), packageId, principal, key).stream().findFirst();
    }
    public void insertRevision(String id, String packageId, WorldPackageCandidate c, String principal) {
        var i = c.document().identity();
        // 能力均经稳定标识校验，不包含引号；仍使用 JDBC 参数保存完整 JSON。
        String capabilities = "[" + i.requiredCapabilities().stream().map(v -> "\"" + v + "\"").collect(java.util.stream.Collectors.joining(",")) + "]";
        jdbc.update("insert into world_package_revision(id,package_id,revision,format_version,runtime_contract,required_capabilities_json,content_hash,compiled_hash,canonical_json,compiled_json,compiler_version,status,created_by) values(?,?,?,?,?,?,?,?,?,?,?,'PUBLISHED',?)",
                id, packageId, i.revision(), i.formatVersion(), i.runtimeContract(), capabilities, c.contentHash(), c.compiledHash(), c.canonicalJson(), c.compiledJson(), c.compilerVersion(), principal);
        for (var asset : c.assets()) {
            var d = asset.descriptor();
            jdbc.update("insert into world_package_asset(revision_id,asset_path,media_type,byte_size,content_hash,asset_bytes) values(?,?,?,?,?,?)",
                    id, d.path(), d.mediaType(), d.byteSize(), d.sha256(), asset.content());
        }
    }
    public void savePublication(String packageId, String principal, String key, String hash, String revisionId) {
        jdbc.update("insert into world_package_publication_request(package_id,principal_key,idempotency_key,request_hash,revision_id) values(?,?,?,?,?)", packageId, principal, key, hash, revisionId);
    }
    public void advanceRevision(String id, long revision) {
        jdbc.update("update world_package set latest_revision=?,updated_at=current_timestamp where id=?", revision, id);
    }
    public void ensureScope(Scope s) {
        jdbc.update("insert into world_package_creation_scope(id,user_id,scope_type) values(?,?,?)", s.id(), s.userId(), s.userId() == null ? "GLOBAL" : "USER");
    }
    public Scope scope(String id, boolean lock) {
        return jdbc.queryForObject("select id,user_id from world_package_creation_scope where id=?" + (lock ? " for update" : ""), (r, n) -> new Scope(r.getString(1), r.getString(2)), id);
    }
    public Optional<Scope> lockScopeIfPresent(String id) {
        return jdbc.query("select id,user_id from world_package_creation_scope where id=? for update", (r, n) -> new Scope(r.getString(1), r.getString(2)), id).stream().findFirst();
    }
    public List<String> enabled(String scope) {
        return jdbc.queryForList("select revision_id from world_package_availability where scope_id=? order by revision_id", String.class, scope);
    }
    public List<String> lockEnabled(String scope) {
        return jdbc.queryForList("select revision_id from world_package_availability where scope_id=? order by revision_id for update", String.class, scope);
    }
    public Optional<String> defaultRevision(String scope) {
        return jdbc.queryForList("select revision_id from world_package_creation_default where scope_id=?", String.class, scope).stream().findFirst();
    }
    public Optional<String> lockDefault(String scope) {
        return jdbc.queryForList("select revision_id from world_package_creation_default where scope_id=? for update", String.class, scope).stream().findFirst();
    }
    public boolean installed(String user, String revision) {
        return jdbc.queryForObject("select count(*) from world_package_installation where user_id=? and revision_id=?", Integer.class, user, revision) > 0;
    }
    public boolean lockInstalled(String user, String revision) {
        return !jdbc.queryForList("select revision_id from world_package_installation where user_id=? and revision_id=? for update", String.class, user, revision).isEmpty();
    }
    public void install(String user, String revision) {
        jdbc.update("insert into world_package_installation(user_id,revision_id) values(?,?)", user, revision);
    }
    public void enable(String scope, String revision) {
        jdbc.update("insert into world_package_availability(scope_id,revision_id) values(?,?)", scope, revision);
    }
    public void disable(String scope, String revision) {
        jdbc.update("delete from world_package_availability where scope_id=? and revision_id=?", scope, revision);
    }
    public void setDefault(String scope, String revision) {
        jdbc.update("delete from world_package_creation_default where scope_id=?", scope);
        if (revision != null) jdbc.update("insert into world_package_creation_default(scope_id,revision_id) values(?,?)", scope, revision);
    }
    public void audit(String actor, String action, String scope, String revision) {
        String auditScope = scope != null && scope.startsWith("USER:")
                ? "USER:" + com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.auditDigest(scope.substring(5)) : scope;
        jdbc.update("insert into world_package_audit(id,actor_key,action,scope_id,revision_id) values(?,?,?,?,?)", UUID.randomUUID().toString(), actor, action, auditScope, revision);
    }
}
