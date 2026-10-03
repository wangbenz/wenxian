package com.wenxian.game.story.replay.playerintent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.infrastructure.OpenAiCompatibleChatModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wenxian.game.story.replay.playerintent.PlayerIntentReplayRealModelTest.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 已授权短链的独立入口；初始案例与追加行动分别留档，同一 H2 逐轮提交、重载。
 * 实际 HTTP 原件继续由独占的本机限额代理保存，每轮关联新增原件；本类不修改代理或重新分配预算。
 * 任一回合或证据写入失败即停止，已开始的 replayId 不允许自动重发。
 */
@EnabledIfEnvironmentVariable(named = "PLAYER_INTENT_SEQUENCE_REPLAY", matches = "1")
class PlayerIntentSequenceRealModelTest {
    @Test
    void replayOneReviewedCaseWithOrderedAcceptanceFollowups() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path repository = Path.of("../..").toRealPath();
        Path output = Path.of(required("PLAYER_INTENT_OUTPUT_DIR")).toRealPath();
        Path proxy = Path.of(required("PLAYER_INTENT_PROXY_DIR")).toRealPath();
        requirePrivateExternal(output, repository);
        requirePrivateExternal(proxy, repository);
        var cases = loadCases(mapper, repository);
        if (cases.size() != 1) throw new IllegalArgumentException("one initial case per sequence required");
        var loaded = cases.getFirst();
        Path planFile = Path.of(required("PLAYER_INTENT_CONTINUATION_FILE")).toRealPath();
        requirePrivateExternal(planFile.getParent(), repository);
        if (Files.size(planFile) > 20_000) throw new IllegalArgumentException("continuation input exceeds bound");
        byte[] planBytes = Files.readAllBytes(planFile);
        var plan = mapper.readValue(planBytes, PlayerIntentReplay.ContinuationPlan.class);
        if (!loaded.fixture().caseId().equals(plan.initialCaseId()))
            throw new IllegalArgumentException("continuation caseId mismatch");
        String baseUrl = requireLoopback(required("PLAYER_INTENT_BASE_URL"));
        var budget = mapper.readTree(proxy.resolve("capture-manifest.json").toFile());
        if (budget.path("formatVersion").asInt() != 1 || budget.path("maxCalls").asInt() < 1
                || budget.path("maxCalls").asInt() > 100) throw new IllegalArgumentException("bounded proxy manifest required");
        var host = new PlayerIntentReplay(mapper);
        var preflight = host.preflight(loaded.fixture());
        if (!"VALID_UNTIL_DIRECTOR".equals(preflight.status()))
            throw new AssertionError("Initial case preflight failed; no provider calls were made");
        Map<String, Object> artifacts = Map.of(
                "initialCaseFile", loaded.path().toString(), "initialCaseSha256", loaded.sha256(),
                "continuationFile", planFile.toString(), "continuationSha256",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(planBytes)));
        // CREATE_NEW 保持启动门闩；初始泛化案例与验收追加输入同时留档，不把二者混为历史行动。
        writePrivate(output.resolve(plan.replayId() + ".started.json"), mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(Map.of("status", "STARTED", "inputArtifacts", artifacts,
                        "initialCase", loaded.fixture(), "continuation", plan,
                        "preflight", preflight, "proxyManifest", budget)));
        Set<Path> seen = new HashSet<>(captureFiles(proxy));
        var result = host.runSequence(loaded.fixture(), plan, PlayerIntentReplay.generator(mapper,
                new OpenAiCompatibleChatModelFactory(), baseUrl, required("DEEPSEEK_API_KEY")), turn -> {
            try {
                List<Path> captures = captureFiles(proxy).stream().filter(path -> !seen.contains(path)).toList();
                writePrivate(output.resolve(plan.replayId() + ".turn-" + turn.ordinal() + ".json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                                "turn", turn, "inputArtifacts", artifacts,
                                "httpCaptureFiles", captures.stream().map(Path::toString).toList(),
                                "captureAssociation", "EXCLUSIVE_SEQUENTIAL_PROXY_INTERVAL")));
                seen.addAll(captures);
            } catch (java.io.IOException error) {
                throw new UncheckedIOException("stop sequence because turn evidence could not be persisted", error);
            } catch (Exception error) {
                throw new IllegalStateException("stop sequence because turn evidence could not be persisted", error);
            }
        });
        writePrivate(output.resolve(plan.replayId() + ".result.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
        assertEquals("COMMITTED_IN_H2", result.status(), "Inspect per-turn private evidence; no automatic sequence retry");
    }

    static List<Path> captureFiles(Path proxy) throws java.io.IOException {
        try (var files = Files.list(proxy)) {
            return files.filter(path -> path.getFileName().toString().matches("[0-9]{4}\\.json"))
                    .sorted().toList();
        }
    }
}
