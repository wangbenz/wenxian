package com.wenxian.game.story.agent.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.wenxian.game.model.application.ModelInvocationService;
import com.wenxian.game.model.application.ModelRoutePolicy;
import com.wenxian.game.model.application.ModelTaskRouter;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.model.domain.ModelAdapterType;
import com.wenxian.game.model.domain.ModelCapability;
import com.wenxian.game.model.domain.ModelCredential;
import com.wenxian.game.model.domain.ModelDefinition;
import com.wenxian.game.model.domain.ModelInvocationOptions;
import com.wenxian.game.model.domain.ModelRouteCandidate;
import com.wenxian.game.model.domain.ModelTask;
import com.wenxian.game.model.infrastructure.AiServiceRegistry;
import com.wenxian.game.model.infrastructure.ChatModelFactory;
import com.wenxian.game.model.infrastructure.ChatModelRegistry;
import com.wenxian.game.model.infrastructure.OpenAiCompatibleChatModelFactory;
import com.wenxian.game.model.infrastructure.PromptResourceLoader;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextRequest;
import com.wenxian.game.story.agent.PromptContextAssembler.ContextSelection;
import com.wenxian.game.story.agent.PromptContextAssembler.KnowledgeRef;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryPromptContext.PendingDecision;
import com.wenxian.game.story.agent.StoryPromptContext.PreviousTurn;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.domain.CultivationSystem;
import com.wenxian.game.story.domain.ProtagonistCultivation;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryFactIntentCompiler;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.CultivationPatch;
import com.wenxian.game.story.domain.StoryPatch.CultivationResolution;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.DecisionCheckpoint;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleValue;
import com.wenxian.game.story.domain.StoryPatch.OutcomeResolution;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
import com.wenxian.game.story.domain.StoryPatch.TimePassage;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryPatch.TurnOutcome;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryProjection.CoreState;
import com.wenxian.game.story.domain.StoryProjection.EntityState;
import com.wenxian.game.story.domain.StoryProjection.KnowledgeState;
import com.wenxian.game.story.domain.StoryProjection.WorldFactState;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.domain.WorldPackage.RealmPhase;
import com.wenxian.game.story.reducer.StoryStateReducer;
import com.wenxian.game.story.support.StoryTestFixtures;
import org.junit.jupiter.api.Test;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Qwen/DP4 的隔离受控回放：使用正式 Prompt、类型化 AI Service、事实意图编译器和 Reducer，
 * 但不读取数据库、不修改模型目录、不写玩家命卷。结果只落在 target/model-evaluation。
 */
class QwenDp4ControlledReplayTest {
    private static final List<String> DIRECTOR_IDS = List.of(
            "D01", "D02", "D03", "D04", "D05", "D06",
            "D07", "D08", "D09", "D10", "D11", "D12");
    private static final List<String> WRITER_IDS = List.of(
            "W01", "W02", "W03", "W04", "W05", "W06",
            "W07", "W08", "W09", "W10", "W11", "W12");
    private static final List<String> CHAIN_IDS = List.of("C01", "C02", "C03");
    private static final Pattern JADE_AFFIRMED = Pattern.compile(
            "(?:你|手中|掌心|怀中).{0,8}(?:握着|握住|拿出|掏出|取出).{0,8}玉佩|玉佩.{0,8}(?:亮起|发光|震动|回应)");
    private static final Pattern SOLD_SWORD_AFFIRMED = Pattern.compile(
            "(?:拔出|抽出|握住|挥动|腰间).{0,10}(?:旧剑|青锋)|(?:旧剑|青锋).{0,10}(?:在手|出鞘|腰间)");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PromptContextAssembler contexts = new PromptContextAssembler();
    private final StoryFactIntentCompiler compiler = new StoryFactIntentCompiler();

