package com.wenxian.game.story.replay.playerintent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.model.infrastructure.OpenAiCompatibleChatModelFactory;
import com.wenxian.game.story.replay.single.SingleCandidateGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.wenxian.game.story.replay.playerintent.PlayerIntentReplayRealModelTest.*;
import static com.wenxian.game.story.replay.playerintent.PlayerIntentSequenceRealModelTest.captureFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 单候选原型的显式真实回放入口：独立 H2、至多三轮、全批至多六次新增 HTTP，绝不回注旧响应。
 * 每轮先保存原件与前后状态，再等待审阅者提交下一行动；技术失败、证据失败或冻结材料改变即停止。
 * 本类不启动或终止外部代理；运行者必须用拥有该代理的启动器在 finally 中关闭自身子进程。
 * 提交成功仅表示技术链完成，小说体验、行动授权及学习承接仍须逐轮人工判断。
 */
@EnabledIfEnvironmentVariable(named = "SINGLE_CANDIDATE_REPLAY", matches = "1")
class SingleCandidateRealModelTest {
    private static final int MAX_TURNS = 3;
    private static final int MAX_CALLS = 6;
    private static final Duration ACTION_WAIT = Duration.ofMinutes(15);

    @Test
    void replayOneCandidateWithHumanSelectedFollowups() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path repository = Path.of("../..").toRealPath();
        Path output = Path.of(required("PLAYER_INTENT_OUTPUT_DIR")).toRealPath();
        Path proxy = Path.of(required("PLAYER_INTENT_PROXY_DIR")).toRealPath();
        requirePrivateExternal(output, repository);
        requirePrivateExternal(proxy, repository);
        if (output.equals(proxy)) throw new IllegalArgumentException("output and capture directories must differ");
        var cases = loadCases(mapper, repository);
        if (cases.size() != 1) throw new IllegalArgumentException("single candidate requires one external initial case");
        var loaded = cases.getFirst();
        String baseUrl = requireLoopback(required("PLAYER_INTENT_BASE_URL"));
        JsonNode budget = freshBudget(mapper, proxy);
        Path actions = output.resolve("actions");
        if (!Files.exists(actions)) Files.createDirectory(actions, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        if (Files.isSymbolicLink(actions)) throw new IllegalArgumentException("action directory cannot be a link");
        requirePrivateExternal(actions, repository);
        if (Files.exists(actions.resolve("2.json")) || Files.exists(actions.resolve("3.json")))
            throw new IllegalArgumentException("followup actions must be selected after reviewing completed turns");

        var host = new PlayerIntentReplay(mapper);
        var preflight = host.preflight(loaded.fixture());
        if (!"VALID_UNTIL_DIRECTOR".equals(preflight.status()))
            throw new AssertionError("Initial state/context preflight failed before supplier calls");
        Map<String, String> frozen = sourceHashes(repository);
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("model", "deepseek-v4-flash");
        parameters.put("temperature", 0.3);
        parameters.put("maxTokensSent", false);
        parameters.putNull("max_tokens");
        parameters.put("timeoutSeconds", 90);
        parameters.put("maximumRetries", 0);
        parameters.put("thinking", "disabled");
        parameters.put("maximumRepairsPerTurn", 1);
        parameters.put("maximumTurns", MAX_TURNS);
        parameters.put("maximumNewSupplierHttpCalls", MAX_CALLS);
        Map<String, Object> artifacts = Map.of(
                "initialCaseFile", loaded.path().toString(), "initialCaseSha256", loaded.sha256(),
                "codeResourceAndRuntimeSha256", frozen, "parameters", parameters,
                "baseUrl", baseUrl, "javaVersion", System.getProperty("java.version"),
                "classPath", System.getProperty("java.class.path"));
        // CREATE_NEW 保持整批启动门闩；失败或中断后也不能删除门闩重新消耗同一批预算。
        write(output.resolve("single.started.json"), mapper, Map.of(
                "status", "STARTED_ONCE", "startedAt", Instant.now().toString(),
                "initialCase", loaded.fixture(), "preflight", preflight,
                "inputArtifacts", artifacts, "proxyManifest", budget,
                "recordedSourceDeliveries", 0, "nextActionDirectory", actions.toString()));
        Set<Path> seen = new HashSet<>();
        AtomicBoolean manuallyStopped = new AtomicBoolean();
        try {
            verifyFrozen(repository, frozen, loaded);
            freshBudget(mapper, proxy);
            var generator = SingleCandidateGenerator.create(mapper, new OpenAiCompatibleChatModelFactory(),
                    baseUrl, required("DEEPSEEK_API_KEY"));
            var result = host.runSequence(loaded.fixture(), "single_candidate", MAX_TURNS, generator, turn -> {
                try {
                    List<Path> captures = captureFiles(proxy).stream().filter(path -> !seen.contains(path)).toList();
                    List<JsonNode> actualParameters = actualParameters(mapper, captures);
                    write(output.resolve("single.turn-" + turn.ordinal() + ".json"), mapper, Map.of(
                            "turn", turn, "recordedAt", Instant.now().toString(), "inputArtifacts", artifacts,
                            "httpCaptureFiles", captures.stream().map(Path::toString).toList(),
                            "actualRequestParameters", actualParameters,
                            "captureAssociation", "EXCLUSIVE_SEQUENTIAL_PROXY_INTERVAL"));
                    seen.addAll(captures);
                    verifyActualParameters(actualParameters);
                    if (captures.size() > 2 || reservations(proxy).size() > MAX_CALLS
                            || ("COMMITTED_IN_H2".equals(turn.result().status()) && captures.isEmpty()))
                        throw new IllegalStateException("single-candidate HTTP budget exceeded; stop without further calls");
                    verifyFrozen(repository, frozen, loaded);
                    System.out.println("SINGLE_RECORDED ordinal=" + turn.ordinal() + " status=" + turn.result().status());
                } catch (Exception error) {
                    throw new IllegalStateException("stop single replay; turn evidence or frozen boundary failed", error);
                }
            }, previous -> awaitAction(mapper, repository, actions, previous, frozen, loaded, manuallyStopped));
            write(output.resolve("single.result.json"), mapper, Map.of(
                    "result", result, "stoppedByReviewer", manuallyStopped.get(),
                    "playAcceptance", "REQUIRES_HUMAN_REVIEW", "recordedSourceDeliveries", 0,
                    "newSupplierHttpReservations", reservations(proxy).size(),
                    "newSupplierHttpResponses", captureFiles(proxy).size(), "inputArtifacts", artifacts));
            assertEquals("COMMITTED_IN_H2", result.status(),
                    "Inspect preserved evidence; technical failure stops this batch and must not automatically rerun");
        } catch (Exception | AssertionError error) {
            write(output.resolve("single.aborted.json"), mapper, Map.of(
                    "status", "ABORTED_NO_AUTOMATIC_RETRY", "errorType", error.getClass().getSimpleName(),
                    "newSupplierHttpReservations", reservations(proxy).size(),
                    "newSupplierHttpResponses", captureFiles(proxy).size()));
            throw error;
        }
    }

