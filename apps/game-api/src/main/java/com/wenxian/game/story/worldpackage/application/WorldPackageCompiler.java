package com.wenxian.game.story.worldpackage.application;

import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.LoreEntry;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ProgressionStage;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ProgressionStep;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ProgressionSystem;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ProgressionTrack;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage.CompiledProgressionStage;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage.CompiledProgressionStep;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage.CompiledProgressionSystem;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage.CompiledProgressionTrack;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage.CreationProfile;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage.LoreActivationIndex;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把已经通过 v1 校验的规范文档确定性编译为运行快照。
 *
 * <p>编译只解析引用、顺序、直接后继和 Lore 索引，不调用模型、数据库或外部网络。同一规范内容和
 * 编译器版本必须产生完全相同的 JSON 与哈希；任何内容裁决都应在校验阶段完成。</p>
 */
public final class WorldPackageCompiler {

    public CompiledWorldPackage compile(WorldPackageDocument document) {
        Map<String, WorldPackageDocument.RaceDefinition> races = sortedMap(
                document.world().races(), WorldPackageDocument.RaceDefinition::raceId);
        Map<String, WorldPackageDocument.RegionDefinition> regions = sortedMap(
                document.world().regions(), WorldPackageDocument.RegionDefinition::regionId);
        Map<String, LoreEntry> lore = sortedMap(document.loreEntries(), LoreEntry::loreId);
        Map<String, CompiledProgressionSystem> progression = new TreeMap<>();
        for (ProgressionSystem system : document.progression().systems()) {
            progression.put(system.systemId(), compile(system));
        }

        Map<String, List<WorldPackageDocument.RequiredOriginFact>> requiredFacts = new TreeMap<>();
        for (var fact : document.creation().requiredOriginFacts()) {
            requiredFacts.computeIfAbsent(fact.identityRef(), ignored -> new ArrayList<>()).add(fact);
        }
        Map<String, WorldPackageDocument.ScenarioSeed> seeds = sortedMap(
                document.creation().scenarioSeeds(), WorldPackageDocument.ScenarioSeed::seedId);
        Map<String, String> trackByIdentity = new TreeMap<>();
        document.world().races().forEach(race -> trackByIdentity.put(race.raceId(),
                race.primaryProgressionTrackRef()));
        CreationProfile creation = new CreationProfile(
                document.creation().playableRaceRefs(),
                document.creation().openingRegionRefs(),
                document.creation().defaultOpeningRegionRef(),
                requiredFacts,
                document.creation().routeThemes(),
                seeds,
                trackByIdentity
        );

        List<WorldPackageDocument.WorldRule> rules = document.world().coreRules().stream()
                .sorted(Comparator.comparingInt(WorldPackageDocument.WorldRule::priority).reversed()
                        .thenComparing(WorldPackageDocument.WorldRule::ruleId))
                .toList();
        return new CompiledWorldPackage(
                document.identity(),
                document.metadata(),
                document.world().premise(),
                rules,
                document.world().contentBoundaries(),
                races,
                regions,
                creation,
                progression,
                lore,
                activationIndex(document.loreEntries()),
                document.world().narrativeProfile(),
                document.assets()
        );
    }

    private CompiledProgressionSystem compile(ProgressionSystem system) {
        List<ProgressionStep> steps = system.steps().stream()
                .sorted(Comparator.comparingInt(ProgressionStep::order)).toList();
        List<CompiledProgressionStep> compiledSteps = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            ProgressionStep step = steps.get(index);
            compiledSteps.add(new CompiledProgressionStep(step.stepId(), step.order(), step.name(),
                    index + 1 == steps.size() ? null : steps.get(index + 1).stepId()));
        }
        Map<String, CompiledProgressionStep> stepsById = sortedMap(compiledSteps,
                CompiledProgressionStep::stepId);
        Map<String, CompiledProgressionTrack> tracksById = new TreeMap<>();
        for (ProgressionTrack track : system.tracks()) tracksById.put(track.trackId(), compile(track));
        return new CompiledProgressionSystem(
                system.systemId(),
                system.revision(),
                system.kind(),
                system.displayName(),
                system.completion().position(),
                system.completion().unlocks(),
                compiledSteps,
                stepsById,
                tracksById
        );
    }

    private CompiledProgressionTrack compile(ProgressionTrack track) {
        List<ProgressionStage> stages = track.stages().stream()
                .sorted(Comparator.comparingInt(ProgressionStage::order)).toList();
        List<CompiledProgressionStage> compiled = new ArrayList<>();
        for (int index = 0; index < stages.size(); index++) {
            ProgressionStage stage = stages.get(index);
            compiled.add(new CompiledProgressionStage(
                    stage.stageId(),
                    stage.order(),
                    stage.comparisonTier(),
                    stage.name(),
                    stage.description(),
                    stage.capabilityBoundary(),
                    list(stage.aliases()),
                    index + 1 == stages.size() ? null : stages.get(index + 1).stageId()
            ));
        }
        return new CompiledProgressionTrack(
                track.trackId(),
                track.identityRefs(),
                track.start().stageRef(),
                track.start().stepRef(),
                compiled,
                sortedMap(compiled, CompiledProgressionStage::stageId)
        );
    }

    private LoreActivationIndex activationIndex(List<LoreEntry> entries) {
        List<LoreEntry> ordered = entries.stream()
                .sorted(Comparator.comparingInt(LoreEntry::priority).reversed().thenComparing(LoreEntry::loreId))
                .toList();
        List<String> always = new ArrayList<>();
        Map<String, List<String>> identities = new TreeMap<>();
        Map<String, List<String>> locations = new TreeMap<>();
        Map<String, List<String>> entityTags = new TreeMap<>();
        Map<String, List<String>> storyThreads = new TreeMap<>();
        Map<String, List<String>> terms = new TreeMap<>();
        for (LoreEntry entry : ordered) {
            if (entry.activation().always()) always.add(entry.loreId());
            index(identities, entry.activation().identityRefs(), entry.loreId(), false);
            index(locations, entry.activation().locationRefs(), entry.loreId(), false);
            index(entityTags, entry.activation().entityTags(), entry.loreId(), false);
            index(storyThreads, entry.activation().storyThreadRefs(), entry.loreId(), false);
            index(terms, entry.activation().terms(), entry.loreId(), true);
        }
        return new LoreActivationIndex(always, identities, locations, entityTags, storyThreads, terms);
    }

    private void index(Map<String, List<String>> target, List<String> keys, String loreId, boolean normalize) {
        for (String key : list(keys)) {
            String indexed = normalize ? normalizeTerm(key) : key;
            target.computeIfAbsent(indexed, ignored -> new ArrayList<>()).add(loreId);
        }
    }

    private String normalizeTerm(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    private static <T> Map<String, T> sortedMap(List<T> values, java.util.function.Function<T, String> key) {
        Map<String, T> result = new TreeMap<>();
        for (T value : values) result.put(key.apply(value), value);
        return new LinkedHashMap<>(result);
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }
}
