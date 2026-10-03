package com.wenxian.game.story.worldpackage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.application.CharacterCreationCatalogService;
import com.wenxian.game.story.worldpackage.application.*;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.Principal;
import com.wenxian.game.story.worldpackage.persistence.JdbcWorldPackageRevisionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 旧核心领域回归使用真正的隔离注册表 fixture，不再走 classpath 运行目录。 */
public final class WorldPackageRegistryTestSupport {
    private static final class Fixture {
        static final EmbeddedDatabase DB = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2)
                .addScript("world-package-test-users.sql").addScript("world-package-registry-h2.sql").build();
        static final JdbcWorldPackageRevisionRepository REPOSITORY = initialize();
        static JdbcWorldPackageRevisionRepository initialize() {
            var repository = new JdbcWorldPackageRevisionRepository(new JdbcTemplate(DB));
            var tx = new TransactionTemplate(new DataSourceTransactionManager(DB));
            var publications = new WorldPackagePublicationService(repository, tx, new ObjectMapper());
            var release = new OfficialWorldPackageRelease(publications, new ObjectMapper(), "world-packages/official/XUANXU/2/");
            var r = release.publish();
            var availability = new WorldPackageAvailabilityService(repository, tx);
            availability.initializeDefaultIfAbsent(r.id(), Principal.systemPublisher());
            return repository;
        }
    }
    public static CharacterCreationCatalogService catalog(ObjectMapper mapper) {
        return new CharacterCreationCatalogService(new WorldPackageQueryService(Fixture.REPOSITORY, mapper), "wenxian.official");
    }

    public static com.wenxian.game.story.application.WorldTurnContextService worldContexts(ObjectMapper mapper) {
        return new com.wenxian.game.story.application.WorldTurnContextService(
                new WorldPackageQueryService(Fixture.REPOSITORY, mapper), mapper, "wenxian.official");
    }
}
