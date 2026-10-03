package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.application.WorldPackageCatalog;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.Bundle;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.CreationContent;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.Manifest;
import com.wenxian.game.story.worldpackage.infrastructure.WorldPackageBundleException;
import com.wenxian.game.story.worldpackage.infrastructure.WorldPackageBundleReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class WorldPackageIngestionPipelineTest {
    private static final Path XUANXU = Path.of("src/main/resources/world-packages/official/XUANXU/2");
    private static final Path DREAMSEA = Path.of("src/test/resources/world-packages/fixtures/DREAMSEA/1");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final WorldPackageIngestionPipeline pipeline = new WorldPackageIngestionPipeline(mapper);
    private final WorldPackageBundleReader reader = new WorldPackageBundleReader(mapper);

    @Test
    void compilesOfficialXuanxuWithoutChangingExistingStableIds() {
        WorldPackageCandidate candidate = pipeline.inspectDirectory(XUANXU);

        assertThat(candidate.document().identity().publisherNamespace()).isEqualTo("wenxian.official");
        assertThat(candidate.document().identity().packageId()).isEqualTo("XUANXU");
        assertThat(candidate.document().identity().revision()).isEqualTo(2);
        assertThat(candidate.compiled().racesById()).containsKeys(
                "race:xuanxu:human", "race:xuanxu:yao", "race:xuanxu:spirit");
        assertThat(candidate.compiled().regionsById()).containsKeys(
                "region:xuanxu:qingqi", "region:xuanxu:cangling");

        var system = candidate.compiled().progressionSystemsById().get("realm-scale:xuanxu");
        assertThat(system.orderedSteps()).extracting("stepId").containsExactly(
                "phase:xuanxu:early", "phase:xuanxu:middle",
                "phase:xuanxu:late", "phase:xuanxu:perfect");
        assertThat(system.tracksById().get("track:xuanxu:human").orderedStages())
                .extracting("stageId")
                .containsExactly("realm:xuanxu:human:l1", "realm:xuanxu:human:l2",
                        "realm:xuanxu:human:l3", "realm:xuanxu:human:l4",
                        "realm:xuanxu:human:l5", "realm:xuanxu:human:l6",
                        "realm:xuanxu:human:l7", "realm:xuanxu:human:l8");
        assertThat(system.tracksById().get("track:xuanxu:human").orderedStages().getLast().nextStageId())
                .isNull();
        assertThat(system.completionUnlock()).isEqualTo("player-confirmed-ending-v1");
        assertThat(candidate.compiled().loreActivationIndex().byLocation().get("region:xuanxu:qingqi"))
                .contains("lore:xuanxu:inheritance");
        assertThat(candidate.contentHash()).hasSize(64);
        assertThat(candidate.compiledHash()).hasSize(64);
        assertThat(candidate.canonicalJson()).doesNotContain("entrypoints", "checksums.json");
    }

    @Test
    void officialBundlePreservesEveryLegacyWorldPackageFact() {
        var legacy = new WorldPackageCatalog(mapper).currentPackage();
        WorldPackageCandidate candidate = pipeline.inspectDirectory(XUANXU);
        var document = candidate.document();

        assertThat(document.identity().packageId()).isEqualTo(legacy.packageId());
        assertThat(document.identity().revision()).isEqualTo(legacy.revision());
        assertThat(document.metadata().name()).isEqualTo(legacy.name());
        assertThat(document.world().premise()).isEqualTo(legacy.premise());
        assertThat(document.world().coreRules()).extracting("text").containsExactlyElementsOf(legacy.coreRules());
        assertThat(document.world().contentBoundaries()).extracting("text")
                .containsExactlyElementsOf(legacy.contentBoundaries());
        assertThat(document.world().narrativeProfile().guidance()).isEqualTo(legacy.narrativeGuidance());
        assertThat(document.world().races()).extracting("raceId")
                .containsExactlyElementsOf(legacy.races().stream().map(value -> value.raceId()).toList());
        assertThat(document.world().regions()).extracting("regionId")
                .containsExactlyElementsOf(legacy.regions().stream().map(value -> value.regionId()).toList());
        assertThat(document.creation().openingRegionRefs()).containsExactlyElementsOf(
                legacy.regions().stream().filter(value -> value.openingEligible())
                        .map(value -> value.regionId()).toList());
        assertThat(document.creation().defaultOpeningRegionRef())
                .isEqualTo(legacy.defaultOpeningRegion().regionId());

        var compiledSystem = candidate.compiled().progressionSystemsById().get(legacy.realmScale().scaleId());
        assertThat(compiledSystem.revision()).isEqualTo(legacy.realmScale().revision());
        assertThat(compiledSystem.orderedSteps()).extracting("name")
                .containsExactly("初期", "中期", "后期", "圆满");
        for (var race : legacy.races()) {
            String trackId = candidate.compiled().creationProfile().progressionTrackByIdentity().get(race.raceId());
            var track = compiledSystem.tracksById().get(trackId);
            assertThat(track.startingStageRef()).isEqualTo(legacy.realmScale().startingStage(race.raceId()).stageId());
            for (var rank : legacy.realmScale().ranks()) {
                var oldStage = rank.raceStages().get(race.raceId());
                var newStage = track.stagesById().get(oldStage.stageId());
                assertThat(newStage.name()).isEqualTo(oldStage.name());
                assertThat(newStage.description()).isEqualTo(oldStage.description());
                assertThat(newStage.capabilityBoundary()).isEqualTo(oldStage.capabilityBoundary());
                assertThat(newStage.aliases()).containsExactlyElementsOf(oldStage.aliases());
                assertThat(newStage.comparisonTier()).isEqualTo(rank.order());
            }
        }
        for (var oldLore : legacy.loreEntries()) {
            var newLore = candidate.compiled().loreById().get(oldLore.loreId());
            assertThat(newLore.content()).isEqualTo(oldLore.content());
            assertThat(newLore.priority()).isEqualTo(oldLore.priority());
            assertThat(newLore.tags()).containsExactlyElementsOf(oldLore.tags());
            assertThat(newLore.relatedRefs()).containsExactlyElementsOf(oldLore.relatedRefs());
        }
    }

    @Test
    void compilesDifferentRaceStageAndStepCountsThroughTheSamePipeline() {
        WorldPackageCandidate first = pipeline.inspectDirectory(DREAMSEA);
        WorldPackageCandidate second = pipeline.inspectDirectory(DREAMSEA);

        assertThat(first.document().identity().packageId()).isEqualTo("DREAMSEA");
        assertThat(first.compiled().racesById()).hasSize(2);
        var system = first.compiled().progressionSystemsById().get("progression:dreamsea:memory");
        assertThat(system.orderedSteps()).hasSize(2);
        assertThat(system.tracksById()).hasSize(2);
        assertThat(system.tracksById().values())
                .allSatisfy(track -> assertThat(track.orderedStages()).hasSize(3));
        assertThat(first.contentHash()).isEqualTo(second.contentHash());
        assertThat(first.compiledHash()).isEqualTo(second.compiledHash());
        assertThat(first.canonicalJson()).isEqualTo(second.canonicalJson());
    }

    @Test
    void contentHashDoesNotChangeWhenOnlyPortableIdentityChanges() {
        Bundle original = reader.readDirectory(DREAMSEA);
        Manifest manifest = original.manifest();
        Manifest forkIdentity = new Manifest(
                manifest.format(), manifest.formatVersion(), "test.other", "DREAMSEA_COPY", 9,
                manifest.defaultLocale(), manifest.name(), manifest.summary(), manifest.authors(),
                manifest.license(), manifest.contentRating(), manifest.runtimeContract(),
                manifest.requiredCapabilities(), manifest.entrypoints(), manifest.assets());
        Bundle fork = new Bundle(forkIdentity, original.world(), original.creation(), original.progression(),
                original.loreEntries(), original.assets());

        WorldPackageCandidate originalCandidate = pipeline.inspect(original);
        WorldPackageCandidate forkCandidate = pipeline.inspect(fork);

        assertThat(forkCandidate.contentHash()).isEqualTo(originalCandidate.contentHash());
        assertThat(forkCandidate.compiledHash()).isNotEqualTo(originalCandidate.compiledHash());
    }

    @Test
    void capabilityDeclarationOrderDoesNotChangeCanonicalResults() {
        Bundle original = reader.readDirectory(DREAMSEA);
        Manifest manifest = original.manifest();
        List<String> reversed = manifest.requiredCapabilities().reversed();
        Manifest reorderedManifest = new Manifest(
                manifest.format(), manifest.formatVersion(), manifest.publisherNamespace(), manifest.packageId(),
                manifest.revision(), manifest.defaultLocale(), manifest.name(), manifest.summary(), manifest.authors(),
                manifest.license(), manifest.contentRating(), manifest.runtimeContract(), reversed,
                manifest.entrypoints(), manifest.assets());
        Bundle reordered = new Bundle(reorderedManifest, original.world(), original.creation(),
                original.progression(), original.loreEntries(), original.assets());

        WorldPackageCandidate first = pipeline.inspect(original);
        WorldPackageCandidate second = pipeline.inspect(reordered);

        assertThat(second.contentHash()).isEqualTo(first.contentHash());
        assertThat(second.compiledHash()).isEqualTo(first.compiledHash());
        assertThat(second.canonicalJson()).isEqualTo(first.canonicalJson());
    }

    @Test
    void reportsDanglingCreationReferenceBeforeCompilation() {
        Bundle original = reader.readDirectory(DREAMSEA);
        CreationContent creation = original.creation();
        CreationContent invalid = new CreationContent(
                creation.playableRaceRefs(), creation.openingRegionRefs(), "region:dreamsea:missing",
                creation.requiredOriginFacts(), creation.routeThemes(), creation.scenarioSeeds());
        Bundle broken = new Bundle(original.manifest(), original.world(), invalid, original.progression(),
                original.loreEntries(), original.assets());

        assertThatExceptionOfType(WorldPackageValidationException.class)
                .isThrownBy(() -> pipeline.inspect(broken))
                .satisfies(error -> assertThat(error.violations())
                        .extracting("code").contains("DEFAULT_OPENING_INVALID"));
    }

    @Test
    void strictParserRejectsUnknownManifestFields() throws IOException {
        Map<String, byte[]> files = files(DREAMSEA);
        String manifest = text(files.get("manifest.json"));
        files.put("manifest.json", manifest.replace("\"formatVersion\": 1,",
                "\"formatVersion\": 1, \"unexpected\": true,").getBytes(StandardCharsets.UTF_8));

        assertThatExceptionOfType(WorldPackageBundleException.class)
                .isThrownBy(() -> pipeline.inspectZip(new ByteArrayInputStream(zip(files))))
                .satisfies(error -> assertThat(error.code()).isEqualTo("BUNDLE_JSON_INVALID"));
    }

    @Test
    void strictParserRejectsDuplicateJsonKeys() throws IOException {
        Map<String, byte[]> files = files(DREAMSEA);
        String manifest = text(files.get("manifest.json"));
        files.put("manifest.json", manifest.replace("\"format\": \"wenxian.world-package\",",
                "\"format\": \"wenxian.world-package\", \"format\": \"other\",")
                .getBytes(StandardCharsets.UTF_8));

        assertThatExceptionOfType(WorldPackageBundleException.class)
                .isThrownBy(() -> pipeline.inspectZip(new ByteArrayInputStream(zip(files))))
                .satisfies(error -> assertThat(error.code()).isEqualTo("BUNDLE_JSON_INVALID"));
    }

    @Test
    void strictParserRejectsMissingRequiredProperties() throws IOException {
        Map<String, byte[]> files = files(DREAMSEA);
        String manifest = text(files.get("manifest.json"));
        files.put("manifest.json", manifest.replace(",\n  \"assets\": []", "")
                .getBytes(StandardCharsets.UTF_8));

        assertThatExceptionOfType(WorldPackageBundleException.class)
                .isThrownBy(() -> pipeline.inspectZip(new ByteArrayInputStream(zip(files))))
                .satisfies(error -> assertThat(error.code()).isEqualTo("BUNDLE_JSON_INVALID"));
    }

    @Test
    void rejectsZipPathTraversalBeforeExtraction() throws IOException {
        Map<String, byte[]> files = Map.of("../manifest.json", "{}".getBytes(StandardCharsets.UTF_8));

        assertThatExceptionOfType(WorldPackageBundleException.class)
                .isThrownBy(() -> pipeline.inspectZip(new ByteArrayInputStream(zip(files))))
                .satisfies(error -> assertThat(error.code()).isEqualTo("BUNDLE_PATH_INVALID"));
    }

    @Test
    void rejectsChecksumMismatchBeforeParsingWorldContent() throws IOException {
        Map<String, byte[]> files = files(DREAMSEA);
        String world = text(files.get("world.json"));
        files.put("world.json", world.replace("梦海随记忆潮汐", "梦海随记忆巨浪")
                .getBytes(StandardCharsets.UTF_8));

        assertThatExceptionOfType(WorldPackageBundleException.class)
                .isThrownBy(() -> pipeline.inspectZip(new ByteArrayInputStream(zip(files))))
                .satisfies(error -> assertThat(error.code()).isEqualTo("BUNDLE_CHECKSUM_MISMATCH"));
    }

    @Test
    void rejectsFilesThatWereNotDeclaredByTheBundle() throws IOException {
        Map<String, byte[]> files = files(DREAMSEA);
        files.put("lore/hidden.json", "{}".getBytes(StandardCharsets.UTF_8));

        assertThatExceptionOfType(WorldPackageBundleException.class)
                .isThrownBy(() -> pipeline.inspectZip(new ByteArrayInputStream(zip(files))))
                .satisfies(error -> assertThat(error.code()).isEqualTo("BUNDLE_FILE_SET_INVALID"));
    }

    private Map<String, byte[]> files(Path root) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                result.put(root.relativize(path).toString().replace(java.io.File.separatorChar, '/'),
                        Files.readAllBytes(path));
            }
        }
        return result;
    }

    private byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
