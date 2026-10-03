package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.PackageRow;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.RevisionRow;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 冻结已发布快照的读取约束；篡改用例会重算外层哈希，避免只测到哈希拒绝而漏检内容图。 */
class CompiledWorldPackageSnapshotReaderTest {
    private static final Path XUANXU = Path.of("src/main/resources/world-packages/official/XUANXU/2");
    private static final Path DREAMSEA = Path.of("src/test/resources/world-packages/fixtures/DREAMSEA/1");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CompiledWorldPackageSnapshotReader reader = new CompiledWorldPackageSnapshotReader(mapper);

    @Test
    void readsBothRealPublishedShapesWithoutRecompilingAtReadTime() throws Exception {
        for (Path path : List.of(XUANXU, DREAMSEA)) {
            Fixture fixture = fixture(path);
            assertThat(reader.read(fixture.world(), fixture.revision(), fixture.json()))
                    .isEqualTo(fixture.candidate().compiled());
        }
    }

    @Test
    void acceptsSupportedSnapshotFromAnOlderCompilerWithoutChangingThePublishedPayload() throws Exception {
        Fixture fixture = fixture(DREAMSEA);
        RevisionRow original = fixture.revision();
        RevisionRow old = new RevisionRow(original.id(), original.packageId(), original.revision(), original.formatVersion(),
                original.runtimeContract(), original.contentHash(), original.compiledHash(),
                "retired-world-compiler-2025", original.status(), original.requiredCapabilitiesJson());

        assertThat(reader.read(fixture.world(), old, fixture.json())).isEqualTo(fixture.candidate().compiled());
        assertThat(old.compiledHash()).isEqualTo(fixture.candidate().compiledHash());
    }

    @Test
    void hashesTheExactPersistedUtf8StringRatherThanReserializingIt() throws Exception {
        Fixture fixture = fixture(XUANXU);
        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(fixture.json()));

