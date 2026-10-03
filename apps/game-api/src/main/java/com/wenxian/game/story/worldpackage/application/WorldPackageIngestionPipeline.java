package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.Bundle;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ContentMetadata;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.PackageIdentity;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage;
import com.wenxian.game.story.worldpackage.infrastructure.WorldPackageBundleReader;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 世界包的纯摄取用例：安全读取、规范化、校验并确定性编译为未发布候选。
 *
 * <p>本管线没有数据库、权限、安装和默认启用副作用。官方包、玩家草稿和外部导入包都必须先经过本管线，
 * 后续用例再分别裁决所有权、修订发布、玩家安装和创角可用性。</p>
 */
public final class WorldPackageIngestionPipeline {
    private final WorldPackageBundleReader reader;
    private final WorldPackageValidator validator;
    private final WorldPackageCompiler compiler;
    private final WorldPackageCanonicalizer canonicalizer;

    public WorldPackageIngestionPipeline(ObjectMapper mapper) {
        this(new WorldPackageBundleReader(mapper), new WorldPackageValidator(),
                new WorldPackageCompiler(), new WorldPackageCanonicalizer(mapper));
    }

    WorldPackageIngestionPipeline(WorldPackageBundleReader reader,
                                  WorldPackageValidator validator,
                                  WorldPackageCompiler compiler,
                                  WorldPackageCanonicalizer canonicalizer) {
        this.reader = reader;
        this.validator = validator;
        this.compiler = compiler;
        this.canonicalizer = canonicalizer;
    }

    public WorldPackageCandidate inspectDirectory(Path root) {
        return inspect(reader.readDirectory(root));
    }

    public WorldPackageCandidate inspectZip(InputStream input) {
        return inspect(reader.readZip(input));
    }

    WorldPackageCandidate inspect(Bundle bundle) {
        WorldPackageDocument document = document(bundle);
        validator.validate(document);
        CompiledWorldPackage compiled = compiler.compile(document);
        WorldPackageCanonicalizer.CanonicalResult canonical = canonicalizer.canonicalize(document, compiled);
        return new WorldPackageCandidate(
                document,
                compiled,
                canonical.canonicalJson(),
                canonical.contentHash(),
                canonical.compiledJson(),
                canonical.compiledHash(),
                WorldPackageValidator.COMPILER_VERSION,
                bundle.assets()
        );
    }

    private WorldPackageDocument document(Bundle bundle) {
        if (bundle == null || bundle.manifest() == null) {
            throw new WorldPackageValidationException(java.util.List.of(
                    new WorldPackageValidationException.Violation("MANIFEST_REQUIRED", "$.manifest",
                            "世界包清单不能为空")));
        }
        var manifest = bundle.manifest();
        PackageIdentity identity = new PackageIdentity(
                manifest.publisherNamespace(),
                manifest.packageId(),
                manifest.revision(),
                manifest.formatVersion(),
                manifest.runtimeContract(),
                manifest.requiredCapabilities() == null ? java.util.List.of()
                        : manifest.requiredCapabilities().stream().sorted().toList()
        );
        ContentMetadata metadata = new ContentMetadata(
                manifest.defaultLocale(),
                manifest.name(),
                manifest.summary(),
                manifest.authors(),
                manifest.license(),
                manifest.contentRating()
        );
        return new WorldPackageDocument(
                identity,
                metadata,
                bundle.world(),
                bundle.creation(),
                bundle.progression(),
                bundle.loreEntries().stream().sorted(Comparator.comparing(
                        WorldPackageDocument.LoreEntry::loreId)).toList(),
                bundle.assets().stream().map(WorldPackageDocument.BundleAsset::descriptor)
                        .sorted(Comparator.comparing(WorldPackageDocument.AssetDescriptor::path)).toList()
        );
    }
}
