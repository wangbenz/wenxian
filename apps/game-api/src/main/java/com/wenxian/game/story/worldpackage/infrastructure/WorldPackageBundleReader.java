package com.wenxian.game.story.worldpackage.infrastructure;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.worldpackage.application.WorldPackageValidator;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.AssetDescriptor;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.Bundle;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.BundleAsset;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ChecksumManifest;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.CreationContent;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.LoreEntry;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.LoreFile;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.LoreIndex;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.Manifest;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ProgressionContent;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.WorldContent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从目录或不可信 ZIP 安全读取世界包 Bundle v1。
 *
 * <p>ZIP 始终在受限内存中读取，不写入文件系统；目录读取拒绝符号链接。读取器限制文件数、单文件大小、
 * 总大小、路径、类型和 JSON 深度，严格拒绝重复键、未知字段、尾随内容、校验和缺失以及未声明文件。</p>
 */
public final class WorldPackageBundleReader {
    static final int MAX_FILES = 256;
    static final int MAX_ENTRY_BYTES = 2_100_000;
    static final int MAX_TOTAL_BYTES = 10_000_000;
    private static final String MANIFEST_PATH = "manifest.json";
    private static final String CHECKSUM_PATH = "checksums.json";

    private final ObjectMapper json;

    public WorldPackageBundleReader(ObjectMapper mapper) {
        this.json = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(64)
                .maxStringLength(1_000_000)
                .maxNumberLength(32)
                .build());
    }

    public Bundle readDirectory(Path root) {
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw failure("BUNDLE_DIRECTORY_INVALID", "$", "世界包目录不存在或不是安全目录");
        }
        try {
            Path base = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Map<String, byte[]> entries = new LinkedHashMap<>();
            try (var paths = Files.walk(base)) {
                for (Path path : paths.toList()) {
                    if (path.equals(base)) continue;
                    if (Files.isSymbolicLink(path)) {
                        throw failure("BUNDLE_SYMLINK_REJECTED", relative(base, path), "世界包不能包含符号链接");
                    }
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        throw failure("BUNDLE_FILE_INVALID", relative(base, path), "世界包包含不支持的文件类型");
                    }
                    String name = cleanPath(relative(base, path));
                    long size = Files.size(path);
                    if (size > MAX_ENTRY_BYTES) throw failure("BUNDLE_ENTRY_TOO_LARGE", name, "世界包单文件超过大小限制");
                    put(entries, name, Files.readAllBytes(path));
                }
            }
            return parse(entries);
        } catch (WorldPackageBundleException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new WorldPackageBundleException("BUNDLE_READ_FAILED", "$", "世界包目录读取失败", exception);
        }
    }

    public Bundle readZip(InputStream input) {
        if (input == null) throw failure("BUNDLE_ZIP_REQUIRED", "$", "世界包 ZIP 输入不能为空");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String rawName = entry.getName();
                if (entry.isDirectory()) {
                    String directoryName = rawName.endsWith("/")
                            ? rawName.substring(0, rawName.length() - 1) : rawName;
                    cleanPath(directoryName);
                    continue;
                }
                String name = cleanPath(rawName);
                put(entries, name, readBounded(zip, name));
                zip.closeEntry();
            }
            return parse(entries);
        } catch (WorldPackageBundleException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new WorldPackageBundleException("BUNDLE_ZIP_READ_FAILED", "$", "世界包 ZIP 读取失败", exception);
        }
    }

    private Bundle parse(Map<String, byte[]> entries) {
        byte[] manifestBytes = required(entries, MANIFEST_PATH);
        Manifest manifest = parse(manifestBytes, MANIFEST_PATH, Manifest.class);
        if (!WorldPackageValidator.FORMAT.equals(manifest.format())) {
            throw failure("BUNDLE_FORMAT_UNSUPPORTED", MANIFEST_PATH + ".format", "不支持的世界包格式");
        }
        if (manifest.formatVersion() != WorldPackageValidator.FORMAT_VERSION) {
            throw failure("BUNDLE_FORMAT_VERSION_UNSUPPORTED", MANIFEST_PATH + ".formatVersion",
                    "不支持的世界包格式版本");
        }
        if (manifest.entrypoints() == null) {
            throw failure("BUNDLE_ENTRYPOINTS_REQUIRED", MANIFEST_PATH + ".entrypoints", "世界包缺少入口文件");
        }

        String worldPath = jsonPath(manifest.entrypoints().world(), "world");
        String creationPath = jsonPath(manifest.entrypoints().creation(), "creation");
        String progressionPath = jsonPath(manifest.entrypoints().progression(), "progression");
        String loreIndexPath = jsonPath(manifest.entrypoints().loreIndex(), "loreIndex");
        Set<String> distinct = new HashSet<>(List.of(worldPath, creationPath, progressionPath, loreIndexPath));
        if (distinct.size() != 4) {
            throw failure("BUNDLE_ENTRYPOINT_DUPLICATE", MANIFEST_PATH + ".entrypoints",
                    "世界包入口文件不能重复");
        }

        LoreIndex loreIndex = parse(required(entries, loreIndexPath), loreIndexPath, LoreIndex.class);
        List<String> lorePaths = list(loreIndex.files()).stream().map(path -> jsonPath(path, "lore")).toList();
        if (new HashSet<>(lorePaths).size() != lorePaths.size()) {
            throw failure("BUNDLE_LORE_FILE_DUPLICATE", loreIndexPath, "Lore 文件不能重复");
        }
        Set<String> contentPaths = new HashSet<>(List.of(worldPath, creationPath, progressionPath, loreIndexPath));
        contentPaths.addAll(lorePaths);
        if (contentPaths.size() != 4 + lorePaths.size()) {
            throw failure("BUNDLE_CONTENT_PATH_CONFLICT", loreIndexPath, "Lore 文件不能与其他内容入口重复");
        }

        List<AssetDescriptor> descriptors = list(manifest.assets());
        Set<String> expected = new HashSet<>(List.of(MANIFEST_PATH, CHECKSUM_PATH,
                worldPath, creationPath, progressionPath, loreIndexPath));
        expected.addAll(lorePaths);
        descriptors.forEach(asset -> {
            if (asset == null) throw failure("BUNDLE_ASSET_DESCRIPTOR_REQUIRED", MANIFEST_PATH + ".assets",
                    "素材描述不能为空");
            expected.add(cleanPath(asset.path()));
        });
        if (!expected.equals(entries.keySet())) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(entries.keySet());
            Set<String> undeclared = new HashSet<>(entries.keySet());
            undeclared.removeAll(expected);
            throw failure("BUNDLE_FILE_SET_INVALID", "$",
                    "世界包文件声明不一致；缺失=" + missing + "，未声明=" + undeclared);
        }
        verifyChecksums(entries);

        WorldContent world = parse(required(entries, worldPath), worldPath, WorldContent.class);
        CreationContent creation = parse(required(entries, creationPath), creationPath, CreationContent.class);
        ProgressionContent progression = parse(required(entries, progressionPath), progressionPath,
                ProgressionContent.class);
        List<LoreEntry> lore = new ArrayList<>();
        for (String path : lorePaths) {
            LoreFile file = parse(required(entries, path), path, LoreFile.class);
            lore.addAll(list(file.entries()));
        }
        List<BundleAsset> assets = new ArrayList<>();
        for (AssetDescriptor descriptor : descriptors) {
            byte[] content = required(entries, cleanPath(descriptor.path()));
            verifyAsset(descriptor, content);
            assets.add(new BundleAsset(descriptor, content));
        }
        return new Bundle(manifest, world, creation, progression, lore, assets);
    }

    private void verifyChecksums(Map<String, byte[]> entries) {
        ChecksumManifest checksums = parse(required(entries, CHECKSUM_PATH), CHECKSUM_PATH, ChecksumManifest.class);
        Map<String, String> hashes = checksums.sha256() == null ? Map.of() : checksums.sha256();
        Set<String> expected = new HashSet<>(entries.keySet());
        expected.remove(CHECKSUM_PATH);
        if (!hashes.keySet().equals(expected)) {
            throw failure("BUNDLE_CHECKSUM_SET_INVALID", CHECKSUM_PATH,
                    "校验和必须覆盖除自身外的全部文件且不能包含额外路径");
        }
        hashes.forEach((path, hash) -> {
            String clean = cleanPath(path);
            String actual = sha256(required(entries, clean));
            if (!actual.equals(hash)) {
                throw failure("BUNDLE_CHECKSUM_MISMATCH", clean, "世界包文件校验和不匹配");
            }
        });
    }

    private void verifyAsset(AssetDescriptor descriptor, byte[] content) {
        if (descriptor.byteSize() != content.length) {
            throw failure("BUNDLE_ASSET_SIZE_MISMATCH", descriptor.path(), "素材字节数与清单不一致");
        }
        if (!sha256(content).equals(descriptor.sha256())) {
            throw failure("BUNDLE_ASSET_HASH_MISMATCH", descriptor.path(), "素材哈希与清单不一致");
        }
        boolean signature = switch (descriptor.mediaType() == null ? "" : descriptor.mediaType()) {
            case "image/png" -> startsWith(content, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> startsWith(content, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "image/webp" -> content.length >= 12
                    && new String(content, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                    && new String(content, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP");
            default -> false;
        };
        if (!signature) throw failure("BUNDLE_ASSET_SIGNATURE_INVALID", descriptor.path(), "素材内容与声明 MIME 不一致");
        String lowerPath = descriptor.path().toLowerCase(java.util.Locale.ROOT);
        boolean extensionMatches = switch (descriptor.mediaType()) {
            case "image/png" -> lowerPath.endsWith(".png");
            case "image/jpeg" -> lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg");
            case "image/webp" -> lowerPath.endsWith(".webp");
            default -> false;
        };
        if (!extensionMatches) {
            throw failure("BUNDLE_ASSET_EXTENSION_INVALID", descriptor.path(), "素材扩展名与声明 MIME 不一致");
        }
    }

    private boolean startsWith(byte[] source, byte[] prefix) {
        return source.length >= prefix.length && Arrays.equals(Arrays.copyOf(source, prefix.length), prefix);
    }

    private String sha256(byte[] value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", exception);
        }
    }

    private <T> T parse(byte[] content, String path, Class<T> type) {
        if (!path.endsWith(".json")) throw failure("BUNDLE_JSON_PATH_INVALID", path, "内容模块必须是 JSON 文件");
        try {
            return json.readValue(content, type);
        } catch (IOException | RuntimeException exception) {
            throw new WorldPackageBundleException("BUNDLE_JSON_INVALID", path,
                    "世界包 JSON 严格解析失败：" + path, exception);
        }
    }

    private byte[] required(Map<String, byte[]> entries, String path) {
        byte[] value = entries.get(path);
        if (value == null) throw failure("BUNDLE_FILE_MISSING", path, "世界包缺少文件：" + path);
        return value;
    }

    private String jsonPath(String value, String field) {
        String path = cleanPath(value);
        if (!path.endsWith(".json") || path.startsWith("assets/")
                || MANIFEST_PATH.equals(path) || CHECKSUM_PATH.equals(path)) {
            throw failure("BUNDLE_ENTRYPOINT_PATH_INVALID", MANIFEST_PATH + ".entrypoints." + field,
                    "内容入口必须是包内 JSON 相对路径");
        }
        return path;
    }

    private void put(Map<String, byte[]> entries, String name, byte[] content) {
        if (entries.size() >= MAX_FILES) throw failure("BUNDLE_FILE_LIMIT", "$", "世界包文件数超过限制");
        if (content.length > MAX_ENTRY_BYTES) throw failure("BUNDLE_ENTRY_TOO_LARGE", name, "世界包单文件超过大小限制");
        int total = entries.values().stream().mapToInt(value -> value.length).sum();
        if (total + content.length > MAX_TOTAL_BYTES) throw failure("BUNDLE_TOTAL_TOO_LARGE", "$", "世界包总大小超过限制");
        if (entries.putIfAbsent(name, content) != null) {
            throw failure("BUNDLE_FILE_DUPLICATE", name, "世界包存在重复文件路径");
        }
    }

    private byte[] readBounded(InputStream input, String name) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > MAX_ENTRY_BYTES) {
                throw failure("BUNDLE_ENTRY_TOO_LARGE", name, "世界包单文件超过大小限制");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String cleanPath(String value) {
        if (value == null || value.isBlank() || value.contains("\\") || value.startsWith("/")) {
            throw failure("BUNDLE_PATH_INVALID", safe(value), "世界包路径必须是安全相对路径");
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException exception) {
            throw failure("BUNDLE_PATH_INVALID", safe(value), "世界包路径格式不正确");
        }
        String normalized = path.normalize().toString().replace(java.io.File.separatorChar, '/');
        if (path.isAbsolute() || normalized.isBlank() || normalized.equals(".") || normalized.startsWith("../")
                || normalized.contains("/../") || !normalized.equals(value)) {
            throw failure("BUNDLE_PATH_INVALID", value, "世界包路径不能越界或包含非规范片段");
        }
        return normalized;
    }

    private String relative(Path base, Path path) {
        return base.relativize(path).toString().replace(java.io.File.separatorChar, '/');
    }

    private WorldPackageBundleException failure(String code, String path, String message) {
        return new WorldPackageBundleException(code, path, message);
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String safe(String value) {
        return value == null ? "<null>" : value;
    }
}
