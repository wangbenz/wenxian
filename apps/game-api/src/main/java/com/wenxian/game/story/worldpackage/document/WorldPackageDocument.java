package com.wenxian.game.story.worldpackage.document;

import java.util.List;
import java.util.Map;

/**
 * 世界包 Bundle v1 的文档协议与规范化聚合。
 *
 * <p>本类型只描述作者可以声明的内容，不携带数据库主键、玩家命卷、搜索向量或可执行代码。
 * 原始 Bundle 必须经过严格读取和语义校验后，才能构造成 {@link WorldPackageDocument} 并交给编译器。
 * 文档字段是交换协议，不得直接承担回合状态裁决。</p>
 */
public record WorldPackageDocument(
        PackageIdentity identity,
        ContentMetadata metadata,
        WorldContent world,
        CreationContent creation,
        ProgressionContent progression,
        List<LoreEntry> loreEntries,
        List<AssetDescriptor> assets
) {
    public WorldPackageDocument {
        loreEntries = immutable(loreEntries);
        assets = immutable(assets);
    }

    /** Bundle 清单；entrypoints 只描述文件布局，不进入内容哈希。 */
    public record Manifest(
            String format,
            int formatVersion,
            String publisherNamespace,
            String packageId,
            long revision,
            String defaultLocale,
            String name,
            String summary,
            List<String> authors,
            String license,
            String contentRating,
            String runtimeContract,
            List<String> requiredCapabilities,
            Entrypoints entrypoints,
            List<AssetDescriptor> assets
    ) {
        public Manifest {
            authors = immutable(authors);
            requiredCapabilities = immutable(requiredCapabilities);
            assets = immutable(assets);
        }
    }

    /** Bundle 中各内容模块的相对路径。 */
    public record Entrypoints(
            String world,
            String creation,
            String progression,
            String loreIndex
    ) {}

    /** Lore 索引只负责列出模块文件，不进入规范化内容。 */
    public record LoreIndex(List<String> files) {
        public LoreIndex {
            files = immutable(files);
        }
    }

    /** 单个 Lore 文件的固定包裹结构。 */
    public record LoreFile(List<LoreEntry> entries) {
        public LoreFile {
            entries = immutable(entries);
        }
    }

    /** Bundle 文件级 SHA-256；不校验自身，也不进入内容哈希。 */
    public record ChecksumManifest(Map<String, String> sha256) {
        public ChecksumManifest {
            sha256 = sha256 == null ? Map.of() : Map.copyOf(sha256);
        }
    }

    /** 完成读取但尚未通过语义校验的 Bundle。 */
    public record Bundle(
            Manifest manifest,
            WorldContent world,
            CreationContent creation,
            ProgressionContent progression,
            List<LoreEntry> loreEntries,
            List<BundleAsset> assets
    ) {
        public Bundle {
            loreEntries = immutable(loreEntries);
            assets = immutable(assets);
        }
    }

    /** 包的可移植发布身份；现有玄墟界继续使用 {@code XUANXU@2}。 */
    public record PackageIdentity(
            String publisherNamespace,
            String packageId,
            long revision,
            int formatVersion,
            String runtimeContract,
            List<String> requiredCapabilities
    ) {
        public PackageIdentity {
            requiredCapabilities = immutable(requiredCapabilities);
        }
    }

    /** 会影响内容哈希的展示、作者和许可信息。 */
    public record ContentMetadata(
            String defaultLocale,
            String name,
            String summary,
            List<String> authors,
            String license,
            String contentRating
    ) {
        public ContentMetadata {
            authors = immutable(authors);
        }
    }

    /** 规范内容中的静态素材描述；素材字节以 SHA-256 寻址。 */
    public record AssetDescriptor(
            String path,
            String role,
            String mediaType,
            long byteSize,
            String sha256
    ) {}

    /** 已读取的素材内容；只在摄取阶段存在。 */
    public record BundleAsset(AssetDescriptor descriptor, byte[] content) {
        public BundleAsset {
            content = content == null ? new byte[0] : content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    /** 世界主体内容，不包含平台状态和命卷事实。 */
    public record WorldContent(
            String premise,
            List<WorldRule> coreRules,
            List<ContentBoundary> contentBoundaries,
            List<RaceDefinition> races,
            List<RegionDefinition> regions,
            NarrativeProfile narrativeProfile
    ) {
        public WorldContent {
            coreRules = immutable(coreRules);
            contentBoundaries = immutable(contentBoundaries);
            races = immutable(races);
            regions = immutable(regions);
        }
    }

    public record WorldRule(String ruleId, String text, int priority) {}

    public record ContentBoundary(String boundaryId, String text) {}

    public record RaceDefinition(
            String raceId,
            String name,
            String description,
            List<String> identityFacts,
            List<String> socialHooks,
            List<String> opportunityTags,
            String primaryProgressionTrackRef
    ) {
        public RaceDefinition {
            identityFacts = immutable(identityFacts);
            socialHooks = immutable(socialHooks);
            opportunityTags = immutable(opportunityTags);
        }
    }

    public record RegionDefinition(
            String regionId,
            String name,
            String description,
            List<String> tags
    ) {
        public RegionDefinition {
            tags = immutable(tags);
        }
    }

    public record NarrativeProfile(
            String guidance,
            List<String> tone,
            List<String> conflictThemes,
            String openSpace
    ) {
        public NarrativeProfile {
            tone = immutable(tone);
            conflictThemes = immutable(conflictThemes);
        }
    }

    /** 当前受支持创角控件可以消费的世界包配置。 */
    public record CreationContent(
            List<String> playableRaceRefs,
            List<String> openingRegionRefs,
            String defaultOpeningRegionRef,
            List<RequiredOriginFact> requiredOriginFacts,
            List<RouteTheme> routeThemes,
            List<ScenarioSeed> scenarioSeeds
    ) {
        public CreationContent {
            playableRaceRefs = immutable(playableRaceRefs);
            openingRegionRefs = immutable(openingRegionRefs);
            requiredOriginFacts = immutable(requiredOriginFacts);
            routeThemes = immutable(routeThemes);
            scenarioSeeds = immutable(scenarioSeeds);
        }
    }

    public record RequiredOriginFact(
            String identityRef,
            String factType,
            String semanticRole,
            String playerLabel
    ) {}

    public record RouteTheme(
            String themeId,
            String summary,
            List<String> loreRefs
    ) {
        public RouteTheme {
            loreRefs = immutable(loreRefs);
        }
    }

    public record ScenarioSeed(
            String seedId,
            List<String> eligibleRegionRefs,
            List<String> tags,
            String summary
    ) {
        public ScenarioSeed {
            eligibleRegionRefs = immutable(eligibleRegionRefs);
            tags = immutable(tags);
        }
    }

    /** 一个或多个声明式成长体系；v1 只执行线性分层语义。 */
    public record ProgressionContent(List<ProgressionSystem> systems) {
        public ProgressionContent {
            systems = immutable(systems);
        }
    }

    public record ProgressionSystem(
            String systemId,
            long revision,
            String kind,
            String displayName,
            ProgressionCompletion completion,
            List<ProgressionStep> steps,
            List<ProgressionTrack> tracks
    ) {
        public ProgressionSystem {
            steps = immutable(steps);
            tracks = immutable(tracks);
        }
    }

    public record ProgressionCompletion(String position, String unlocks) {}

    public record ProgressionStep(String stepId, int order, String name) {}

    public record ProgressionTrack(
            String trackId,
            List<String> identityRefs,
            ProgressionStart start,
            List<ProgressionStage> stages
    ) {
        public ProgressionTrack {
            identityRefs = immutable(identityRefs);
            stages = immutable(stages);
        }
    }

    public record ProgressionStart(String stageRef, String stepRef) {}

    public record ProgressionStage(
            String stageId,
            int order,
            int comparisonTier,
            String name,
            String description,
            String capabilityBoundary,
            List<String> aliases
    ) {
        public ProgressionStage {
            aliases = immutable(aliases);
        }
    }

    /** 可由稳定引用、标签和普通词项触发的世界知识。 */
    public record LoreEntry(
            String loreId,
            String title,
            String content,
            int priority,
            List<String> tags,
            List<String> relatedRefs,
            LoreActivation activation
    ) {
        public LoreEntry {
            tags = immutable(tags);
            relatedRefs = immutable(relatedRefs);
        }
    }

    public record LoreActivation(
            boolean always,
            List<String> identityRefs,
            List<String> locationRefs,
            List<String> entityTags,
            List<String> storyThreadRefs,
            List<String> terms
    ) {
        public LoreActivation {
            identityRefs = immutable(identityRefs);
            locationRefs = immutable(locationRefs);
            entityTags = immutable(entityTags);
            storyThreadRefs = immutable(storyThreadRefs);
            terms = immutable(terms);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
