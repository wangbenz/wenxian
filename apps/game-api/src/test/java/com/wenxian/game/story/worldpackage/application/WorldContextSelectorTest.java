package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.LoreActivation;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.LoreEntry;
import com.wenxian.game.story.worldpackage.domain.WorldContext;
import com.wenxian.game.story.worldpackage.domain.WorldContext.Material;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.PackageRow;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.ResolvedRevision;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.RevisionRow;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 纯选择器验收：真实包、缺失地域、有限关联、资源预算及冻结输入；不依赖数据库或模型。 */
class WorldContextSelectorTest {
    private static final Path XUANXU = Path.of("src/main/resources/world-packages/official/XUANXU/2");
    private static final Path DREAMSEA = Path.of("src/test/resources/world-packages/fixtures/DREAMSEA/1");
    private static final String HUMAN = "race:xuanxu:human";
    private static final String HUMAN_STAGE = "realm:xuanxu:human:l1";
    private static final String REGION = "region:xuanxu:qingqi";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final WorldContextSelector selector = new WorldContextSelector(mapper);

    @Test
    void freezesExactSourceAndIdentityWithoutUsingDefaultOpeningAsCurrentRegion() {
        ResolvedRevision resolved = fixture(XUANXU);
        WorldContext selected = selector.select(resolved, humanQuery(null, "观察四周"));

        assertThat(selected.source().revisionId()).isEqualTo(resolved.revision().id());
        assertThat(selected.source().publisherNamespace()).isEqualTo("wenxian.official");
        assertThat(selected.source().packageId()).isEqualTo("XUANXU");
        assertThat(selected.source().revision()).isEqualTo(2);
        assertThat(selected.source().contentHash()).isEqualTo(resolved.revision().contentHash());
        assertThat(selected.source().compiledHash()).isEqualTo(resolved.revision().compiledHash());
        assertThat(selected.source().runtimeContract()).isEqualTo(resolved.revision().runtimeContract());
        assertThat(selected.selectorVersion()).isEqualTo(WorldContextSelector.SELECTOR_VERSION);
        assertThat(mapper.valueToTree(selected).path("trustLevel").asText()).isEqualTo("UNTRUSTED_WORLD_BACKGROUND");
        assertThat(kinds(selected)).contains("PREMISE", "CORE_RULE", "CONTENT_BOUNDARY", "NARRATIVE_PROFILE",
                "RACE", "PROGRESSION_STAGE").doesNotContain("REGION");
        assertThat(loreRefs(selected)).containsExactly("lore:xuanxu:plural-orders");
        assertThat(selected.unavailableInputs()).contains("CURRENT_REGION_UNAVAILABLE");
        assertThat(material(selected, HUMAN).data().path("socialHooks").isArray()).isTrue();
    }

    @Test
    void onlyVerifiedRegionCanActivateRegionalLore() {
        ResolvedRevision resolved = fixture(XUANXU);
        WorldContext selected = selector.select(resolved, humanQuery(REGION, "观察四周"));
        assertThat(material(selected, REGION).kind()).isEqualTo("REGION");
        assertThat(material(selected, "lore:xuanxu:inheritance").reasons()).contains("LOCATION_REF:" + REGION);

        WorldContext invalid = selector.select(resolved, humanQuery("region:not-in-package", "观察四周"));
        assertThat(kinds(invalid)).doesNotContain("REGION");
        assertThat(loreRefs(invalid)).doesNotContain("lore:xuanxu:inheritance");
        assertThat(invalid.unavailableInputs()).contains("CURRENT_REGION_UNAVAILABLE");
    }

    @Test
    void normalizesLiteralTermsWithoutInterpretingRegexOrMakingThemLocations() {
        ResolvedRevision resolved = withLore(List.of(
                lore("lore:test:term", 70, false, List.of(), List.of("Sect"), List.of()),
                lore("lore:test:regex", 99, false, List.of(), List.of(".*"), List.of())));
        WorldContext selected = selector.select(resolved, humanQuery(null, "去了解ＳＥＣＴ"));
        assertThat(loreRefs(selected)).containsExactly("lore:test:term");
        assertThat(material(selected, "lore:test:term").reasons()).containsExactly("TERM_MATCH:sect");
        assertThat(kinds(selected)).doesNotContain("REGION");
    }

