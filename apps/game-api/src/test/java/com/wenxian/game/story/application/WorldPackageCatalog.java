package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.story.domain.WorldPackage;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旧 classpath 对照 fixture，已退出 Spring 生产装配；阶段 C 随旧类型退出。
 *
 * <p>目录只从 {@code resources/worlds} 加载受版本控制的内容。资源缺失、重复或结构非法时服务启动失败，
 * 不会在运行时回退为前端文案或模型生成内容。</p>
 */
public class WorldPackageCatalog {
    private static final String MANIFEST = "worlds/catalog.json";
    private final Map<PackageKey, WorldPackage> packages;
    private final List<String> packageOrder;

    public WorldPackageCatalog(ObjectMapper mapper) {
        CatalogManifest manifest = read(mapper, MANIFEST, CatalogManifest.class);
        if (manifest.packages() == null || manifest.packages().isEmpty()) {
            throw new IllegalStateException("世界包目录不能为空：" + MANIFEST);
        }
        Map<PackageKey, WorldPackage> loaded = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (String path : manifest.packages()) {
            WorldPackage world = read(mapper, path, WorldPackage.class);
            PackageKey key = new PackageKey(world.packageId(), world.revision());
            if (loaded.putIfAbsent(key, world) != null) {
                throw new IllegalStateException("世界包目录存在重复修订：" + key);
            }
            if (!order.contains(world.packageId())) order.add(world.packageId());
        }
        this.packages = Map.copyOf(loaded);
        this.packageOrder = List.copyOf(order);
    }

    /** 返回当前唯一可用于创角的官方世界包。 */
    public WorldPackage currentPackage() {
        if (packageOrder.size() != 1) {
            throw new IllegalStateException("当前创角目录必须且只能启用一个世界包");
        }
        return resolveCurrent(packageOrder.getFirst());
    }

    /** 按稳定身份返回当前可用于创角的最高修订。 */
    public WorldPackage resolveCurrent(String packageId) {
        return packages.values().stream()
                .filter(world -> world.packageId().equals(packageId))
                .max(java.util.Comparator.comparingLong(WorldPackage::revision))
                .orElseThrow(() -> new IllegalArgumentException("没有找到所选世界包"));
    }

    /** 按创建契约固定的身份和修订精确读取世界包。 */
    public WorldPackage resolve(String packageId, long revision) {
        WorldPackage world = packages.get(new PackageKey(packageId, revision));
        if (world == null) throw new IllegalArgumentException("没有找到所选世界包修订");
        return world;
    }

    private static <T> T read(ObjectMapper mapper, String path, Class<T> type) {
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(stream, type);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("世界包资源无法加载：" + path, exception);
        }
    }

    private record CatalogManifest(List<String> packages) {}

    private record PackageKey(String packageId, long revision) {}
}