    @Test
    void runsDesignedReplayWithoutTouchingProductRoutes() throws Exception {
        String key = System.getenv("MODEL_EVAL_API_KEY");
        String baseUrl = System.getenv("MODEL_EVAL_BASE_URL");
        assumeTrue(key != null && !key.isBlank(), "set MODEL_EVAL_API_KEY to run controlled replay");
        assumeTrue(baseUrl != null && !baseUrl.isBlank(), "set MODEL_EVAL_BASE_URL to run controlled replay");

        List<ModelRuntime> models = List.of(
                runtime("QWEN", "qwen3.7-flash-2026-07-15", baseUrl, key),
                runtime("DEEPSEEK", "deepseek-v4-flash", baseUrl, key));
        Set<String> selected = selectedScenarios();
        int repeats = Integer.parseInt(System.getenv().getOrDefault("MODEL_EVAL_REPEATS", "3"));
        List<Callable<List<ResultRow>>> jobs = new ArrayList<>();
        for (int run = 1; run <= repeats; run++) {
            for (String id : DIRECTOR_IDS) {
                if (!selected.isEmpty() && !selected.contains(id)) continue;
                for (ModelRuntime model : alternating(models, run, id)) {
                    int currentRun = run;
                    jobs.add(() -> List.of(runDirector(model, id, currentRun)));
                }
            }
            for (String id : WRITER_IDS) {
                if (!selected.isEmpty() && !selected.contains(id)) continue;
                for (ModelRuntime model : alternating(models, run, id)) {
                    int currentRun = run;
                    jobs.add(() -> List.of(runWriter(model, id, currentRun)));
                }
            }
        }
        for (String id : CHAIN_IDS) {
            if (!selected.isEmpty() && !selected.contains(id)) continue;
            for (ModelRuntime model : alternating(models, 1, id)) {
                jobs.add(() -> runChain(model, id));
            }
        }

        List<ResultRow> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        ExecutorCompletionService<List<ResultRow>> completion = new ExecutorCompletionService<>(pool);
        jobs.forEach(completion::submit);
        try {
            for (int completed = 1; completed <= jobs.size(); completed++) {
                results.addAll(completion.take().get());
                if (completed % 10 == 0 || completed == jobs.size()) {
                    System.out.printf("MODEL_EVAL_PROGRESS jobs=%d/%d calls=%d%n",
                            completed, jobs.size(), results.size());
                }
            }
        } finally {
            pool.shutdown();
            assertEquals(true, pool.awaitTermination(30, TimeUnit.SECONDS));
        }

        results.sort(Comparator.comparing(ResultRow::scenario)
                .thenComparing(ResultRow::model)
                .thenComparingInt(ResultRow::run)
                .thenComparingInt(ResultRow::chainTurn));
        Path directory = Path.of("target", "model-evaluation");
        Files.createDirectories(directory);
        String mode = explicitNoThinking() ? "no-thinking" : "adapter-default";
        if (strictWriterFields()) mode += "-strict-writer-fields";
        if (useQwenJsonSchema()) mode += "-qwen-json-schema";
        Path output = directory.resolve("qwen-dp4-controlled-replay-" + mode + "-"
                + Instant.now().toEpochMilli() + ".json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), results);

        long calls = results.size();
        long callFailures = results.stream().filter(value -> "CALL_FAIL".equals(value.status())).count();
        long ruleFailures = results.stream().filter(value -> "RULE_FAIL".equals(value.status())).count();
        System.out.printf("MODEL_EVAL_RESULT path=%s calls=%d callFailures=%d ruleFailures=%d%n",
                output.toAbsolutePath(), calls, callFailures, ruleFailures);
        assertFalse(results.isEmpty());
    }

    private Set<String> selectedScenarios() {
        String value = System.getenv("MODEL_EVAL_SCENARIOS");
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::strip).filter(item -> !item.isBlank()).collect(java.util.stream.Collectors.toSet());
    }

    private ResultRow runDirector(ModelRuntime runtime, String id, int run) {
        long started = System.nanoTime();
        try {
            StoryProjection projection = projection(id);
            StoryPromptContext context = context(id, projection, null, null);
            DirectedNarrativeGenerator.Session session = runtime.generator().open(invocation(runtime, id, run));
            DirectedNarrativeGenerator.DirectedPlan directed = session.plan(context);
            StoryPatch compiled = compiler.compile(projection, directed.decision());
            new StoryStateReducer(new com.wenxian.game.story.domain.StoryPatchValidator(),
                    prefix -> prefix + "-eval").reduce(projection, compiled);
            List<String> issues = directorIssues(id, directed.decision(), compiled);
            return success(runtime, "TURN_DIRECTOR", id, run, 0, started, issues,
                    directed.modelCalls(), directed.decision());
        } catch (RuntimeException error) {
            return failure(runtime, "TURN_DIRECTOR", id, run, 0, started, error);
        }
    }

    private ResultRow runWriter(ModelRuntime runtime, String id, int run) {
        long started = System.nanoTime();
        try {
            StoryProjection projection = projection(id);
            StoryPromptContext context = context(id, projection, null, null);
            StoryPatch accepted = writerPlan(id, projection);
            DirectedNarrativeGenerator.Session session = runtime.generator().open(invocation(runtime, id, run));
            var applied = new StoryStateReducer(new com.wenxian.game.story.domain.StoryPatchValidator(),
                    prefix -> prefix + "-writer-eval").reduce(projection, accepted);
            DirectedNarrativeGenerator.RenderedNarrative rendered = session.render(
                    context, applied.acceptedPatch(), NarrativeEndState.from(applied.projection(), applied.acceptedPatch()));
            List<String> issues = writerIssues(id, rendered);
            return success(runtime, "NARRATIVE_WRITER", id, run, 0, started, issues,
                    rendered.modelCalls(), renderedPayload(rendered));
        } catch (RuntimeException error) {
            return failure(runtime, "NARRATIVE_WRITER", id, run, 0, started, error);
        }
    }

    private List<ResultRow> runChain(ModelRuntime runtime, String id) {
        List<ResultRow> rows = new ArrayList<>();
        StoryProjection projection = projection(id);
        PreviousTurn previous = chainSeed(id);
        String rollingSummary = previous.acceptedPatchSummary();
        for (int turn = 1; turn <= 3; turn++) {
            int currentTurn = turn;
            String scenario = id;
            String action = chainAction(id, turn);
            PendingDecision pending = "C03".equals(id)
                    ? new PendingDecision("是否签署尚未确认完整条款的血契？") : null;
            ContextRequest request = request(id, action, rollingSummary, previous, pending);
            StoryPromptContext context = contexts.assemble(projection, request);
            DirectedNarrativeGenerator.Session session = runtime.generator().open(
                    invocation(runtime, id + "-chain", turn));

            long directorStarted = System.nanoTime();
            try {
                DirectedNarrativeGenerator.DirectedPlan directed = session.plan(context);
                StoryPatch compiled = compiler.compile(projection, directed.decision());
                StoryStateReducer.Applied applied = new StoryStateReducer(
                        new com.wenxian.game.story.domain.StoryPatchValidator(),
                        prefix -> prefix + "-chain-" + currentTurn).reduce(projection, compiled);
                List<String> directorIssues = chainDirectorIssues(id, directed.decision(), applied.projection());
                rows.add(success(runtime, "TURN_DIRECTOR", scenario, 1, turn, directorStarted,
                        directorIssues, directed.modelCalls(), directed.decision()));

                long writerStarted = System.nanoTime();
                try {
                    DirectedNarrativeGenerator.RenderedNarrative rendered = session.render(
                            context, applied.acceptedPatch(), NarrativeEndState.from(applied.projection(), applied.acceptedPatch()));
                    List<String> writerIssues = chainWriterIssues(id, rendered);
                    rows.add(success(runtime, "NARRATIVE_WRITER", scenario, 1, turn, writerStarted,
                            writerIssues, rendered.modelCalls(), renderedPayload(rendered)));
                    projection = applied.projection();
                    rollingSummary = rendered.rollingSummary();
                    previous = new PreviousTurn(turn, action, rendered.title(), rendered.narrative(),
                            rendered.rollingSummary());
                } catch (RuntimeException error) {
                    rows.add(failure(runtime, "NARRATIVE_WRITER", scenario, 1, turn, writerStarted, error));
                    break;
                }
            } catch (RuntimeException error) {
                rows.add(failure(runtime, "TURN_DIRECTOR", scenario, 1, turn, directorStarted, error));
                break;
            }
        }
        return rows;
    }

