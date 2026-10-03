package com.wenxian.game.story.worldpackage.domain;

/** 注册表领域数据；不从包正文取得主体权限，不把编译快照作为治理状态缓存。 */
public final class WorldPackageRegistry {
    private WorldPackageRegistry() {}

    /** 主体只由受信应用入口构建，未来控制器必须从服务端会话解析。 */
    public record Principal(String userId, boolean system) {
        public Principal {
            if (!system && (userId == null || userId.isBlank())) throw new IllegalArgumentException("缺少玩家主体");
            if (system && userId != null) throw new IllegalArgumentException("系统主体不能携带玩家身份");
        }
        public static Principal systemPublisher() { return new Principal(null, true); }
        public static Principal player(String userId) { return new Principal(userId, false); }
        public String key() { return system ? "SYSTEM" : "USER:" + auditDigest(userId); }
    }

    public record PackageRow(String id, String namespace, String packageKey, String ownerUserId,
                             String sourceType, String visibility, String status, long latestRevision) {}

    public record RevisionRow(String id, String packageId, long revision, int formatVersion,
                              String runtimeContract, String contentHash, String compiledHash,
                              String compilerVersion, String status, String requiredCapabilitiesJson) {}

    public record StoredContent(String canonicalJson, String compiledJson) {}
    public record PublicationResult(String requestHash, String revisionId) {}
    public record Scope(String id, String userId) {}

    /** 已复核哈希的内容和本次查询的治理头；调用方必须在查询服务中检查访问用途。 */
    public record ResolvedRevision(PackageRow worldPackage, RevisionRow revision, CompiledWorldPackage compiled) {}

    /** 随机账号ID的不可逆审计关联，不保留原账号标识；不是访问令牌或授权凭证。 */
    public static String auditDigest(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
