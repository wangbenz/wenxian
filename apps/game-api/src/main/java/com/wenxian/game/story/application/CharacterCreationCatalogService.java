package com.wenxian.game.story.application;

import com.wenxian.game.story.domain.PlayPreference;
import com.wenxian.game.story.domain.WorldPackage;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向创角流程提供世界包、世界内种族和游玩偏好选项。
 *
 * <p>该服务是 H5 创角目录的唯一应用层来源。它只返回可选择的语义和稳定 ID，不创建命卷、
 * 不调用模型，也不把玩家尚未确认的选择写成事实。</p>
 */
@Service
public class CharacterCreationCatalogService {
    private final com.wenxian.game.story.worldpackage.application.WorldPackageQueryService registry;
    private final String legacyNamespace;
    private final com.wenxian.game.story.worldpackage.application.LegacyWorldPackageAdapter adapter =
            new com.wenxian.game.story.worldpackage.application.LegacyWorldPackageAdapter();

    @org.springframework.beans.factory.annotation.Autowired
    public CharacterCreationCatalogService(
            com.wenxian.game.story.worldpackage.application.WorldPackageQueryService registry,
            @org.springframework.beans.factory.annotation.Value("${game.world-packages.legacy-namespace:wenxian.official}") String legacyNamespace) {
        this.registry = registry;
        this.legacyNamespace = legacyNamespace;
    }

    public CreationCatalog current() {
        return new CreationCatalog(worldOption(adapter.adapt(registry.defaultForCreation(null).compiled())),
                new PlayPreferenceOptions(
                        List.of(
                                option(PlayPreference.Focus.EXPLORATION, "探索", "更多未知地域、线索与世界机会"),
                                option(PlayPreference.Focus.RELATIONSHIP, "关系", "更多人物互动、立场变化与长期关系"),
                                option(PlayPreference.Focus.CULTIVATION, "修炼", "更多功法、突破准备与修行选择"),
                                option(PlayPreference.Focus.MANAGEMENT, "经营", "更多资源、势力与长期建设"),
                                option(PlayPreference.Focus.COMBAT, "战斗", "更多冲突与战斗场景，但不保证胜利")),
                        List.of(
                                option(PlayPreference.CombatFrequency.LOW, "低", "战斗只在局势需要时出现"),
                                option(PlayPreference.CombatFrequency.BALANCED, "适中", "战斗与其他场景保持平衡"),
                                option(PlayPreference.CombatFrequency.HIGH, "高", "更频繁提供战斗机会")),
                        List.of(
                                option(PlayPreference.Pace.RELAXED, "舒缓", "允许更多观察、生活与铺垫"),
                                option(PlayPreference.Pace.BALANCED, "适中", "铺垫与事件推进保持平衡"),
                                option(PlayPreference.Pace.FAST, "紧凑", "更快出现决定点和局势变化")),
                        List.of(
                                option(PlayPreference.Guidance.LIGHT, "少提示", "推荐行动更克制，保留更多自行判断"),
                                option(PlayPreference.Guidance.BALANCED, "适中", "提供必要方向但不替玩家决定"),
                                option(PlayPreference.Guidance.EXPLICIT, "明确", "更清楚说明当前可探索的方向")),
                        List.of(PlayPreference.Focus.EXPLORATION.name(), PlayPreference.Focus.CULTIVATION.name()),
                        PlayPreference.CombatFrequency.BALANCED.name(),
                        PlayPreference.Pace.BALANCED.name(),
                        PlayPreference.Guidance.BALANCED.name()));
    }

    public WorldPackage resolveCurrentWorld(String packageId) {
        var selected = registry.defaultForCreation(null);
        if (!selected.worldPackage().packageKey().equals(packageId)) throw new IllegalArgumentException("所选世界不是显式默认项");
        return adapter.adapt(selected.compiled());
    }

    public WorldPackage resolveWorld(String packageId, long revision) {
        return adapter.adapt(registry.portable(legacyNamespace, packageId, revision).compiled());
    }

    public WorldPackage resolveForCreation(String userId, String packageId, long revision) {
        var exact = registry.portable(legacyNamespace, packageId, revision);
        return adapter.adapt(registry.forCreation(userId, exact.revision().id()).compiled());
    }

    /** 在命卷提交事务内重验并锁定可用性，防止停用与创建并发越过授权边界。 */
    public void lockForCreation(String userId, String packageId, long revision) {
        registry.lockForCreation(userId, registry.portable(legacyNamespace, packageId, revision).revision().id());
    }

