package com.wenxian.game.story.prototype.cultivation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.domain.*;
import com.wenxian.game.model.infrastructure.OpenAiCompatibleChatModelFactory;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 已确认成长尺度的四个独立前态探测，仅调用现有 G1 导演并预演资格，不生成正文或提交游戏回合。
 * 每案一次请求、无修复/SDK重试；独占本机代理整批最多四次，启动标记阻止整批自动重发。
 * 结构接受与语义评审分开；即使原型保留资格，也不能证明模型文字没有撤销资格或虚构经历。
 */
@EnabledIfEnvironmentVariable(named = "SYNTHETIC_GROWTH_SCALE_REPLAY", matches = "1")
class CultivationStageScaleRealModelTest {
    @Test
    void probeFourFrozenPriorStatesWithoutNewPracticeOrPromotion() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path repository = Path.of("../..").toRealPath();
        Path root = Path.of(required("GROWTH_SCALE_ROOT")).toRealPath();
        Path proxy = root.resolve("proxy").toRealPath();
        privateExternal(root, repository);
        privateExternal(proxy, repository);
        String baseUrl = loopback(required("GROWTH_SCALE_BASE_URL"));
        var manifest = mapper.readTree(proxy.resolve("capture-manifest.json").toFile());
        if (manifest.path("formatVersion").asInt() != 1 || manifest.path("maxCalls").asInt() != 4
                || manifest.path("priorCalls").asInt(-1) != 0 || Files.exists(proxy.resolve("0001.reservation.json")))
            throw new IllegalArgumentException("fresh four-call proxy required");
        var cases = CultivationScaleCases.load(mapper);
        if (cases.cases().size() != 4) throw new IllegalArgumentException("exactly four cases required");
        var replay = new CultivationGrowthReplay(mapper, cases.scenario());
        String directorPrompt = resource("prompts/cultivation-replay-director.txt");
        String scalePolicy = resource("prompts/cultivation-stage-scale-policy.txt");
        Map<String, String> inputs = new LinkedHashMap<>();
        // 四案在首次外发前全部经过宿主输入检查并冻结；评审提示不进入导演输入。
        for (var sample : cases.cases()) inputs.put(sample.id(), mapper.writeValueAsString(
                replay.directorInput(sample.before(), cases.playerAction())));
        writeNew(root.resolve("scale.started.json"), mapper, Map.of(
                "status", "STARTED", "kind", "SYNTHETIC_QUALIFICATION_PROBE_NOT_GAME_TURNS",
                "frozenInputs", inputs, "caseFixtures", cases.cases(),
                "sourceHashes", sourceHashes(repository), "proxyManifest", manifest,
                "parameters", Map.of("model", "deepseek-v4-flash", "temperature", 0.3,
                        "maxOutputTokens", 2200, "timeoutSeconds", 90, "retries", 0, "repairs", 0)));
        var definition = new ModelDefinition("DEEPSEEK", "CULTIVATION_SCALE_PROBE", baseUrl,
                ModelAdapterType.OPENAI_COMPATIBLE, "deepseek-v4-flash",
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL), 128_000, 6000);
        var model = new OpenAiCompatibleChatModelFactory().create(definition,
                ModelCredential.platform(required("DEEPSEEK_API_KEY")),
                new ModelInvocationOptions(0.3, Duration.ofSeconds(90), 0, 2200, true));
        var director = AiServices.builder(CultivationGrowthReplay.DirectorAi.class)
                .systemMessage(directorPrompt + "\n\n" + scalePolicy).chatModel(model).build();
        List<Map<String, Object>> outcomes = new ArrayList<>();
        Set<Path> seen = new java.util.HashSet<>();
        for (var sample : cases.cases()) {
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("caseId", sample.id());
            attempt.put("before", sample.before());
            attempt.put("directorInput", mapper.readTree(inputs.get(sample.id())));
            attempt.put("reviewHintsNotSentToModel", sample.reviewHints());
            long started = System.nanoTime();
            try {
                var response = director.direct(inputs.get(sample.id()));
                attempt.put("modelLatencyMs", (System.nanoTime() - started) / 1_000_000);
                attempt.put("directorDraft", response.content());
                if (response.tokenUsage() != null) {
                    Map<String, Object> tokens = new LinkedHashMap<>();
                    tokens.put("inputTokens", response.tokenUsage().inputTokenCount());
                    tokens.put("outputTokens", response.tokenUsage().outputTokenCount());
                    tokens.put("totalTokens", response.tokenUsage().totalTokenCount());
                    attempt.put("reportedTokens", tokens);
                }
                var prepared = replay.prepare(sample.before(), cases.playerAction(), response.content());
                attempt.put("acceptedOutcome", prepared.outcome());
                attempt.put("candidateGrowth", prepared.after());
                attempt.put("learningUnchanged", prepared.after().learning().equals(sample.before().growth().learning()));
                attempt.put("accumulationUnchanged", java.util.Objects.equals(
                        prepared.after().accumulation(), sample.before().growth().accumulation()));
                attempt.put("positionUnchanged", prepared.after().position().equals(sample.before().growth().position()));
                attempt.put("status", "PREPARED_ONLY_SEMANTICS_UNREVIEWED");
            } catch (RuntimeException error) {
                attempt.putIfAbsent("modelLatencyMs", (System.nanoTime() - started) / 1_000_000);
                attempt.put("status", "FAILED_NO_STATE_WRITE");
                attempt.put("errorType", error.getClass().getSimpleName());
                if (error instanceof CultivationGrowthPrototype.Rejected rejected)
                    attempt.put("localReason", rejected.failure().name());
                else if (error instanceof CultivationGrowthReplay.InvalidReplay)
                    attempt.put("localReason", error.getMessage());
            }
            List<Path> captures;
            try (var stream = Files.list(proxy)) {
                captures = stream.filter(p -> p.getFileName().toString().matches("[0-9]{4}\\.json"))
                        .filter(p -> !seen.contains(p)).sorted().toList();
            }
            attempt.put("httpCaptures", captures.stream().map(Path::toString).toList());
            // 原件落盘失败停止整批，不在 finally 中吞掉证据异常继续消耗预算。
            writeNew(root.resolve(sample.id() + ".result.json"), mapper, attempt);
            seen.addAll(captures);
            outcomes.add(Map.of("caseId", sample.id(), "status", attempt.get("status")));
        }
        writeNew(root.resolve("scale.result.json"), mapper, outcomes);
        assertEquals(0, outcomes.stream().filter(row -> !"PREPARED_ONLY_SEMANTICS_UNREVIEWED".equals(row.get("status"))).count(),
                "Inspect raw responses; do not rerun this batch or call this a gameplay pass");
    }

    private static Map<String, String> sourceHashes(Path repository) throws Exception {
        Map<String, String> hashes = new java.util.TreeMap<>();
        for (String file : List.of(
                "src/test/java/com/wenxian/game/story/prototype/cultivation/CultivationGrowthPrototype.java",
                "src/test/java/com/wenxian/game/story/prototype/cultivation/CultivationGrowthReplay.java",
                "src/test/java/com/wenxian/game/story/prototype/cultivation/CultivationScaleCases.java",
                "src/test/java/com/wenxian/game/story/prototype/cultivation/CultivationStageScaleRealModelTest.java",
                "src/test/resources/story/cultivation-stage-scale-cases.json",
                "src/test/resources/prompts/cultivation-replay-director.txt",
                "src/test/resources/prompts/cultivation-stage-scale-policy.txt")) {
            hashes.put(file, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(repository.resolve("apps/game-api").resolve(file)))));
        }
        return hashes;
    }

    private static String resource(String path) throws Exception {
        try (var input = CultivationStageScaleRealModelTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalArgumentException("missing test resource");
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String loopback(String value) {
        URI uri = URI.create(value);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1024 || uri.getPort() > 65535 || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null
                || !Set.of("", "/", "/v1", "/v1/").contains(uri.getPath()))
            throw new IllegalArgumentException("explicit loopback proxy required");
        return value;
    }

    private static void privateExternal(Path directory, Path repository) throws Exception {
        if (!Files.isDirectory(directory) || directory.startsWith(repository)
                || Files.getPosixFilePermissions(directory).stream().anyMatch(p ->
                p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
            throw new IllegalArgumentException("private external directory required");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + name);
        return value;
    }

    private static void writeNew(Path file, ObjectMapper mapper, Object value) throws Exception {
        Files.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
}
