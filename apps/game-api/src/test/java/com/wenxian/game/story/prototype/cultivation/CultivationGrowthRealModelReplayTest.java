package com.wenxian.game.story.prototype.cultivation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.model.domain.*;
import com.wenxian.game.model.infrastructure.OpenAiCompatibleChatModelFactory;
import com.wenxian.game.story.agent.ai.NarrativeWriterAi;
import com.wenxian.game.story.prototype.cultivation.CultivationGrowthReplay.*;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式启用的单轮真实模型实验入口；必须使用同一限 18 次的回环取证代理，禁止直连供应商。
 * 每轮最多一次结构修复，无 SDK 重试或模型回退；HTTP 错误/超时不自动重试，仍占代理预算。
 * 单个原子检查点包含状态、经历、正文；仅恢复本批合成文件，不启动 Spring 或访问数据库。
 */
@EnabledIfEnvironmentVariable(named = "SYNTHETIC_GROWTH_REPLAY", matches = "1")
class CultivationGrowthRealModelReplayTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final List<Map<String, Object>> calls = new ArrayList<>();

    @Test
    void runOneReviewedActionWithinTheSharedCaptureBudget() throws Exception {
        String baseUrl = required("GROWTH_REPLAY_BASE_URL");
        URI uri = URI.create(baseUrl);
        assertTrue(uri.getScheme().equals("http") && uri.getHost().equals("127.0.0.1")
                && uri.getPort() >= 1024 && uri.getUserInfo() == null, "only the local capture proxy is allowed");
        Path root = Path.of(required("GROWTH_REPLAY_ROOT")).toRealPath();
        Path repository = Path.of("../..").toRealPath();
        assertTrue(!root.startsWith(repository), "synthetic originals must remain outside the repository");
        String key = required("DEEPSEEK_API_KEY");
        String action = Files.readString(root.resolve("next-action.txt"), StandardCharsets.UTF_8).strip();
        CultivationGrowthReplay replay = new CultivationGrowthReplay(mapper);
        try (FileChannel channel = FileChannel.open(root.resolve("replay.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            assertNotNull(lock, "another replay process already owns this batch");
            Path checkpointPath = root.resolve("checkpoint.json");
            Checkpoint before = Files.exists(checkpointPath)
                    ? mapper.readValue(checkpointPath.toFile(), Checkpoint.class) : replay.initial();
            Map<String, Object> input = replay.directorInput(before, action);
            int turn = before.growth().lastTurn() + 1;
            String resumeFile = System.getenv("GROWTH_REPLAY_RESUME_DIRECTOR");
            DirectorDraft resumedDraft = null;
            if (resumeFile != null && !resumeFile.isBlank()) {
                Path resume = Path.of(resumeFile).toRealPath();
                assertEquals(root, resume.getParent(), "resume must use this private batch's failure evidence");
                var failure = mapper.readTree(resume.toFile());
                assertEquals("FAILED_CHECKPOINT_UNCHANGED", failure.path("status").asText());
                assertEquals(turn, failure.path("turn").asInt());
                assertEquals(action, failure.path("action").asText());
                // 证据是集合；跨 JVM 序列化顺序可变，比较类型化值而不是 JSON 数组顺序。
                assertTrue(before.equals(mapper.treeToValue(failure.path("before"), Checkpoint.class)),
                        "resume checkpoint mismatch");
                assertEquals(2, failure.path("calls").size());
                assertEquals("director", failure.path("calls").get(0).path("phase").asText());
                assertEquals("director-repair", failure.path("calls").get(1).path("phase").asText());
                assertTrue(failure.path("calls").findValues("success").stream().allMatch(v -> v.asBoolean(false)));
                assertTrue(Files.isRegularFile(root.resolve("turn-" + turn + ".started")));
                resumedDraft = mapper.treeToValue(failure.path("repairedDirectorDraft"), DirectorDraft.class);
            }
            // 同一回合执行结果不明时禁止新进程再调用；先检查原件，不重置整批预算。
            if (resumedDraft == null) Files.createFile(root.resolve("turn-" + turn + ".started"));
            String directorPrompt = prompt("director");
            String writerPrompt = prompt("writer");
            Map<String, Object> manifest = Map.of("model", "deepseek-v4-flash", "proxy", baseUrl,
                    "maximumSupplierCalls", 18, "worldContentHash", before.worldContentHash(),
                    "directorPromptSha256", sha(directorPrompt), "writerPromptSha256", sha(writerPrompt),
                    "directorTemperature", 0.3, "writerTemperature", 0.8, "sdkRetries", 0);
            Path manifestPath = root.resolve("manifest.json");
            if (Files.exists(manifestPath)) {
                assertEquals(mapper.valueToTree(manifest), mapper.readTree(manifestPath.toFile()),
                        "the experiment configuration changed within the batch");
            } else {
                Files.writeString(manifestPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                        StandardOpenOption.CREATE_NEW);
            }
            var definition = new ModelDefinition("DEEPSEEK", "SYNTHETIC_GROWTH", baseUrl,
                    ModelAdapterType.OPENAI_COMPATIBLE, "deepseek-v4-flash",
                    Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL),
                    128_000, 6000);
            var factory = new OpenAiCompatibleChatModelFactory();
            var director = AiServices.builder(DirectorAi.class).systemMessage(directorPrompt)
                    .chatModel(factory.create(definition, ModelCredential.platform(key),
                            new ModelInvocationOptions(0.3, Duration.ofSeconds(150), 0, 4000, true))).build();
            var writer = AiServices.builder(NarrativeWriterAi.class).systemMessage(writerPrompt)
                    .chatModel(factory.create(definition, ModelCredential.platform(key),
                            new ModelInvocationOptions(0.8, Duration.ofSeconds(150), 0, 3200, true))).build();
            Map<String, Object> attempt = new LinkedHashMap<>();
            attempt.put("turn", turn);
            attempt.put("action", action);
            attempt.put("before", before);
            attempt.put("directorInput", input);
            boolean repaired = resumedDraft != null;
            try {
                Prepared prepared;
                if (resumedDraft != null) {
                    // 仅恢复已明确失败且未写检查点的本地提案，不再调用导演，也不额外开放修复。
                    attempt.put("resumedFromFailure", Path.of(resumeFile).getFileName().toString());
                    attempt.put("directorDraft", resumedDraft);
                    prepared = replay.prepare(before, action, resumedDraft);
                    Files.createFile(root.resolve("turn-" + turn + "-director-resume.started"));
                } else {
                    try {
                        String json = mapper.writeValueAsString(input);
                        DirectorDraft draft = invoke("director", () -> director.direct(json).content());
                        attempt.put("directorDraft", draft);
                        prepared = replay.prepare(before, action, draft);
                    } catch (RuntimeException error) {
                        if (!repairable(error)) throw error;
                        repaired = true;
                        Map<String, Object> repair = new LinkedHashMap<>(input);
                        repair.put("repair", Map.of("reason", safeReason(error),
                                "instruction", "按原行动和状态修复结构或引用，不追加行动，不为通过校验虚构成长。"));
                        if (attempt.containsKey("directorDraft")) repair.put("rejectedDraft", attempt.get("directorDraft"));
                        String json = mapper.writeValueAsString(repair);
                        DirectorDraft draft = invoke("director-repair", () -> director.direct(json).content());
                        attempt.put("repairedDirectorDraft", draft);
                        prepared = replay.prepare(before, action, draft);
                    }
                }
                attempt.put("writerInput", prepared.writerInput());
                Checkpoint completed;
                try {
                    String json = mapper.writeValueAsString(prepared.writerInput());
                    var prose = invoke("writer", () -> writer.write(json).content());
                    attempt.put("writerDraft", prose);
                    completed = replay.complete(prepared, prose);
                } catch (RuntimeException error) {
                    if (repaired || !repairable(error)) throw error;
                    Map<String, Object> repair = new LinkedHashMap<>(prepared.writerInput());
                    repair.put("repair", Map.of("reason", safeReason(error),
                            "instruction", "保持已接受的结果与末态，只修复正文输出结构，不新增事实。"));
                    if (attempt.containsKey("writerDraft")) repair.put("rejectedDraft", attempt.get("writerDraft"));
                    String json = mapper.writeValueAsString(repair);
                    var prose = invoke("writer-repair", () -> writer.write(json).content());
                    attempt.put("repairedWriterDraft", prose);
                    completed = replay.complete(prepared, prose);
                }
                attempt.put("calls", calls);
                attempt.put("status", "ACCEPTED_IN_EXPERIMENT");
                writeNew(root.resolve("turn-" + turn + "-attempt.json"), attempt);
                Path pending = root.resolve("checkpoint-" + UUID.randomUUID() + ".pending");
                writeNew(pending, completed);
                Files.move(pending, checkpointPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                System.out.printf("GROWTH_REPLAY turn=%d calls=%d outcome=%s qualified=%s checkpointAccepted=true%n",
                        turn, calls.size(), prepared.outcome().resolution(), completed.growth().qualification() != null);
            } catch (Exception error) {
                attempt.put("calls", calls);
                attempt.put("status", "FAILED_CHECKPOINT_UNCHANGED");
                attempt.put("errorType", error.getClass().getSimpleName());
                writeNew(root.resolve("turn-" + turn + "-failure-" + UUID.randomUUID() + ".json"), attempt);
                throw new IllegalStateException("synthetic replay failed; inspect capture and unchanged checkpoint ("
                        + error.getClass().getSimpleName() + ")");
            }
        }
    }

    private <T> T invoke(String phase, Supplier<T> operation) {
        long started = System.nanoTime();
        boolean success = false;
        try {
            T result = operation.get();
            success = true;
            return result;
        } finally {
            calls.add(Map.of("phase", phase, "success", success, "latencyMs", (System.nanoTime() - started) / 1_000_000));
        }
    }

    private boolean repairable(RuntimeException error) {
        return error instanceof IllegalArgumentException || error.getClass().getSimpleName().equals("OutputParsingException");
    }

    private String safeReason(RuntimeException error) {
        // 仅暴露本地领域错误，供应商异常不在此修复或回传到模型。
        return error instanceof CultivationGrowthPrototype.Rejected rejected ? rejected.failure().name()
                : error instanceof CultivationGrowthReplay.InvalidReplay ? error.getMessage()
                : error instanceof IllegalArgumentException ? "invalid typed fields or references" : "invalid typed JSON";
    }

    private String prompt(String role) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("prompts/cultivation-replay-" + role + ".txt")) {
            assertNotNull(stream, "missing experiment prompt");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void writeNew(Path path, Object value) throws Exception {
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                StandardOpenOption.CREATE_NEW);
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing " + name);
        return value;
    }
}
