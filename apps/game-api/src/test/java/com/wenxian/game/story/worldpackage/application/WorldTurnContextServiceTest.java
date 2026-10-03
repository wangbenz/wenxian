package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.application.WorldTurnContextService;
import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.CreationContract;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.support.StoryTestFixtures;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.*;
import com.wenxian.game.story.worldpackage.persistence.JdbcWorldPackageRevisionRepository;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** 用独立注册表证明回合读取绑定修订，且不以 v2 开场或命名猜测当前地点和阶段。 */
class WorldTurnContextServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private JdbcWorldPackageRevisionRepository repository;
    private WorldPackagePublicationService publications;
    private WorldPackageAvailabilityService availability;
    private WorldPackageCandidate candidate;
    private PackageRow world;
    private RevisionRow revision;

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("world-package-test-users.sql").addScript("world-package-registry-h2.sql").build();
        jdbc = new JdbcTemplate(db);
        repository = new JdbcWorldPackageRevisionRepository(jdbc);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(db));
        publications = new WorldPackagePublicationService(repository, tx, mapper);
        availability = new WorldPackageAvailabilityService(repository, tx);
        candidate = new WorldPackageIngestionPipeline(mapper).inspectDirectory(
                Path.of("src/main/resources/world-packages/official/XUANXU/2"));
        world = publications.registerOfficial(Principal.systemPublisher(), "wenxian.official", "XUANXU");
        revision = publications.publish(world.id(), candidate, Principal.systemPublisher(), "original", true);
    }

    @AfterEach void cleanup() { db.shutdown(); }

    @Test void boundWorldLoadsWithoutAnyCreationDefaultOrInstallation() {
        var selected = service().select(projection("石门前"), "打听宗门传承");
        assertThat(repository.defaultRevision("GLOBAL")).isEmpty();
        assertThat(selected.source().revisionId()).isEqualTo(revision.id());
        assertThat(selected.source().contentHash()).isEqualTo(candidate.contentHash());
        assertThat(selected.materials()).anyMatch(m -> m.ref().equals("lore:xuanxu:inheritance"));
        assertThat(jdbc.queryForObject("select count(*) from world_package_installation", Integer.class)).isZero();
    }

    @Test void defaultChangeAndRecreatedReaderDoNotChangeExistingSaveWorld() throws Exception {
        availability.initializeDefaultIfAbsent(revision.id(), Principal.systemPublisher());
        String before = mapper.writeValueAsString(service().select(projection("石门前"), "打听传承"));
        var d = candidate.document();
        var identity = d.identity();
        var metadata = d.metadata();
        var changed = new WorldPackageDocument(new WorldPackageDocument.PackageIdentity(identity.publisherNamespace(),
                identity.packageId(), 3, identity.formatVersion(), identity.runtimeContract(), identity.requiredCapabilities()),
                new WorldPackageDocument.ContentMetadata(metadata.defaultLocale(), "第三版测试世界", metadata.summary(),
                        metadata.authors(), metadata.license(), metadata.contentRating()),
                d.world(), d.creation(), d.progression(), d.loreEntries(), d.assets());
        var canonical = new WorldPackageCanonicalizer(mapper).canonicalize(changed, new WorldPackageCompiler().compile(changed));
        var next = publications.publish(world.id(), new WorldPackageCandidate(changed,
                new WorldPackageCompiler().compile(changed), canonical.canonicalJson(), canonical.contentHash(),
                canonical.compiledJson(), canonical.compiledHash(), WorldPackageValidator.COMPILER_VERSION, List.of()),
                Principal.systemPublisher(), "next", true);
        availability.enableForCreation(next.id(), Principal.systemPublisher());
        availability.setDefault(next.id(), Principal.systemPublisher());
        assertThat(repository.defaultRevision("GLOBAL")).contains(next.id());
        assertThat(mapper.writeValueAsString(service().select(projection("石门前"), "打听传承"))).isEqualTo(before);
    }

    @Test void matchingLocationNameStillDoesNotProveCurrentRegionOrActivateItsLore() {
        // 即使显示名与开场地域相同，v2 也没有可证明的当前地域身份。
        var selected = service().select(projection("东陆青岐州"), "停下观察");
        assertThat(selected.unavailableInputs()).contains("CURRENT_REGION_UNVERIFIED_V2",
                "ENTITY_TAG_MAPPING_UNAVAILABLE_V2", "STORY_THREAD_MAPPING_UNAVAILABLE_V2");
        assertThat(selected.materials()).noneMatch(m -> m.kind().equals("REGION"));
        assertThat(selected.materials()).noneMatch(m -> m.reasons().stream().anyMatch(r -> r.startsWith("LOCATION")));
    }

    @Test void locallyDifferentCultivationDoesNotGetReplacedByPackageStartingStage() {
        var before = projection("石门前");
        var selected = service().select(before, "检视修行");
        assertThat(selected.unavailableInputs()).contains("PROGRESSION_SYSTEM_NOT_BOUND_TO_PACKAGE");
        assertThat(selected.materials()).noneMatch(m -> m.kind().equals("PROGRESSION_STAGE"));
        assertThat(before.protagonistCultivation().currentStageId()).isEqualTo("stage:test:1");
    }

    @Test void selectsSavedCurrentStageRatherThanReturningToPackageOpeningStage() {
        var before = packageProjection(1, CultivationSystem.ConfirmationSource.WORLD_PACKAGE_FIXED, 1);
        var selected = service().select(before, "检视修行");
        assertThat(selected.materials().stream().filter(m -> m.kind().equals("PROGRESSION_STAGE")))
                .singleElement().satisfies(m -> assertThat(m.ref()).isEqualTo("realm:xuanxu:human:l3"));
        assertThat(selected.unavailableInputs()).doesNotContain("CURRENT_STAGE_UNAVAILABLE");
        assertThat(before.protagonistCultivation().currentStageId()).isEqualTo("realm:xuanxu:human:l3");
    }

    @Test void matchingStageIdDoesNotProvePackageRevisionOrConfirmationSource() {
        var differentSources = List.of(
                packageProjection(2, CultivationSystem.ConfirmationSource.WORLD_PACKAGE_FIXED, 1),
                packageProjection(1, CultivationSystem.ConfirmationSource.PLAYER_CONFIRMED_EDIT, 1),
                packageProjection(1, CultivationSystem.ConfirmationSource.WORLD_PACKAGE_FIXED, 2));
        for (var before : differentSources) {
            var selected = service().select(before, "检视修行");
            assertThat(selected.materials()).noneMatch(m -> m.kind().equals("PROGRESSION_STAGE"));
            assertThat(selected.unavailableInputs()).contains("PROGRESSION_SYSTEM_NOT_BOUND_TO_PACKAGE");
            assertThat(before.protagonistCultivation().currentStageId()).isEqualTo("realm:xuanxu:human:l3");
        }
    }

    @Test void withdrawnRevisionRemainsReadableButSuspendedRevisionDoesNot() {
        jdbc.update("update world_package_revision set status='WITHDRAWN' where id=?", revision.id());
        var sameService = service();
        assertThat(sameService.select(projection("石门前"), "观察").source().revisionId()).isEqualTo(revision.id());
        jdbc.update("update world_package_revision set status='SUSPENDED' where id=?", revision.id());
        assertThatThrownBy(() -> sameService.select(projection("石门前"), "观察"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("暂停");
    }

    @Test void corruptedSnapshotDoesNotFallBackToAuthorFiles() {
        jdbc.update("update world_package_revision set compiled_json='{}' where id=?", revision.id());
        assertThatThrownBy(() -> service().select(projection("石门前"), "观察"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("快照");
    }

    private WorldTurnContextService service() {
        return new WorldTurnContextService(new WorldPackageQueryService(repository, mapper), mapper, "wenxian.official");
    }

    private StoryProjection packageProjection(int systemRevision, CultivationSystem.ConfirmationSource source,
                                              long boundScaleRevision) {
        var original = projection("石门前");
        var system = candidate.compiled().progressionSystemsById().get("realm-scale:xuanxu");
        var track = system.tracksById().get("track:xuanxu:human");
        var savedSystem = new CultivationSystem(system.systemId(), systemRevision,
                system.displayName(), "世界包固定投影", "人族", track.startingStageRef(),
                track.orderedStages().stream().map(stage -> new CultivationSystem.Stage(stage.stageId(),
                        stage.order(), stage.name(), stage.description(), stage.aliases(),
                        stage.nextStageId() == null ? List.of() : List.of(stage.nextStageId()))).toList(),
                new CultivationSystem.Confirmation(source, Instant.EPOCH));
        var c = original.creationContract();
        var creation = new CreationContract(c.worldBinding(), c.identity(), c.origin(), c.opening(),
                new CreationContract.RealmBinding(system.systemId(), boundScaleRevision, "L1", c.realmBinding().startingPhase()));
        return new StoryProjection(original.worldTime(), original.coreState(), Map.of(), Map.of(), Map.of(), List.of(),
                creation, original.playPreference(), savedSystem,
                new ProtagonistCultivation(system.systemId(), "realm:xuanxu:human:l3", ""));
    }

    private StoryProjection projection(String location) {
        return new StoryProjection(StoryWorldTime.opening(), new StoryProjection.CoreState(location, "平静", "", ""),
                Map.of(), Map.of(), Map.of(), List.of(), StoryTestFixtures.creationContract(),
                StoryTestFixtures.playPreference(), StoryTestFixtures.cultivationSystem(), StoryTestFixtures.protagonistCultivation());
    }
}
