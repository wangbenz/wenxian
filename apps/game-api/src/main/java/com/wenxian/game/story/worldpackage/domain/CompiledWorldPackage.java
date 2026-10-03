package com.wenxian.game.story.worldpackage.domain;

import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ContentBoundary;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ContentMetadata;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.LoreEntry;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.NarrativeProfile;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.PackageIdentity;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.RaceDefinition;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.RegionDefinition;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.RequiredOriginFact;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.RouteTheme;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ScenarioSeed;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.WorldRule;

import java.util.List;
import java.util.Map;

/**
 * 世界包通过校验后生成的确定性运行快照。
 *
 * <p>本对象已经解析全部稳定引用、顺序和 Lore 激活索引。创角与回合运行时只读取本对象，
 * 不依赖 Bundle 文件布局；它仍然只是世界内容与声明式玩法边界，不能直接修改命卷事实。</p>
 */
public record CompiledWorldPackage(
        PackageIdentity identity,
        ContentMetadata metadata,
        String premise,
        List<WorldRule> alwaysOnRules,
        List<ContentBoundary> contentBoundaries,
        Map<String, RaceDefinition> racesById,
        Map<String, RegionDefinition> regionsById,
        CreationProfile creationProfile,
        Map<String, CompiledProgressionSystem> progressionSystemsById,
        Map<String, LoreEntry> loreById,
        LoreActivationIndex loreActivationIndex,
        NarrativeProfile narrativeProfile,
        List<WorldPackageDocument.AssetDescriptor> assets
) {
    public CompiledWorldPackage {
        alwaysOnRules = List.copyOf(alwaysOnRules);
        contentBoundaries = List.copyOf(contentBoundaries);
        racesById = Map.copyOf(racesById);
        regionsById = Map.copyOf(regionsById);
        progressionSystemsById = Map.copyOf(progressionSystemsById);
        loreById = Map.copyOf(loreById);
        assets = List.copyOf(assets);
    }

    public record CreationProfile(
            List<String> playableRaceRefs,
            List<String> openingRegionRefs,
            String defaultOpeningRegionRef,
            Map<String, List<RequiredOriginFact>> requiredFactsByIdentity,
            List<RouteTheme> routeThemes,
            Map<String, ScenarioSeed> scenarioSeedsById,
            Map<String, String> progressionTrackByIdentity
    ) {
        public CreationProfile {
            playableRaceRefs = List.copyOf(playableRaceRefs);
            openingRegionRefs = List.copyOf(openingRegionRefs);
            requiredFactsByIdentity = immutableListMap(requiredFactsByIdentity);
            routeThemes = List.copyOf(routeThemes);
            scenarioSeedsById = Map.copyOf(scenarioSeedsById);
            progressionTrackByIdentity = Map.copyOf(progressionTrackByIdentity);
        }
    }

    public record CompiledProgressionSystem(
            String systemId,
            long revision,
            String kind,
            String displayName,
            String completionPosition,
            String completionUnlock,
            List<CompiledProgressionStep> orderedSteps,
            Map<String, CompiledProgressionStep> stepsById,
            Map<String, CompiledProgressionTrack> tracksById
    ) {
        public CompiledProgressionSystem {
            orderedSteps = List.copyOf(orderedSteps);
            stepsById = Map.copyOf(stepsById);
            tracksById = Map.copyOf(tracksById);
        }
    }

    public record CompiledProgressionStep(
            String stepId,
            int order,
            String name,
            String nextStepId
    ) {}

    public record CompiledProgressionTrack(
            String trackId,
            List<String> identityRefs,
            String startingStageRef,
            String startingStepRef,
            List<CompiledProgressionStage> orderedStages,
            Map<String, CompiledProgressionStage> stagesById
    ) {
        public CompiledProgressionTrack {
            identityRefs = List.copyOf(identityRefs);
            orderedStages = List.copyOf(orderedStages);
            stagesById = Map.copyOf(stagesById);
        }
    }

    public record CompiledProgressionStage(
            String stageId,
            int order,
            int comparisonTier,
            String name,
            String description,
            String capabilityBoundary,
            List<String> aliases,
            String nextStageId
    ) {
        public CompiledProgressionStage {
            aliases = List.copyOf(aliases);
        }
    }

    public record LoreActivationIndex(
            List<String> always,
            Map<String, List<String>> byIdentity,
            Map<String, List<String>> byLocation,
            Map<String, List<String>> byEntityTag,
            Map<String, List<String>> byStoryThread,
            Map<String, List<String>> byTerm
    ) {
        public LoreActivationIndex {
            always = List.copyOf(always);
            byIdentity = immutableListMap(byIdentity);
            byLocation = immutableListMap(byLocation);
            byEntityTag = immutableListMap(byEntityTag);
            byStoryThread = immutableListMap(byStoryThread);
            byTerm = immutableListMap(byTerm);
        }
    }

    private static <K, V> Map<K, List<V>> immutableListMap(Map<K, List<V>> source) {
        java.util.LinkedHashMap<K, List<V>> copy = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }
}