    @Test
    void sortsByActivationClassThenPriorityThenStableIdAndDeduplicatesReasons() {
        ResolvedRevision resolved = withLore(List.of(
                lore("lore:test:term", 99, false, List.of(), List.of("宗门"), List.of()),
                lore("lore:test:direct-b", 80, false, List.of(HUMAN), List.of("宗门"), List.of()),
                lore("lore:test:direct-a", 80, false, List.of(HUMAN), List.of(), List.of()),
                lore("lore:test:direct-low", 10, false, List.of(HUMAN), List.of(), List.of()),
                lore("lore:test:always", 1, true, List.of(HUMAN), List.of("宗门"), List.of()),
                lore("lore:test:related", 100, false, List.of(), List.of(), List.of("lore:test:direct-a"))));
        WorldContext selected = selector.select(resolved, humanQuery(null, "寻找宗门"));
        assertThat(loreRefs(selected)).containsExactly("lore:test:always", "lore:test:direct-a", "lore:test:direct-b",
                "lore:test:direct-low", "lore:test:term", "lore:test:related");
        assertThat(material(selected, "lore:test:always").reasons())
                .containsExactly("ALWAYS", "IDENTITY_REF:" + HUMAN, "TERM_MATCH:宗门");
        assertThat(selected.omittedCount()).isZero();
        assertThat(selected.candidateCount()).isEqualTo(selected.materials().size());
    }

    @Test
    void expandsRelatedRefsOnlyOneHopAndDoesNotLeakUnmatchedContent() {
        ResolvedRevision resolved = withLore(List.of(
                lore("lore:test:first", 50, false, List.of(), List.of("问路"), List.of("lore:test:second")),
                lore("lore:test:second", 50, false, List.of(), List.of(), List.of("lore:test:third")),
                lore("lore:test:third", 50, false, List.of(), List.of(), List.of())));
        WorldContext selected = selector.select(resolved, humanQuery(null, "问路"));
        assertThat(loreRefs(selected)).containsExactly("lore:test:first", "lore:test:second");
        assertThat(material(selected, "lore:test:second").reasons()).containsExactly("RELATED_REF:lore:test:first");
        assertThat(mapper.valueToTree(selected).toString()).doesNotContain("内容-lore:test:third");
    }

    @Test
    void supportsNoRelatedLoreWithoutSubstitutingOtherEntries() {
        ResolvedRevision resolved = withLore(List.of(
                lore("lore:test:unmatched", 100, false, List.of(), List.of("不存在的词"), List.of())));
        WorldContext selected = selector.select(resolved, humanQuery(null, "观察环境"));
        assertThat(loreRefs(selected)).isEmpty();
        assertThat(selected.omittedCount()).isZero();
        assertThat(mapper.valueToTree(selected).toString()).doesNotContain("内容-lore:test:unmatched");
    }

    @Test
    void acceptsHeterogeneousStagesButNeverUsesAnotherRacesTrackOrStartingFallback() {
        ResolvedRevision resolved = fixture(DREAMSEA);
        var query = new WorldContextSelector.Query("race:dreamsea:tideborn", "region:dreamsea:harbor",
                "stage:dreamsea:tideborn:current", Set.of(), Set.of(), "观察退潮", List.of());
        WorldContext selected = selector.select(resolved, query);
        assertThat(material(selected, "stage:dreamsea:tideborn:current").data().path("stage").path("name").asText())
                .isEqualTo("长流");
        assertThat(loreRefs(selected)).containsExactly("lore:dreamsea:memory-tide");

        WorldContext wrongTrack = selector.select(resolved, new WorldContextSelector.Query(
                "race:dreamsea:tideborn", null, "stage:dreamsea:islander:listener", Set.of(), Set.of(), "", List.of()));
        assertThat(kinds(wrongTrack)).doesNotContain("PROGRESSION_STAGE");
        assertThat(wrongTrack.unavailableInputs()).contains("CURRENT_STAGE_UNAVAILABLE");
        assertThat(mapper.valueToTree(wrongTrack).toString()).doesNotContain("stage:dreamsea:tideborn:ripple");
    }

