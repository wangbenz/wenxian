package com.wenxian.game.story.worldpackage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.worldpackage.document.WorldPackageDocument;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.*;
import com.wenxian.game.story.worldpackage.persistence.JdbcWorldPackageRevisionRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 一次性本地 MySQL 注册表验证。仅允许显式端口及经核对的临时 datadir；不读取项目数据库配置。
 * 每例新建随机测试库并只清理该库，默认跳过，禁止连接共享或既有开发数据库。
 */
@EnabledIfSystemProperty(named = "wenxian.world.mysql.port", matches = "[0-9]{4,5}")
class WorldPackageRegistryMysqlTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate server;
    private JdbcTemplate jdbc;
    private String database;
    private JdbcWorldPackageRevisionRepository repository;
    private WorldPackagePublicationService publications;
    private WorldPackageAvailabilityService availability;
    private WorldPackageCandidate candidate;
    private PackageRow worldPackage;
    private RevisionRow first;

    @BeforeEach void setup() throws Exception {
        int port = Integer.parseInt(System.getProperty("wenxian.world.mysql.port"));
        assertThat(port).isBetween(1024, 65535);
        String expected = System.getProperty("wenxian.world.mysql.directory", "");
        assertThat(expected).startsWith("/private/tmp/wenxian-world-registry-");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000&socketTimeout=15000";
        server = new JdbcTemplate(new DriverManagerDataSource(base + options, "root", ""));
        assertThat(Path.of(server.queryForObject("select @@datadir", String.class)).toRealPath())
                .isEqualTo(Path.of(expected, "data").toRealPath());
        database = "world_registry_test_" + UUID.randomUUID().toString().replace("-", "");
        server.execute("create database " + database + " character set utf8mb4 collate utf8mb4_unicode_ci");
        var source = new DriverManagerDataSource(base + database + options, "root", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table app_user(id varchar(64) primary key comment '隔离测试账号') engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci comment='隔离测试账号表'");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../../database/migrations/world-package-registry-v1.sql"));
        }
        repository = spy(new JdbcWorldPackageRevisionRepository(jdbc));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        publications = new WorldPackagePublicationService(repository, tx, mapper);
        availability = new WorldPackageAvailabilityService(repository, tx);
        candidate = new WorldPackageIngestionPipeline(mapper).inspectDirectory(Path.of("src/main/resources/world-packages/official/XUANXU/2"));
        worldPackage = publications.registerOfficial(Principal.systemPublisher(), "wenxian.official", "XUANXU");
        first = publications.publish(worldPackage.id(), candidate, Principal.systemPublisher(), "first", true);
    }

    @AfterEach void cleanup() {
        if (database != null) server.execute("drop database " + database);
    }

    @Test void initializationFailureRollsBackScopeAvailabilityDefaultAndAudit() {
        doAnswer(call -> { call.callRealMethod(); throw new DataAccessResourceFailureException("after default write"); })
                .when(repository).setDefault("GLOBAL", first.id());
        assertThatThrownBy(() -> availability.initializeDefaultIfAbsent(first.id(), Principal.systemPublisher()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        for (String table : new String[]{"world_package_creation_scope", "world_package_availability", "world_package_creation_default", "world_package_audit"})
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).isZero();
        assertThat(repository.revision(first.id()).id()).isEqualTo(first.id());
        doCallRealMethod().when(repository).setDefault("GLOBAL", first.id());
        assertThat(availability.initializeDefaultIfAbsent(first.id(), Principal.systemPublisher())).isTrue();
    }

    @Test void concurrentInitializersAcrossPackagesKeepOneCompleteWinner() throws Exception {
        var second = publishOther();
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return availability.initializeDefaultIfAbsent(first.id(), Principal.systemPublisher()); });
            var b = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return availability.initializeDefaultIfAbsent(second.id(), Principal.systemPublisher()); });
            assertThat(java.util.List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        String selected = repository.defaultRevision("GLOBAL").orElseThrow();
        assertThat(repository.enabled("GLOBAL")).containsExactly(selected);
        assertThat(jdbc.queryForObject("select count(*) from world_package_creation_scope", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from world_package_audit", Integer.class)).isEqualTo(2);
    }

    @Test void explicitDefaultChangeAndClearSurviveNewQueryInstances() {
        availability.initializeDefaultIfAbsent(first.id(), Principal.systemPublisher());
        var second = publishOther();
        availability.enableForCreation(second.id(), Principal.systemPublisher());
        availability.setDefault(second.id(), Principal.systemPublisher());
        jdbc.update("update world_package_revision set compiler_version='historical' where id=?", first.id());
        var restarted = new WorldPackageQueryService(repository, mapper);
        assertThat(restarted.defaultForCreation(null).revision().id()).isEqualTo(second.id());
        assertThat(restarted.boundRevision(first.id()).compiled().identity().packageId()).isEqualTo("XUANXU");
        availability.setDefault(null, Principal.systemPublisher());
        assertThat(availability.initializeDefaultIfAbsent(first.id(), Principal.systemPublisher())).isFalse();
        assertThatThrownBy(() -> restarted.defaultForCreation(null)).hasMessageContaining("未配置");
        assertThat(restarted.boundRevision(first.id()).compiled()).isNotNull();
    }

    @Test void realMigrationPreservesExactSnapshotBytesAndProtectsDefaultForeignKey() {
        assertThat(repository.compiledSnapshot(first.id())).isEqualTo(candidate.compiledJson());
        assertThat(new WorldPackageIntegrityService(repository, mapper).verify(first.id()).revision().contentHash()).isEqualTo(candidate.contentHash());
        availability.initializeDefaultIfAbsent(first.id(), Principal.systemPublisher());
        var second = publishOther();
        assertThatThrownBy(() -> jdbc.update("update world_package_creation_default set revision_id=? where scope_id='GLOBAL'", second.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.defaultRevision("GLOBAL")).contains(first.id());
    }

    @Test void compatibilityUpgradePreservesAccountDeletionAndPublishedContent() throws Exception {
        // 模拟曾为绕过旧迁移冲突而改成 RESTRICT 的已有结构；不能靠重跑 CREATE IF NOT EXISTS 修复。
        jdbc.execute("alter table world_package drop foreign key fk_world_package_owner");
        jdbc.execute("alter table world_package add constraint fk_world_package_owner foreign key (owner_user_id) references app_user(id) on delete restrict, "
                + "add constraint ck_world_package_official_owner check (source_type<>'OFFICIAL' or owner_user_id is null)");
        try (var connection = jdbc.getDataSource().getConnection()) {
            var upgrade = new FileSystemResource("../../database/migrations/world-package-owner-constraint-compat.sql");
            ScriptUtils.executeSqlScript(connection, upgrade);
            ScriptUtils.executeSqlScript(connection, upgrade);
        }
        String userId = "local-fixture-user";
        jdbc.update("insert into app_user(id) values(?)", userId);
        var principal = Principal.player(userId);
        var owned = publications.registerPlayer(principal);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        new WorldPackageInstallationService(repository, tx).installForUser(first.id(), principal);
        availability.prepareScope(principal);
        availability.enableForCreation(first.id(), principal);
        availability.setDefault(first.id(), principal);

        jdbc.update("delete from app_user where id=?", userId);

        assertThat(repository.packageById(owned.id(), false).ownerUserId()).isNull();
        assertThat(repository.revision(first.id())).isNotNull();
        for (String table : new String[]{"world_package_installation", "world_package_creation_scope", "world_package_availability", "world_package_creation_default"})
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from world_package_audit", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> publications.publish(owned.id(), candidate, principal, "deleted-owner", false))
                .isInstanceOf(SecurityException.class).hasMessageContaining("发布权限");
    }

    private RevisionRow publishOther() {
        var target = publications.registerOfficial(Principal.systemPublisher(), "wenxian.official", "OTHER");
        var d = candidate.document();
        var i = d.identity();
        var changed = new WorldPackageDocument(new WorldPackageDocument.PackageIdentity(target.namespace(), target.packageKey(), 1,
                i.formatVersion(), i.runtimeContract(), i.requiredCapabilities()), d.metadata(), d.world(), d.creation(), d.progression(), d.loreEntries(), d.assets());
        var compiled = new WorldPackageCompiler().compile(changed);
        var normalized = new WorldPackageCanonicalizer(mapper).canonicalize(changed, compiled);
        return publications.publish(target.id(), new WorldPackageCandidate(changed, compiled, normalized.canonicalJson(), normalized.contentHash(),
                normalized.compiledJson(), normalized.compiledHash(), WorldPackageValidator.COMPILER_VERSION, candidate.assets()), Principal.systemPublisher(), "other", true);
    }
}
