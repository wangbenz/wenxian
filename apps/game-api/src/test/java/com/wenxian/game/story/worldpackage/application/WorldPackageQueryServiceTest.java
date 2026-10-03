package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.*;
import com.wenxian.game.story.worldpackage.persistence.JdbcWorldPackageRevisionRepository;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorldPackageQueryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private JdbcWorldPackageRevisionRepository repository;
    private WorldPackagePublicationService publications;
    private WorldPackageCandidate candidate;
    private PackageRow worldPackage;
    private RevisionRow first;

    @BeforeEach void setup() {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("world-package-test-users.sql").addScript("world-package-registry-h2.sql").build();
        jdbc = new JdbcTemplate(db);
        repository = spy(new JdbcWorldPackageRevisionRepository(jdbc));
        publications = new WorldPackagePublicationService(repository,
                new TransactionTemplate(new DataSourceTransactionManager(db)), mapper);
        candidate = new WorldPackageIngestionPipeline(mapper).inspectDirectory(
                Path.of("src/main/resources/world-packages/official/XUANXU/2"));
        worldPackage = publications.registerOfficial(Principal.systemPublisher(), "wenxian.official", "XUANXU");
        first = publications.publish(worldPackage.id(), candidate, Principal.systemPublisher(), "first", true);
        clearInvocations(repository);
    }

    @AfterEach void cleanup() { db.shutdown(); }

    @Test void runtimeReadsOriginalSnapshotWithoutAuthorDocumentOrAssets() {
        jdbc.update("update world_package_revision set canonical_json='broken author copy', compiler_version='historical-compiler' where id=?", first.id());
        doThrow(new AssertionError("runtime must not fetch canonical source")).when(repository).content(anyString());
        doThrow(new AssertionError("runtime must not fetch binary assets")).when(repository).assets(anyString());
        var queries = new WorldPackageQueryService(repository, mapper);
        assertThat(queries.boundRevision(first.id()).compiled().metadata().name()).isEqualTo(candidate.compiled().metadata().name());
        assertThat(queries.boundRevision(first.id()).compiled().identity().revision()).isEqualTo(2);
        verify(repository, times(1)).compiledSnapshot(first.id());
    }

    @Test void fullInspectionStillDetectsAuthorDocumentDamageWithoutDependingOnDefault() {
        var integrity = new WorldPackageIntegrityService(repository, mapper);
        assertThat(integrity.verify(first.id()).revision().id()).isEqualTo(first.id());
        assertThat(repository.defaultRevision("GLOBAL")).isEmpty();
        jdbc.update("update world_package_revision set canonical_json='{}' where id=?", first.id());
        assertThatThrownBy(() -> integrity.verify(first.id())).isInstanceOf(RuntimeException.class);
    }

    @Test void fullInspectionDetectsValidButDifferentSnapshotContent() throws Exception {
        var json = mapper.readTree(candidate.compiledJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).put("premise", "与发布原文不同的世界前提");
        String changed = mapper.writeValueAsString(json);
        jdbc.update("update world_package_revision set compiled_json=?, compiled_hash=? where id=?",
                changed, WorldPackageCanonicalizer.sha256(changed), first.id());
        assertThatThrownBy(() -> new WorldPackageIntegrityService(repository, mapper).verify(first.id()))
                .hasMessageContaining("规范内容与运行快照不一致");
    }

    @Test void cachedContentCannotHideBindingHeaderOrCapabilityCorruption() {
        var queries = new WorldPackageQueryService(repository, mapper);
        queries.boundRevision(first.id());
        jdbc.update("update world_package_revision set required_capabilities_json='[]' where id=?", first.id());
        assertThatThrownBy(() -> queries.boundRevision(first.id())).isInstanceOf(IllegalStateException.class);
        jdbc.update("update world_package_revision set required_capabilities_json=?, revision=99 where id=?",
                first.requiredCapabilitiesJson(), first.id());
        assertThatThrownBy(() -> queries.boundRevision(first.id())).isInstanceOf(IllegalStateException.class);
    }

    @Test void failedLoadIsNotCachedAndRetryCanReadRestoredSnapshot() {
        var queries = new WorldPackageQueryService(repository, mapper);
        doThrow(new DataAccessResourceFailureException("temporary snapshot read failure"))
                .doCallRealMethod().when(repository).compiledSnapshot(first.id());
        assertThatThrownBy(() -> queries.boundRevision(first.id())).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(queries.boundRevision(first.id()).compiled()).isNotNull();
        verify(repository, times(2)).compiledSnapshot(first.id());
    }

    @Test void byteBudgetEvictsSnapshotsEvenWhenEntryBudgetHasRoom() {
        var second = publishSecond();
        long bytes = Math.max(candidate.compiledJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                repository.compiledSnapshot(second.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        var queries = new WorldPackageQueryService(repository, mapper, 128, bytes);
        clearInvocations(repository);
        queries.boundRevision(first.id());
        queries.boundRevision(second.id());
        queries.boundRevision(first.id());
        verify(repository, times(2)).compiledSnapshot(first.id());
    }

    @Test void entryBudgetEvictsSnapshotsEvenWhenByteBudgetHasRoom() {
        var second = publishSecond();
        var queries = new WorldPackageQueryService(repository, mapper, 1, 64L * 1024 * 1024);
        queries.boundRevision(first.id());
        queries.boundRevision(second.id());
        queries.boundRevision(first.id());
        verify(repository, times(2)).compiledSnapshot(first.id());
    }

    @Test void blockedColdLoadDoesNotBlockAnotherRevisionAndSameKeySharesResult() throws Exception {
        var second = publishSecond();
        var queries = new WorldPackageQueryService(repository, mapper);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), joined = new CountDownLatch(1);
        doAnswer(call -> {
            started.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("snapshot load was not released");
            return call.callRealMethod();
        }).when(repository).compiledSnapshot(first.id());
        try (var executor = Executors.newFixedThreadPool(3)) {
            var a = executor.submit(() -> queries.boundRevision(first.id()));
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                var b = executor.submit(() -> { joined.countDown(); return queries.boundRevision(first.id()); });
                assertThat(joined.await(5, TimeUnit.SECONDS)).isTrue();
                var other = executor.submit(() -> queries.boundRevision(second.id()));
                assertThat(other.get(5, TimeUnit.SECONDS).revision().id()).isEqualTo(second.id());
                release.countDown();
                assertThat(a.get(5, TimeUnit.SECONDS).compiled()).isSameAs(b.get(5, TimeUnit.SECONDS).compiled());
                verify(repository, times(1)).compiledSnapshot(first.id());
            } finally { release.countDown(); }
        }
    }

    private RevisionRow publishSecond() {
        var d = candidate.document();
        var i = d.identity();
        var m = d.metadata();
        var changed = new WorldPackageDocument(
                new WorldPackageDocument.PackageIdentity(i.publisherNamespace(), i.packageId(), 3,
                        i.formatVersion(), i.runtimeContract(), i.requiredCapabilities()),
                new WorldPackageDocument.ContentMetadata(m.defaultLocale(), "玄墟界新修订", m.summary(),
                        m.authors(), m.license(), m.contentRating()), d.world(), d.creation(), d.progression(), d.loreEntries(), d.assets());
        var compiled = new WorldPackageCompiler().compile(changed);
        var canonical = new WorldPackageCanonicalizer(mapper).canonicalize(changed, compiled);
        var next = new WorldPackageCandidate(changed, compiled, canonical.canonicalJson(), canonical.contentHash(),
                canonical.compiledJson(), canonical.compiledHash(), WorldPackageValidator.COMPILER_VERSION, List.of());
        return publications.publish(worldPackage.id(), next, Principal.systemPublisher(), "second", true);
    }
}