    private List<String> directorIssues(String id, StoryTurnDecision decision, StoryPatch compiled) {
        List<String> issues = new ArrayList<>();
        String text = json(decision);
        switch (id) {
            case "D01" -> {
                require(issues, decision.duration().unit() == SemanticDuration.Unit.SCENE, "TIME_NOT_SCENE");
                require(issues, decision.entities().isEmpty() && decision.worldFacts().isEmpty(),
                        "UNNEEDED_FACT_CHANGE");
                rejectAny(issues, text, "擅自进入山门", "进入了山门", "推门而入");
            }
            case "D02" -> {
                require(issues, decision.outcome().resolution() != OutcomeResolution.SUCCEEDED,
                        "PHANTOM_ITEM_ACTION_SUCCEEDED");
                require(issues, decision.entities().stream().noneMatch(value -> contains(value.name(), "玉佩")),
                        "PHANTOM_ITEM_CREATED");
                rejectPattern(issues, text, JADE_AFFIRMED, "PHANTOM_ITEM_AFFIRMED");
            }
            case "D03" -> {
                require(issues, decision.outcome().resolution() != OutcomeResolution.SUCCEEDED,
                        "SOLD_ITEM_ACTION_SUCCEEDED");
                require(issues, compiled.entities().stream().noneMatch(value ->
                                "item-sold-sword".equals(value.entityId())
                                        && (value.operation() == EntityOperation.RESTORE
                                        || Boolean.TRUE.equals(value.inPossession()))),
                        "SOLD_ITEM_RESTORED");
                rejectPattern(issues, text, SOLD_SWORD_AFFIRMED, "SOLD_ITEM_USED");
            }
            case "D04" -> {
                compiled.entities().stream().filter(value -> "item-herbs".equals(value.entityId()))
                        .map(EntityPatch::quantity).filter(value -> value != null && value.known())
                        .forEach(value -> require(issues, value.value() != null && value.value() >= 0,
                                "KNOWN_QUANTITY_INVALID"));
            }
            case "D05" -> {
                require(issues, decision.worldFacts().stream().noneMatch(value ->
                                contains(value.statement(), "天门已经断绝") || contains(value.statement(), "天门不存在")),
                        "BELIEF_PROMOTED_TO_WORLD_FACT");
                rejectAny(issues, text, "确认天门已经断绝", "证实天门不存在");
            }
            case "D06" -> {
                require(issues, decision.decisionCheckpoint().required(), "MISSING_DECISION_CHECKPOINT");
                require(issues, decision.worldFacts().isEmpty(), "UNKNOWN_CONTRACT_TERMS_PERSISTED");
                rejectAny(issues, text, "签下血契", "血契已经成立", "违约将被");
            }
            case "D07" -> {
                require(issues, decision.duration().unit() == SemanticDuration.Unit.PHASE,
                        "OVERNIGHT_WRONG_DURATION_UNIT");
                require(issues, "DAWN".equalsIgnoreCase(decision.duration().targetPhase()),
                        "OVERNIGHT_WRONG_TARGET_PHASE");
                rejectAny(issues, text, "出门", "开始赶路", "前往大厅");
            }
            case "D08" -> {
                require(issues, decision.duration().unit() == SemanticDuration.Unit.MONTH,
                        "CULTIVATION_WRONG_DURATION");
                require(issues, decision.cultivation() != null, "CULTIVATION_INTENT_MISSING");
                if (decision.cultivation() != null) {
                    require(issues, !decision.cultivation().breakthroughAttempted(),
                            "UNAUTHORIZED_BREAKTHROUGH_ATTEMPT");
                }
                require(issues, decision.coreState() == null || blank(decision.coreState().realm()),
                        "MODEL_WROTE_REALM_DIRECTLY");
            }
            case "D09" -> {
                require(issues, decision.cultivation() != null, "BREAKTHROUGH_INTENT_MISSING");
                if (decision.cultivation() != null) {
                    require(issues, decision.cultivation().breakthroughAttempted(),
                            "EXPLICIT_BREAKTHROUGH_NOT_RECORDED");
                }
                require(issues, decision.coreState() == null || blank(decision.coreState().realm()),
                        "MODEL_WROTE_REALM_DIRECTLY");
            }
            case "D10" -> {
                require(issues, decision.entities().stream().noneMatch(value ->
                                value.lifecycle() != null && value.lifecycle().state() == LifecycleState.DEAD),
                        "PROBE_KILLED_OPPONENT");
                rejectAny(issues, text, "追击", "搜身", "取走战利品");
            }
            case "D11" -> {
                require(issues, compiled.entities().stream().anyMatch(value -> "ITEM".equals(value.entityType())
                                && value.quantity() != null && value.quantity().known()
                                && Integer.valueOf(1).equals(value.quantity().value())
                                && value.custody() != null && value.custody().holderType() == HolderType.PLAYER),
                        "DELIVERED_ITEM_NOT_COMPILED");
            }
            case "D12" -> {
                require(issues, decision.entities().stream().noneMatch(value ->
                                "character-old-friend".equals(value.existingEntityId())
                                        && value.relationship() != null),
                        "UNAUTHORIZED_RELATIONSHIP_CHANGE");
                require(issues, compiled.entities().stream().noneMatch(value -> value.nameChange() != null),
                        "UNAUTHORIZED_NAME_CHANGE");
            }
            default -> throw new IllegalArgumentException("unknown director scenario " + id);
        }
        return List.copyOf(issues);
    }

