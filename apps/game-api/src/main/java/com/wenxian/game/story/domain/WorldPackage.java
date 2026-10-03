package com.wenxian.game.story.domain;

import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * 一份具有稳定身份和不可变修订号的世界内容包。
 *
 * <p>世界包定义可长期成立的内容边界和创角选项，不规定玩家必须经历的剧情。命卷只引用
 * {@code packageId + revision}，显示名称和说明不能作为业务身份。</p>
 */
public record WorldPackage(
        String packageId,
        long revision,
        String name,
        String premise,
        List<String> coreRules,
        List<RaceDefinition> races,
        RealmScale realmScale,
        List<RegionDefinition> regions,
        List<LoreEntry> loreEntries,
        String narrativeGuidance,
        List<String> contentBoundaries
) {
    public WorldPackage {
        packageId = required(packageId, "世界包 ID");
        if (revision < 1) throw new IllegalArgumentException("世界包修订号必须大于零");
        name = required(name, "世界包名称");
        premise = required(premise, "世界包简介");
        coreRules = nonEmpty(coreRules, "世界包核心规则");
        races = nonEmpty(races, "世界包种族");
        if (realmScale == null) throw new IllegalArgumentException("世界包境界标尺不能为空");
        regions = nonEmpty(regions, "世界包地域");
        loreEntries = copy(loreEntries);
        narrativeGuidance = required(narrativeGuidance, "世界包叙事引导");
        contentBoundaries = copy(contentBoundaries);
        unique(races.stream().map(RaceDefinition::raceId).toList(), "世界包种族 ID");
        realmScale.validateRaces(races.stream().map(RaceDefinition::raceId).toList());
        unique(regions.stream().map(RegionDefinition::regionId).toList(), "世界包地域 ID");
        unique(loreEntries.stream().map(LoreEntry::loreId).toList(), "世界包知识 ID");
    }

    /**
     * 世界包内所有种族共享的可比较境界标尺。
     *
     * <p>{@code rankKey + phase} 是服务端比较与持久化的权威值；中文境界名只负责展示。
     * 当前玄墟界固定八个大境界和四个小阶段，不能由玩家或模型在创角时改写。</p>
     */
    public record RealmScale(
            String scaleId,
            long revision,
            List<RealmPhase> phases,
            List<RealmRank> ranks
    ) {
        public RealmScale {
            scaleId = required(scaleId, "境界标尺 ID");
            if (revision < 1) throw new IllegalArgumentException("境界标尺修订号必须大于零");
            phases = nonEmpty(phases, "境界小阶段");
            ranks = nonEmpty(ranks, "境界大境界");
            if (!phases.equals(List.of(RealmPhase.EARLY, RealmPhase.MIDDLE,
                    RealmPhase.LATE, RealmPhase.PERFECT))) {
                throw new IllegalArgumentException("境界小阶段必须依次为初期、中期、后期、圆满");
            }
            if (ranks.size() != 8) throw new IllegalArgumentException("当前世界境界标尺必须包含 L1-L8");
            unique(ranks.stream().map(RealmRank::rankKey).toList(), "境界等级键");
            Set<Integer> orders = new HashSet<>();
            for (RealmRank rank : ranks) {
                if (!orders.add(rank.order())) throw new IllegalArgumentException("境界顺序重复：" + rank.order());
                String expected = "L" + rank.order();
                if (!expected.equals(rank.rankKey())) {
                    throw new IllegalArgumentException("境界等级键必须与连续顺序一致：" + expected);
                }
            }
            for (int order = 1; order <= 8; order++) {
                if (!orders.contains(order)) throw new IllegalArgumentException("境界顺序必须从一到八连续排列");
            }
        }

        /** 返回指定种族和大境界的权威展示定义。 */
        public RaceRealmStage stage(String raceId, String rankKey) {
            return ranks.stream().filter(rank -> rank.rankKey().equals(rankKey)).findFirst()
                    .map(rank -> rank.raceStages().get(raceId))
                    .orElseThrow(() -> new IllegalArgumentException("境界标尺没有对应种族或等级"));
        }

        /** 返回固定的 L1 开局境界。 */
        public RaceRealmStage startingStage(String raceId) {
            return stage(raceId, "L1");
        }

        private void validateRaces(List<String> raceIds) {
            Set<String> expected = Set.copyOf(raceIds);
            for (RealmRank rank : ranks) {
                if (!rank.raceStages().keySet().equals(expected)) {
                    throw new IllegalArgumentException("每个境界必须完整定义当前世界全部种族：" + rank.rankKey());
                }
            }
        }
    }

    /** 服务端使用的小阶段枚举，顺序同时代表同一大境界内的高低。 */
    public enum RealmPhase {
        EARLY,
        MIDDLE,
        LATE,
        PERFECT
    }

    /** 一个跨种族可比较的大境界位置。 */
    public record RealmRank(String rankKey, int order, Map<String, RaceRealmStage> raceStages) {
        public RealmRank {
            rankKey = required(rankKey, "境界等级键");
            if (order < 1) throw new IllegalArgumentException("境界顺序必须大于零");
            raceStages = raceStages == null ? Map.of() : Map.copyOf(raceStages);
            if (raceStages.isEmpty()) throw new IllegalArgumentException("境界缺少种族展示定义");
        }
    }

    /** 某个种族在共享大境界位置上的名称、边界与可识别别称。 */
    public record RaceRealmStage(
            String stageId,
            String name,
            String description,
            String capabilityBoundary,
            List<String> aliases
    ) {
        public RaceRealmStage {
            stageId = required(stageId, "种族境界 ID");
            name = required(name, "种族境界名称");
            description = required(description, "种族境界说明");
            capabilityBoundary = required(capabilityBoundary, "种族境界能力边界");
            aliases = copy(aliases);
        }
    }

    /** 返回用于未指定开场位置时的首个可开场地域。 */
    public RegionDefinition defaultOpeningRegion() {
        return regions.stream().filter(RegionDefinition::openingEligible).findFirst()
                .orElseThrow(() -> new IllegalStateException("世界包没有可用于开场的地域：" + packageId));
    }

    /** 当前世界内可选择的种族定义。 */
    public record RaceDefinition(
            String raceId,
            String name,
            String description,
            List<String> identityFacts,
            List<String> socialHooks,
            List<String> opportunityTags
    ) {
        public RaceDefinition {
            raceId = required(raceId, "种族 ID");
            name = required(name, "种族名称");
            description = required(description, "种族说明");
            identityFacts = nonEmpty(identityFacts, "种族身份事实");
            socialHooks = copy(socialHooks);
            opportunityTags = copy(opportunityTags);
        }
    }

    /** 世界包内可稳定引用的地域。 */
    public record RegionDefinition(
            String regionId,
            String name,
            String description,
            boolean openingEligible,
            List<String> tags
    ) {
        public RegionDefinition {
            regionId = required(regionId, "地域 ID");
            name = required(name, "地域名称");
            description = required(description, "地域说明");
            tags = copy(tags);
        }
    }

    /** 可按稳定引用和标签选择性装配进模型上下文的世界知识。 */
    public record LoreEntry(
            String loreId,
            List<String> tags,
            String content,
            int priority,
            List<String> relatedRefs
    ) {
        public LoreEntry {
            loreId = required(loreId, "世界知识 ID");
            tags = copy(tags);
            content = required(content, "世界知识内容");
            if (priority < 0) throw new IllegalArgumentException("世界知识优先级不能小于零");
            relatedRefs = copy(relatedRefs);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
        return value.strip();
    }

    private static <T> List<T> nonEmpty(List<T> values, String field) {
        List<T> result = copy(values);
        if (result.isEmpty()) throw new IllegalArgumentException(field + "不能为空");
        return result;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static void unique(List<String> values, String field) {
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            if (!seen.add(value)) throw new IllegalArgumentException(field + "重复：" + value);
        }
    }
}
