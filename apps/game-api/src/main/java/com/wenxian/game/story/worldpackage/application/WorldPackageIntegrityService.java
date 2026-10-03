package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.ResolvedRevision;
import com.wenxian.game.story.worldpackage.persistence.WorldPackageRevisionRepository;

import java.io.IOException;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 受控维护入口的完整性巡检：读取规范原文、运行快照及素材原件，复核既有身份与哈希。
 *
 * <p>本用例不依赖创角默认项，也不执行修复、重新编译、发布或启用。损坏直接报告；
 * 调用方必须是已授权的维护入口，不可把它接到普通回合冷读或无授权的玩家接口。</p>
 */
public final class WorldPackageIntegrityService {
    private final WorldPackageRevisionRepository repository;
    private final ObjectMapper mapper;
    private final CompiledWorldPackageSnapshotReader snapshots;

    public WorldPackageIntegrityService(WorldPackageRevisionRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.snapshots = new CompiledWorldPackageSnapshotReader(mapper);
    }

    public ResolvedRevision verify(String revisionId) {
        var revision = repository.revision(revisionId);
        var worldPackage = repository.packageById(revision.packageId(), false);
        var content = repository.content(revisionId);
        var compiled = snapshots.read(worldPackage, revision, content.compiledJson());
        try {
            if (content.canonicalJson() == null || content.canonicalJson().length() > 40_000_000)
                throw new IllegalStateException("世界包规范内容大小无效");
            var document = mapper.readValue(content.canonicalJson(), WorldPackageDocument.class);
            new WorldPackageValidator().validate(document);
            var normalized = new WorldPackageCanonicalizer(mapper).canonicalize(document, compiled);
            if (!document.identity().equals(compiled.identity())
                    || !normalized.contentHash().equals(revision.contentHash())
                    || !normalized.canonicalJson().equals(content.canonicalJson())
                    || !document.assets().equals(compiled.assets()))
                throw new IllegalStateException("世界包规范内容或绑定校验失败");
            verifySourceProjection(document, compiled);

            var descriptors = new HashMap<String, WorldPackageDocument.AssetDescriptor>();
            document.assets().forEach(asset -> descriptors.put(asset.path(), asset));
            var assets = repository.assets(revisionId);
            if (assets.size() != descriptors.size()) throw new IllegalStateException("世界包素材原件缺失");
            for (var asset : assets) {
                var descriptor = descriptors.remove(asset.descriptor().path());
                byte[] bytes = asset.content();
                if (descriptor == null || !descriptor.mediaType().equals(asset.descriptor().mediaType())
                        || descriptor.byteSize() != asset.descriptor().byteSize()
                        || descriptor.byteSize() != bytes.length
                        || !descriptor.sha256().equals(asset.descriptor().sha256())
                        || !descriptor.sha256().equals(WorldPackageCanonicalizer.sha256(bytes)))
                    throw new IllegalStateException("世界包素材原件损坏");
            }
            return new ResolvedRevision(worldPackage, revision, compiled);
        } catch (IOException failure) {
            throw new IllegalStateException("世界包规范内容损坏", failure);
        }
    }

    /** v1 源字段与既有快照的对应检查；后继与激活索引由快照 Reader 校验，不生成替代编译产物。 */
    private static void verifySourceProjection(WorldPackageDocument d, CompiledWorldPackage c) {
        same(d.metadata(), c.metadata());
        same(d.world().premise(), c.premise());
        same(d.world().coreRules().stream().sorted(Comparator.comparingInt(WorldPackageDocument.WorldRule::priority)
                .reversed().thenComparing(WorldPackageDocument.WorldRule::ruleId)).toList(), c.alwaysOnRules());
        same(d.world().contentBoundaries(), c.contentBoundaries());
        same(index(d.world().races(), WorldPackageDocument.RaceDefinition::raceId), c.racesById());
        same(index(d.world().regions(), WorldPackageDocument.RegionDefinition::regionId), c.regionsById());
        same(d.world().narrativeProfile(), c.narrativeProfile());
        same(index(d.loreEntries(), WorldPackageDocument.LoreEntry::loreId), c.loreById());
        var creation = c.creationProfile();
        same(d.creation().playableRaceRefs(), creation.playableRaceRefs());
        same(d.creation().openingRegionRefs(), creation.openingRegionRefs());
        same(d.creation().defaultOpeningRegionRef(), creation.defaultOpeningRegionRef());
        same(d.creation().requiredOriginFacts().stream().collect(Collectors.groupingBy(
                WorldPackageDocument.RequiredOriginFact::identityRef)), creation.requiredFactsByIdentity());
        same(d.creation().routeThemes(), creation.routeThemes());
        same(index(d.creation().scenarioSeeds(), WorldPackageDocument.ScenarioSeed::seedId), creation.scenarioSeedsById());
        same(index(d.progression().systems(), WorldPackageDocument.ProgressionSystem::systemId).keySet(), c.progressionSystemsById().keySet());
        for (var source : d.progression().systems()) {
            var system = c.progressionSystemsById().get(source.systemId());
            same(source.revision(), system.revision());
            same(source.kind(), system.kind());
            same(source.displayName(), system.displayName());
            same(source.completion().position(), system.completionPosition());
            same(source.completion().unlocks(), system.completionUnlock());
            same(index(source.steps(), WorldPackageDocument.ProgressionStep::stepId).keySet(), system.stepsById().keySet());
            for (var step : source.steps()) {
                var target = system.stepsById().get(step.stepId());
                same(step.order(), target.order());
                same(step.name(), target.name());
            }
            same(index(source.tracks(), WorldPackageDocument.ProgressionTrack::trackId).keySet(), system.tracksById().keySet());
            for (var track : source.tracks()) {
                var target = system.tracksById().get(track.trackId());
                same(track.identityRefs(), target.identityRefs());
                same(track.start().stageRef(), target.startingStageRef());
                same(track.start().stepRef(), target.startingStepRef());
                same(index(track.stages(), WorldPackageDocument.ProgressionStage::stageId).keySet(), target.stagesById().keySet());
                for (var stage : track.stages()) {
                    var compiled = target.stagesById().get(stage.stageId());
                    same(stage.order(), compiled.order());
                    same(stage.comparisonTier(), compiled.comparisonTier());
                    same(stage.name(), compiled.name());
                    same(stage.description(), compiled.description());
                    same(stage.capabilityBoundary(), compiled.capabilityBoundary());
                    same(stage.aliases() == null ? List.of() : stage.aliases(), compiled.aliases());
                }
            }
        }
    }

    private static <T> Map<String, T> index(List<T> values, Function<T, String> key) {
        return values.stream().collect(Collectors.toMap(key, Function.identity()));
    }

    private static void same(Object source, Object snapshot) {
        if (!Objects.equals(source, snapshot)) throw new IllegalStateException("世界包规范内容与运行快照不一致");
    }
}