    @Test
    void rejectsOversizedCoreInsteadOfSilentlyRemovingNecessaryRules() {
        assertThatThrownBy(() -> new WorldContextSelector(mapper, 1, 8)
                .select(fixture(XUANXU), humanQuery(null, "")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("核心资料超过字符预算");
    }

    @Test
    void omitsWholeOversizedLoreAndStillFitsLaterSmallerCandidate() {
        LoreEntry big = new LoreEntry("lore:test:big", "大条目", "长".repeat(2_000), 99, List.of(), List.of(),
                new LoreActivation(true, List.of(), List.of(), List.of(), List.of(), List.of()));
        ResolvedRevision resolved = withLore(List.of(big,
                lore("lore:test:small", 10, true, List.of(), List.of(), List.of())));
        var query = humanQuery(null, "");
        WorldContext coreOnly = new WorldContextSelector(mapper, 100_000, 0).select(resolved, query);
        WorldContext all = new WorldContextSelector(mapper, 100_000, 8).select(resolved, query);
        int exactBudget = coreOnly.usedCharacters()
                + mapper.valueToTree(material(all, "lore:test:small")).toString().length();

        WorldContext selected = new WorldContextSelector(mapper, exactBudget, 8).select(resolved, query);
        assertThat(loreRefs(selected)).containsExactly("lore:test:small");
        assertThat(selected.usedCharacters()).isEqualTo(exactBudget);
        assertThat(selected.omittedCount()).isEqualTo(1);
        assertThat(selected.candidateCount()).isEqualTo(selected.materials().size() + 1);
        assertThat(mapper.valueToTree(selected).toString()).doesNotContain("长长长");
    }

    @Test
    void limitsLoreCountAndCountsCompactJsonCharactersRatherThanTokens() {
        ResolvedRevision resolved = withLore(IntStream.range(0, 10)
                .mapToObj(i -> lore("lore:test:" + i, 50, false, List.of(HUMAN), List.of(), List.of())).toList());
        WorldContext selected = selector.select(resolved, humanQuery(null, ""));
        assertThat(loreRefs(selected)).hasSize(8);
        assertThat(selected.omittedCount()).isEqualTo(2);
        assertThat(selected.usedCharacters()).isEqualTo(selected.materials().stream()
                .mapToInt(value -> mapper.valueToTree(value).toString().length()).sum());
        assertThat(selected.maximumCharacters()).isEqualTo(24_000);
    }

    @Test
    void freezesQueryCollectionsMaterialJsonAndReturnedLists() {
        ResolvedRevision resolved = fixture(XUANXU);
        Set<String> tags = new HashSet<>(Set.of("sect"));
        Set<String> threads = new HashSet<>(Set.of("seed:xuanxu:qingqi-arrival"));
        List<String> missing = new ArrayList<>(List.of("CALLER_REGION_UNVERIFIED"));
        var query = new WorldContextSelector.Query(HUMAN, null, HUMAN_STAGE, tags, threads, "宗门", missing);
        WorldContext selected = selector.select(resolved, query);
        String frozen = mapper.valueToTree(selected).toString();
        tags.clear();
        threads.clear();
        missing.add("ADDED_AFTER_SELECTION");
        ((ObjectNode) material(selected, HUMAN).data()).put("description", "changed");
        assertThat(selector.select(resolved, query)).isEqualTo(selected);
        assertThat(mapper.valueToTree(selected).toString()).isEqualTo(frozen);
        assertThat(selected.unavailableInputs()).doesNotContain("ADDED_AFTER_SELECTION");
        assertThatThrownBy(() -> selected.materials().clear()).isInstanceOf(UnsupportedOperationException.class);

        ObjectNode data = mapper.createObjectNode().put("content", "original");
        Material material = new Material("test", "LORE", data, new ArrayList<>(List.of("TEST")));
        data.put("content", "changed");
        assertThat(material.data().path("content").asText()).isEqualTo("original");
    }

    @Test
    void rejectsMislabelledSourceInsteadOfAttachingWrongRevisionIdentity() {
        ResolvedRevision resolved = fixture(XUANXU);
        var p = resolved.worldPackage();
        var wrong = new PackageRow(p.id(), "wrong.publisher", p.packageKey(), p.ownerUserId(), p.sourceType(),
                p.visibility(), p.status(), p.latestRevision());
        assertThatThrownBy(() -> selector.select(new ResolvedRevision(wrong, resolved.revision(), resolved.compiled()),
                humanQuery(null, ""))).isInstanceOf(IllegalStateException.class).hasMessageContaining("来源");
    }

    private WorldContextSelector.Query humanQuery(String region, String action) {
        return new WorldContextSelector.Query(HUMAN, region, HUMAN_STAGE, Set.of(), Set.of(), action, List.of());
    }

    private List<String> loreRefs(WorldContext selected) {
        return selected.materials().stream().filter(value -> "LORE".equals(value.kind())).map(Material::ref).toList();
    }

    private List<String> kinds(WorldContext selected) {
        return selected.materials().stream().map(Material::kind).toList();
    }

    private Material material(WorldContext selected, String ref) {
        return selected.materials().stream().filter(value -> ref.equals(value.ref())).findFirst().orElseThrow();
    }

    private ResolvedRevision fixture(Path path) {
        return resolve(new WorldPackageIngestionPipeline(mapper).inspectDirectory(path).document());
    }

    private ResolvedRevision withLore(List<LoreEntry> lore) {
        WorldPackageDocument base = new WorldPackageIngestionPipeline(mapper).inspectDirectory(XUANXU).document();
        Set<String> loreIds = lore.stream().map(LoreEntry::loreId).collect(java.util.stream.Collectors.toSet());
        var original = base.creation();
        // 测试只替换知识集合；保留路线本身，并同步移除不再存在的关联，继续通过正式跨文件校验。
        var creation = new WorldPackageDocument.CreationContent(original.playableRaceRefs(),
                original.openingRegionRefs(), original.defaultOpeningRegionRef(), original.requiredOriginFacts(),
                original.routeThemes().stream().map(theme -> new WorldPackageDocument.RouteTheme(
                        theme.themeId(), theme.summary(), theme.loreRefs().stream().filter(loreIds::contains).toList()))
                        .toList(), original.scenarioSeeds());
        return resolve(new WorldPackageDocument(base.identity(), base.metadata(), base.world(), creation,
                base.progression(), lore, base.assets()));
    }

    private ResolvedRevision resolve(WorldPackageDocument document) {
        new WorldPackageValidator().validate(document);
        var compiled = new WorldPackageCompiler().compile(document);
        var canonical = new WorldPackageCanonicalizer(mapper).canonicalize(document, compiled);
        var identity = compiled.identity();
        PackageRow world = new PackageRow("package-test", identity.publisherNamespace(), identity.packageId(),
                null, "OFFICIAL", "PUBLIC", "ACTIVE", identity.revision());
        RevisionRow revision = new RevisionRow("revision-test", world.id(), identity.revision(), identity.formatVersion(),
                identity.runtimeContract(), canonical.contentHash(), canonical.compiledHash(), WorldPackageValidator.COMPILER_VERSION,
                "PUBLISHED", mapper.valueToTree(identity.requiredCapabilities()).toString());
        return new ResolvedRevision(world, revision, compiled);
    }

    private LoreEntry lore(String id, int priority, boolean always, List<String> identities,
                           List<String> terms, List<String> related) {
        List<String> activationTerms = !always && identities.isEmpty() && terms.isEmpty()
                ? List.of("未触发词-" + id) : terms;
        return new LoreEntry(id, "标题-" + id, "内容-" + id, priority, List.of(), related,
                new LoreActivation(always, identities, List.of(), List.of(), List.of(), activationTerms));
    }
}
