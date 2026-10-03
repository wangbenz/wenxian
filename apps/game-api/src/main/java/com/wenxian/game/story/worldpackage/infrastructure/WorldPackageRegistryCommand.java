package com.wenxian.game.story.worldpackage.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.worldpackage.application.*;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.Principal;
import com.wenxian.game.story.worldpackage.persistence.JdbcWorldPackageRevisionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;

/** 独立受控运维入口：不启动游戏、不执行 schema、不连接默认库；每条命令只完成一个明确用例。 */
public final class WorldPackageRegistryCommand {
    private WorldPackageRegistryCommand() {}
    public static void main(String[] args) {
        if (args.length < 2 || !java.util.Set.of("inspect", "publish", "enable", "disable", "default", "preflight", "verify").contains(args[0]))
            throw new IllegalArgumentException("用法：inspect <Bundle目录> | publish <Bundle目录> <预期内容哈希> <幂等键> | enable/disable/default/preflight/verify <修订ID>");
        var mapper = new ObjectMapper();
        if (args[0].equals("inspect")) {
            var c = new WorldPackageIngestionPipeline(mapper).inspectDirectory(Path.of(args[1]));
            System.out.println(c.document().identity().publisherNamespace() + "/" + c.document().identity().packageId()
                    + "@" + c.document().identity().revision() + " sha256=" + c.contentHash());
            return;
        }
        var ds = new DriverManagerDataSource(required("GAME_DATABASE_URL"), required("GAME_DATABASE_USERNAME"), required("GAME_DATABASE_PASSWORD"));
        var repository = new JdbcWorldPackageRevisionRepository(new JdbcTemplate(ds));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var queries = new WorldPackageQueryService(repository, mapper);
        var availability = new WorldPackageAvailabilityService(repository, tx);
        var principal = Principal.systemPublisher();
        switch (args[0]) {
            case "publish" -> {
                if (args.length != 4) throw new IllegalArgumentException("发布必须提供预期哈希和幂等键");
                var c = new WorldPackageIngestionPipeline(mapper).inspectDirectory(Path.of(args[1]));
                if (!c.contentHash().equals(args[2])) throw new IllegalArgumentException("制品与批准发布的哈希不同");
                var service = new WorldPackagePublicationService(repository, tx, mapper); var identity = c.document().identity();
                var p = service.registerOfficial(principal, identity.publisherNamespace(), identity.packageId());
                var r = service.publish(p.id(), c, principal, args[3], true);
                System.out.println("published revisionId=" + r.id() + " contentHash=" + r.contentHash());
            }
            case "enable" -> { availability.prepareScope(principal); availability.enableForCreation(args[1], principal); }
            case "disable" -> availability.disableForCreation(args[1], principal);
            case "default" -> availability.setDefault(args[1].equals("NONE") ? null : args[1], principal);
            case "preflight" -> {
                var r = queries.forCreation(null, args[1]);
                if (!queries.defaultForCreation(null).revision().id().equals(args[1])) throw new IllegalStateException("目标不是显式默认项");
                System.out.println("verified revisionId=" + r.revision().id() + " contentHash=" + r.revision().contentHash());
            }
            case "verify" -> {
                var result = new WorldPackageIntegrityService(repository, mapper).verify(args[1]);
                System.out.println("integrity verified revisionId=" + result.revision().id()
                        + " contentHash=" + result.revision().contentHash());
            }
            default -> throw new IllegalArgumentException("不支持的命令");
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少显式数据库环境变量：" + name);
        return value;
    }
}
