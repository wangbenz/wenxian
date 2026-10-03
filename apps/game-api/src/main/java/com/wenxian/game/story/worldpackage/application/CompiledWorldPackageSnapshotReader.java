package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.*;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage.*;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.PackageRow;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.RevisionRow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 按 narrative-world-v1 合同读取已经发布的不可变编译快照。
 *
 * <p>先对数据库原始 JSON 的 UTF-8 字节核对 compiledHash，再检查运行时结构、索引和绑定。
 * 不重编译作者文档，不读取素材原件，不用当前编译器版本拒绝历史快照。这里的 v1 约束独立于
 * 摄取校验器，未来编译结构变化必须显式增加合同 Reader，不能悄悄用新编译结果替换旧快照。</p>
 *
 * <p>损坏、不支持或超限内容抛出 IllegalStateException，由查询层隔离所属修订；本类不负责访问权限、
 * 发布状态或默认项裁决。缓存命中仍须调用 validateBinding，防止可变数据库元数据绕过绑定检查。</p>
 */
public final class CompiledWorldPackageSnapshotReader {
    /** 编译后列表与查找映射包含重复结构，快照上限独立于 Bundle 的 10 MB 原件上限。 */
    public static final int MAX_SNAPSHOT_BYTES = 40_000_000;
    private static final int MAX_JSON_NODES = 1_000_000;
    private static final String RUNTIME_CONTRACT = "narrative-world-v1";
    private static final Set<String> V1_CAPABILITIES = Set.of("creation-catalog-v1", "linear-progression-v1",
            "triggered-lore-v1", "player-confirmed-ending-v1");
    private static final Pattern STABLE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{1,79}");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private final ObjectMapper json;

    public CompiledWorldPackageSnapshotReader(ObjectMapper mapper) {
        if (mapper == null) throw new IllegalArgumentException("快照读取器缺少 JSON mapper");
        this.json = mapper.copy().deactivateDefaultTyping()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                // 线性轨道末尾的 nextStepId/nextStageId 明确允许 null，其余 null 由树检查拒绝。
                .disable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .disable(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS)
                .disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .disable(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        // 上游 HTTP mapper 即使允许宽松 JSON，也不能改变已发布快照的合同读取规则。
        for (JsonReadFeature feature : JsonReadFeature.values()) {
            this.json.getFactory().configure(feature.mappedFeature(), feature.enabledByDefault());
        }
        this.json.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        this.json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(64).maxStringLength(1_000_000).maxNumberLength(32).build());
    }

    /** 读取并验证一个完整快照；只核对已保存字节，不重新规范化 JSON 或计算作者内容哈希。 */
    public CompiledWorldPackage read(PackageRow p, RevisionRow r, String compiledJson) {
        check(r != null, "$.revision", "缺少修订元数据");
        check(compiledJson != null && !compiledJson.isBlank() && compiledJson.length() <= MAX_SNAPSHOT_BYTES,
                "$", "编译快照为空或超过大小限制");
        byte[] bytes = compiledJson.getBytes(StandardCharsets.UTF_8);
        check(bytes.length <= MAX_SNAPSHOT_BYTES, "$", "编译快照超过 UTF-8 大小限制");
        check(WorldPackageCanonicalizer.sha256(bytes).equals(r.compiledHash()), "$", "编译快照哈希不一致");
        try {
            JsonNode tree = json.readTree(bytes);
            check(tree != null && tree.isObject(), "$", "编译快照必须为 JSON 对象");
            inspectTree(tree, "$", new int[]{0});
            CompiledWorldPackage snapshot = json.treeToValue(tree, CompiledWorldPackage.class);
            validateBinding(p, r, snapshot);
            validateV1(snapshot);
            return snapshot;
        } catch (IOException error) {
            throw new IllegalStateException("世界包编译快照 JSON 结构无效", error);
        }
    }

