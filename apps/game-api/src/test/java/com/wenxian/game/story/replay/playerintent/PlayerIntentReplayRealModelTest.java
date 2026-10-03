package com.wenxian.game.story.replay.playerintent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.infrastructure.OpenAiCompatibleChatModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 显式授权后才可执行的单案例入口。仅允许本机限额取证代理；外部案例与结果均留在仓库外的私有目录。
 * 不加载应用配置或共享数据源；重复启动同一案例会在供应商调用前失败，失败原件保留供审阅。
 * 代理清单只验证其预算声明，代理进程和整批调用上限仍须由批次负责人核对并保持存续。
 */
@EnabledIfEnvironmentVariable(named = "PLAYER_INTENT_REPLAY", matches = "1")
class PlayerIntentReplayRealModelTest {
    @Test
    void replayReviewedCasesSequentially() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path repository = Path.of("../..").toRealPath();
        Path output = Path.of(required("PLAYER_INTENT_OUTPUT_DIR")).toRealPath();
        Path proxy = Path.of(required("PLAYER_INTENT_PROXY_DIR")).toRealPath();
        requirePrivateExternal(output, repository);
        requirePrivateExternal(proxy, repository);
        List<LoadedCase> cases = loadCases(mapper, repository);
        String baseUrl = requireLoopback(required("PLAYER_INTENT_BASE_URL"));
        var budget = mapper.readTree(proxy.resolve("capture-manifest.json").toFile());
        if (budget.path("formatVersion").asInt() != 1 || budget.path("maxCalls").asInt() < 1
                || budget.path("maxCalls").asInt() > 100) throw new IllegalArgumentException("bounded proxy manifest required");
        String key = required("DEEPSEEK_API_KEY");
        var host = new PlayerIntentReplay(mapper);
        // 全部案例先到达正式导演边界，避免前置结构错误消耗供应商预算。
        List<PlayerIntentReplay.PreflightResult> preflights = cases.stream().map(value -> host.preflight(value.fixture())).toList();
        writePrivate(output.resolve("batch-preflight-" + UUID.randomUUID() + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(preflights));
        if (preflights.stream().anyMatch(value -> !"VALID_UNTIL_DIRECTOR".equals(value.status())))
            throw new AssertionError("Fixture preflight failed; no provider calls were made");
        List<Map<String, String>> outcomes = new ArrayList<>();
        for (LoadedCase loaded : cases) {
            var fixture = loaded.fixture();
            Path started = output.resolve(fixture.caseId() + ".started.json");
            if (Files.exists(started)) {
                outcomes.add(Map.of("caseId", fixture.caseId(), "status", "ALREADY_STARTED"));
                continue;
            }
            // CREATE_NEW 是单案例启动门闩；断电或失败后也不自动再次发送整轮。
            writePrivate(started, mapper.writeValueAsBytes(Map.of("caseId", fixture.caseId(), "status", "STARTED",
                    "caseFile", loaded.path().toString(), "caseSha256", loaded.sha256(),
                    "proxyManifest", proxy.resolve("capture-manifest.json").toString(), "budget", budget)));
            PlayerIntentReplay.ReplayResult result;
            try {
                result = host.run(fixture, PlayerIntentReplay.generator(mapper,
                        new OpenAiCompatibleChatModelFactory(), baseUrl, key));
            } catch (RuntimeException error) {
                writePrivate(output.resolve(fixture.caseId() + ".failure.json"), mapper.writeValueAsBytes(Map.of(
                        "caseId", fixture.caseId(), "status", "HOST_FAILED", "errorType", error.getClass().getSimpleName())));
                outcomes.add(Map.of("caseId", fixture.caseId(), "status", "HOST_FAILED"));
                continue;
            }
            // 落原件失败时停止整批，避免后续继续产生无法审阅的调用。
            writePrivate(output.resolve(fixture.caseId() + ".result.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
            outcomes.add(Map.of("caseId", fixture.caseId(), "status", result.status()));
        }
        writePrivate(output.resolve("batch-summary-" + UUID.randomUUID() + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(outcomes));
        assertEquals(0, outcomes.stream().filter(value -> !"COMMITTED_IN_H2".equals(value.get("status"))).count(),
                "Inspect private batch results; no automatic case retry");
    }

    record LoadedCase(Path path, String sha256, PlayerIntentReplay.CaseFixture fixture) { }

    static List<LoadedCase> loadCases(ObjectMapper mapper, Path repository) throws Exception {
        String directory = System.getenv("PLAYER_INTENT_CASE_DIRECTORY");
        String single = System.getenv("PLAYER_INTENT_CASE_FILE");
        boolean hasDirectory = directory != null && !directory.isBlank();
        boolean hasSingle = single != null && !single.isBlank();
        if (hasDirectory == hasSingle) throw new IllegalArgumentException("provide exactly one case file or directory");
        List<Path> files;
        if (hasDirectory) {
            Path folder = Path.of(directory).toRealPath();
            requirePrivateExternal(folder, repository);
            try (var stream = Files.list(folder)) {
                files = stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
            }
        } else files = List.of(Path.of(single));
        if (files.isEmpty() || files.size() > 20) throw new IllegalArgumentException("batch requires 1 to 20 cases");
        List<LoadedCase> loaded = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Path file : files) {
            Path input = file.toRealPath();
            requirePrivateExternal(input.getParent(), repository);
            if (Files.size(input) > 2_000_000) throw new IllegalArgumentException("case exceeds local input bound");
            byte[] bytes = Files.readAllBytes(input);
            var fixture = mapper.readValue(bytes, PlayerIntentReplay.CaseFixture.class);
            if (!ids.add(fixture.caseId())) throw new IllegalArgumentException("duplicate caseId");
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            loaded.add(new LoadedCase(input, hash, fixture));
        }
        return List.copyOf(loaded);
    }

    static String requireLoopback(String url) {
        URI uri = URI.create(url);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1024 || uri.getPort() > 65535 || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !Set.of("", "/", "/v1", "/v1/").contains(uri.getPath()))
            throw new IllegalArgumentException("explicit loopback capture proxy required");
        return url;
    }

    static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    static void requirePrivateExternal(Path directory, Path repository) throws Exception {
        if (!Files.isDirectory(directory) || directory.startsWith(repository))
            throw new IllegalArgumentException("private directory outside repository required");
        for (var permission : Files.getPosixFilePermissions(directory))
            if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))
                throw new IllegalArgumentException("private directory permissions required");
    }

    static void writePrivate(Path target, byte[] bytes) throws Exception {
        try (var stream = Files.newByteChannel(target,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)))) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) stream.write(buffer);
        }
    }
}