    private List<String> writerIssues(String id, DirectedNarrativeGenerator.RenderedNarrative value) {
        List<String> issues = baseWriterIssues(value, !"W11".equals(id));
        String text = value.narrative() + "\n" + value.rollingSummary();
        switch (id) {
            case "W01" -> rejectPattern(issues, text, JADE_AFFIRMED, "PHANTOM_ITEM_AFFIRMED");
            case "W02" -> {
                require(issues, containsAny(text, "未证实", "无法确认", "不能确定", "尚不能证明", "仍是未知"),
                        "UNCERTAINTY_LOST");
                rejectAny(issues, text, "天门已经断绝", "天门确定不存在");
            }
            case "W03" -> rejectAny(issues, text, "天亮", "晨光", "次日", "翌日", "一夜", "整夜");
            case "W04" -> rejectAny(issues, text, "走出房门", "出门", "前往大厅", "开始赶路", "已经决定");
            case "W05" -> rejectAny(issues, text, "获得一枚进展", "进度增加", "修为提升", "更进一步", "突破了");
            case "W06" -> {
                require(issues, containsAny(text, "还差一", "尚差一", "再积一", "二/三", "2/3"),
                        "PROGRESS_REMAINDER_INACCURATE");
                rejectAny(issues, text, "还差两", "尚差两", "突破成功", "进入中期");
            }
            case "W07" -> rejectAny(issues, text, "签下血契", "血契已经成立", "违约将被", "条款规定");
            case "W08" -> rejectAny(issues, text, "追击", "搜身", "击杀", "取走战利品");
            case "W09" -> rejectAny(issues, text, "正式结盟", "拜师", "收徒", "生死之交", "好感度");
            case "W10" -> rejectPattern(issues, text, SOLD_SWORD_AFFIRMED, "SOLD_ITEM_USED");
            case "W11" -> { }
            case "W12" -> rejectAny(issues, text, "包治", "突破丹", "增长修为", "解毒", "疗伤圣药");
            default -> throw new IllegalArgumentException("unknown writer scenario " + id);
        }
        return List.copyOf(issues);
    }

    private List<String> chainDirectorIssues(String id, StoryTurnDecision decision, StoryProjection after) {
        List<String> issues = new ArrayList<>();
        String text = json(decision);
        if ("C01".equals(id)) {
            require(issues, after.entities().values().stream().noneMatch(value -> contains(value.name(), "玉佩")),
                    "PHANTOM_ITEM_PERSISTED");
            rejectPattern(issues, text, JADE_AFFIRMED, "PHANTOM_ITEM_AFFIRMED");
        } else if ("C02".equals(id)) {
            require(issues, after.worldFacts().values().stream().noneMatch(value ->
                            contains(value.statement(), "天门已经断绝") || contains(value.statement(), "天门不存在")),
                    "BELIEF_OVERWROTE_WORLD_FACT");
            rejectAny(issues, text, "确认天门已经断绝", "证实天门不存在");
        } else if ("C03".equals(id)) {
            rejectAny(issues, text, "签下血契", "血契已经成立", "开始履行契约");
            require(issues, decision.decisionCheckpoint().required(), "PENDING_DECISION_LOST");
        }
        return List.copyOf(issues);
    }

    private List<String> chainWriterIssues(String id, DirectedNarrativeGenerator.RenderedNarrative value) {
        List<String> issues = baseWriterIssues(value, false);
        String text = value.narrative() + "\n" + value.rollingSummary();
        if ("C01".equals(id)) rejectPattern(issues, text, JADE_AFFIRMED, "PHANTOM_ITEM_AFFIRMED");
        if ("C02".equals(id)) {
            require(issues, containsAny(text, "未证实", "无法确认", "不能确定", "尚不能证明", "仍是未知"),
                    "UNCERTAINTY_LOST");
            rejectAny(issues, text, "天门已经断绝", "天门确定不存在");
        }
        if ("C03".equals(id)) rejectAny(issues, text, "签下血契", "血契已经成立", "开始履行契约");
        return List.copyOf(issues);
    }

    private List<String> baseWriterIssues(DirectedNarrativeGenerator.RenderedNarrative value,
                                          boolean enforceNormalLength) {
        List<String> issues = new ArrayList<>();
        require(issues, !blank(value.title()), "TITLE_BLANK");
        require(issues, !blank(value.narrative()), "NARRATIVE_BLANK");
        require(issues, !blank(value.rollingSummary()), "SUMMARY_BLANK");
        require(issues, value.choices().size() >= 2 && value.choices().size() <= 4, "CHOICE_COUNT_INVALID");
        require(issues, contains(value.narrative(), "你"), "SECOND_PERSON_MISSING");
        if (enforceNormalLength) {
            require(issues, value.narrative().length() >= 550, "QUALITY_TOO_SHORT");
            require(issues, value.narrative().length() <= 1_500, "QUALITY_TOO_LONG");
        }
        rejectAny(issues, value.narrative(), "```", "{\"title\"");
        return issues;
    }

