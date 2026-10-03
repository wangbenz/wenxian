package com.wenxian.game.story.worldpackage.application;

import com.wenxian.game.story.worldpackage.application.WorldPackageValidationException.Violation;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.AssetDescriptor;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.CreationContent;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.LoreEntry;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ProgressionStage;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ProgressionStep;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ProgressionSystem;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ProgressionTrack;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.RaceDefinition;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.RegionDefinition;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.WorldContent;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 校验 Bundle v1 规范文档的通用结构、稳定引用、能力和资源上限。
 *
 * <p>校验器不判断内容是否“像修仙”，也不执行作者文本。它只接受当前运行时明确支持的声明式能力，
 * 累积全部可定位问题后一次返回；失败候选不得持久化、调用模型或进入创角目录。</p>
 */
public final class WorldPackageValidator {
    public static final String FORMAT = "wenxian.world-package";
    public static final int FORMAT_VERSION = 1;
    public static final String RUNTIME_CONTRACT = "narrative-world-v1";
    public static final String COMPILER_VERSION = "world-package-compiler-v1";

    private static final Pattern STABLE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{1,79}");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");
    private static final Set<String> SUPPORTED_CAPABILITIES = Set.of(
            "creation-catalog-v1",
            "linear-progression-v1",
            "triggered-lore-v1",
            "player-confirmed-ending-v1"
    );
    private static final Set<String> CONTENT_RATINGS = Set.of("GENERAL", "TEEN", "MATURE");
    private static final Set<String> FACT_TYPES = Set.of("IDENTITY", "PAST_EVENT", "OBLIGATION", "KNOWLEDGE");

    public void validate(WorldPackageDocument document) {
        List<Violation> errors = new ArrayList<>();
        if (document == null) {
            throw new WorldPackageValidationException(List.of(
                    new Violation("DOCUMENT_REQUIRED", "$", "规范文档不能为空")));
        }
        validateIdentity(document, errors);
        validateMetadata(document, errors);
        Map<String, String> refs = registerReferences(document, errors);
        validateWorld(document.world(), refs, errors);
        Map<String, ProgressionTrack> tracks = validateProgression(document.progression(), refs, errors);
        validateCreation(document.creation(), document.world(), tracks, refs, errors);
        validateLore(document.loreEntries(), refs, document.creation(), errors);
        validateAssets(document.assets(), errors);
        if (!errors.isEmpty()) throw new WorldPackageValidationException(errors);
    }

    private void validateIdentity(WorldPackageDocument document, List<Violation> errors) {
        var identity = document.identity();
        if (identity == null) {
            error(errors, "IDENTITY_REQUIRED", "$.identity", "包身份不能为空");
            return;
        }
        stableId(identity.publisherNamespace(), "$.identity.publisherNamespace", errors);
        stableId(identity.packageId(), "$.identity.packageId", errors);
        if (identity.revision() < 1) {
            error(errors, "REVISION_INVALID", "$.identity.revision", "修订号必须大于零");
        }
        if (identity.formatVersion() != FORMAT_VERSION) {
            error(errors, "FORMAT_VERSION_UNSUPPORTED", "$.identity.formatVersion", "只支持 Bundle 格式版本 1");
        }
        if (!RUNTIME_CONTRACT.equals(identity.runtimeContract())) {
            error(errors, "RUNTIME_CONTRACT_UNSUPPORTED", "$.identity.runtimeContract",
                    "当前运行时不支持该合同");
        }
        bounded(identity.requiredCapabilities(), 1, 16, "$.identity.requiredCapabilities", errors);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < list(identity.requiredCapabilities()).size(); index++) {
            String capability = list(identity.requiredCapabilities()).get(index);
            String path = "$.identity.requiredCapabilities[" + index + "]";
            text(capability, 80, path, errors);
            if (!SUPPORTED_CAPABILITIES.contains(capability)) {
                error(errors, "CAPABILITY_UNSUPPORTED", path, "当前运行时不支持能力：" + safe(capability));
            } else if (!seen.add(capability)) {
                error(errors, "CAPABILITY_DUPLICATE", path, "能力不能重复");
            }
        }
        for (String required : SUPPORTED_CAPABILITIES) {
            if (!seen.contains(required)) {
                error(errors, "CAPABILITY_REQUIRED", "$.identity.requiredCapabilities",
                        "Bundle v1 缺少必要能力：" + required);
            }
        }
    }

    private void validateMetadata(WorldPackageDocument document, List<Violation> errors) {
        var metadata = document.metadata();
        if (metadata == null) {
            error(errors, "METADATA_REQUIRED", "$.metadata", "内容元数据不能为空");
            return;
        }
        text(metadata.defaultLocale(), 24, "$.metadata.defaultLocale", errors);
        text(metadata.name(), 80, "$.metadata.name", errors);
        text(metadata.summary(), 500, "$.metadata.summary", errors);
        stringList(metadata.authors(), 1, 8, 80, "$.metadata.authors", errors);
        text(metadata.license(), 80, "$.metadata.license", errors);
        if (!CONTENT_RATINGS.contains(metadata.contentRating())) {
            error(errors, "CONTENT_RATING_INVALID", "$.metadata.contentRating",
                    "内容分级必须为 GENERAL、TEEN 或 MATURE");
        }
    }

    private Map<String, String> registerReferences(WorldPackageDocument document, List<Violation> errors) {
        Map<String, String> refs = new LinkedHashMap<>();
        WorldContent world = document.world();
        if (world != null) {
            registerAll(list(world.coreRules()), value -> value == null ? null : value.ruleId(), "rule", refs, errors);
            registerAll(list(world.contentBoundaries()), value -> value == null ? null : value.boundaryId(),
                    "boundary", refs, errors);
            registerAll(list(world.races()), value -> value == null ? null : value.raceId(), "race", refs, errors);
            registerAll(list(world.regions()), value -> value == null ? null : value.regionId(), "region", refs, errors);
        }
        CreationContent creation = document.creation();
        if (creation != null) {
            registerAll(list(creation.routeThemes()), value -> value == null ? null : value.themeId(),
                    "route-theme", refs, errors);
            registerAll(list(creation.scenarioSeeds()), value -> value == null ? null : value.seedId(),
                    "scenario-seed", refs, errors);
        }
        if (document.progression() != null) {
            for (ProgressionSystem system : list(document.progression().systems())) {
                if (system == null) continue;
                register(system.systemId(), "progression-system", refs, errors);
                registerAll(list(system.steps()), value -> value == null ? null : value.stepId(),
                        "progression-step", refs, errors);
                for (ProgressionTrack track : list(system.tracks())) {
                    if (track == null) continue;
                    register(track.trackId(), "progression-track", refs, errors);
                    registerAll(list(track.stages()), value -> value == null ? null : value.stageId(),
                            "progression-stage", refs, errors);
                }
            }
        }
        registerAll(document.loreEntries(), value -> value == null ? null : value.loreId(), "lore", refs, errors);
        return refs;
    }

    private void validateWorld(WorldContent world, Map<String, String> refs, List<Violation> errors) {
        if (world == null) {
            error(errors, "WORLD_REQUIRED", "$.world", "世界主体不能为空");
            return;
        }
        text(world.premise(), 2_000, "$.world.premise", errors);
        bounded(world.coreRules(), 1, 32, "$.world.coreRules", errors);
        for (int index = 0; index < list(world.coreRules()).size(); index++) {
            var rule = list(world.coreRules()).get(index);
            String path = "$.world.coreRules[" + index + "]";
            if (rule == null) {
                error(errors, "RULE_REQUIRED", path, "核心规则不能为空");
                continue;
            }
            text(rule.text(), 1_000, path + ".text", errors);
            range(rule.priority(), 0, 1_000, path + ".priority", errors);
        }
        bounded(world.contentBoundaries(), 0, 32, "$.world.contentBoundaries", errors);
        for (int index = 0; index < list(world.contentBoundaries()).size(); index++) {
            var boundary = list(world.contentBoundaries()).get(index);
            if (boundary == null) {
                error(errors, "BOUNDARY_REQUIRED", "$.world.contentBoundaries[" + index + "]", "内容边界不能为空");
            } else {
                text(boundary.text(), 1_000, "$.world.contentBoundaries[" + index + "].text", errors);
            }
        }
        bounded(world.races(), 1, 20, "$.world.races", errors);
        for (int index = 0; index < list(world.races()).size(); index++) {
            RaceDefinition race = list(world.races()).get(index);
            String path = "$.world.races[" + index + "]";
            if (race == null) {
                error(errors, "RACE_REQUIRED", path, "种族不能为空");
                continue;
            }
            text(race.name(), 80, path + ".name", errors);
            text(race.description(), 1_000, path + ".description", errors);
            stringList(race.identityFacts(), 1, 16, 500, path + ".identityFacts", errors);
            stringList(race.socialHooks(), 0, 16, 500, path + ".socialHooks", errors);
            tags(race.opportunityTags(), path + ".opportunityTags", errors);
            reference(race.primaryProgressionTrackRef(), "progression-track",
                    path + ".primaryProgressionTrackRef", refs, errors);
        }
        bounded(world.regions(), 1, 100, "$.world.regions", errors);
        for (int index = 0; index < list(world.regions()).size(); index++) {
            RegionDefinition region = list(world.regions()).get(index);
            String path = "$.world.regions[" + index + "]";
            if (region == null) {
                error(errors, "REGION_REQUIRED", path, "地域不能为空");
                continue;
            }
            text(region.name(), 100, path + ".name", errors);
            text(region.description(), 1_500, path + ".description", errors);
            tags(region.tags(), path + ".tags", errors);
        }
        if (world.narrativeProfile() == null) {
            error(errors, "NARRATIVE_PROFILE_REQUIRED", "$.world.narrativeProfile", "叙事配置不能为空");
        } else {
            text(world.narrativeProfile().guidance(), 2_000,
                    "$.world.narrativeProfile.guidance", errors);
            stringList(world.narrativeProfile().tone(), 1, 12, 120, "$.world.narrativeProfile.tone", errors);
            stringList(world.narrativeProfile().conflictThemes(), 0, 20, 120,
                    "$.world.narrativeProfile.conflictThemes", errors);
            text(world.narrativeProfile().openSpace(), 1_000, "$.world.narrativeProfile.openSpace", errors);
        }
    }

    private Map<String, ProgressionTrack> validateProgression(WorldPackageDocument.ProgressionContent progression,
                                                               Map<String, String> refs,
                                                               List<Violation> errors) {
        Map<String, ProgressionTrack> tracksById = new HashMap<>();
        if (progression == null) {
            error(errors, "PROGRESSION_REQUIRED", "$.progression", "成长配置不能为空");
            return tracksById;
        }
        bounded(progression.systems(), 1, 8, "$.progression.systems", errors);
        for (int systemIndex = 0; systemIndex < list(progression.systems()).size(); systemIndex++) {
            ProgressionSystem system = list(progression.systems()).get(systemIndex);
            String path = "$.progression.systems[" + systemIndex + "]";
            if (system == null) {
                error(errors, "PROGRESSION_SYSTEM_REQUIRED", path, "成长体系不能为空");
                continue;
            }
            if (!"LINEAR_TIERED".equals(system.kind())) {
                error(errors, "PROGRESSION_KIND_UNSUPPORTED", path + ".kind", "v1 只支持 LINEAR_TIERED");
            }
            if (system.revision() < 1) {
                error(errors, "PROGRESSION_REVISION_INVALID", path + ".revision", "成长体系修订号必须大于零");
            }
            text(system.displayName(), 80, path + ".displayName", errors);
            if (system.completion() == null
                    || !"TRACK_END".equals(system.completion().position())
                    || !"player-confirmed-ending-v1".equals(system.completion().unlocks())) {
                error(errors, "PROGRESSION_COMPLETION_INVALID", path + ".completion",
                        "成长终点必须只解锁玩家确认的终章");
            }
            bounded(system.steps(), 1, 8, path + ".steps", errors);
            validateOrdered(list(system.steps()), ProgressionStep::order, path + ".steps", errors);
            for (int index = 0; index < list(system.steps()).size(); index++) {
                ProgressionStep step = list(system.steps()).get(index);
                if (step != null) text(step.name(), 40, path + ".steps[" + index + "].name", errors);
            }
            bounded(system.tracks(), 1, 20, path + ".tracks", errors);
            Set<Integer> expectedTiers = null;
            for (int trackIndex = 0; trackIndex < list(system.tracks()).size(); trackIndex++) {
                ProgressionTrack track = list(system.tracks()).get(trackIndex);
                String trackPath = path + ".tracks[" + trackIndex + "]";
                if (track == null) {
                    error(errors, "PROGRESSION_TRACK_REQUIRED", trackPath, "成长轨道不能为空");
                    continue;
                }
                tracksById.put(track.trackId(), track);
                bounded(track.identityRefs(), 1, 20, trackPath + ".identityRefs", errors);
                references(track.identityRefs(), "race", trackPath + ".identityRefs", refs, errors);
                bounded(track.stages(), 2, 20, trackPath + ".stages", errors);
                validateOrdered(list(track.stages()), ProgressionStage::order, trackPath + ".stages", errors);
                Set<Integer> tiers = new HashSet<>();
                Map<String, String> stageReferences = new HashMap<>();
                for (int stageIndex = 0; stageIndex < list(track.stages()).size(); stageIndex++) {
                    ProgressionStage stage = list(track.stages()).get(stageIndex);
                    String stagePath = trackPath + ".stages[" + stageIndex + "]";
                    if (stage == null) continue;
                    if (stage.comparisonTier() != stage.order() || !tiers.add(stage.comparisonTier())) {
                        error(errors, "COMPARISON_TIER_INVALID", stagePath + ".comparisonTier",
                                "线性分层 v1 的比较层级必须与连续顺序一致");
                    }
                    text(stage.name(), 80, stagePath + ".name", errors);
                    text(stage.description(), 1_000, stagePath + ".description", errors);
                    text(stage.capabilityBoundary(), 1_000, stagePath + ".capabilityBoundary", errors);
                    bounded(stage.aliases(), 0, 32, stagePath + ".aliases", errors);
                    registerStageReference(stageReferences, stage.name(), stage.stageId(), stagePath + ".name", errors);
                    for (int aliasIndex = 0; aliasIndex < list(stage.aliases()).size(); aliasIndex++) {
                        String aliasPath = stagePath + ".aliases[" + aliasIndex + "]";
                        text(list(stage.aliases()).get(aliasIndex), 80, aliasPath, errors);
                        registerStageReference(stageReferences, list(stage.aliases()).get(aliasIndex),
                                stage.stageId(), aliasPath, errors);
                    }
                }
                if (expectedTiers == null) expectedTiers = Set.copyOf(tiers);
                else if (!expectedTiers.equals(tiers)) {
                    error(errors, "COMPARISON_TIERS_MISMATCH", trackPath + ".stages",
                            "同一线性体系的各轨道必须覆盖相同比较层级");
                }
                if (track.start() == null) {
                    error(errors, "PROGRESSION_START_REQUIRED", trackPath + ".start", "成长轨道缺少开局位置");
                } else {
                    reference(track.start().stageRef(), "progression-stage", trackPath + ".start.stageRef", refs, errors);
                    reference(track.start().stepRef(), "progression-step", trackPath + ".start.stepRef", refs, errors);
                    if (list(track.stages()).stream().filter(java.util.Objects::nonNull)
                            .noneMatch(stage -> stage.stageId().equals(track.start().stageRef()))) {
                        error(errors, "START_STAGE_OUTSIDE_TRACK", trackPath + ".start.stageRef",
                                "开局大境界必须属于当前轨道");
                    }
                    if (list(system.steps()).stream().filter(java.util.Objects::nonNull)
                            .noneMatch(step -> step.stepId().equals(track.start().stepRef()))) {
                        error(errors, "START_STEP_OUTSIDE_SYSTEM", trackPath + ".start.stepRef",
                                "开局小阶段必须属于当前体系");
                    }
                }
            }
        }
        return tracksById;
    }

    private void validateCreation(CreationContent creation, WorldContent world,
                                  Map<String, ProgressionTrack> tracks, Map<String, String> refs,
                                  List<Violation> errors) {
        if (creation == null) {
            error(errors, "CREATION_REQUIRED", "$.creation", "创角配置不能为空");
            return;
        }
        bounded(creation.playableRaceRefs(), 1, 20, "$.creation.playableRaceRefs", errors);
        Set<String> playable = new HashSet<>();
        for (int index = 0; index < list(creation.playableRaceRefs()).size(); index++) {
            String value = list(creation.playableRaceRefs()).get(index);
            reference(value, "race", "$.creation.playableRaceRefs[" + index + "]", refs, errors);
            if (!playable.add(value)) error(errors, "PLAYABLE_RACE_DUPLICATE",
                    "$.creation.playableRaceRefs[" + index + "]", "可玩种族不能重复");
        }
        bounded(creation.openingRegionRefs(), 1, 100, "$.creation.openingRegionRefs", errors);
        Set<String> openings = new HashSet<>();
        for (int index = 0; index < list(creation.openingRegionRefs()).size(); index++) {
            String value = list(creation.openingRegionRefs()).get(index);
            reference(value, "region", "$.creation.openingRegionRefs[" + index + "]", refs, errors);
            if (!openings.add(value)) error(errors, "OPENING_REGION_DUPLICATE",
                    "$.creation.openingRegionRefs[" + index + "]", "开场地域不能重复");
        }
        if (!openings.contains(creation.defaultOpeningRegionRef())) {
            error(errors, "DEFAULT_OPENING_INVALID", "$.creation.defaultOpeningRegionRef",
                    "默认开场地域必须属于可开场地域列表");
        }
        bounded(creation.requiredOriginFacts(), 0, 40, "$.creation.requiredOriginFacts", errors);
        for (int index = 0; index < list(creation.requiredOriginFacts()).size(); index++) {
            var fact = list(creation.requiredOriginFacts()).get(index);
            String path = "$.creation.requiredOriginFacts[" + index + "]";
            if (fact == null) {
                error(errors, "ORIGIN_FACT_REQUIRED", path, "根源事实约束不能为空");
                continue;
            }
            reference(fact.identityRef(), "race", path + ".identityRef", refs, errors);
            if (!playable.contains(fact.identityRef())) {
                error(errors, "ORIGIN_FACT_IDENTITY_NOT_PLAYABLE", path + ".identityRef",
                        "根源事实约束只能引用可玩种族");
            }
            if (!FACT_TYPES.contains(fact.factType())) {
                error(errors, "ORIGIN_FACT_TYPE_INVALID", path + ".factType", "不支持该根源事实类型");
            }
            stableId(fact.semanticRole(), path + ".semanticRole", errors);
            text(fact.playerLabel(), 40, path + ".playerLabel", errors);
        }
        bounded(creation.routeThemes(), 0, 40, "$.creation.routeThemes", errors);
        for (int index = 0; index < list(creation.routeThemes()).size(); index++) {
            var theme = list(creation.routeThemes()).get(index);
            String path = "$.creation.routeThemes[" + index + "]";
            if (theme == null) continue;
            text(theme.summary(), 500, path + ".summary", errors);
            references(theme.loreRefs(), "lore", path + ".loreRefs", refs, errors);
        }
        bounded(creation.scenarioSeeds(), 0, 100, "$.creation.scenarioSeeds", errors);
        for (int index = 0; index < list(creation.scenarioSeeds()).size(); index++) {
            var seed = list(creation.scenarioSeeds()).get(index);
            String path = "$.creation.scenarioSeeds[" + index + "]";
            if (seed == null) continue;
            bounded(seed.eligibleRegionRefs(), 1, 100, path + ".eligibleRegionRefs", errors);
            for (int regionIndex = 0; regionIndex < list(seed.eligibleRegionRefs()).size(); regionIndex++) {
                String ref = list(seed.eligibleRegionRefs()).get(regionIndex);
                reference(ref, "region", path + ".eligibleRegionRefs[" + regionIndex + "]", refs, errors);
                if (!openings.contains(ref)) error(errors, "SCENARIO_REGION_NOT_OPENING",
                        path + ".eligibleRegionRefs[" + regionIndex + "]", "场景种子只能引用可开场地域");
            }
            tags(seed.tags(), path + ".tags", errors);
            text(seed.summary(), 500, path + ".summary", errors);
        }
        for (int index = 0; index < list(world == null ? null : world.races()).size(); index++) {
            RaceDefinition race = list(world.races()).get(index);
            if (race == null) continue;
            ProgressionTrack track = tracks.get(race.primaryProgressionTrackRef());
            if (track != null && !list(track.identityRefs()).contains(race.raceId())) {
                error(errors, "RACE_TRACK_BINDING_INVALID", "$.world.races[" + index + "].primaryProgressionTrackRef",
                        "种族引用的主成长轨道没有绑定该种族");
            }
        }
        Map<String, Integer> assignments = new HashMap<>();
        tracks.values().forEach(track -> list(track.identityRefs())
                .forEach(ref -> assignments.merge(ref, 1, Integer::sum)));
        for (String raceRef : playable) {
            if (assignments.getOrDefault(raceRef, 0) != 1) {
                error(errors, "PLAYABLE_RACE_TRACK_AMBIGUOUS", "$.creation.playableRaceRefs",
                        "每个可玩种族必须且只能绑定一条成长轨道：" + raceRef);
            }
        }
    }

    private void validateLore(List<LoreEntry> loreEntries, Map<String, String> refs,
                              CreationContent creation, List<Violation> errors) {
        bounded(loreEntries, 0, 2_000, "$.loreEntries", errors);
        Set<String> scenarioIds = new HashSet<>();
        if (creation != null) list(creation.scenarioSeeds()).stream().filter(java.util.Objects::nonNull)
                .forEach(seed -> scenarioIds.add(seed.seedId()));
        int alwaysCount = 0;
        for (int index = 0; index < list(loreEntries).size(); index++) {
            LoreEntry lore = list(loreEntries).get(index);
            String path = "$.loreEntries[" + index + "]";
            if (lore == null) {
                error(errors, "LORE_REQUIRED", path, "Lore 条目不能为空");
                continue;
            }
            text(lore.title(), 120, path + ".title", errors);
            text(lore.content(), 4_000, path + ".content", errors);
            range(lore.priority(), 0, 1_000, path + ".priority", errors);
            tags(lore.tags(), path + ".tags", errors);
            references(lore.relatedRefs(), null, path + ".relatedRefs", refs, errors);
            if (lore.activation() == null) {
                error(errors, "LORE_ACTIVATION_REQUIRED", path + ".activation", "Lore 必须声明激活方式");
                continue;
            }
            if (lore.activation().always()) alwaysCount++;
            references(lore.activation().identityRefs(), "race", path + ".activation.identityRefs", refs, errors);
            references(lore.activation().locationRefs(), "region", path + ".activation.locationRefs", refs, errors);
            tags(lore.activation().entityTags(), path + ".activation.entityTags", errors);
            Set<String> threadRefs = new HashSet<>();
            for (int threadIndex = 0; threadIndex < list(lore.activation().storyThreadRefs()).size(); threadIndex++) {
                String ref = list(lore.activation().storyThreadRefs()).get(threadIndex);
                if (!scenarioIds.contains(ref)) error(errors, "LORE_THREAD_REF_INVALID",
                        path + ".activation.storyThreadRefs[" + threadIndex + "]",
                        "v1 故事线触发只能引用当前包的场景种子");
                if (!threadRefs.add(ref)) error(errors, "REFERENCE_DUPLICATE",
                        path + ".activation.storyThreadRefs[" + threadIndex + "]", "引用不能重复");
            }
            stringList(lore.activation().terms(), 0, 32, 80, path + ".activation.terms", errors);
            Set<String> normalizedTerms = new HashSet<>();
            for (int termIndex = 0; termIndex < lore.activation().terms().size(); termIndex++) {
                String term = lore.activation().terms().get(termIndex);
                if (term != null && !normalizedTerms.add(Normalizer.normalize(term, Normalizer.Form.NFKC)
                        .strip().toLowerCase(Locale.ROOT))) {
                    error(errors, "LORE_TERM_DUPLICATE", path + ".activation.terms[" + termIndex + "]",
                            "规范化后的触发词不能重复");
                }
            }
            if (!lore.activation().always()
                    && lore.activation().identityRefs().isEmpty()
                    && lore.activation().locationRefs().isEmpty()
                    && lore.activation().entityTags().isEmpty()
                    && lore.activation().storyThreadRefs().isEmpty()
                    && lore.activation().terms().isEmpty()) {
                error(errors, "LORE_UNREACHABLE", path + ".activation", "Lore 至少需要一种可达的激活方式");
            }
        }
        if (alwaysCount > 8) {
            error(errors, "LORE_ALWAYS_LIMIT", "$.loreEntries", "始终注入的 Lore 不能超过 8 条");
        }
    }

    private void validateAssets(List<AssetDescriptor> assets, List<Violation> errors) {
        bounded(assets, 0, 32, "$.assets", errors);
        Set<String> paths = new HashSet<>();
        for (int index = 0; index < list(assets).size(); index++) {
            AssetDescriptor asset = list(assets).get(index);
            String path = "$.assets[" + index + "]";
            if (asset == null) {
                error(errors, "ASSET_REQUIRED", path, "素材描述不能为空");
                continue;
            }
            if (asset.path() == null || !asset.path().startsWith("assets/") || asset.path().contains("\\")
                    || asset.path().contains("..") || !paths.add(asset.path())) {
                error(errors, "ASSET_PATH_INVALID", path + ".path", "素材必须使用唯一的 assets/ 相对路径");
            }
            stableId(asset.role(), path + ".role", errors);
            if (!Set.of("image/png", "image/jpeg", "image/webp").contains(asset.mediaType())) {
                error(errors, "ASSET_MEDIA_TYPE_INVALID", path + ".mediaType", "只允许 PNG、JPEG 或 WebP 图片");
            }
            if (asset.byteSize() < 1 || asset.byteSize() > 2_000_000) {
                error(errors, "ASSET_SIZE_INVALID", path + ".byteSize", "单个素材必须在 1 字节至 2 MB 之间");
            }
            if (asset.sha256() == null || !SHA_256.matcher(asset.sha256()).matches()) {
                error(errors, "ASSET_HASH_INVALID", path + ".sha256", "素材 SHA-256 必须为小写十六进制");
            }
        }
    }

    private <T> void registerAll(List<T> values, Function<T, String> id,
                                 String type, Map<String, String> refs, List<Violation> errors) {
        for (T value : list(values)) if (value != null) register(id.apply(value), type, refs, errors);
    }

    private void register(String id, String type, Map<String, String> refs, List<Violation> errors) {
        if (!stableId(id, "$.references", errors)) return;
        String previous = refs.putIfAbsent(id, type);
        if (previous != null) error(errors, "STABLE_ID_DUPLICATE", "$.references",
                "稳定 ID 在包内重复：" + id + "（" + previous + " / " + type + "）");
    }

    private void reference(String value, String expectedType, String path,
                           Map<String, String> refs, List<Violation> errors) {
        if (!stableId(value, path, errors)) return;
        String actual = refs.get(value);
        if (actual == null) error(errors, "REFERENCE_MISSING", path, "引用不存在：" + value);
        else if (expectedType != null && !expectedType.equals(actual)) {
            error(errors, "REFERENCE_TYPE_INVALID", path,
                    "引用类型应为 " + expectedType + "，实际为 " + actual);
        }
    }

    private void references(List<String> values, String expectedType, String path,
                            Map<String, String> refs, List<Violation> errors) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < list(values).size(); index++) {
            String value = list(values).get(index);
            reference(value, expectedType, path + "[" + index + "]", refs, errors);
            if (!seen.add(value)) {
                error(errors, "REFERENCE_DUPLICATE", path + "[" + index + "]", "引用不能重复");
            }
        }
    }

    private void registerStageReference(Map<String, String> owners, String value, String stageId,
                                        String path, List<Violation> errors) {
        if (value == null || value.isBlank()) return;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        String previous = owners.putIfAbsent(normalized, stageId);
        if (previous != null) error(errors, "STAGE_REFERENCE_AMBIGUOUS", path,
                "同一轨道内名称或别称不能指向多个大境界");
    }

    private <T> void validateOrdered(List<T> values, Function<T, Integer> order,
                                     String path, List<Violation> errors) {
        Set<Integer> orders = new HashSet<>();
        for (int index = 0; index < values.size(); index++) {
            T value = values.get(index);
            if (value == null) {
                error(errors, "ORDERED_ITEM_REQUIRED", path + "[" + index + "]", "有序项不能为空");
                continue;
            }
            int actual = order.apply(value);
            if (actual < 1 || !orders.add(actual)) {
                error(errors, "ORDER_INVALID", path + "[" + index + "].order", "顺序必须从 1 开始且不能重复");
            }
        }
        for (int expected = 1; expected <= values.size(); expected++) {
            if (!orders.contains(expected)) {
                error(errors, "ORDER_NOT_CONTIGUOUS", path, "顺序必须从 1 连续排列");
                break;
            }
        }
    }

    private void stringList(List<String> values, int minimum, int maximum, int maxLength,
                            String path, List<Violation> errors) {
        bounded(values, minimum, maximum, path, errors);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < list(values).size(); index++) {
            String value = list(values).get(index);
            text(value, maxLength, path + "[" + index + "]", errors);
            if (value != null && !seen.add(value.strip())) {
                error(errors, "VALUE_DUPLICATE", path + "[" + index + "]", "列表内容不能重复");
            }
        }
    }

    private void tags(List<String> values, String path, List<Violation> errors) {
        bounded(values, 0, 32, path, errors);
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < list(values).size(); index++) {
            String value = list(values).get(index);
            if (value == null || !TAG.matcher(value).matches()) {
                error(errors, "TAG_INVALID", path + "[" + index + "]", "标签只能使用受限 ASCII 字符");
            } else if (!seen.add(value)) {
                error(errors, "TAG_DUPLICATE", path + "[" + index + "]", "标签不能重复");
            }
        }
    }

    private boolean stableId(String value, String path, List<Violation> errors) {
        if (value == null || !STABLE_ID.matcher(value).matches()) {
            error(errors, "STABLE_ID_INVALID", path, "稳定 ID 必须为 2—80 位受限 ASCII 字符");
            return false;
        }
        return true;
    }

    private void text(String value, int maximum, String path, List<Violation> errors) {
        if (value == null || value.isBlank()) error(errors, "TEXT_REQUIRED", path, "文本不能为空");
        else if (value.length() > maximum) error(errors, "TEXT_TOO_LONG", path, "文本超过最大长度 " + maximum);
    }

    private void bounded(List<?> values, int minimum, int maximum, String path, List<Violation> errors) {
        int size = list(values).size();
        if (size < minimum || size > maximum) {
            error(errors, "LIST_SIZE_INVALID", path, "数量必须在 " + minimum + "—" + maximum + " 之间");
        }
    }

    private void range(int value, int minimum, int maximum, String path, List<Violation> errors) {
        if (value < minimum || value > maximum) {
            error(errors, "NUMBER_OUT_OF_RANGE", path, "数值必须在 " + minimum + "—" + maximum + " 之间");
        }
    }

    private void error(List<Violation> errors, String code, String path, String message) {
        errors.add(new Violation(code, path, message));
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String safe(String value) {
        return value == null ? "<null>" : value;
    }
}
