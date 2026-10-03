package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.LoreEntry;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.RaceDefinition;
import com.wenxian.game.story.worldpackage.domain.CompiledWorldPackage;
import com.wenxian.game.story.worldpackage.domain.WorldContext;
import com.wenxian.game.story.worldpackage.domain.WorldContext.Material;
import com.wenxian.game.story.worldpackage.domain.WorldContext.Source;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.ResolvedRevision;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 从已验证、已按用途授权的修订中确定性选取本轮需要的世界资料。
 *
 * <p>不读取数据库、不调用模型、不认定玩家已知，也不修改状态。缺失动态地域时不猜测开场地域；
 * 必要资料超过预算时明确失败，补充 Lore 只整条省略。一跳关联只从原始查询和直接命中扩展一次。</p>
 */
public final class WorldContextSelector {
    public static final String SELECTOR_VERSION = "world-context-selector-v1";
    public static final int DEFAULT_MAXIMUM_CHARACTERS = 24_000;
    public static final int DEFAULT_MAXIMUM_LORE_ENTRIES = 8;

    private final ObjectMapper mapper;
    private final int maximumCharacters;
    private final int maximumLoreEntries;

    public WorldContextSelector(ObjectMapper mapper) {
        this(mapper, DEFAULT_MAXIMUM_CHARACTERS, DEFAULT_MAXIMUM_LORE_ENTRIES);
    }

    public WorldContextSelector(ObjectMapper mapper, int maximumCharacters, int maximumLoreEntries) {
        this.mapper = Objects.requireNonNull(mapper, "世界资料选择缺少 JSON 编码器").copy();
        if (maximumCharacters < 1 || maximumLoreEntries < 0) {
            throw new IllegalArgumentException("世界资料字符预算必须为正，Lore 数量上限不能为负");
        }
        this.maximumCharacters = maximumCharacters;
        this.maximumLoreEntries = maximumLoreEntries;
    }