    private StoryProjection projection(String id) {
        StoryWorldTime time = Set.of("D07", "W03", "W04").contains(id)
                ? new StoryWorldTime(1, 12, StoryWorldTime.Phase.NIGHT)
                : StoryWorldTime.opening();
        Map<String, EntityState> entities = new LinkedHashMap<>();
        Map<String, WorldFactState> facts = new LinkedHashMap<>();
        Map<String, KnowledgeState> knowledge = new LinkedHashMap<>();
        CultivationSystem system = null;
        ProtagonistCultivation cultivation = null;

        if (Set.of("D03", "W10").contains(id)) entities.put("item-sold-sword", soldSword());
        if ("D04".equals(id)) entities.put("item-herbs", unknownHerbs());
        if (Set.of("D05", "W02", "C02").contains(id)) {
            entities.put("character-mentor", mentor());
            facts.put("world-fact-ascension", uncertainGateFact());
            knowledge.put("mentor-gate-belief", new KnowledgeState("character-mentor", "world-fact-ascension",
                    "师尊相信天门已经断绝", List.of("turn:8")));
        }
        if ("D10".equals(id) || "W08".equals(id)) entities.put("character-blocker", blocker());
        if ("D12".equals(id) || "W09".equals(id)) entities.put("character-old-friend", oldFriend());
        if (Set.of("D08", "D09", "W05", "W06").contains(id)) {
            system = cultivationSystem();
            int marks = "W06".equals(id) ? 1 : ("D09".equals(id) ? 0 : 1);
            cultivation = new ProtagonistCultivation("cultivation-eval", "stage-02", RealmPhase.EARLY,
                    marks, 3, marks + "/3，气息逐渐凝实");
        }
        return new StoryProjection(time, new CoreState("青石山门前", "平静", "炼气初期", "寻找问道路"),
                entities, facts, knowledge, List.of(), StoryTestFixtures.creationContract(),
                StoryTestFixtures.playPreference(), system, cultivation);
    }

    private StoryPromptContext context(String id, StoryProjection projection,
                                       String rollingSummary, PreviousTurn previous) {
        return contexts.assemble(projection, request(id, action(id),
                rollingSummary == null ? summary(id) : rollingSummary, previous,
                pendingDecision(id)));
    }

    private ContextRequest request(String id, String action, String summary, PreviousTurn previous,
                                   PendingDecision pending) {
        ContextSelection selection = selection(id);
        SemanticDuration duration = switch (id) {
            case "D07", "W04" -> new SemanticDuration(SemanticDuration.Unit.PHASE, 1, "DAWN");
            case "D08", "D09" -> new SemanticDuration(SemanticDuration.Unit.MONTH, 1, "");
            default -> null;
        };
        return new ContextRequest(action, List.of(), selection, summary, previous, pending,
                null, duration, previous == null ? List.of() : List.of(previous));
    }

    private ContextSelection selection(String id) {
        return switch (id) {
            case "D03", "W10" -> new ContextSelection(Set.of("item-sold-sword"),
                    Set.of("item-sold-sword"), Set.of(), List.of(), Set.of());
            case "D04" -> new ContextSelection(Set.of("item-herbs"), Set.of("item-herbs"),
                    Set.of(), List.of(), Set.of());
            case "D05", "W02", "C02" -> new ContextSelection(Set.of("character-mentor"),
                    Set.of("character-mentor"), Set.of("world-fact-ascension"),
                    List.of(new KnowledgeRef("character-mentor", "world-fact-ascension")), Set.of());
            case "D10", "W08" -> new ContextSelection(Set.of("character-blocker"),
                    Set.of("character-blocker"), Set.of(), List.of(), Set.of());
            case "D12", "W09" -> new ContextSelection(Set.of("character-old-friend"),
                    Set.of("character-old-friend"), Set.of(), List.of(), Set.of());
            default -> ContextSelection.empty();
        };
    }

    private PendingDecision pendingDecision(String id) {
        return Set.of("D06", "W07", "C03").contains(id)
                ? new PendingDecision("是否签署尚未确认完整条款的血契？") : null;
    }

    private String action(String id) {
        return switch (id) {
            case "D01" -> "站在原地仔细观察门上的旧纹，观察完成后停下，不进入山门";
            case "D02" -> "从行囊里拿出祖传玉佩照向石门，看看是否有反应";
            case "D03" -> "拔出已经卖掉的青锋旧剑试一招，随后停下";
            case "D04" -> "清点这包来源不明的灵材，清点完成后停下";
            case "D05" -> "核对师尊关于天门已经断绝的说法，只整理能确认和不能确认的部分";
            case "D06" -> "只查看血契目前能够看清的内容，列出已知和未知，不签署";
            case "D07" -> "睡到第二天黎明，醒来后停下，不做其他事情";
            case "D08" -> "闭关一个月稳固当前修为，不尝试突破，结束后停下";
            case "D09" -> "闭关一个月明确尝试突破当前大境界，结果落定后停下";
            case "D10" -> "与拦路者只对一招试探深浅，随后拉开距离，不追击";
            case "D11" -> "接过陌生药师递来的一枚丹药，收好后停下，不服用";
            case "D12" -> "与旧识林照寒暄几句，听完回应后停下，不讨论称呼或关系";
            case "W01" -> "尝试拿出祖传玉佩照向石门";
            case "W02" -> "整理天门是否存在的已知与未知";
            case "W03" -> "在原地观察夜色中的石门，观察后停下";
            case "W04" -> "睡到第二天黎明，醒来后停下";
            case "W05" -> "运转功法稳固气息";
            case "W06" -> "闭关一个月稳固修为";
            case "W07" -> "只查看血契，不签署";
            case "W08" -> "与拦路者只对一招后拉开距离";
            case "W09" -> "与林照交谈，确认他是否愿意继续交流";
            case "W10" -> "整理当前随身物品后停下";
            case "W11" -> "用手拂去石碑上的一层薄灰，看清一行字后停下";
            case "W12" -> "接过药师递来的一枚丹药并收好，不服用";
            default -> "观察当前处境，整理完成后停下";
        };
    }