    public void validateOriginFacts(String packageId, long revision, String raceId, java.util.Set<String> factTypes) {
        var compiled = registry.portable(legacyNamespace, packageId, revision).compiled();
        for (var fact : compiled.creationProfile().requiredFactsByIdentity().getOrDefault(raceId, List.of())) {
            if (!factTypes.contains(fact.factType())) throw new IllegalArgumentException("请在根源事实中确认具体" + fact.playerLabel());
        }
    }

    private WorldOption worldOption(WorldPackage world) {
        return new WorldOption(world.packageId(), world.revision(), world.name(), world.premise(),
                world.coreRules(), world.narrativeGuidance(),
                world.races().stream().map(race -> new RaceOption(race.raceId(), race.name(), race.description(),
                        race.identityFacts(), race.socialHooks())).toList(),
                world.regions().stream().filter(WorldPackage.RegionDefinition::openingEligible)
                        .map(region -> new RegionOption(region.regionId(), region.name(), region.description()))
                        .toList(), realmScale(world));
    }

    private RealmScaleOption realmScale(WorldPackage world) {
        var scale = world.realmScale();
        return new RealmScaleOption(scale.scaleId(), scale.revision(),
                scale.phases().stream().map(Enum::name).toList(),
                scale.ranks().stream().map(rank -> new RealmRankOption(rank.rankKey(), rank.order(),
                        world.races().stream().map(race -> {
                            var stage = rank.raceStages().get(race.raceId());
                            return new RaceRealmOption(race.raceId(), stage.stageId(), stage.name(),
                                    stage.description(), stage.capabilityBoundary(), stage.aliases());
                        }).toList())).toList());
    }

    private static CatalogOption option(Enum<?> value, String name, String description) {
        return new CatalogOption(value.name(), name, description);
    }

    /** 一次创角所需的完整权威目录快照。 */
    public record CreationCatalog(WorldOption world, PlayPreferenceOptions playPreferences) {}

    /** 世界包当前修订及其可选身份与开场地域。 */
    public record WorldOption(
            String packageId,
            long revision,
            String name,
            String premise,
            List<String> coreRules,
            String narrativeGuidance,
            List<RaceOption> races,
            List<RegionOption> openingRegions,
            RealmScaleOption realmScale
    ) {
        public WorldOption {
            coreRules = List.copyOf(coreRules);
            races = List.copyOf(races);
            openingRegions = List.copyOf(openingRegions);
        }
    }

    /** 当前世界内可提交的种族稳定身份和玩家可读影响说明。 */
    public record RaceOption(String raceId, String name, String description,
                             List<String> identityFacts, List<String> socialHooks) {
        public RaceOption {
            identityFacts = List.copyOf(identityFacts);
            socialHooks = List.copyOf(socialHooks);
        }
    }

    /** 当前世界内可作为开场位置的地域。 */
    public record RegionOption(String regionId, String name, String description) {}

    /** 世界包固定的公共境界标尺。 */
    public record RealmScaleOption(String scaleId, long revision, List<String> phases,
                                   List<RealmRankOption> ranks) {
        public RealmScaleOption {
            phases = List.copyOf(phases);
            ranks = List.copyOf(ranks);
        }
    }

    /** 同一等级位置及三个种族在该位置上的显示定义。 */
    public record RealmRankOption(String rankKey, int order, List<RaceRealmOption> raceRealms) {
        public RealmRankOption {
            raceRealms = List.copyOf(raceRealms);
        }
    }

    /** 单个种族在某一共享等级上的名称与能力边界。 */
    public record RaceRealmOption(String raceId, String stageId, String name, String description,
                                  String capabilityBoundary, List<String> aliases) {
        public RaceRealmOption {
            aliases = List.copyOf(aliases);
        }
    }

    /** 游玩偏好的四组服务端权威选项。 */
    public record PlayPreferenceOptions(
            List<CatalogOption> focus,
            List<CatalogOption> combatFrequency,
            List<CatalogOption> pace,
            List<CatalogOption> guidance,
            List<String> defaultFocus,
            String defaultCombatFrequency,
            String defaultPace,
            String defaultGuidance
    ) {
        public PlayPreferenceOptions {
            focus = List.copyOf(focus);
            combatFrequency = List.copyOf(combatFrequency);
            pace = List.copyOf(pace);
            guidance = List.copyOf(guidance);
            defaultFocus = List.copyOf(defaultFocus);
        }
    }

    /** 一个稳定编码、显示名称和影响说明组成的创角选项。 */
    public record CatalogOption(String code, String name, String description) {}
}