    private static PlayerIntentReplay.FollowupAction awaitAction(ObjectMapper mapper, Path repository,
            Path actions, PlayerIntentReplay.SequenceTurn previous, Map<String, String> frozen,
            LoadedCase loaded, AtomicBoolean manuallyStopped) {
        int ordinal = previous.ordinal() + 1;
        Path file = actions.resolve(ordinal + ".json");
        System.out.println("SINGLE_AWAITING_ACTION ordinal=" + ordinal + " previousRequestId=" + previous.requestId());
        long deadline = System.nanoTime() + ACTION_WAIT.toNanos();
        try {
            while (!Files.exists(file)) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("no reviewed action; stop without retry");
                Thread.sleep(200);
            }
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 12_000)
                throw new IllegalArgumentException("followup action file outside allowed boundary");
            byte[] bytes = Files.readAllBytes(file);
            JsonNode value = mapper.readTree(bytes);
            if (value == null || !value.isObject() || !value.path("selectedAfterOrdinal").isIntegralNumber()
                    || value.path("selectedAfterOrdinal").asInt() != previous.ordinal()
                    || (value.has("previousRequestId")
                    && !previous.requestId().equals(value.path("previousRequestId").asText())))
                throw new IllegalArgumentException("followup must refer to the completed previous turn");
            if (value.has("stop") && !value.get("stop").isBoolean())
                throw new IllegalArgumentException("stop must be a boolean");
            boolean stop = value.path("stop").asBoolean(false);
            if (!stop && (!value.path("action").isTextual()
                    || (value.hasNonNull("choiceId") && !value.get("choiceId").isTextual())))
                throw new IllegalArgumentException("action and optional choiceId must be text");
            PlayerIntentReplay.FollowupAction next = stop ? null : new PlayerIntentReplay.FollowupAction(
                    value.get("action").asText(), value.hasNonNull("choiceId") ? value.get("choiceId").asText() : null);
            verifyFrozen(repository, frozen, loaded);
            write(actions.resolve(ordinal + ".accepted.json"), mapper, Map.of(
                    "action", value, "sha256", hash(bytes), "selectedAt", Instant.now().toString(),
                    "previousRequestId", previous.requestId()));
            if (stop) {
                manuallyStopped.set(true);
                return null;
            }
            return next;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("review wait interrupted; stop without further calls", error);
        } catch (Exception error) {
            throw new IllegalStateException("reviewed action unavailable; stop without further calls", error);
        }
    }

    private static JsonNode freshBudget(ObjectMapper mapper, Path proxy) throws Exception {
        JsonNode budget = mapper.readTree(proxy.resolve("capture-manifest.json").toFile());
        if (budget.path("formatVersion").asInt() != 1 || budget.path("maxCalls").asInt() != MAX_CALLS
                || budget.path("priorCalls").asInt(-1) != 0
                || budget.path("acceptCompleteJsonOnTruncatedTransfer").asBoolean(true)
                || !reservations(proxy).isEmpty() || !captureFiles(proxy).isEmpty())
            throw new IllegalArgumentException("fresh exclusive six-call capture budget required");
        return budget;
    }

    private static List<Path> reservations(Path proxy) throws Exception {
        try (var files = Files.list(proxy)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".reservation.json")).sorted().toList();
        }
    }

    private static List<JsonNode> actualParameters(ObjectMapper mapper, List<Path> captures) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (Path path : captures) {
            JsonNode request = mapper.readTree(path.toFile()).path("request");
            ObjectNode value = mapper.createObjectNode().put("capture", path.getFileName().toString());
            for (String field : List.of("model", "temperature", "max_tokens", "max_completion_tokens", "stream", "thinking"))
                if (request.has(field)) value.set(field, request.get(field));
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static void verifyActualParameters(List<JsonNode> requests) {
        for (JsonNode request : requests) {
            if (!"deepseek-v4-flash".equals(request.path("model").asText())
                    || !request.path("temperature").isNumber() || request.path("temperature").asDouble() != 0.3
                    || request.has("max_tokens") || request.has("max_completion_tokens")
                    || request.path("stream").asBoolean(false)
                    || !"disabled".equals(request.path("thinking").path("type").asText()))
                throw new IllegalStateException("captured request differs from frozen single-candidate parameters");
        }
    }

    private static Map<String, String> sourceHashes(Path repository) throws Exception {
        Map<String, String> result = new TreeMap<>();
        // 分别冻结原始材料与实际 classpath 产物，不能只凭源码文件推断实际运行版本。
        for (String folder : List.of("apps/game-api/src/main/java", "apps/game-api/src/test/java",
                "apps/game-api/src/main/resources", "apps/game-api/src/test/resources",
                "apps/game-api/target/classes", "apps/game-api/target/test-classes")) {
            try (var files = Files.walk(repository.resolve(folder))) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList())
                    result.put(repository.relativize(file).toString(), hash(Files.readAllBytes(file)));
            }
        }
        for (String name : List.of("apps/game-api/pom.xml", "apps/game-api/scripts/capture-synthetic-model.py"))
            result.put(name, hash(Files.readAllBytes(repository.resolve(name))));
        return result;
    }

    private static void verifyFrozen(Path repository, Map<String, String> frozen, LoadedCase loaded) throws Exception {
        if (!frozen.equals(sourceHashes(repository)) || !loaded.sha256().equals(hash(Files.readAllBytes(loaded.path()))))
            throw new IllegalStateException("frozen code, prompt, runtime or initial case changed; stop without further calls");
    }

    private static String hash(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static void write(Path target, ObjectMapper mapper, Object value) throws Exception {
        writePrivate(target, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }
}