    private String summary(String id) {
        return switch (id) {
            case "W10" -> "你曾经佩戴青锋旧剑多年，后来已经将它售出，当前不再持有。";
            case "W02", "D05", "C02" -> "师尊曾说他相信天门已经断绝，但客观上仍无法证明天门是否存在。";
            default -> "你来到青石山门前，尚未进入，也没有作出新的重大决定。";
        };
    }

    private StoryPatch writerPlan(String id, StoryProjection projection) {
        SemanticDuration duration = switch (id) {
            case "W04" -> new SemanticDuration(SemanticDuration.Unit.PHASE, 1, "DAWN");
            case "W06" -> new SemanticDuration(SemanticDuration.Unit.MONTH, 1, "");
            default -> new SemanticDuration(SemanticDuration.Unit.SCENE, 1, "");
        };
        TurnOutcome outcome = switch (id) {
            case "W01", "W05" -> new TurnOutcome(OutcomeResolution.FAILED,
                    "当前权威状态不支持完成玩家声称的结果");
            case "W08" -> new TurnOutcome(OutcomeResolution.PARTIAL,
                    "双方只交换一招，主角按授权拉开距离，胜负未分");
            default -> new TurnOutcome(OutcomeResolution.SUCCEEDED, writerOutcome(id));
        };
        DecisionCheckpoint checkpoint = Set.of("W07").contains(id)
                ? new DecisionCheckpoint(true, "是否在条款尚未完全确认时签署血契？")
                : new DecisionCheckpoint(false, null);
        CultivationPatch cultivation = null;
        if ("W05".equals(id)) {
            cultivation = new CultivationPatch("stage-02", RealmPhase.EARLY, 1, 3,
                    false, "本轮没有形成新的修行进展", List.of("PLAYER_ACTION"),
                    CultivationResolution.UNCHANGED, "气息未能形成可保存的新变化");
        } else if ("W06".equals(id)) {
            cultivation = new CultivationPatch("stage-02", RealmPhase.EARLY, 2, 3,
                    false, "本轮形成一枚进展，当前二比三", List.of("PLAYER_ACTION"),
                    CultivationResolution.PROGRESSED, "本轮接受一枚修行进展");
        }
        List<EntityPatch> entities = new ArrayList<>();
        if ("W09".equals(id)) {
            entities.add(new EntityPatch(EntityOperation.UPDATE, "character-old-friend", "CHARACTER",
                    null, null, "林照明确愿意继续与主角交谈", null, null, null, null, null,
                    null, new RelationshipValue("愿意继续交谈的旧识", "本轮当面确认"), null, ""));
        }
        if ("W12".equals(id)) {
            entities.add(new EntityPatch(EntityOperation.CREATE, null, "ITEM", "无名丹药", "尚未鉴定",
                    "陌生药师本轮交付的一枚丹药，药效未知", null, new QuantityValue(true, 1), true,
                    new CustodyValue(HolderType.PLAYER, null), null, null));
        }
        return new StoryPatch("", "", new TimePassage(duration, TimeResolution.COMPLETE, null),
                null, entities, List.of(), List.of(), List.of(), checkpoint, List.of(), "", outcome, cultivation);
    }

    private String writerOutcome(String id) {
        return switch (id) {
            case "W02" -> "只确认目前仍不能证明天门是否存在";
            case "W03" -> "在同一夜晚时段内看清石门表面的浅痕并停下";
            case "W04" -> "完整休息到次日黎明，醒来后停下";
            case "W06" -> "本轮形成一枚修行进展，由一比三推进到二比三，尚差一枚";
            case "W07" -> "看清的内容不足以确认完整条款，停在是否签署的决定前";
            case "W09" -> "林照明确愿意继续交谈，但没有形成其他关系或承诺";
            case "W10" -> "确认当前没有青锋旧剑，整理完成后停下";
            case "W11" -> "拂去薄灰后看清石碑上写着‘北院’二字";
            case "W12" -> "接过一枚药效未知的丹药并由主角保管，未服用";
            default -> "行动的直接结果已经落定，并在下一决定前停下";
        };
    }

    private List<String> writerBeats(String id) {
        return switch (id) {
            case "W04" -> List.of("入睡", "时间推进到次日黎明", "醒来即停止");
            case "W06" -> List.of("运转功法", "形成一枚进展", "二比三处停止");
            case "W08" -> List.of("双方交换一招", "主角拉开距离", "胜负未分处停止");
            default -> List.of("执行玩家行动", "呈现直接结果", "在下一决定前停止");
        };
    }

    private PreviousTurn chainSeed(String id) {
        return switch (id) {
            case "C01" -> new PreviousTurn(7, "整理旧物", "祖传玉佩",
                    "你摸了摸一直佩戴在胸前的祖传玉佩，它曾多次为你打开禁制。",
                    "你一直持有能打开禁制的祖传玉佩。");
            case "C02" -> new PreviousTurn(7, "询问师尊", "天门旧闻",
                    "师尊断言天门已经彻底断绝，世间再无飞升可能。",
                    "天门已经断绝，飞升不再可能。");
            case "C03" -> new PreviousTurn(7, "查看血契", "血契",
                    "你按下指印签了血契，答应三日后替来使取回镇门法印。",
                    "你已经签署血契并承诺取回法印。");
            default -> throw new IllegalArgumentException("unknown chain " + id);
        };
    }