    /** 复核缓存快照与本次数据库头的身份、合同和能力；不再次遍历内容图，不授予访问权限。 */
    public void validateBinding(PackageRow p, RevisionRow r, CompiledWorldPackage snapshot) {
        check(p != null && r != null && snapshot != null && snapshot.identity() != null,
                "$.identity", "缺少包、修订或快照身份");
        PackageIdentity identity = snapshot.identity();
        id(identity.publisherNamespace(), "$.identity.publisherNamespace");
        id(identity.packageId(), "$.identity.packageId");
        check(p.id() != null && p.id().equals(r.packageId()), "$.identity", "数据库包与修订归属不一致");
        check(Objects.equals(identity.publisherNamespace(), p.namespace())
                        && Objects.equals(identity.packageId(), p.packageKey())
                        && identity.revision() > 0 && identity.revision() == r.revision(),
                "$.identity", "快照身份与数据库绑定不一致");
        check(identity.formatVersion() == 1 && r.formatVersion() == 1,
                "$.identity.formatVersion", "不支持该格式版本");
        check(RUNTIME_CONTRACT.equals(identity.runtimeContract()) && RUNTIME_CONTRACT.equals(r.runtimeContract()),
                "$.identity.runtimeContract", "不支持该运行时合同");
        check(r.contentHash() != null && HASH.matcher(r.contentHash()).matches()
                        && r.compiledHash() != null && HASH.matcher(r.compiledHash()).matches(),
                "$.revision", "数据库哈希元数据无效");
        Set<String> capabilities = capabilities(identity.requiredCapabilities(), "$.identity.requiredCapabilities");
        String persisted = r.requiredCapabilitiesJson();
        check(persisted != null && persisted.length() <= 2_000, "$.revision.requiredCapabilities", "能力元数据无效");
        try {
            JsonNode stored = json.readTree(persisted);
            check(stored != null && stored.isArray(), "$.revision.requiredCapabilities", "能力必须为数组");
            List<String> values = new ArrayList<>();
            for (JsonNode value : stored) {
                check(value.isTextual(), "$.revision.requiredCapabilities", "能力必须为字符串");
                values.add(value.textValue());
            }
            check(capabilities.equals(capabilities(values, "$.revision.requiredCapabilities")),
                    "$.identity.requiredCapabilities", "快照能力与数据库元数据不一致");
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("世界包编译快照能力元数据 JSON 无效", error);
        }
    }

    private void validateV1(CompiledWorldPackage c) {
        Map<String, String> refs = new HashMap<>();
        size(c.alwaysOnRules(), 1, 32, "$.alwaysOnRules");
        size(c.contentBoundaries(), 0, 32, "$.contentBoundaries");
        c.alwaysOnRules().forEach(rule -> register(refs, rule.ruleId(), "rule"));
        c.contentBoundaries().forEach(boundary -> register(refs, boundary.boundaryId(), "boundary"));
        indexed(c.racesById(), RaceDefinition::raceId, 1, 20, "race", refs);
        indexed(c.regionsById(), RegionDefinition::regionId, 1, 100, "region", refs);
        indexed(c.loreById(), LoreEntry::loreId, 0, 2_000, "lore", refs);
        indexed(c.progressionSystemsById(), CompiledProgressionSystem::systemId, 1, 8, "system", refs);
        CreationProfile creation = c.creationProfile();
        indexed(creation.scenarioSeedsById(), ScenarioSeed::seedId, 0, 100, "seed", refs);
        size(creation.routeThemes(), 0, 40, "$.creationProfile.routeThemes");
        creation.routeThemes().forEach(theme -> register(refs, theme.themeId(), "theme"));

        Map<String, CompiledProgressionTrack> tracks = new HashMap<>();
        for (CompiledProgressionSystem system : c.progressionSystemsById().values()) {
            check(system.revision() > 0 && "LINEAR_TIERED".equals(system.kind()), system.systemId(), "成长体系不受支持");
            text(system.displayName(), 80, system.systemId());
            check("TRACK_END".equals(system.completionPosition())
                            && "player-confirmed-ending-v1".equals(system.completionUnlock()),
                    system.systemId(), "成长终点只能解锁玩家确认终章");
            indexed(system.stepsById(), CompiledProgressionStep::stepId, 1, 8, "step", refs);
            indexed(system.tracksById(), CompiledProgressionTrack::trackId, 1, 20, "track", refs);
            validateSteps(system);
            Integer tierCount = null;
            for (CompiledProgressionTrack track : system.tracksById().values()) {
                tracks.put(track.trackId(), track);
                indexed(track.stagesById(), CompiledProgressionStage::stageId, 2, 20, "stage", refs);
                validateStages(system, track);
                if (tierCount == null) tierCount = track.orderedStages().size();
                check(tierCount == track.orderedStages().size(), track.trackId(), "同体系轨道比较层级不一致");
            }
        }
        for (RaceDefinition race : c.racesById().values()) {
            CompiledProgressionTrack track = tracks.get(race.primaryProgressionTrackRef());
            check(track != null && track.identityRefs().contains(race.raceId()), race.raceId(), "种族主轨道绑定无效");
            text(race.name(), 80, race.raceId());
        }
        for (CompiledProgressionTrack track : tracks.values()) {
            references(track.identityRefs(), "race", refs, track.trackId());
        }
        validateCreation(c, refs, tracks);
        validateLore(c, refs);
        validateTextAndAssets(c);
    }

