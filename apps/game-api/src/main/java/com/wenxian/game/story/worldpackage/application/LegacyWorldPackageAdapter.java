package com.wenxian.game.story.worldpackage.application;

import com.wenxian.game.story.domain.WorldPackage;
import com.wenxian.game.story.domain.WorldPackage.*;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage;
import java.util.*;

/** 阶段 B 到 C 的 v2 过渡投影；只读取通用编译结果，不能容忍不同开局或阶段语义被截断。 */
public final class LegacyWorldPackageAdapter {
    public WorldPackage adapt(CompiledWorldPackage c) {
        if (c.progressionSystemsById().size() != 1) throw incompatible();
        var system = c.progressionSystemsById().values().iterator().next();
        if (system.orderedSteps().size() != 4 || !system.orderedSteps().stream().map(s -> s.name()).toList().equals(List.of("初期", "中期", "后期", "圆满"))) throw incompatible();
        var raceIds = c.creationProfile().playableRaceRefs();
        if (!new HashSet<>(raceIds).equals(c.racesById().keySet())) throw incompatible();
        List<RealmRank> ranks = new ArrayList<>();
        for (int order = 1; order <= 8; order++) {
            Map<String, RaceRealmStage> raceStages = new LinkedHashMap<>();
            for (String raceId : raceIds) {
                var track = system.tracksById().get(c.creationProfile().progressionTrackByIdentity().get(raceId));
                if (track == null || track.orderedStages().size() != 8 || !track.startingStageRef().equals(track.orderedStages().getFirst().stageId())
                        || !track.startingStepRef().equals(system.orderedSteps().getFirst().stepId())) throw incompatible();
                var stage = track.orderedStages().get(order - 1);
                if (stage.comparisonTier() != order) throw incompatible();
                raceStages.put(raceId, new RaceRealmStage(stage.stageId(), stage.name(), stage.description(), stage.capabilityBoundary(), stage.aliases()));
            }
            ranks.add(new RealmRank("L" + order, order, raceStages));
        }
        // v2 默认开场由首个可开场项表达；显式保留 Bundle 默认，不能依赖 Map 顺序。
        List<String> regionIds = new ArrayList<>();
        regionIds.add(c.creationProfile().defaultOpeningRegionRef());
        c.creationProfile().openingRegionRefs().stream().filter(id -> !regionIds.contains(id)).forEach(regionIds::add);
        c.regionsById().keySet().stream().sorted().filter(id -> !regionIds.contains(id)).forEach(regionIds::add);
        return new WorldPackage(c.identity().packageId(), c.identity().revision(), c.metadata().name(), c.premise(),
                c.alwaysOnRules().stream().map(r -> r.text()).toList(),
                raceIds.stream().map(id -> { var r = c.racesById().get(id); return new RaceDefinition(id, r.name(), r.description(), r.identityFacts(), r.socialHooks(), r.opportunityTags()); }).toList(),
                new RealmScale(system.systemId(), system.revision(), List.of(RealmPhase.values()), ranks),
                regionIds.stream().map(id -> { var r = c.regionsById().get(id); return new RegionDefinition(id, r.name(), r.description(), c.creationProfile().openingRegionRefs().contains(id), r.tags()); }).toList(),
                c.loreById().values().stream().sorted(Comparator.comparing(l -> l.loreId())).map(l -> new LoreEntry(l.loreId(), l.tags(), l.content(), l.priority(), l.relatedRefs())).toList(),
                c.narrativeProfile().guidance(), c.contentBoundaries().stream().map(b -> b.text()).toList());
    }
    private IllegalStateException incompatible() { return new IllegalStateException("该世界修订需要命卷 v3，不能通过旧结构创角或继续"); }
}