    private String chainAction(String id, int turn) {
        return switch (id) {
            case "C01" -> List.of("环顾四周并确认手边可用之物，随后停下",
                    "只观察石门表面，不使用任何物品，随后停下",
                    "坐下整理已知线索，不采取新行动").get(turn - 1);
            case "C02" -> List.of("区分师尊的观点和目前可确认的事实",
                    "只观察天象能提供的证据，不作确定结论",
                    "整理仍然未知的问题并停下").get(turn - 1);
            case "C03" -> List.of("只阅读目前能看清的契约文字，不作答",
                    "询问来使契约来源，不签署也不承诺",
                    "整理风险与未知，继续把决定留给我").get(turn - 1);
            default -> throw new IllegalArgumentException("unknown chain " + id);
        };
    }

    private EntityState soldSword() {
        return new EntityState("item-sold-sword", "ITEM", "青锋旧剑", "已经售出",
                "曾随身多年，现已不再持有", null, false, new QuantityValue(true, 0), false,
                new CustodyValue(HolderType.NONE, null), null, null);
    }

    private EntityState unknownHerbs() {
        return new EntityState("item-herbs", "ITEM", "一包灵材", "尚未清点", "来源不明",
                null, true, new QuantityValue(false, null), true,
                new CustodyValue(HolderType.PLAYER, null), null, null);
    }

    private EntityState mentor() {
        var attributes = JsonNodeFactory.instance.objectNode().put("identity", "旧宗门长老");
        return new EntityState("character-mentor", "CHARACTER", "旧日师尊", "DEAD", "旧宗门长老",
                attributes, true, null, null, null, null, LifecycleState.DEAD,
                new RelationshipValue("旧识", "曾经教导主角"));
    }

    private EntityState blocker() {
        return new EntityState("character-blocker", "CHARACTER", "蒙面拦路者", "ACTIVE", "身份未知",
                null, true, null, null, null, null, LifecycleState.ACTIVE, null);
    }

    private EntityState oldFriend() {
        return new EntityState("character-old-friend", "CHARACTER", "林照", "ACTIVE", "旧识",
                JsonNodeFactory.instance.objectNode().put("identity", "青石镇药铺学徒"), true,
                null, null, null, null, LifecycleState.ACTIVE,
                new RelationshipValue("旧识", "三年前在青石镇相识"));
    }

    private WorldFactState uncertainGateFact() {
        return new WorldFactState("world-fact-ascension", "世人尚不能证明天门是否存在", 2, true,
                WorldFactOperation.TRANSFORM, "百年前天象变化");
    }

    private CultivationSystem cultivationSystem() {
        List<String> names = List.of("引气", "炼气", "筑基", "金丹", "元婴", "化神");
        List<CultivationSystem.Stage> stages = new ArrayList<>();
        for (int index = 0; index < names.size(); index++) {
            String id = "stage-0" + (index + 1);
            List<String> next = index + 1 == names.size() ? List.of() : List.of("stage-0" + (index + 2));
            stages.add(new CultivationSystem.Stage(id, index + 1, names.get(index),
                    names.get(index) + "阶段", List.of(), next));
        }
        return new CultivationSystem("cultivation-eval", 1, "青冥炼真道", "借天地清气磨炼真形",
                "人族", "stage-01", stages,
                new CultivationSystem.Confirmation(
                        CultivationSystem.ConfirmationSource.PLAYER_CONFIRMED_RECOMMENDATION,
                        Instant.parse("2026-08-31T00:00:00Z")));
    }