    private void validateSteps(CompiledProgressionSystem system) {
        List<CompiledProgressionStep> ordered = system.orderedSteps();
        check(ordered.size() == system.stepsById().size(), system.systemId(), "小阶段列表与映射数量不一致");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ordered.size(); i++) {
            CompiledProgressionStep step = ordered.get(i);
            check(seen.add(step.stepId()) && step.equals(system.stepsById().get(step.stepId())) && step.order() == i + 1,
                    system.systemId(), "小阶段顺序、身份或映射内容不一致");
            check(Objects.equals(step.nextStepId(), i + 1 < ordered.size() ? ordered.get(i + 1).stepId() : null),
                    step.stepId(), "小阶段后继不一致");
            text(step.name(), 40, step.stepId());
        }
    }

    private void validateStages(CompiledProgressionSystem system, CompiledProgressionTrack track) {
        size(track.identityRefs(), 1, 20, track.trackId());
        check(track.stagesById().containsKey(track.startingStageRef())
                        && system.stepsById().containsKey(track.startingStepRef()),
                track.trackId(), "开局位置不属于所在轨道或体系");
        List<CompiledProgressionStage> ordered = track.orderedStages();
        check(ordered.size() == track.stagesById().size(), track.trackId(), "大阶段列表与映射数量不一致");
        Set<String> seen = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < ordered.size(); i++) {
            CompiledProgressionStage stage = ordered.get(i);
            check(seen.add(stage.stageId()) && stage.equals(track.stagesById().get(stage.stageId()))
                            && stage.order() == i + 1 && stage.comparisonTier() == i + 1,
                    track.trackId(), "大阶段顺序、比较层级或映射内容不一致");
            check(Objects.equals(stage.nextStageId(), i + 1 < ordered.size() ? ordered.get(i + 1).stageId() : null),
                    stage.stageId(), "大阶段后继不一致");
            text(stage.name(), 80, stage.stageId());
            check(names.add(normalizeTerm(stage.name())), track.trackId(), "阶段名称或别名存在歧义");
            size(stage.aliases(), 0, 32, stage.stageId());
            for (String alias : stage.aliases()) {
                text(alias, 80, stage.stageId());
                check(names.add(normalizeTerm(alias)), track.trackId(), "阶段名称或别名存在歧义");
            }
            text(stage.description(), 1_000, stage.stageId());
            text(stage.capabilityBoundary(), 1_000, stage.stageId());
        }
    }

    private void validateCreation(CompiledWorldPackage c, Map<String, String> refs,
                                  Map<String, CompiledProgressionTrack> tracks) {
        CreationProfile creation = c.creationProfile();
        size(creation.playableRaceRefs(), 1, 20, "$.creationProfile.playableRaceRefs");
        size(creation.openingRegionRefs(), 1, 100, "$.creationProfile.openingRegionRefs");
        references(creation.playableRaceRefs(), "race", refs, "$.creationProfile.playableRaceRefs");
        references(creation.openingRegionRefs(), "region", refs, "$.creationProfile.openingRegionRefs");
        check(creation.openingRegionRefs().contains(creation.defaultOpeningRegionRef()), "$.creationProfile", "默认开场引用不在可选集合");
        check(creation.progressionTrackByIdentity().keySet().equals(c.racesById().keySet()),
                "$.creationProfile.progressionTrackByIdentity", "主轨道索引的种族集合不一致");
        c.racesById().forEach((id, race) -> check(Objects.equals(race.primaryProgressionTrackRef(),
                creation.progressionTrackByIdentity().get(id)), id, "主轨道索引与种族绑定不一致"));
        for (String playable : creation.playableRaceRefs()) {
            long assignments = tracks.values().stream().filter(track -> track.identityRefs().contains(playable)).count();
            check(assignments == 1, playable, "可玩种族必须唯一绑定成长轨道");
        }
        int totalFacts = 0;
        for (var entry : creation.requiredFactsByIdentity().entrySet()) {
            check(creation.playableRaceRefs().contains(entry.getKey()), entry.getKey(), "根源要求引用非可玩身份");
            totalFacts += entry.getValue().size();
            for (RequiredOriginFact fact : entry.getValue()) {
                check(entry.getKey().equals(fact.identityRef()), entry.getKey(), "根源要求索引与身份不一致");
                check(Set.of("IDENTITY", "PAST_EVENT", "OBLIGATION", "KNOWLEDGE").contains(fact.factType()),
                        entry.getKey(), "不支持的根源事实类型");
                id(fact.semanticRole(), entry.getKey());
                text(fact.playerLabel(), 40, entry.getKey());
            }
        }
        check(totalFacts <= 40, "$.creationProfile.requiredFactsByIdentity", "根源要求数量超限");
        creation.routeThemes().forEach(theme -> {
            text(theme.summary(), 500, theme.themeId());
            references(theme.loreRefs(), "lore", refs, theme.themeId());
        });
        creation.scenarioSeedsById().values().forEach(seed -> {
            size(seed.eligibleRegionRefs(), 1, 100, seed.seedId());
            // 原 v1 编译器保留种子地域列表中的重复项；读取不能借修复快照改变已发布合同。
            seed.eligibleRegionRefs().forEach(ref -> check("region".equals(refs.get(ref)), seed.seedId(), "场景种子引用无效地域"));
            check(creation.openingRegionRefs().containsAll(seed.eligibleRegionRefs()), seed.seedId(), "场景种子引用不可开场地域");
            text(seed.summary(), 500, seed.seedId());
            tags(seed.tags(), seed.seedId());
        });
    }

    private void validateLore(CompiledWorldPackage c, Map<String, String> refs) {
        List<LoreEntry> entries = c.loreById().values().stream()
                .sorted(Comparator.comparingInt(LoreEntry::priority).reversed().thenComparing(LoreEntry::loreId)).toList();
        List<String> always = new ArrayList<>();
        Map<String, List<String>> identities = new HashMap<>(), locations = new HashMap<>(), entityTags = new HashMap<>(),
                threads = new HashMap<>(), terms = new HashMap<>();
        for (LoreEntry entry : entries) {
            String id = entry.loreId();
            text(entry.title(), 120, id);
            text(entry.content(), 4_000, id);
            check(entry.priority() >= 0 && entry.priority() <= 1_000, id, "Lore 优先级无效");
            tags(entry.tags(), id);
            references(entry.relatedRefs(), null, refs, id);
            LoreActivation activation = entry.activation();
            references(activation.identityRefs(), "race", refs, id);
            references(activation.locationRefs(), "region", refs, id);
            references(activation.storyThreadRefs(), "seed", refs, id);
            tags(activation.entityTags(), id);
            size(activation.terms(), 0, 32, id);
            Set<String> normalizedTerms = new HashSet<>();
            activation.terms().forEach(term -> {
                text(term, 80, id);
                check(normalizedTerms.add(normalizeTerm(term)), id, "Lore 规范词项重复");
            });
            check(activation.always() || !activation.identityRefs().isEmpty() || !activation.locationRefs().isEmpty()
                            || !activation.entityTags().isEmpty() || !activation.storyThreadRefs().isEmpty() || !activation.terms().isEmpty(),
                    id, "Lore 不可通过任何索引访问");
            if (activation.always()) always.add(id);
            addIndex(identities, activation.identityRefs(), id, false);
            addIndex(locations, activation.locationRefs(), id, false);
            addIndex(entityTags, activation.entityTags(), id, false);
            addIndex(threads, activation.storyThreadRefs(), id, false);
            addIndex(terms, activation.terms(), id, true);
        }
        check(always.size() <= 8, "$.loreActivationIndex.always", "常驻 Lore 数量超限");
        LoreActivationIndex index = c.loreActivationIndex();
        check(always.equals(index.always()) && identities.equals(index.byIdentity()) && locations.equals(index.byLocation())
                        && entityTags.equals(index.byEntityTag()) && threads.equals(index.byStoryThread()) && terms.equals(index.byTerm()),
                "$.loreActivationIndex", "Lore 激活索引与条目、顺序或规范词项不一致");
    }

    private void validateTextAndAssets(CompiledWorldPackage c) {
        text(c.premise(), 2_000, "$.premise");
        ContentMetadata metadata = c.metadata();
        text(metadata.defaultLocale(), 24, "$.metadata.defaultLocale");
        text(metadata.name(), 80, "$.metadata.name");
        text(metadata.summary(), 500, "$.metadata.summary");
        text(metadata.license(), 80, "$.metadata.license");
        size(metadata.authors(), 1, 8, "$.metadata.authors");
        metadata.authors().forEach(author -> text(author, 80, "$.metadata.authors"));
        check(Set.of("GENERAL", "TEEN", "MATURE").contains(metadata.contentRating()), "$.metadata.contentRating", "内容分级无效");
        List<WorldRule> ordered = c.alwaysOnRules().stream()
                .sorted(Comparator.comparingInt(WorldRule::priority).reversed().thenComparing(WorldRule::ruleId)).toList();
        check(c.alwaysOnRules().equals(ordered), "$.alwaysOnRules", "常驻规则顺序无效");
        c.alwaysOnRules().forEach(rule -> {
            text(rule.text(), 1_000, rule.ruleId());
            check(rule.priority() >= 0 && rule.priority() <= 1_000, rule.ruleId(), "规则优先级无效");
        });
        c.contentBoundaries().forEach(boundary -> text(boundary.text(), 1_000, boundary.boundaryId()));
        NarrativeProfile narrative = c.narrativeProfile();
        text(narrative.guidance(), 2_000, "$.narrativeProfile.guidance");
        text(narrative.openSpace(), 1_000, "$.narrativeProfile.openSpace");
        size(narrative.tone(), 1, 12, "$.narrativeProfile.tone");
        size(narrative.conflictThemes(), 0, 20, "$.narrativeProfile.conflictThemes");
        narrative.tone().forEach(tone -> text(tone, 120, "$.narrativeProfile.tone"));
        narrative.conflictThemes().forEach(theme -> text(theme, 120, "$.narrativeProfile.conflictThemes"));
        size(c.assets(), 0, 32, "$.assets");
        Set<String> paths = new HashSet<>();
        for (AssetDescriptor asset : c.assets()) {
            String path = asset.path();
            check(path != null && path.startsWith("assets/") && !path.contains("\\") && !path.contains("..")
                            && !path.contains("//") && !path.contains("/./") && !path.endsWith("/")
                            && !path.endsWith("/.") && !path.contains("\u0000") && paths.add(path),
                    "$.assets.path", "素材相对路径无效或重复");
            id(asset.role(), "$.assets.role");
            check(Set.of("image/png", "image/jpeg", "image/webp").contains(asset.mediaType()), path, "素材类型无效");
            check(asset.byteSize() >= 1 && asset.byteSize() <= 2_000_000, path, "素材大小无效");
            check(asset.sha256() != null && HASH.matcher(asset.sha256()).matches(), path, "素材哈希无效");
        }
    }

    private void inspectTree(JsonNode node, String field, int[] nodes) {
        check(++nodes[0] <= MAX_JSON_NODES, "$", "JSON 节点数超过限制");
        if (node.isNull()) {
            check("nextStepId".equals(field) || "nextStageId".equals(field), field, "必填结构不能为 null");
        } else if (node.isObject()) {
            check(node.size() <= 100_000, field, "JSON 映射超过限制");
            node.fields().forEachRemaining(entry -> inspectTree(entry.getValue(), entry.getKey(), nodes));
        } else if (node.isArray()) {
            check(node.size() <= 100_000, field, "JSON 数组超过限制");
            node.forEach(value -> inspectTree(value, "[]", nodes));
        }
    }

    private static <T> void indexed(Map<String, T> values, Function<T, String> identity, int minimum, int maximum,
                                    String kind, Map<String, String> refs) {
        size(values.values(), minimum, maximum, kind);
        values.forEach((key, value) -> {
            check(value != null && Objects.equals(key, identity.apply(value)), kind, "映射键与条目身份不一致");
            register(refs, key, kind);
        });
    }

    private static void register(Map<String, String> refs, String value, String kind) {
        id(value, kind);
        check(refs.putIfAbsent(value, kind) == null, kind, "稳定引用在包内重复");
    }

    private static void references(List<String> values, String kind, Map<String, String> refs, String path) {
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            check(refs.containsKey(value) && (kind == null || kind.equals(refs.get(value))) && seen.add(value),
                    path, "稳定引用不存在、类型错误或重复");
        }
    }

    private static Set<String> capabilities(List<String> values, String path) {
        size(values, 1, 16, path);
        Set<String> result = new HashSet<>(values);
        check(result.size() == values.size() && result.equals(V1_CAPABILITIES), path, "v1 能力缺失、重复或不受支持");
        return result;
    }

    private static void tags(List<String> values, String path) {
        size(values, 0, 32, path);
        Set<String> seen = new HashSet<>();
        values.forEach(value -> check(value != null && TAG.matcher(value).matches() && seen.add(value), path, "标签无效或重复"));
    }

    private static void addIndex(Map<String, List<String>> index, List<String> keys, String id, boolean normalize) {
        keys.forEach(key -> index.computeIfAbsent(normalize ? normalizeTerm(key) : key, ignored -> new ArrayList<>()).add(id));
    }

    private static String normalizeTerm(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    private static void id(String value, String path) {
        check(value != null && STABLE_ID.matcher(value).matches(), path, "稳定 ID 无效");
    }

    private static void text(String value, int max, String path) {
        check(value != null && !value.isBlank() && value.length() <= max, path, "文本为空或超限");
    }

    private static void size(Collection<?> values, int minimum, int maximum, String path) {
        check(values != null && values.size() >= minimum && values.size() <= maximum, path, "集合数量无效");
    }

    private static void check(boolean valid, String path, String message) {
        if (!valid) throw new IllegalStateException("世界包编译快照无效：" + message + "（" + path + "）");
    }
}