        assertThatThrownBy(() -> reader.read(fixture.world(), fixture.revision(), pretty))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("哈希");
        assertThat(reader.read(fixture.world(), withHash(fixture.revision(), pretty), pretty))
                .isEqualTo(fixture.candidate().compiled());
    }

    @Test
    void rejectsDatabaseBindingChangesEvenForAnAlreadyCachedSnapshot() throws Exception {
        Fixture fixture = fixture(XUANXU);
        PackageRow p = fixture.world();
        PackageRow renamed = new PackageRow(p.id(), "another.publisher", p.packageKey(), p.ownerUserId(),
                p.sourceType(), p.visibility(), p.status(), p.latestRevision());

        assertThatThrownBy(() -> reader.validateBinding(renamed, fixture.revision(), fixture.candidate().compiled()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("绑定");
        RevisionRow r = fixture.revision();
        RevisionRow wrongParent = new RevisionRow(r.id(), "other-package", r.revision(), r.formatVersion(),
                r.runtimeContract(), r.contentHash(), r.compiledHash(), r.compilerVersion(), r.status(), r.requiredCapabilitiesJson());
        assertThatThrownBy(() -> reader.validateBinding(p, wrongParent, fixture.candidate().compiled()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("归属");
    }

    @Test
    void rejectsSnapshotIdentityAndRuntimeVersionTamperingDespiteAValidHash() throws Exception {
        Fixture fixture = fixture(XUANXU);
        rejectMutation(fixture, tree -> ((ObjectNode) tree.path("identity")).put("revision", 9), "绑定");
        rejectMutation(fixture, tree -> ((ObjectNode) tree.path("identity")).put("formatVersion", 2), "格式版本");
        rejectMutation(fixture, tree -> ((ObjectNode) tree.path("identity")).put("runtimeContract", "narrative-world-v2"), "运行时合同");
    }

    @Test
    void validatesBothSnapshotAndDatabaseCapabilityDeclarations() throws Exception {
        Fixture fixture = fixture(DREAMSEA);
        rejectMutation(fixture, tree -> ((ArrayNode) tree.path("identity").path("requiredCapabilities"))
                .add("arbitrary-code-v1"), "能力");
        rejectMutation(fixture, tree -> ((ArrayNode) tree.path("identity").path("requiredCapabilities")).remove(0), "能力");
        RevisionRow r = fixture.revision();
        RevisionRow missing = new RevisionRow(r.id(), r.packageId(), r.revision(), r.formatVersion(), r.runtimeContract(),
                r.contentHash(), r.compiledHash(), r.compilerVersion(), r.status(), "[\"creation-catalog-v1\"]");
        assertThatThrownBy(() -> reader.validateBinding(fixture.world(), missing, fixture.candidate().compiled()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("能力");
        List<String> reversed = fixture.candidate().compiled().identity().requiredCapabilities().reversed();
        RevisionRow reordered = new RevisionRow(r.id(), r.packageId(), r.revision(), r.formatVersion(), r.runtimeContract(),
                r.contentHash(), r.compiledHash(), r.compilerVersion(), r.status(), mapper.writeValueAsString(reversed));
        reader.validateBinding(fixture.world(), reordered, fixture.candidate().compiled());
    }

    @Test
    void rejectsUnknownFieldsDuplicateKeysMissingFieldsNullStructuresAndTrailingJson() throws Exception {
        Fixture fixture = fixture(DREAMSEA);
        rejectMutation(fixture, tree -> tree.put("systemPrompt", "unknown"), "JSON");
        rejectMutation(fixture, tree -> ((ObjectNode) tree.path("identity")).remove("requiredCapabilities"), "JSON");
        rejectMutation(fixture, tree -> tree.putNull("creationProfile"), "null");
        rejectMutation(fixture, tree -> ((ObjectNode) tree.path("identity")).put("revision", "1"), "JSON");
        String duplicate = fixture.json().substring(0, fixture.json().length() - 1) + ",\"premise\":\"duplicate\"}";
        rejectPayload(fixture, duplicate, "JSON");
        rejectPayload(fixture, fixture.json() + " {}", "JSON");
    }

    @Test
    void rejectsMapIdentityAndOrderedMapDivergence() throws Exception {
        Fixture fixture = fixture(DREAMSEA);
        rejectMutation(fixture, tree -> ((ObjectNode) tree.path("racesById").elements().next()).put("raceId", "race:foreign"), "映射键");
        rejectMutation(fixture, tree -> ((ObjectNode) system(tree).path("orderedSteps").get(0)).put("name", "different"), "映射内容");
        rejectMutation(fixture, tree -> ((ObjectNode) track(tree).path("orderedStages").get(0)).put("order", 2), "顺序");
    }

    @Test
    void rejectsSelfReferentialSuccessorsAndStartingPositionsOutsideTheirTrack() throws Exception {
        Fixture fixture = fixture(DREAMSEA);
        rejectMutation(fixture, tree -> {
            ObjectNode track = track(tree);
            ObjectNode stage = (ObjectNode) track.path("orderedStages").get(0);
            String stageId = stage.path("stageId").asText();
            stage.put("nextStageId", stageId);
            ((ObjectNode) track.path("stagesById").path(stageId)).put("nextStageId", stageId);
        }, "后继");
        rejectMutation(fixture, tree -> track(tree).put("startingStageRef", "stage:missing"), "开局位置");
        rejectMutation(fixture, tree -> {
            ObjectNode system = system(tree);
            ObjectNode last = (ObjectNode) system.path("orderedSteps").get(1);
            String stepId = last.path("stepId").asText();
            last.put("nextStepId", stepId);
            ((ObjectNode) system.path("stepsById").path(stepId)).put("nextStepId", stepId);
        }, "后继");
    }

    @Test
    void rejectsCreationAndLoreReferencesThatDoNotMatchTheSnapshot() throws Exception {
        Fixture fixture = fixture(XUANXU);
        rejectMutation(fixture, tree -> ((ObjectNode) tree.path("creationProfile")).put("defaultOpeningRegionRef", "region:missing"), "默认开场");
        rejectMutation(fixture, tree -> ((ObjectNode) tree.path("creationProfile").path("progressionTrackByIdentity"))
                .put("race:xuanxu:human", "track:missing"), "主轨道索引");
        rejectMutation(fixture, tree -> ((ArrayNode) tree.path("loreById").elements().next().path("relatedRefs"))
                .add("region:missing"), "稳定引用");
        rejectMutation(fixture, tree -> ((ObjectNode) tree.path("loreActivationIndex")).set("byLocation", mapper.createObjectNode()), "Lore 激活索引");
    }

    @Test
    void boundsJsonNestingAndIndividualStringResources() throws Exception {
        Fixture fixture = fixture(DREAMSEA);
        rejectPayload(fixture, "{\"nested\":".repeat(70) + "0" + "}".repeat(70), "JSON");
        rejectMutation(fixture, tree -> tree.put("premise", "x".repeat(1_000_001)), "JSON");
    }

    @Test
    void validatesAssetDescriptorsWithoutNeedingOriginalAssetBlobs() throws Exception {
        Fixture fixture = fixture(DREAMSEA);
        ObjectNode tree = (ObjectNode) mapper.readTree(fixture.json());
        ((ArrayNode) tree.path("assets")).addObject().put("path", "assets/cover.png").put("role", "cover")
                .put("mediaType", "image/png").put("byteSize", 123).put("sha256", "a".repeat(64));
        String json = mapper.writeValueAsString(tree);
        assertThat(reader.read(fixture.world(), withHash(fixture.revision(), json), json).assets()).hasSize(1);
        ((ObjectNode) tree.path("assets").get(0)).put("path", "assets/../escape.png");
        rejectPayload(fixture, mapper.writeValueAsString(tree), "素材相对路径");
    }

    private Fixture fixture(Path path) throws Exception {
        WorldPackageCandidate candidate = new WorldPackageIngestionPipeline(mapper).inspectDirectory(path);
        var identity = candidate.compiled().identity();
        PackageRow world = new PackageRow("package-test", identity.publisherNamespace(), identity.packageId(),
                null, "OFFICIAL", "PUBLIC", "ACTIVE", identity.revision());
        RevisionRow revision = new RevisionRow("revision-test", world.id(), identity.revision(), identity.formatVersion(),
                identity.runtimeContract(), candidate.contentHash(), candidate.compiledHash(), candidate.compilerVersion(),
                "PUBLISHED", mapper.writeValueAsString(identity.requiredCapabilities()));
        return new Fixture(candidate, world, revision, candidate.compiledJson());
    }

    private void rejectMutation(Fixture fixture, Consumer<ObjectNode> mutate, String message) throws Exception {
        ObjectNode tree = (ObjectNode) mapper.readTree(fixture.json());
        mutate.accept(tree);
        rejectPayload(fixture, mapper.writeValueAsString(tree), message);
    }

    private void rejectPayload(Fixture fixture, String json, String message) {
        assertThatThrownBy(() -> reader.read(fixture.world(), withHash(fixture.revision(), json), json))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(message);
    }

    private RevisionRow withHash(RevisionRow r, String json) {
        return new RevisionRow(r.id(), r.packageId(), r.revision(), r.formatVersion(), r.runtimeContract(),
                r.contentHash(), WorldPackageCanonicalizer.sha256(json), r.compilerVersion(), r.status(), r.requiredCapabilitiesJson());
    }

    private ObjectNode system(ObjectNode tree) {
        return (ObjectNode) tree.path("progressionSystemsById").elements().next();
    }

    private ObjectNode track(ObjectNode tree) {
        return (ObjectNode) system(tree).path("tracksById").elements().next();
    }

    private record Fixture(WorldPackageCandidate candidate, PackageRow world, RevisionRow revision, String json) {}
}
