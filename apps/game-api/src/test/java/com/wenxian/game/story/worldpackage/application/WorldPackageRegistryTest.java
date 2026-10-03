package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.application.CharacterCreationCatalogService;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.*;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.*;
import com.wenxian.game.story.worldpackage.infrastructure.WorldPackageRegistryBootstrap;
import com.wenxian.game.story.worldpackage.persistence.JdbcWorldPackageRevisionRepository;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldPackageRegistryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Principal SYSTEM = Principal.systemPublisher();
    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private JdbcWorldPackageRevisionRepository repository;
    private WorldPackagePublicationService publications;
    private WorldPackageAvailabilityService availability;
    private WorldPackageInstallationService installations;
    private WorldPackageQueryService queries;
    private WorldPackageCandidate official;
    private PackageRow p;

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("world-package-test-users.sql").addScript("world-package-registry-h2.sql").build();
        jdbc = new JdbcTemplate(db); tx = new TransactionTemplate(new DataSourceTransactionManager(db));
        repository = spy(new JdbcWorldPackageRevisionRepository(jdbc));
        publications = new WorldPackagePublicationService(repository, tx, mapper);
        availability = new WorldPackageAvailabilityService(repository, tx);
        installations = new WorldPackageInstallationService(repository, tx);
        queries = new WorldPackageQueryService(repository, mapper);
        official = new WorldPackageIngestionPipeline(mapper).inspectDirectory(Path.of("src/main/resources/world-packages/official/XUANXU/2"));
        p = publications.registerOfficial(SYSTEM, "wenxian.official", "XUANXU");
        jdbc.update("insert into app_user(id) values('alice'),('bob')");
    }
    @AfterEach void cleanup() { db.shutdown(); }
    private RevisionRow publish() { return publications.publish(p.id(), official, SYSTEM, "release-2", true); }
    private void activate(RevisionRow r) {
        availability.prepareScope(SYSTEM); availability.enableForCreation(r.id(), SYSTEM); availability.setDefault(r.id(), SYSTEM);
    }
    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }

    @Test void exactPublicationIsDurablyIdempotentAndDoesNotEnable() {
        var r = publish();
        assertThat(publish()).isEqualTo(r);
        assertThat(new WorldPackagePublicationService(repository, tx, mapper).publish(p.id(), official, SYSTEM, "release-2", true)).isEqualTo(r);
        assertThat(count("world_package_revision")).isEqualTo(1);
        assertThat(count("world_package_publication_request")).isEqualTo(1);
        assertThat(repository.enabled("GLOBAL")).isEmpty();
        assertThatThrownBy(() -> queries.defaultForCreation(null)).hasMessageContaining("未配置");
    }

    @Test void refusesConflictingRevisionHashAndReusedRequestKey() {
        publish(); var changed = change(official, p, 2, "不同内容");
        assertThatThrownBy(() -> publications.publish(p.id(), changed, SYSTEM, "release-2", true)).hasMessageContaining("幂等键");
        assertThatThrownBy(() -> publications.publish(p.id(), changed, SYSTEM, "other-key", true)).hasMessageContaining("禁止覆盖");
        var same = change(official, p, 3, official.document().metadata().name());
        assertThatThrownBy(() -> publications.publish(p.id(), same, SYSTEM, "release-3", true)).hasMessageContaining("相同内容");
        assertThat(count("world_package_revision")).isEqualTo(1);
        assertThat(count("world_package_publication_request")).isEqualTo(1);
    }

    @Test void concurrentAuthorPublicationsAllocateDistinctNumbersAndRecompileIdentity() throws Exception {
        var actor = Principal.player("alice"); var owned = publications.registerPlayer(actor);
        var barrier = new CyclicBarrier(6);
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Future<RevisionRow>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                final int n = i;
                futures.add(executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return publications.publish(owned.id(), change(official, owned, 999, "世界" + n), actor, "request-" + n, false); }));
            }
            Set<Long> numbers = new HashSet<>();
            for (var f : futures) {
                var r = f.get(20, TimeUnit.SECONDS); numbers.add(r.revision());
                assertThat(queries.accessible("alice", r.id()).compiled().identity().revision()).isEqualTo(r.revision());
            }
            assertThat(numbers).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L);
        }
        assertThat(repository.packageById(owned.id(), false).latestRevision()).isEqualTo(6);
    }

    @Test void concurrentRetriesProduceOneRevision() throws Exception {
        var barrier = new CyclicBarrier(4);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Future<RevisionRow>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return publish(); }));
            Set<String> ids = new HashSet<>();
            for (var f : futures) ids.add(f.get(20, TimeUnit.SECONDS).id());
            assertThat(ids).hasSize(1);
        }
        assertThat(count("world_package_revision")).isEqualTo(1);
    }

    @Test void sameAuthorContentDoesNotConsumeAnotherNumber() {
        var actor = Principal.player("alice"); var owned = publications.registerPlayer(actor);
        var c = change(official, owned, 42, "自定义");
        var first = publications.publish(owned.id(), c, actor, "first", false);
        var second = publications.publish(owned.id(), c, actor, "second", false);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(repository.packageById(owned.id(), false).latestRevision()).isEqualTo(1);
    }

    @Test void packageAndPublicationPermissionsDoNotTrustBundleClaims() {
        var alice = Principal.player("alice"); var owned = publications.registerPlayer(alice);
        assertThatThrownBy(() -> publications.registerOfficial(alice, "wenxian.official", "XUANXU")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> publications.publish(p.id(), official, alice, "spoof", false)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> publications.publish(owned.id(), change(official, owned, 1, "玩家世界"), Principal.player("bob"), "stolen", false)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> publications.publish(owned.id(), change(official, owned, 1, "玩家世界"), alice, "exact", true)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> publications.publish(owned.id(), official, alice, "wrong-identity", false)).hasMessageContaining("身份");
    }

    @Test void installationAvailabilityAndDefaultAreIndependent() {
        var r = publish(); var alice = Principal.player("alice");
        installations.installForUser(r.id(), alice); installations.installForUser(r.id(), alice);
        assertThat(count("world_package_installation")).isEqualTo(1);
        assertThatThrownBy(() -> queries.forCreation("alice", r.id())).hasMessageContaining("尚未启用");
        availability.prepareScope(alice);
        assertThatThrownBy(() -> availability.setDefault(r.id(), alice)).hasMessageContaining("同一范围启用");
        availability.enableForCreation(r.id(), alice);
        assertThat(repository.defaultRevision("USER:alice")).isEmpty();
        availability.setDefault(r.id(), alice);
        assertThat(queries.defaultForCreation("alice").revision().id()).isEqualTo(r.id());
        assertThat(queries.listForCreation("bob")).isEmpty();
        assertThat(repository.defaultRevision("GLOBAL")).isEmpty();
    }

    @Test void defaultSwitchIsExplicitAndCannotPointToDisabledRevision() {
        var first = publish(); activate(first);
        var next = publications.publish(p.id(), change(official, p, 3, "第三修订"), SYSTEM, "release-3", true);
        assertThat(queries.defaultForCreation(null).revision().id()).isEqualTo(first.id());
        assertThatThrownBy(() -> availability.setDefault(next.id(), SYSTEM)).hasMessageContaining("同一范围启用");
        assertThatThrownBy(() -> availability.disableForCreation(first.id(), SYSTEM)).hasMessageContaining("清空或切换");
        assertThatThrownBy(() -> jdbc.update("update world_package_creation_default set revision_id=? where scope_id='GLOBAL'", next.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        availability.enableForCreation(next.id(), SYSTEM); availability.setDefault(next.id(), SYSTEM); availability.disableForCreation(first.id(), SYSTEM);
        assertThat(new WorldPackageQueryService(repository, mapper).defaultForCreation(null).revision().id()).isEqualTo(next.id());
        assertThat(new CharacterCreationCatalogService(queries, "wenxian.official").current().world().revision()).isEqualTo(3);
        assertThatThrownBy(() -> new CharacterCreationCatalogService(queries, "wenxian.official").resolveForCreation("alice", "XUANXU", 2)).hasMessageContaining("尚未启用");
        assertThat(queries.boundRevision(first.id()).compiled().identity().revision()).isEqualTo(2);
    }

    @Test void concurrentDefaultSwitchesKeepOneEnabledPointerAndAuditBothChanges() throws Exception {
        var first = publish(); activate(first);
        var otherPackage = publications.registerOfficial(SYSTEM, "wenxian.official", "OTHER");
        var second = publications.publish(otherPackage.id(), change(official, otherPackage, 1, "另一世界"), SYSTEM, "other", true);
        availability.enableForCreation(second.id(), SYSTEM);
        long audits = count("world_package_audit");
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); availability.setDefault(first.id(), SYSTEM); return true; });
            var b = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); availability.setDefault(second.id(), SYSTEM); return true; });
            a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
        }
        assertThat(count("world_package_creation_default")).isEqualTo(1);
        assertThat(repository.enabled("GLOBAL")).contains(repository.defaultRevision("GLOBAL").orElseThrow());
        assertThat(count("world_package_audit")).isEqualTo(audits + 2);
    }

    @Test void creationLockRejectsRevisionDisabledAfterCatalogRead() {
        var r = publish(); activate(r); queries.defaultForCreation(null);
        availability.setDefault(null, SYSTEM); availability.disableForCreation(r.id(), SYSTEM);
        clearInvocations(repository);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> queries.lockForCreation("alice", r.id())))
                .hasMessageContaining("尚未启用");
        verify(repository).lockRevision(r.id());
        verify(repository).lockEnabled("GLOBAL");
    }

    @Test void cacheCannotGrantPrivateAccessOrBypassSuspension() {
        var alice = Principal.player("alice"); var owned = publications.registerPlayer(alice);
        var r = publications.publish(owned.id(), change(official, owned, 1, "私有世界"), alice, "private", false);
        assertThat(queries.accessible("alice", r.id()).compiled()).isNotNull();
        assertThatThrownBy(() -> queries.accessible("bob", r.id())).isInstanceOf(SecurityException.class);
        availability.prepareScope(SYSTEM);
        assertThatThrownBy(() -> availability.enableForCreation(r.id(), SYSTEM)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> installations.installForUser(r.id(), Principal.player("bob"))).isInstanceOf(SecurityException.class);
        jdbc.update("update world_package set status='SUSPENDED' where id=?", owned.id());
        assertThatThrownBy(() -> queries.accessible("alice", r.id())).hasMessageContaining("暂停");
    }

    @Test void withdrawalPreservesBoundContentButStopsCreation() {
        var r = publish(); activate(r); queries.defaultForCreation(null);
        jdbc.update("update world_package_revision set status='WITHDRAWN' where id=?", r.id());
        assertThat(queries.boundRevision(r.id()).compiled().identity().revision()).isEqualTo(2);
        assertThatThrownBy(() -> queries.forCreation("alice", r.id())).hasMessageContaining("停止创角");
        assertThat(queries.listForCreation("alice")).isEmpty();
    }

    @Test void assetAndRevisionFailureRollBackTogetherAndRetryKeepsNumber() {
        var withAsset = withAsset(official);
        doAnswer(call -> { call.callRealMethod(); throw new DataAccessResourceFailureException("injected after asset persistence"); })
                .when(repository).insertRevision(anyString(), anyString(), any(), anyString());
        assertThatThrownBy(() -> publications.publish(p.id(), withAsset, SYSTEM, "asset", true)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count("world_package_revision")).isZero();
        assertThat(count("world_package_asset")).isZero();
        assertThat(count("world_package_publication_request")).isZero();
        assertThat(repository.packageById(p.id(), false).latestRevision()).isZero();
        doCallRealMethod().when(repository).insertRevision(anyString(), anyString(), any(), anyString());
        var r = publications.publish(p.id(), withAsset, SYSTEM, "asset", true);
        assertThat(queries.boundRevision(r.id()).compiled().assets()).hasSize(1);
        jdbc.update("update world_package_asset set asset_bytes=? where revision_id=?", new byte[]{1}, r.id());
        assertThat(new WorldPackageQueryService(repository, mapper).boundRevision(r.id()).compiled().assets()).hasSize(1);
        assertThatThrownBy(() -> new WorldPackageIntegrityService(repository, mapper).verify(r.id())).hasMessageContaining("原件损坏");
    }

    @Test void auditFailureRollsBackDefaultSwitch() {
        var r = publish(); activate(r);
        doThrow(new DataAccessResourceFailureException("audit unavailable")).when(repository).audit(anyString(), anyString(), anyString(), isNull());
        assertThatThrownBy(() -> availability.setDefault(null, SYSTEM)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(repository.defaultRevision("GLOBAL")).contains(r.id());
    }

    @Test void databaseFailureDoesNotFallBackToCacheOrClasspath() {
        var r = publish(); activate(r); queries.defaultForCreation(null);
        doThrow(new DataAccessResourceFailureException("database unavailable")).when(repository).revision(r.id());
        assertThatThrownBy(() -> queries.defaultForCreation(null)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test void restartChecksCompiledIntegrityAndPortableIdentity() {
        var r = publish(); assertThat(new WorldPackageQueryService(repository, mapper).boundRevision(r.id()).compiled()).isNotNull();
        jdbc.update("update world_package_revision set compiled_json='{}' where id=?", r.id());
        assertThatThrownBy(() -> new WorldPackageQueryService(repository, mapper).boundRevision(r.id())).hasMessageContaining("快照");
    }

    @Test void accountDeletionRemovesInstallationAndScopeWithoutDeletingPublishedContent() {
        var actor = Principal.player("alice"); var owned = publications.registerPlayer(actor);
        var r = publications.publish(owned.id(), change(official, owned, 1, "个人世界"), actor, "owned", false);
        installations.installForUser(r.id(), actor); availability.prepareScope(actor); availability.enableForCreation(r.id(), actor); availability.setDefault(r.id(), actor);
        jdbc.update("delete from app_user where id='alice'");
        assertThat(repository.packageById(owned.id(), false).ownerUserId()).isNull();
        assertThat(count("world_package_installation")).isZero(); assertThat(count("world_package_creation_scope")).isZero();
        assertThat(count("world_package_creation_default")).isZero(); assertThat(count("world_package_revision")).isEqualTo(1);
        assertThatThrownBy(() -> publications.publish(owned.id(), change(official, owned, 2, "失去所有者"), actor, "orphan", false)).isInstanceOf(SecurityException.class);
        assertThat(jdbc.queryForObject("select actor_key from world_package_audit limit 1", String.class)).doesNotContain("alice");
    }

    @Test void heterogeneousPackageUsesSameRegistryAndRequiresV3OnlyAtLegacyBoundary() {
        var actor = Principal.player("alice"); var owned = publications.registerPlayer(actor);
        var dream = new WorldPackageIngestionPipeline(mapper).inspectDirectory(Path.of("src/test/resources/world-packages/fixtures/DREAMSEA/1"));
        var r = publications.publish(owned.id(), change(dream, owned, 1, "梦海"), actor, "dream", false);
        installations.installForUser(r.id(), actor); availability.prepareScope(actor); availability.enableForCreation(r.id(), actor); availability.setDefault(r.id(), actor);
        var compiled = queries.defaultForCreation("alice").compiled();
        assertThat(compiled.racesById()).hasSize(2);
        assertThatThrownBy(() -> new LegacyWorldPackageAdapter().adapt(compiled)).hasMessageContaining("v3");
    }

    @Test void preflightIsReadOnlyAndRestartDoesNotUndoExplicitDisable() {
        var env = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:h2:mem:local"); env.setActiveProfiles("test");
        var release = new OfficialWorldPackageRelease(publications, mapper, "world-packages/official/XUANXU/2/");
        var bootstrap = new WorldPackageRegistryBootstrap(env, release, availability, queries, true);
        bootstrap.run(new DefaultApplicationArguments());
        long audits = count("world_package_audit"), requests = count("world_package_publication_request");
        bootstrap.preflight(); bootstrap.run(new DefaultApplicationArguments());
        assertThat(count("world_package_audit")).isEqualTo(audits); assertThat(count("world_package_publication_request")).isEqualTo(requests);
        var r = queries.defaultForCreation(null).revision(); availability.setDefault(null, SYSTEM); availability.disableForCreation(r.id(), SYSTEM);
        assertThatCode(() -> bootstrap.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThatThrownBy(() -> queries.defaultForCreation(null)).hasMessageContaining("未配置");
        assertThat(repository.enabled("GLOBAL")).isEmpty();
    }

    @Test void startupAutoPublishRejectsProductionMixedProfilesAndRemoteDatabase() {
        assertThatThrownBy(() -> WorldPackageRegistryBootstrap.assertLocalAutoPublication(new String[]{"dev", "prod"}, "jdbc:h2:mem:local")).hasMessageContaining("禁止");
        assertThatThrownBy(() -> WorldPackageRegistryBootstrap.assertLocalAutoPublication(new String[]{"test"}, "jdbc:mysql://shared.example:3306/world")).hasMessageContaining("禁止");
        assertThatThrownBy(() -> WorldPackageRegistryBootstrap.assertLocalAutoPublication(new String[]{}, "jdbc:h2:mem:local")).hasMessageContaining("禁止");
        assertThatCode(() -> WorldPackageRegistryBootstrap.assertLocalAutoPublication(new String[]{"dev"}, "jdbc:mysql://127.0.0.1:3306/world")).doesNotThrowAnyException();
    }

    private WorldPackageCandidate change(WorldPackageCandidate source, PackageRow target, long revision, String name) {
        var d = source.document(); var i = d.identity(); var m = d.metadata();
        var changed = new WorldPackageDocument(new PackageIdentity(target.namespace(), target.packageKey(), revision, i.formatVersion(), i.runtimeContract(), i.requiredCapabilities()),
                new ContentMetadata(m.defaultLocale(), name, m.summary(), m.authors(), m.license(), m.contentRating()), d.world(), d.creation(), d.progression(), d.loreEntries(), d.assets());
        return candidate(changed, source.assets());
    }
    private WorldPackageCandidate candidate(WorldPackageDocument d, List<BundleAsset> assets) {
        var compiled = new WorldPackageCompiler().compile(d); var c = new WorldPackageCanonicalizer(mapper).canonicalize(d, compiled);
        return new WorldPackageCandidate(d, compiled, c.canonicalJson(), c.contentHash(), c.compiledJson(), c.compiledHash(), WorldPackageValidator.COMPILER_VERSION, assets);
    }
    private WorldPackageCandidate withAsset(WorldPackageCandidate source) {
        byte[] bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jF5sAAAAASUVORK5CYII=");
        var descriptor = new AssetDescriptor("assets/cover.png", "COVER", "image/png", bytes.length, WorldPackageCanonicalizer.sha256(bytes));
        var d = source.document(); return candidate(new WorldPackageDocument(d.identity(), d.metadata(), d.world(), d.creation(), d.progression(), d.loreEntries(), List.of(descriptor)), List.of(new BundleAsset(descriptor, bytes)));
    }
}
