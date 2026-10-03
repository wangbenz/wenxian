package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.application.CharacterCreationCatalogService;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.ContentMetadata;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument.PackageIdentity;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.PackageRow;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.Principal;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.RevisionRow;
import com.wenxian.game.story.worldpackage.infrastructure.WorldPackageRegistryBootstrap;
import com.wenxian.game.story.worldpackage.persistence.JdbcWorldPackageRevisionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.stubbing.Answer;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

/** 使用独立 H2 注册表验证初始化原子性和启动故障范围，不连接外部数据库。 */
class WorldPackageRegistryBootstrapTest {
    private static final Principal SYSTEM = Principal.systemPublisher();
    private final ObjectMapper mapper = new ObjectMapper();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private JdbcWorldPackageRevisionRepository repository;
    private WorldPackagePublicationService publications;
    private WorldPackageAvailabilityService availability;
    private WorldPackageCandidate official;
    private PackageRow world;
    private RevisionRow published;

    @BeforeEach
    void setup() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("world-package-test-users.sql").addScript("world-package-registry-h2.sql").build();
        jdbc = new JdbcTemplate(database);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        repository = spy(new JdbcWorldPackageRevisionRepository(jdbc));
        publications = new WorldPackagePublicationService(repository, transactions, mapper);
        availability = new WorldPackageAvailabilityService(repository, transactions);
        official = new WorldPackageIngestionPipeline(mapper)
                .inspectDirectory(Path.of("src/main/resources/world-packages/official/XUANXU/2"));
        world = publications.registerOfficial(SYSTEM, "wenxian.official", "XUANXU");
        published = publications.publish(world.id(), official, SYSTEM, "initial-publication", true);
    }

    @AfterEach
    void cleanup() {
        database.shutdown();
    }

    @Test
    void firstInitializationCommitsScopeAvailabilityDefaultAndAuditTogether() {
        assertThat(availability.initializeDefaultIfAbsent(published.id(), SYSTEM)).isTrue();
        assertInitialized(published.id());
        assertThat(availability.initializeDefaultIfAbsent(published.id(), SYSTEM)).isFalse();
        assertInitialized(published.id());
    }

    @ParameterizedTest
    @EnumSource(WriteBoundary.class)
    void everyInitializationWriteFailureRollsBackAndAllowsRetry(WriteBoundary boundary) {
        injectFailureAfter(boundary);

        assertThatThrownBy(() -> availability.initializeDefaultIfAbsent(published.id(), SYSTEM))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count("world_package_creation_scope")).isZero();
        assertThat(count("world_package_availability")).isZero();
        assertThat(count("world_package_creation_default")).isZero();
        assertThat(count("world_package_audit")).isZero();
        assertThat(count("world_package_revision")).isEqualTo(1);
        assertThat(count("world_package_publication_request")).isEqualTo(1);

        reset(repository);
        assertThat(availability.initializeDefaultIfAbsent(published.id(), SYSTEM)).isTrue();
        assertInitialized(published.id());
    }

    @Test
    void initializationDoesNotJoinCallerTransactionMarkedForRollback() {
        transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            assertThat(availability.initializeDefaultIfAbsent(published.id(), SYSTEM)).isTrue();
        });
        assertInitialized(published.id());
    }

    @Test
    void concurrentDifferentPackagesInitializeOnlyOneGlobalDefault() throws Exception {
        var secondWorld = publications.registerOfficial(SYSTEM, "wenxian.official", "SECOND_WORLD");
        var second = publications.publish(secondWorld.id(), candidateFor(official, secondWorld, 2, "另一测试世界"),
                SYSTEM, "second-world", true);
        var bothObservedNoScope = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Optional<?> result = (Optional<?>) invocation.callRealMethod();
            if (result.isEmpty()) bothObservedNoScope.await(10, TimeUnit.SECONDS);
            return result;
        }).when(repository).lockScopeIfPresent("GLOBAL");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> availability.initializeDefaultIfAbsent(published.id(), SYSTEM));
            var secondResult = executor.submit(() -> availability.initializeDefaultIfAbsent(second.id(), SYSTEM));
            assertThat(java.util.List.of(firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        String selected = repository.defaultRevision("GLOBAL").orElseThrow();
        assertThat(selected).isIn(published.id(), second.id());
        assertInitialized(selected);
    }

    @Test
    void existingEmptyScopeIsNotAssumedToBeAnInterruptedInitialization() {
        availability.prepareScope(SYSTEM);

        assertThat(availability.initializeDefaultIfAbsent(published.id(), SYSTEM)).isFalse();
        assertThat(repository.enabled("GLOBAL")).isEmpty();
        assertThat(repository.defaultRevision("GLOBAL")).isEmpty();
        assertThat(count("world_package_audit")).isZero();
    }

    @Test
    void playerCannotUseSystemInitialization() {
        assertThatThrownBy(() -> availability.initializeDefaultIfAbsent(published.id(), Principal.player("alice")))
                .isInstanceOf(SecurityException.class);
        assertThat(count("world_package_creation_scope")).isZero();
    }

    @Test
    void autoPublicationRetryDoesNotRestoreExplicitlyClearedDefaultOrDisabledRevision() {
        var release = new OfficialWorldPackageRelease(publications, mapper, "world-packages/official/XUANXU/2/");
        var queries = queries();
        var bootstrap = bootstrap(localEnvironment(), release, queries, true);
        bootstrap.run(new DefaultApplicationArguments());
        availability.setDefault(null, SYSTEM);
        availability.disableForCreation(published.id(), SYSTEM);
        long auditCount = count("world_package_audit");
        long requestCount = count("world_package_publication_request");

        assertThatCode(() -> bootstrap.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThat(repository.defaultRevision("GLOBAL")).isEmpty();
        assertThat(repository.enabled("GLOBAL")).isEmpty();
        assertThat(count("world_package_audit")).isEqualTo(auditCount);
        assertThat(count("world_package_publication_request")).isEqualTo(requestCount);
        assertThatThrownBy(bootstrap::preflight).hasMessageContaining("未配置");
        assertThatThrownBy(() -> queries.defaultForCreation(null)).hasMessageContaining("未配置");
        assertThat(queries.boundRevision(published.id()).revision().id()).isEqualTo(published.id());
    }

    @Test
    void failedAutoInitializationIsNotSwallowedAndReusesPublishedRevisionOnRetry() {
        var release = new OfficialWorldPackageRelease(publications, mapper, "world-packages/official/XUANXU/2/");
        var bootstrap = bootstrap(localEnvironment(), release, queries(), true);
        injectFailureAfter(WriteBoundary.DEFAULT_AUDIT);

        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count("world_package_creation_scope")).isZero();
        assertThat(count("world_package_audit")).isZero();
        assertThat(count("world_package_revision")).isEqualTo(1);
        long requestCount = count("world_package_publication_request");

        reset(repository);
        assertThatCode(() -> bootstrap.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertInitialized(published.id());
        assertThat(count("world_package_revision")).isEqualTo(1);
        assertThat(count("world_package_publication_request")).isEqualTo(requestCount);
    }

    @Test
    void independentDatabaseDefaultSurvivesRestartWithoutReadingPackagedArtifact() {
        availability.initializeDefaultIfAbsent(published.id(), SYSTEM);
        var next = publications.publish(world.id(), candidateFor(official, world, 3, "玄墟界新版测试"),
                SYSTEM, "next-revision", true);
        availability.enableForCreation(next.id(), SYSTEM);
        availability.setDefault(next.id(), SYSTEM);
        var release = mock(OfficialWorldPackageRelease.class);
        var queries = queries();
        var restarted = bootstrap(new MockEnvironment(), release, queries, false);
        long audits = count("world_package_audit");

        assertThatCode(restarted::preflight).doesNotThrowAnyException();
        assertThatCode(() -> restarted.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThat(queries.defaultForCreation(null).revision().id()).isEqualTo(next.id());
        assertThat(queries.boundRevision(published.id()).revision().id()).isEqualTo(published.id());
        assertThat(count("world_package_audit")).isEqualTo(audits);
        verifyNoInteractions(release);
    }

    @Test
    void missingDefaultDoesNotAbortNormalStartupAndRemainsAnErrorForCreation() {
        var release = mock(OfficialWorldPackageRelease.class);
        var queries = queries();
        var bootstrap = bootstrap(new MockEnvironment(), release, queries, false);

        assertThatCode(() -> bootstrap.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThatThrownBy(bootstrap::preflight).hasMessageContaining("未配置");
        assertThatThrownBy(() -> new CharacterCreationCatalogService(queries, "wenxian.official").current())
                .hasMessageContaining("未配置");
        assertThat(queries.boundRevision(published.id()).revision().id()).isEqualTo(published.id());
        assertThat(count("world_package_creation_scope")).isZero();
        assertThat(count("world_package_audit")).isZero();
        verifyNoInteractions(release);
    }

    @Test
    void corruptDefaultDoesNotAbortStartupOrBreakAnotherRevision() {
        var corrupt = publications.publish(world.id(), candidateFor(official, world, 3, "损坏默认测试"),
                SYSTEM, "corrupt-default", true);
        availability.initializeDefaultIfAbsent(corrupt.id(), SYSTEM);
        jdbc.update("update world_package_revision set compiled_json='{}' where id=?", corrupt.id());
        var release = mock(OfficialWorldPackageRelease.class);
        var queries = queries();
        var bootstrap = bootstrap(new MockEnvironment(), release, queries, false);
        long audits = count("world_package_audit");

        assertThatCode(() -> bootstrap.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThatThrownBy(bootstrap::preflight).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> queries.defaultForCreation(null)).isInstanceOf(IllegalStateException.class);
        assertThat(queries.boundRevision(published.id()).revision().id()).isEqualTo(published.id());
        assertThat(count("world_package_audit")).isEqualTo(audits);
        verifyNoInteractions(release);
    }

    @Test
    void v2CompatibilityIsCheckedWithoutRestrictingDefaultToAnOfficialArtifact() {
        var dream = new WorldPackageIngestionPipeline(mapper)
                .inspectDirectory(Path.of("src/test/resources/world-packages/fixtures/DREAMSEA/1"));
        var dreamWorld = publications.registerOfficial(SYSTEM, "wenxian.official", "DREAMSEA_TEST");
        var revision = publications.publish(dreamWorld.id(), candidateFor(dream, dreamWorld, 1, "异构默认测试"),
                SYSTEM, "dream-default", true);
        availability.initializeDefaultIfAbsent(revision.id(), SYSTEM);
        var release = mock(OfficialWorldPackageRelease.class);
        var queries = queries();
        var bootstrap = bootstrap(new MockEnvironment(), release, queries, false);

        assertThatCode(() -> bootstrap.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThatThrownBy(bootstrap::preflight).hasMessageContaining("v3");
        assertThatThrownBy(() -> new CharacterCreationCatalogService(queries, "wenxian.official").current())
                .hasMessageContaining("v3");
        verifyNoInteractions(release);
    }

    @Test
    void remoteOrProductionAutoPublicationStillAbortsBeforeWritingOrReadingArtifacts() {
        var release = mock(OfficialWorldPackageRelease.class);
        var remote = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:mysql://shared.example:3306/world");
        remote.setActiveProfiles("test");
        assertThatThrownBy(() -> bootstrap(remote, release, queries(), true).run(new DefaultApplicationArguments()))
                .hasMessageContaining("禁止");
        var mixed = localEnvironment();
        mixed.setActiveProfiles("dev", "prod");
        assertThatThrownBy(() -> bootstrap(mixed, release, queries(), true).run(new DefaultApplicationArguments()))
                .hasMessageContaining("禁止");
        assertThat(count("world_package_creation_scope")).isZero();
        assertThat(count("world_package_audit")).isZero();
        verifyNoInteractions(release);
    }

    private void assertInitialized(String revisionId) {
        assertThat(count("world_package_creation_scope")).isEqualTo(1);
        assertThat(repository.enabled("GLOBAL")).containsExactly(revisionId);
        assertThat(repository.defaultRevision("GLOBAL")).contains(revisionId);
        assertThat(count("world_package_audit")).isEqualTo(2);
        assertThat(jdbc.queryForList("select action from world_package_audit", String.class))
                .containsExactlyInAnyOrder("ENABLE", "DEFAULT");
    }

    private void injectFailureAfter(WriteBoundary point) {
        Answer<Void> failure = invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("初始化写入故障注入");
        };
        switch (point) {
            case SCOPE -> doAnswer(failure).when(repository).ensureScope(any());
            case ENABLE -> doAnswer(failure).when(repository).enable(anyString(), anyString());
            case ENABLE_AUDIT -> doAnswer(failure).when(repository).audit(anyString(), eq("ENABLE"), anyString(), anyString());
            case DEFAULT -> doAnswer(failure).when(repository).setDefault(anyString(), anyString());
            case DEFAULT_AUDIT -> doAnswer(failure).when(repository).audit(anyString(), eq("DEFAULT"), anyString(), anyString());
        }
    }

    private MockEnvironment localEnvironment() {
        var environment = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:h2:mem:local");
        environment.setActiveProfiles("test");
        return environment;
    }

    private WorldPackageQueryService queries() {
        return new WorldPackageQueryService(repository, mapper);
    }

    private WorldPackageRegistryBootstrap bootstrap(MockEnvironment environment, OfficialWorldPackageRelease release,
                                                    WorldPackageQueryService queries, boolean autoPublish) {
        return new WorldPackageRegistryBootstrap(environment, release, availability, queries, autoPublish);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private WorldPackageCandidate candidateFor(WorldPackageCandidate source, PackageRow target, long revision, String name) {
        var document = source.document();
        var identity = document.identity();
        var metadata = document.metadata();
        var changed = new WorldPackageDocument(new PackageIdentity(target.namespace(), target.packageKey(), revision,
                identity.formatVersion(), identity.runtimeContract(), identity.requiredCapabilities()),
                new ContentMetadata(metadata.defaultLocale(), name, metadata.summary(), metadata.authors(),
                        metadata.license(), metadata.contentRating()),
                document.world(), document.creation(), document.progression(), document.loreEntries(), document.assets());
        var compiled = new WorldPackageCompiler().compile(changed);
        var canonical = new WorldPackageCanonicalizer(mapper).canonicalize(changed, compiled);
        return new WorldPackageCandidate(changed, compiled, canonical.canonicalJson(), canonical.contentHash(),
                canonical.compiledJson(), canonical.compiledHash(), WorldPackageValidator.COMPILER_VERSION, source.assets());
    }

    private enum WriteBoundary { SCOPE, ENABLE, ENABLE_AUDIT, DEFAULT, DEFAULT_AUDIT }
}
