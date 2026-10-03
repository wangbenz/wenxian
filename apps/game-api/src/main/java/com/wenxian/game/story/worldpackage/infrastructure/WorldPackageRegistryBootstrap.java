package com.wenxian.game.story.worldpackage.infrastructure;

import com.wenxian.game.story.worldpackage.application.*;
import com.wenxian.game.story.worldpackage.domain.WorldPackageRegistry.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.Arrays;

/**
 * 启动检查数据库指定的创角默认修订；局部内容异常只影响创角，实际请求仍须重新校验并明确失败。
 * 仅 dev/test 且显式允许的本地库可自动发布和首次初始化，prod 混合 profile 同样拒绝写入。
 */
@Component
public class WorldPackageRegistryBootstrap implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(WorldPackageRegistryBootstrap.class);
    private final Environment environment;
    private final OfficialWorldPackageRelease release;
    private final WorldPackageAvailabilityService availability;
    private final WorldPackageQueryService queries;
    private final boolean autoPublish;
    public WorldPackageRegistryBootstrap(Environment environment, OfficialWorldPackageRelease release,
            WorldPackageAvailabilityService availability, WorldPackageQueryService queries,
            @Value("${game.world-packages.auto-publish:false}") boolean autoPublish) {
        this.environment = environment; this.release = release; this.availability = availability;
        this.queries = queries; this.autoPublish = autoPublish;
    }
    @Override public void run(ApplicationArguments args) {
        if (autoPublish) {
            assertLocalAutoPublication(environment.getActiveProfiles(), environment.getProperty("spring.datasource.url", ""));
            var r = release.publish();
            availability.initializeDefaultIfAbsent(r.id(), Principal.systemPublisher());
        }
        try {
            preflight();
        } catch (RuntimeException unavailable) {
            // 不输出数据库异常、内容正文或凭证；不将启动检查结果缓存为授权或可用性结论。
            LOG.warn("创角默认世界检查未通过；创角请求将独立校验，已有命卷按自身绑定检查。异常类别={}",
                    unavailable.getClass().getSimpleName());
        }
    }
    /** 只读检查显式默认修订及当前 v2 创角兼容性；调用方决定故障范围，本方法不吞异常。 */
    public void preflight() {
        var selected = queries.defaultForCreation(null);
        // 尚未切换命卷 v3；该限制来自入口合同，不来自官方包 ID 或随应用制品。
        new LegacyWorldPackageAdapter().adapt(selected.compiled());
    }
    public static void assertLocalAutoPublication(String[] profiles, String url) {
        var active = Arrays.asList(profiles);
        boolean profile = !active.contains("prod") && (active.contains("dev") || active.contains("test"));
        boolean local = url.startsWith("jdbc:h2:mem:") || url.startsWith("jdbc:mysql://127.0.0.1:") || url.startsWith("jdbc:mysql://localhost:");
        if (!profile || !local) throw new IllegalStateException("自动发布仅限显式 dev/test 本地数据库，生产或远程库禁止启动写入");
    }
}
