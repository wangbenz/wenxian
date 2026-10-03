package com.wenxian.game.story.replay.playerintent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.infrastructure.OpenAiCompatibleChatModelFactory;
import com.wenxian.game.story.replay.slim.SlimNarrativeGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wenxian.game.story.replay.playerintent.PlayerIntentReplayRealModelTest.*;
import static com.wenxian.game.story.replay.playerintent.PlayerIntentSequenceRealModelTest.captureFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 显式开启的三轮成对实验：同一冻结初始状态和行动计划，分别在独立 H2 延续各自结果。
 * 只允许仓库外私有取证目录及独占本机代理，整批至多十八次调用；启动标记阻止整批自动重发。
 * 任一回合失败停止该组；证据写入失败停止整批。沿用生产结算与提交，不切换正式玩法。
 */
@EnabledIfEnvironmentVariable(named = "SLIM_NARRATIVE_COMPARISON_REPLAY", matches = "1")
class SlimNarrativeComparisonRealModelTest {
    @Test
    void compareFrozenBaselineAndSlimAcrossThreeTurns() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path repository = Path.of("../..").toRealPath();
        Path output = Path.of(required("PLAYER_INTENT_OUTPUT_DIR")).toRealPath();
        Path proxy = Path.of(required("PLAYER_INTENT_PROXY_DIR")).toRealPath();
        requirePrivateExternal(output, repository);
        requirePrivateExternal(proxy, repository);
        var cases = loadCases(mapper, repository);
        if (cases.size() != 1) throw new IllegalArgumentException("one initial case required");
        var loaded = cases.getFirst();
        Path planFile = Path.of(required("PLAYER_INTENT_CONTINUATION_FILE")).toRealPath();
        requirePrivateExternal(planFile.getParent(), repository);
        if (Files.size(planFile) > 20_000) throw new IllegalArgumentException("plan exceeds bound");
        byte[] planBytes = Files.readAllBytes(planFile);
        var plan = mapper.readValue(planBytes, PlayerIntentReplay.ContinuationPlan.class);
        if (!loaded.fixture().caseId().equals(plan.initialCaseId()) || plan.followupActions().size() != 2)
            throw new IllegalArgumentException("comparison requires exactly three planned turns per arm");
        String baseUrl = requireLoopback(required("PLAYER_INTENT_BASE_URL"));
        var budget = mapper.readTree(proxy.resolve("capture-manifest.json").toFile());
        if (budget.path("formatVersion").asInt() != 1 || budget.path("maxCalls").asInt() != 18
                || budget.path("priorCalls").asInt(-1) != 0 || Files.exists(proxy.resolve("0001.reservation.json"))
                || !captureFiles(proxy).isEmpty()) throw new IllegalArgumentException("fresh eighteen-call proxy required");
        var host = new PlayerIntentReplay(mapper);
        var preflight = host.preflight(loaded.fixture());
        if (!"VALID_UNTIL_DIRECTOR".equals(preflight.status()))
            throw new AssertionError("Preflight failed before provider calls");
        // 冻结源码/Prompt 与运行参数；宿主诊断中的正式 Prompt 版本不能替代本清单。
        Map<String, Object> artifacts = Map.of(
                "initialCaseFile", loaded.path().toString(), "initialCaseSha256", loaded.sha256(),
                "continuationFile", planFile.toString(), "continuationSha256", hash(planBytes),
                "codeAndPromptSha256", sourceHashes(repository),
                "parameters", Map.of("model", "deepseek-v4-flash", "directorMaxTokens", 2200,
                        "writerMaxTokens", 3200, "directorTemperature", 0.3, "writerTemperature", 0.8,
                        "timeoutSeconds", 90, "transportRetries", 0, "directorRepairsPerTurn", 1));
        writePrivate(output.resolve("comparison.started.json"), mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(Map.of("status", "STARTED", "inputArtifacts", artifacts,
                        "case", loaded.fixture(), "plan", plan, "preflight", preflight, "proxyManifest", budget)));
        Set<Path> seen = new HashSet<>();
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (String arm : List.of("baseline", "slim")) {
            var armPlan = new PlayerIntentReplay.ContinuationPlan(plan.replayId() + "_" + arm,
                    plan.initialCaseId(), plan.followupActions());
            var factory = new OpenAiCompatibleChatModelFactory();
            var generator = arm.equals("baseline")
                    ? SlimNarrativeGenerator.createBaseline(mapper, factory, baseUrl, required("DEEPSEEK_API_KEY"))
                    : SlimNarrativeGenerator.create(mapper, factory, baseUrl, required("DEEPSEEK_API_KEY"));
            var result = host.runSequence(loaded.fixture(), armPlan, generator, turn -> {
                try {
                    var captures = captureFiles(proxy).stream().filter(path -> !seen.contains(path)).toList();
                    writePrivate(output.resolve(arm + ".turn-" + turn.ordinal() + ".json"),
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                                    "arm", arm, "turn", turn, "inputArtifacts", artifacts,
                                    "httpCaptureFiles", captures.stream().map(Path::toString).toList(),
                                    "captureAssociation", "EXCLUSIVE_SEQUENTIAL_PROXY_INTERVAL")));
                    seen.addAll(captures);
                } catch (Exception error) {
                    throw new IllegalStateException("stop comparison because turn evidence could not be persisted", error);
                }
            });
            writePrivate(output.resolve(arm + ".result.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
            outcomes.add(Map.of("arm", arm, "status", result.status(), "turns", result.turns().size()));
        }
        writePrivate(output.resolve("comparison.result.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(outcomes));
        assertEquals(0, outcomes.stream().filter(row -> !"COMMITTED_IN_H2".equals(row.get("status"))).count(),
                "Inspect both arms; do not automatically rerun or alter the frozen batch");
    }

    private static Map<String, String> sourceHashes(Path repository) throws Exception {
        Map<String, String> result = new java.util.TreeMap<>();
        for (String folder : List.of("apps/game-api/src/test/java/com/wenxian/game/story/replay/slim",
                "apps/game-api/src/test/resources/prompts/experimental")) {
            try (var files = Files.list(repository.resolve(folder))) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList())
                    result.put(repository.relativize(file).toString(), hash(Files.readAllBytes(file)));
            }
        }
        for (String file : List.of(
                "apps/game-api/src/test/java/com/wenxian/game/story/replay/playerintent/SlimNarrativeComparisonRealModelTest.java",
                "apps/game-api/src/main/resources/prompts/model/turn-director-system.txt",
                "apps/game-api/src/main/resources/prompts/model/narrative-writer-system.txt")) {
            result.put(file, hash(Files.readAllBytes(repository.resolve(file))));
        }
        return result;
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