    private ModelRuntime runtime(String provider, String apiModel, String baseUrl, String key) {
        Set<ModelCapability> capabilities = Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE,
                ModelCapability.THINKING_CONTROL, ModelCapability.CACHE_USAGE);
        ModelDefinition definition = new ModelDefinition(provider, apiModel, baseUrl,
                ModelAdapterType.OPENAI_COMPATIBLE, apiModel, capabilities, 128_000, 8_000);
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(
                CredentialSource.PLATFORM, List.of(new ModelRouteCandidate(definition,
                ModelCredential.platform(key), options(request.task()))));
        ModelTaskRouter router = new ModelTaskRouter(policy);
        ChatModelFactory factory = explicitNoThinking()
                ? this::createExplicitNoThinkingModel : new OpenAiCompatibleChatModelFactory();
        ChatModelRegistry models = new ChatModelRegistry(factory, 8);
        ModelInvocationService invocations = new ModelInvocationService(router, models);
        PromptResourceLoader promptLoader = strictWriterFields() ? strictPromptLoader() : new PromptResourceLoader();
        StoryAiServiceRegistry services = new StoryAiServiceRegistry(
                new AiServiceRegistry(promptLoader, 16));
        return new ModelRuntime(apiModel, new LangChain4jDirectedNarrativeGenerator(
                router, invocations, services, mapper));
    }

    /** 百炼的 enable_thinking 是非 OpenAI 标准扩展，只在隔离评测中显式透传，不改生产工厂。 */
    private dev.langchain4j.model.chat.ChatModel createExplicitNoThinkingModel(
            ModelDefinition definition, ModelCredential credential, ModelInvocationOptions options) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(definition.baseUrl())
                .apiKey(credential.secret())
                .modelName(definition.apiModelName())
                .temperature(options.temperature())
                .timeout(options.timeout())
                .maxRetries(options.maximumRetries())
                .customParameters(Map.of("enable_thinking", false))
                .sendThinking(false)
                .returnThinking(false)
                .logRequests(false)
                .logResponses(false);
        if (useQwenJsonSchema() && "QWEN".equalsIgnoreCase(definition.providerCode())) {
            builder.supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
        } else {
            builder.responseFormat(ResponseFormat.JSON);
        }
        if (options.maximumOutputTokens() != null) {
            builder.maxTokens(Math.min(options.maximumOutputTokens(), definition.maximumOutputTokens()));
        }
        return builder.build();
    }

    private boolean explicitNoThinking() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("MODEL_EVAL_EXPLICIT_NO_THINKING", "false"));
    }

    private boolean strictWriterFields() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("MODEL_EVAL_STRICT_WRITER_FIELDS", "false"));
    }

    private boolean useQwenJsonSchema() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("MODEL_EVAL_QWEN_JSON_SCHEMA", "false"));
    }

    /** 只在评测进程中追加 DTO 字段约束，用于区分模型能力问题与可修复的字段别名问题。 */
    private PromptResourceLoader strictPromptLoader() {
        return new PromptResourceLoader() {
            @Override
            public PromptResource load(ModelTask task) {
                PromptResource source = super.load(task);
                if (task != ModelTask.NARRATIVE_WRITER) return source;
                String suffix = "\n输出 JSON 顶层字段必须且只能是 title、narrative、choices、summary；"
                        + "正文必须写在 narrative，严禁使用 narr、content、body、story、text 等别名。"
                        + "choices 中每项必须且只能使用 title、description。";
                return new PromptResource(task, source.path(), source.version() + "-eval-strict-fields",
                        source.content() + suffix);
            }
        };
    }

    private ModelInvocationOptions options(ModelTask task) {
        return new ModelInvocationOptions(task == ModelTask.TURN_DIRECTOR ? 0.3 : 0.8,
                Duration.ofSeconds(90), 0, task == ModelTask.TURN_DIRECTOR ? 2_200 : 3_200, true);
    }

    private DirectedNarrativeGenerator.InvocationContext invocation(ModelRuntime runtime, String id, int run) {
        return new DirectedNarrativeGenerator.InvocationContext("eval-user",
                runtime.name() + "-" + id + "-" + run + "-" + UUID.randomUUID(),
                "eval-save-" + id, Math.max(1, run));
    }

    private List<ModelRuntime> alternating(List<ModelRuntime> models, int run, String id) {
        boolean reverse = Math.floorMod(run + id.hashCode(), 2) == 0;
        return reverse ? List.of(models.get(1), models.get(0)) : models;
    }

    private ResultRow success(ModelRuntime runtime, String task, String scenario, int run, int chainTurn,
                              long started, List<String> issues,
                              List<DirectedNarrativeGenerator.ModelCall> calls, Object payload) {
        long latency = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int input = calls.stream().mapToInt(DirectedNarrativeGenerator.ModelCall::inputTokens).sum();
        int output = calls.stream().mapToInt(DirectedNarrativeGenerator.ModelCall::outputTokens).sum();
        int cacheHit = calls.stream().mapToInt(DirectedNarrativeGenerator.ModelCall::cacheHitTokens).sum();
        int cacheMiss = calls.stream().mapToInt(DirectedNarrativeGenerator.ModelCall::cacheMissTokens).sum();
        return new ResultRow(runtime.name(), task, scenario, run, chainTurn,
                issues.isEmpty() ? "PASS" : "RULE_FAIL", issues, latency,
                input, output, cacheHit, cacheMiss, "", payload);
    }

    private ResultRow failure(ModelRuntime runtime, String task, String scenario, int run, int chainTurn,
                              long started, RuntimeException error) {
        String message = errorChain(error);
        return new ResultRow(runtime.name(), task, scenario, run, chainTurn, "CALL_FAIL",
                List.of("CALL_OR_PROTOCOL_FAILURE"),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                0, 0, 0, 0, message, null);
    }

    private String errorChain(Throwable error) {
        List<String> chain = new ArrayList<>();
        Throwable current = error;
        while (current != null && chain.size() < 6) {
            String message = current.getMessage() == null ? "" : current.getMessage();
            chain.add(current.getClass().getSimpleName() + ": " + message.replaceAll("[\\r\\n]+", " "));
            current = current.getCause();
        }
        return String.join(" <- ", chain);
    }

    private Map<String, Object> renderedPayload(DirectedNarrativeGenerator.RenderedNarrative rendered) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", rendered.title());
        payload.put("narrative", rendered.narrative());
        payload.put("choices", rendered.choices());
        payload.put("summary", rendered.rollingSummary());
        payload.put("narrativeChars", rendered.narrative().length());
        return payload;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            return String.valueOf(value);
        }
    }

    private void require(List<String> issues, boolean condition, String issue) {
        if (!condition) issues.add(issue);
    }

    private void rejectAny(List<String> issues, String text, String... forbidden) {
        for (String value : forbidden) {
            if (contains(text, value)) issues.add("FORBIDDEN_TEXT:" + value);
        }
    }

    private void rejectPattern(List<String> issues, String text, Pattern pattern, String issue) {
        if (pattern.matcher(text).find()) issues.add(issue);
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) if (contains(text, candidate)) return true;
        return false;
    }

    private boolean contains(String text, String candidate) {
        return text != null && candidate != null
                && text.toLowerCase(Locale.ROOT).contains(candidate.toLowerCase(Locale.ROOT));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record ModelRuntime(String name, LangChain4jDirectedNarrativeGenerator generator) {}

    private record ResultRow(
            String model,
            String task,
            String scenario,
            int run,
            int chainTurn,
            String status,
            List<String> issues,
            long latencyMs,
            int inputTokens,
            int outputTokens,
            int cacheHitTokens,
            int cacheMissTokens,
            String error,
            Object payload
    ) {}
}