    public WorldContext select(ResolvedRevision resolved, Query query) {
        Objects.requireNonNull(query, "世界资料查询不能为空");
        Source source = source(resolved);
        CompiledWorldPackage world = resolved.compiled();
        TreeSet<String> unavailable = new TreeSet<>(query.unavailableInputs());
        List<Material> materials = new ArrayList<>();
        Set<String> referenceAnchors = new TreeSet<>();

        ObjectNode premise = mapper.createObjectNode();
        premise.put("name", world.metadata().name());
        premise.put("premise", world.premise());
        materials.add(material("$world.premise", "PREMISE", premise, "CORE"));
        world.alwaysOnRules().stream().sorted(Comparator
                        .comparingInt(com.wenxian.game.story.worldpackage.document.WorldPackageDocument.WorldRule::priority)
                        .reversed().thenComparing(com.wenxian.game.story.worldpackage.document.WorldPackageDocument.WorldRule::ruleId))
                .forEach(rule -> materials.add(material(rule.ruleId(), "CORE_RULE", rule, "CORE")));
        world.contentBoundaries().stream().sorted(Comparator.comparing(
                        com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ContentBoundary::boundaryId))
                .forEach(boundary -> materials.add(material(boundary.boundaryId(), "CONTENT_BOUNDARY", boundary, "CORE")));
        materials.add(material("$world.narrativeProfile", "NARRATIVE_PROFILE", world.narrativeProfile(), "CORE"));

        RaceDefinition race = world.racesById().get(query.raceId());
        if (race == null) {
            unavailable.add("RACE_UNAVAILABLE");
        } else {
            materials.add(material(race.raceId(), "RACE", race, "IDENTITY_REF:" + race.raceId()));
            referenceAnchors.add(race.raceId());
        }
        var region = world.regionsById().get(query.currentRegionRef());
        if (region == null) {
            unavailable.add("CURRENT_REGION_UNAVAILABLE");
        } else {
            materials.add(material(region.regionId(), "REGION", region, "LOCATION_REF:" + region.regionId()));
            referenceAnchors.add(region.regionId());
        }
        addCurrentStage(world, race, query.currentStageId(), materials, referenceAnchors, unavailable);

        Map<String, Candidate> candidates = new LinkedHashMap<>();
        var index = world.loreActivationIndex();
        match(world, candidates, index.always(), 0, "ALWAYS");
        if (race != null) match(world, candidates, index.byIdentity().get(race.raceId()), 1,
                "IDENTITY_REF:" + race.raceId());
        if (region != null) match(world, candidates, index.byLocation().get(region.regionId()), 1,
                "LOCATION_REF:" + region.regionId());
        query.entityTags().stream().sorted().forEach(tag ->
                match(world, candidates, index.byEntityTag().get(tag), 1, "ENTITY_TAG:" + tag));
        query.storyThreadRefs().stream().sorted().forEach(ref -> {
            if (world.creationProfile().scenarioSeedsById().containsKey(ref)) {
                referenceAnchors.add(ref);
                match(world, candidates, index.byStoryThread().get(ref), 1, "STORY_THREAD_REF:" + ref);
            } else {
                unavailable.add("STORY_THREAD_REF_UNAVAILABLE");
            }
        });
        String action = normalizeTerm(query.playerAction());
        index.byTerm().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!entry.getKey().isBlank() && action.contains(entry.getKey())) {
                match(world, candidates, entry.getValue(), 2, "TERM_MATCH:" + entry.getKey());
            }
        });
        expandOneHop(world, candidates, referenceAnchors);

        int used = 0;
        for (Material material : materials) used = Math.addExact(used, characters(material));
        if (used > maximumCharacters) {
            throw new IllegalStateException("世界核心资料超过字符预算，不能静默截断必要设定");
        }
        int candidateCount = Math.addExact(materials.size(), candidates.size());
        int acceptedLore = 0;
        int omitted = 0;
        for (Candidate candidate : candidates.values().stream().sorted(Comparator
                .comparingInt((Candidate value) -> value.rank)
                .thenComparing(Comparator.comparingInt((Candidate value) -> value.entry.priority()).reversed())
                .thenComparing(value -> value.entry.loreId())).toList()) {
            Material material = new Material(candidate.entry.loreId(), "LORE", mapper.valueToTree(candidate.entry),
                    List.copyOf(candidate.reasons));
            int size = characters(material);
            if (acceptedLore >= maximumLoreEntries || size > maximumCharacters - used) {
                omitted++;
                continue;
            }
            materials.add(material);
            used += size;
            acceptedLore++;
        }
        return new WorldContext(source, SELECTOR_VERSION, materials, candidateCount, omitted, used,
                maximumCharacters, List.copyOf(unavailable));
    }

    private void addCurrentStage(CompiledWorldPackage world, RaceDefinition race, String stageId,
                                 List<Material> materials, Set<String> anchors, Set<String> unavailable) {
        if (race != null && !stageId.isBlank()) {
            for (var system : world.progressionSystemsById().values().stream()
                    .sorted(Comparator.comparing(CompiledWorldPackage.CompiledProgressionSystem::systemId)).toList()) {
                var track = system.tracksById().get(race.primaryProgressionTrackRef());
                if (track == null || !track.identityRefs().contains(race.raceId())) continue;
                var stage = track.stagesById().get(stageId);
                if (stage == null) continue;
                ObjectNode data = mapper.createObjectNode();
                data.put("systemId", system.systemId());
                data.put("systemRevision", system.revision());
                data.put("systemName", system.displayName());
                data.put("trackId", track.trackId());
                data.set("stage", mapper.valueToTree(stage));
                materials.add(material(stage.stageId(), "PROGRESSION_STAGE", data, "CURRENT_STAGE_REF:" + stage.stageId()));
                anchors.add(stage.stageId());
                return;
            }
        }
        unavailable.add("CURRENT_STAGE_UNAVAILABLE");
    }

    private void expandOneHop(CompiledWorldPackage world, Map<String, Candidate> candidates, Set<String> references) {
        Set<String> anchors = new TreeSet<>(references);
        anchors.addAll(candidates.keySet());
        // 固定扩展前的候选集合；新关联条目不能再成为下一轮种子。
        List<Candidate> direct = List.copyOf(candidates.values());
        for (Candidate candidate : direct) {
            candidate.entry.relatedRefs().stream().sorted().filter(world.loreById()::containsKey)
                    .forEach(ref -> match(world, candidates, List.of(ref), 3,
                            "RELATED_REF:" + candidate.entry.loreId()));
        }
        world.loreById().values().stream().sorted(Comparator.comparing(LoreEntry::loreId)).forEach(entry ->
                entry.relatedRefs().stream().sorted().filter(anchors::contains).forEach(ref ->
                        match(world, candidates, List.of(entry.loreId()), 3, "RELATED_REF:" + ref)));
    }

    private void match(CompiledWorldPackage world, Map<String, Candidate> candidates, List<String> refs,
                       int rank, String reason) {
        if (refs == null) return;
        for (String ref : refs) {
            LoreEntry entry = world.loreById().get(ref);
            if (entry == null) throw new IllegalStateException("世界 Lore 激活索引包含无效引用");
            Candidate candidate = candidates.computeIfAbsent(ref, ignored -> new Candidate(entry, rank));
            candidate.rank = Math.min(candidate.rank, rank);
            candidate.reasons.add(reason);
        }
    }

    private Material material(String ref, String kind, Object data, String reason) {
        return new Material(ref, kind, mapper.valueToTree(data), List.of(reason));
    }

    private int characters(Material material) {
        return mapper.valueToTree(material).toString().length();
    }

    private Source source(ResolvedRevision resolved) {
        Objects.requireNonNull(resolved, "世界资料选择缺少已验证修订");
        var p = resolved.worldPackage();
        var r = resolved.revision();
        var identity = resolved.compiled().identity();
        if (!p.id().equals(r.packageId()) || !p.namespace().equals(identity.publisherNamespace())
                || !p.packageKey().equals(identity.packageId()) || r.revision() != identity.revision()
                || !r.runtimeContract().equals(identity.runtimeContract())) {
            throw new IllegalStateException("世界资料来源与编译快照身份不一致");
        }
        return new Source(r.id(), p.namespace(), p.packageKey(), r.revision(), r.contentHash(), r.compiledHash(),
                r.runtimeContract());
    }

    private static String normalizeTerm(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    /** 输入引用由命卷调用层验证；空地域表示未知，不能用开场或名称猜测替代。 */
    public record Query(String raceId, String currentRegionRef, String currentStageId, Set<String> entityTags,
                        Set<String> storyThreadRefs, String playerAction, List<String> unavailableInputs) {
        public Query {
            raceId = text(raceId);
            currentRegionRef = text(currentRegionRef);
            currentStageId = text(currentStageId);
            entityTags = entityTags == null ? Set.of() : Set.copyOf(entityTags);
            storyThreadRefs = storyThreadRefs == null ? Set.of() : Set.copyOf(storyThreadRefs);
            playerAction = text(playerAction);
            unavailableInputs = unavailableInputs == null ? List.of() : List.copyOf(unavailableInputs);
        }

        private static String text(String value) {
            return value == null ? "" : value.strip();
        }
    }

    private static final class Candidate {
        private final LoreEntry entry;
        private final Set<String> reasons = new TreeSet<>();
        private int rank;

        private Candidate(LoreEntry entry, int rank) {
            this.entry = entry;
            this.rank = rank;
        }
    }
}
