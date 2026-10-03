package com.wenxian.game.story.replay.playerintent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.credit.PlatformTurnAccessService;
import com.wenxian.game.model.application.*;
import com.wenxian.game.model.domain.*;
import com.wenxian.game.model.infrastructure.*;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.agent.ai.*;
import com.wenxian.game.story.application.*;
import com.wenxian.game.story.diagnostic.*;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.persistence.*;
import com.wenxian.game.story.persistence.StoryPersistenceModels.*;
import com.wenxian.game.story.reducer.StoryStateReducer;
import com.wenxian.game.story.replay.records.RecordCandidateGenerator;
import com.wenxian.game.story.replay.records.RecordCandidatePreparation;
import com.wenxian.game.story.replay.records.RecordStoryTurnRepository;
import com.wenxian.game.story.replay.records.StoryRecords;
import com.wenxian.game.story.replay.single.SingleCandidateGenerator;
import com.wenxian.game.story.replay.single.SingleCandidatePreparation;
import com.wenxian.game.story.worldpackage.WorldPackageRegistryTestSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 已审阅案例的测试专用宿主：外部完整 v2 前态进入独立 H2，沿正式准备、事实归并、正文及事务提交执行一次行动。
 * 不连接共享库，不改变正式成长算法或 Prompt，不代替历史资料补造人物、世界和结果。
 * 失败返回原状核对材料；本入口绕过账号 API 与收费门禁，不声称验证线上权限、额度或历史前态真实性。
 */
public final class PlayerIntentReplay {
    private static final String USER = "player-intent-replay-user";
    private final ObjectMapper mapper;

    public PlayerIntentReplay(ObjectMapper mapper) { this.mapper = Objects.requireNonNull(mapper); }

    /** initialState 必须由案例审阅者提供；sourceEvidence 只用于本地归因，不进入模型上下文。 */
    public record CaseFixture(String caseId, JsonNode sourceEvidence, JsonNode initialState, String initialSummary,
                              List<InitialEntity> entities, List<StoryMemoryRepository.StoredMemory> memories,
                              List<InitialTurn> history, String action, String choiceId, SeedMetadata seedMetadata) {
        /** 旧合成案例没有来源时间元数据，继续使用隔离初始化时间。 */
        public CaseFixture(String caseId, JsonNode sourceEvidence, JsonNode initialState, String initialSummary,
                           List<InitialEntity> entities, List<StoryMemoryRepository.StoredMemory> memories,
                           List<InitialTurn> history, String action, String choiceId) {
            this(caseId, sourceEvidence, initialState, initialSummary, entities, memories, history, action, choiceId, null);
        }

        public CaseFixture {
            if (caseId == null || !caseId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("invalid caseId");
            if (initialState == null || !initialState.isObject() || initialState.path("schemaVersion").asInt() != 2
                    || !"ACTIVE".equals(initialState.path("status").asText())
                    || initialState.path("turnNumber").asInt(-1) < 0) throw new IllegalArgumentException("complete active v2 state required");
            if (action == null || action.isBlank() || action.length() > 1000) throw new IllegalArgumentException("invalid action");
            if (choiceId != null && choiceId.length() > 100) throw new IllegalArgumentException("invalid choiceId");
            initialState = initialState.deepCopy();
            sourceEvidence = sourceEvidence == null ? null : sourceEvidence.deepCopy();
            entities = List.copyOf(Objects.requireNonNull(entities));
            memories = List.copyOf(Objects.requireNonNull(memories));
            history = List.copyOf(Objects.requireNonNull(history));
            if (entities.size() > 200 || memories.size() > 80 || history.size() > 20)
                throw new IllegalArgumentException("case exceeds bounded replay input");
            if (initialSummary == null) throw new IllegalArgumentException("initialSummary must be explicit");
        }
    }

    /**
     * 业务快照续玩的可选种子元数据，仅保留会影响同轮记忆读取排序的原创建时间。
     * 值是源快照的 JDBC TIMESTAMP 字面值，不据此猜测时区；不承担账号、额度或生产恢复。
     */
    public record SeedMetadata(Map<String, String> memoryCreatedAt) {
        public SeedMetadata { memoryCreatedAt = Map.copyOf(Objects.requireNonNull(memoryCreatedAt)); }
    }

    public record InitialEntity(String id, String type, String name, String status, boolean visible,
                                String summary, JsonNode attributes, int firstTurn, int lastChangedTurn) { }

    /** 仅为已审阅前情装载；原版本、原始前态置信度由 sourceEvidence 保留，不把这些行称为本批新生成结果。 */
    public record InitialTurn(int turnNumber, String playerAction, String narrative, JsonNode choices,
                              JsonNode statePatch, long worldTimeBefore, long worldTimeAfter,
                              String id, String requestId, String promptVersion) {
        public InitialTurn(int turnNumber, String playerAction, String narrative, JsonNode choices,
                           JsonNode statePatch, long worldTimeBefore, long worldTimeAfter) {
            this(turnNumber, playerAction, narrative, choices, statePatch, worldTimeBefore, worldTimeAfter,
                    null, null, null);
        }

        public InitialTurn {
            long provided = java.util.stream.Stream.of(id, requestId, promptVersion)
                    .filter(value -> value != null && !value.isBlank()).count();
            if (provided != 0 && provided != 3) throw new IllegalArgumentException("historical id, requestId and promptVersion must be supplied together");
        }
    }

    /** 权威及软投影逐列快照，专用于隔离 H2 前后比较；字段值为数据库重新读取结果。 */
    public record Authority(Map<String, List<Map<String, String>>> tables) { }
    public record ReplayResult(String caseId, String status, String errorType, JsonNode sourceEvidence,
                               Authority before, JsonNode prepared, Authority after,
                               List<JsonNode> stages, List<Map<String, Object>> diagnostics,
                               boolean unchangedOnFailure) { }

    /** 独立于历史案例的验收追加行动；不从模型正文生成，不冒充实际玩家的原始行动。 */
    public record FollowupAction(String action, String choiceId) {
        public FollowupAction {
            if (action == null || action.isBlank() || action.length() > 1000)
                throw new IllegalArgumentException("invalid followup action");
            if (choiceId != null && choiceId.length() > 100) throw new IllegalArgumentException("invalid followup choiceId");
        }
    }

    /** 只声明初始案例与有序追加行动；最多六轮，不携带期望结果或覆盖后续权威前态。 */
    public record ContinuationPlan(String replayId, String initialCaseId, List<FollowupAction> followupActions) {
        public ContinuationPlan {
            if (replayId == null || !replayId.matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("invalid replayId");
            if (initialCaseId == null || !initialCaseId.matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("invalid initialCaseId");
            followupActions = List.copyOf(Objects.requireNonNull(followupActions));
            if (followupActions.size() > 5) throw new IllegalArgumentException("sequence exceeds six turns");
        }
    }

    public enum InputOrigin { REVIEWED_INITIAL, ACCEPTANCE_FOLLOWUP }

    /** 每轮独立取证；requestId 可与阶段元数据、数据库回合及代理 HTTP 原件交叉核对。 */
    public record SequenceTurn(int ordinal, InputOrigin inputOrigin, String action, String choiceId,
                               String requestId, ReplayResult result) { }

    /**
     * 失败即停，保留此前已提交回合；不会跳过失败行动或自动重试整条链。
     * plannedTurns 在固定计划中是行动数，在逐轮选择入口中是批次上限；实际已执行数以 turns 为准。
     */
    public record SequenceResult(String replayId, String initialCaseId, String status,
                                 int plannedTurns, List<SequenceTurn> turns, Authority finalAuthority) { }

    /** 只验证装载、选项和正式上下文组装；不会请求模型，也不把未执行的编译与正文称为通过。 */
    public record PreflightResult(String caseId, String status, String errorType, String errorDetail,
                                  Authority loaded, JsonNode directorContext) { }

    public PreflightResult preflight(CaseFixture fixture) {
        DirectedNarrativeGenerator stopAtDirector = invocation -> new DirectedNarrativeGenerator.Session() {
            public CredentialSource credentialSource() { return CredentialSource.PLATFORM; }
            public DirectedNarrativeGenerator.DirectedPlan plan(StoryPromptContext context) { throw new PreflightBoundaryReached(); }
            public DirectedNarrativeGenerator.DirectedPlan repair(StoryPromptContext context, StoryTurnDecision rejected, String problem) {
                throw new IllegalStateException("preflight cannot repair");
            }
            public DirectedNarrativeGenerator.RenderedNarrative render(StoryPromptContext context, StoryPatch plan, NarrativeEndState end) {
                throw new IllegalStateException("preflight cannot render");
            }
        };
        try {
            ReplayResult result = run(fixture, stopAtDirector);
            boolean valid = "PreflightBoundaryReached".equals(result.errorType()) && result.unchangedOnFailure()
                    && result.stages().size() == 1;
            String detail = result.diagnostics().isEmpty() ? "" : Objects.toString(
                    result.diagnostics().getLast().get("failure_detail"), "");
            return new PreflightResult(fixture.caseId(), valid ? "VALID_UNTIL_DIRECTOR" : "INVALID", valid ? "" : result.errorType(),
                    valid ? "" : detail, result.before(), result.stages().isEmpty() ? null : result.stages().getFirst().path("context"));
        } catch (RuntimeException error) {
            return new PreflightResult(fixture.caseId(), "INVALID", error.getClass().getSimpleName(),
                    Objects.toString(error.getMessage(), ""), null, null);
        }
    }

    private static final class PreflightBoundaryReached extends RuntimeException { }

    public ReplayResult run(CaseFixture fixture, DirectedNarrativeGenerator generator) {
        return run(fixture, generator, false);
    }

    /** 离线事务反例：真实回合行插入后抛错，验证整个正式事务回滚；真实回放入口不会启用。 */
    ReplayResult runWithFailureAfterTurnInsert(CaseFixture fixture, DirectedNarrativeGenerator generator) {
        return run(fixture, generator, true);
    }

    private ReplayResult run(CaseFixture fixture, DirectedNarrativeGenerator generator, boolean failAfterTurnInsert) {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            String saveId = "replay-save-" + UUID.randomUUID();
            Authority beforeSeed = authority(jdbc);
            try {
                seed(jdbc, fixture, saveId);
            } catch (RuntimeException error) {
                Authority afterSeed = authority(jdbc);
                Map<String, Object> failure = Map.of("current_stage", "SEED_VALIDATION",
                        "failure_detail", Objects.toString(error.getMessage(), ""));
                return new ReplayResult(fixture.caseId(), "FAILED", error.getClass().getSimpleName(),
                        fixture.sourceEvidence(), beforeSeed, null, afterSeed, List.of(), List.of(failure),
                        beforeSeed.equals(afterSeed));
            }
            return runTurn(database, jdbc, fixture, saveId, "replay-request-" + UUID.randomUUID(),
                    fixture.action(), fixture.choiceId(), generator, failAfterTurnInsert);
        } finally {
            database.shutdown();
        }
    }

    public SequenceResult runSequence(CaseFixture fixture, ContinuationPlan plan, DirectedNarrativeGenerator generator) {
        return runSequence(fixture, plan, generator, ignored -> { });
    }

    /**
     * 同一隔离 H2 中逐轮提交；每轮重新建立仓库与准备服务，并从数据库重载命卷。
     * 回调在下一轮开始前保存本轮证据；回调失败立即停止，避免继续产生未留档的外部调用。
     */
    public SequenceResult runSequence(CaseFixture fixture, ContinuationPlan plan, DirectedNarrativeGenerator generator,
                                      Consumer<SequenceTurn> afterTurn) {
        if (!fixture.caseId().equals(plan.initialCaseId())) throw new IllegalArgumentException("continuation caseId mismatch");
        return runSequence(fixture, plan.replayId(), plan.followupActions().size() + 1, generator, afterTurn,
                turn -> plan.followupActions().get(turn.ordinal() - 1));
    }

    /**
     * 复用同一 H2 和正式回合链，允许调用方读完已提交故事后选择下一条行动。
     * 首轮使用 fixture.action；仅在成功提交、证据回调完成且未达到上限后调用 nextAction。
     * nextAction 返回 null 表示正常结束；任一回调抛错即退出，不再调用模型或重试整链。
     * 本入口最多六轮，只负责受控试玩输入，不替玩家生成行动或改变正式成长规则。
     */
    public SequenceResult runSequence(CaseFixture fixture, String replayId, int maxTurns,
                                      DirectedNarrativeGenerator generator, Consumer<SequenceTurn> afterTurn,
                                      Function<SequenceTurn, FollowupAction> nextAction) {
        if (replayId == null || !replayId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("invalid replayId");
        if (maxTurns < 1 || maxTurns > 6) throw new IllegalArgumentException("sequence requires one to six turns");
        Objects.requireNonNull(afterTurn);
        Objects.requireNonNull(nextAction);
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema-h2.sql").build();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            String saveId = "replay-save-" + UUID.randomUUID();
            seed(jdbc, fixture, saveId);
            FollowupAction action = new FollowupAction(fixture.action(), fixture.choiceId());
            List<SequenceTurn> results = new ArrayList<>();
            for (int index = 0; index < maxTurns; index++) {
                String requestId = "replay-request-" + UUID.randomUUID();
                ReplayResult result = runTurn(database, jdbc, fixture, saveId, requestId,
                        action.action(), action.choiceId(), generator, false);
                SequenceTurn turn = new SequenceTurn(index + 1,
                        index == 0 ? InputOrigin.REVIEWED_INITIAL : InputOrigin.ACCEPTANCE_FOLLOWUP,
                        action.action(), action.choiceId(), requestId, result);
                results.add(turn);
                afterTurn.accept(turn);
                if (!"COMMITTED_IN_H2".equals(result.status())) break;
                if (index + 1 == maxTurns) break;
                action = nextAction.apply(turn);
                if (action == null) break;
            }
            boolean completed = results.stream().allMatch(turn -> "COMMITTED_IN_H2".equals(turn.result().status()));
            return new SequenceResult(replayId, fixture.caseId(),
                    completed ? "COMMITTED_IN_H2" : "STOPPED_ON_FAILURE", maxTurns, List.copyOf(results), authority(jdbc));
        } finally {
            database.shutdown();
        }
    }

    private ReplayResult runTurn(EmbeddedDatabase database, JdbcTemplate jdbc, CaseFixture fixture, String saveId,
                                  String requestId, String action, String choiceId, DirectedNarrativeGenerator generator,
                                  boolean failAfterTurnInsert) {
        var saves = new StorySaveRepository(jdbc, mapper);
        StoryTurnRepository turns = generator instanceof RecordCandidateGenerator
                ? new RecordStoryTurnRepository(jdbc, mapper) {
                    @Override public void insert(NewTurn turn) {
                        super.insert(turn);
                        if (failAfterTurnInsert) throw new IllegalStateException("offline failure after story_turn insert");
                    }
                } : new StoryTurnRepository(jdbc, mapper) {
            @Override public void insert(NewTurn turn) {
                super.insert(turn);
                if (failAfterTurnInsert) throw new IllegalStateException("offline failure after story_turn insert");
            }
        };
        var entities = new StoryEntityRepository(jdbc, mapper);
        var memories = new StoryMemoryRepository(jdbc);
        var conversations = new StoryConversationRepository(jdbc);
        var projections = new StoryProjectionPersistenceMapper(mapper);
        var diagnostics = new StoryTurnDiagnosticService(new StoryTurnDiagnosticRepository(jdbc, mapper), mapper, true);
        List<JsonNode> stages = new ArrayList<>();
        var preparation = new LegacyDirectedTurnPreparationService(entities, memories, conversations, turns,
                projections, new PromptContextAssembler(), new StoryStateReducer(),
                WorldPackageRegistryTestSupport.worldContexts(mapper), diagnostics, observing(generator, stages));
        var commit = new StoryTurnCommitService(saves, turns, entities, memories, conversations, projections,
                new GameVersionService(jdbc), new PlatformTurnAccessService(null, null, false),
                new TransactionTemplate(new DataSourceTransactionManager(database)), mapper);
        StoredSave saved = saves.findOwned(USER, saveId).orElseThrow();
        Authority before = authority(jdbc);
        JsonNode preparedRecord = null;
        try {
            PreparedStoryTurn prepared;
            List<StoryRecords.Value> acceptedRecords = null;
            if (generator instanceof RecordCandidateGenerator records) {
                // 显式读旧 v1/v2 和新记录；未知或损坏历史在外部调用之前拒绝，不能被旧 reader 静默跳过。
                List<StoryRecords.Envelope> history = turns.recent(saved.id(), 6).stream()
                        .map(StoryRecords::read).map(StoryRecords.History::storyRecords)
                        .filter(Objects::nonNull).toList();
                var contextReader = new LegacyDirectedTurnPreparationService(entities, memories, conversations, turns,
                        projections, new PromptContextAssembler(), new StoryStateReducer(),
                        WorldPackageRegistryTestSupport.worldContexts(mapper), null, generator);
                StoryPromptContext context = captureContext(contextReader, saved, requestId, action, choiceId);
                var existing = entities.listAll(saved.id());
                var projection = projections.load(saved, existing, memories.recentActive(saved.id(), 20));
                var session = records.open(new DirectedNarrativeGenerator.InvocationContext(
                        saved.userId(), requestId, saved.id(), saved.turnNumber() + 1));
                var candidate = new RecordCandidatePreparation().prepare(projection, context, existing, session, history,
                        entry -> stages.add(mapper.valueToTree(entry)));
                prepared = candidate.turn();
                acceptedRecords = candidate.records();
                ((RecordStoryTurnRepository) turns).stage(requestId, acceptedRecords);
            } else if (generator instanceof SingleCandidateGenerator single) {
                // 仅测试入口：在当前同一 H2/命卷捕获正式组装结果，不另建前态，不伪造模型调用或失败诊断。
                var contextReader = new LegacyDirectedTurnPreparationService(entities, memories, conversations, turns,
                        projections, new PromptContextAssembler(), new StoryStateReducer(),
                        WorldPackageRegistryTestSupport.worldContexts(mapper), null, generator);
                StoryPromptContext context = captureContext(contextReader, saved, requestId, action, choiceId);
                var existing = entities.listAll(saved.id());
                var projection = projections.load(saved, existing, memories.recentActive(saved.id(), 20));
                var session = single.open(new DirectedNarrativeGenerator.InvocationContext(
                        saved.userId(), requestId, saved.id(), saved.turnNumber() + 1));
                prepared = new SingleCandidatePreparation().prepare(projection, context, existing, session,
                        entry -> stages.add(mapper.valueToTree(entry)));
            } else {
                var session = preparation.openSession(saved, requestId);
                prepared = preparation.prepare(saved, requestId, action, choiceId, session);
            }
            Map<String, Object> preparationRecord = new LinkedHashMap<>();
            preparationRecord.put("beforeProjection", prepared.before());
            preparationRecord.put("afterProjection", prepared.applied().projection());
            preparationRecord.put("acceptedPatch", prepared.applied().acceptedPatch());
            preparationRecord.put("trustedEvents", prepared.trustedEvents());
            preparationRecord.put("generation", prepared.generation());
            preparationRecord.put("acceptedResult", prepared.acceptedResult());
            if (acceptedRecords != null) preparationRecord.put("storyRecords", acceptedRecords);
            preparedRecord = mapper.valueToTree(preparationRecord);
            try {
                commit.commit(USER, saved, requestId, action, prepared, null);
            } catch (RuntimeException error) {
                if (prepared.diagnostic() != null) prepared.diagnostic().failed("COMMIT_FAILED", error);
                throw error;
            }
            // 正式仓库重新读取；不能仅凭 preparation 候选就宣告已提交。
            turns.findByRequestId(saveId, requestId).orElseThrow();
            saves.findOwned(USER, saveId).orElseThrow();
            return new ReplayResult(fixture.caseId(), "COMMITTED_IN_H2", "", fixture.sourceEvidence(),
                    before, preparedRecord, authority(jdbc), List.copyOf(stages), diagnostics(jdbc, requestId), false);
        } catch (RuntimeException error) {
            Authority after = authority(jdbc);
            boolean committed = turns.findByRequestId(saveId, requestId).isPresent();
            return new ReplayResult(fixture.caseId(), committed ? "POST_COMMIT_INSPECTION_FAILED" : "FAILED",
                    error.getClass().getSimpleName(), fixture.sourceEvidence(), before, preparedRecord, after,
                    List.copyOf(stages), diagnostics(jdbc, requestId), !committed && before.equals(after));
        }
    }

    /** 正式上下文组装的测试观察点；专用异常不是模型失败，既不请求供应商也不占修复预算。 */
    private StoryPromptContext captureContext(LegacyDirectedTurnPreparationService reader, StoredSave saved,
                                              String requestId, String action, String choiceId) {
        var capture = new DirectedNarrativeGenerator.Session() {
            public CredentialSource credentialSource() { return CredentialSource.PLATFORM; }
            public DirectedNarrativeGenerator.DirectedPlan plan(StoryPromptContext context) {
                throw new CapturedContext(context);
            }
            public DirectedNarrativeGenerator.DirectedPlan repair(StoryPromptContext context, StoryTurnDecision rejected, String problem) {
                throw new IllegalStateException("context capture cannot repair");
            }
            public DirectedNarrativeGenerator.RenderedNarrative render(StoryPromptContext context, StoryPatch plan, NarrativeEndState end) {
                throw new IllegalStateException("context capture cannot render");
            }
        };
        try {
            reader.prepare(saved, requestId, action, choiceId, capture);
        } catch (CapturedContext captured) {
            return captured.context;
        }
        throw new IllegalStateException("formal context boundary was not reached");
    }

    private static final class CapturedContext extends RuntimeException {
        private final StoryPromptContext context;
        private CapturedContext(StoryPromptContext context) {
            super("test context captured", null, false, false);
            this.context = context;
        }
    }

    /** 复用正式类型化 AI Service 和任务 Prompt；单一供应商候选、零 SDK 重试，调用预算由外部取证代理硬限制。 */
    public static DirectedNarrativeGenerator generator(ObjectMapper mapper, ChatModelFactory factory,
                                                        String baseUrl, String key) {
        var definition = new ModelDefinition("DEEPSEEK", "PLAYER_INTENT_REPLAY", baseUrl,
                ModelAdapterType.OPENAI_COMPATIBLE, "deepseek-v4-flash",
                Set.of(ModelCapability.CHAT, ModelCapability.JSON_MODE, ModelCapability.THINKING_CONTROL), 128_000, 8000);
        ModelRoutePolicy policy = request -> new ModelRoutePolicy.ResolvedCandidates(CredentialSource.PLATFORM,
                List.of(new ModelRouteCandidate(definition, ModelCredential.platform(key),
                        new ModelInvocationOptions(request.task() == ModelTask.TURN_DIRECTOR ? 0.3 : 0.8,
                                Duration.ofSeconds(150), 0, request.task() == ModelTask.TURN_DIRECTOR ? 4000 : 3200, true))));
        var router = new ModelTaskRouter(policy);
        return new LangChain4jDirectedNarrativeGenerator(router,
                new ModelInvocationService(router, new ChatModelRegistry(factory, 4)),
                new StoryAiServiceRegistry(new AiServiceRegistry(new PromptResourceLoader(), 8)), mapper);
    }

    private void seed(JdbcTemplate jdbc, CaseFixture fixture, String saveId) {
        // 隔离种子也是完整输入：装载失败不留下半份前态，不调用模型修复历史。
        new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())))
                .executeWithoutResult(status -> seedInTransaction(jdbc, fixture, saveId));
    }

    private void seedInTransaction(JdbcTemplate jdbc, CaseFixture fixture, String saveId) {
        Instant now = Instant.now();
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                USER, "player-intent-replay@example.invalid", "ACTIVE", timestamp, timestamp);
        ObjectNode state = fixture.initialState().deepCopy();
        state.put("saveId", saveId);
        int turn = state.path("turnNumber").asInt();
        String world = state.path("trustedProjection").path("creationContract").path("worldBinding").path("packageId").asText();
        if (world.isBlank()) throw new IllegalArgumentException("fixture lacks world package binding");
        new StorySaveRepository(jdbc, mapper).insert(new NewSave(saveId, USER, 1, 1, "ACTIVE", world,
                "LIGHT_NARRATIVE", 1, 2, "NATIVE", "LOCAL_REPLAY", null, null, state, turn,
                "LOCAL_REPLAY", "LOCAL_REPLAY", now));
        var entities = new StoryEntityRepository(jdbc, mapper);
        for (InitialEntity e : fixture.entities()) {
            if (e.firstTurn() < 0 || e.lastChangedTurn() < e.firstTurn() || e.lastChangedTurn() > turn)
                throw new IllegalArgumentException("entity time outside reviewed initial state");
            entities.insert(new Entity(e.id(), saveId, e.type(), e.name(), e.status(), e.visible(), e.summary(),
                    e.attributes(), e.firstTurn(), e.lastChangedTurn(), now, now));
        }
        var memories = new StoryMemoryRepository(jdbc);
        Map<String, String> memoryCreatedAt = fixture.seedMetadata() == null ? null : fixture.seedMetadata().memoryCreatedAt();
        if (memoryCreatedAt != null && !memoryCreatedAt.keySet().equals(fixture.memories().stream()
                .map(StoryMemoryRepository.StoredMemory::id).collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalArgumentException("seed memoryCreatedAt must cover exactly all imported memory IDs");
        }
        for (var memory : fixture.memories()) {
            if (memory.occurredTurn() < 0 || memory.occurredTurn() > turn)
                throw new IllegalArgumentException("memory time outside reviewed initial state");
            memories.insert(saveId, memory);
            if (memoryCreatedAt != null) {
                Timestamp sourceCreatedAt = Timestamp.valueOf(memoryCreatedAt.get(memory.id()));
                int changed = jdbc.update("update story_memory set created_at=? where save_id=? and id=?",
                        sourceCreatedAt, saveId, memory.id());
                if (changed != 1) throw new IllegalStateException("imported memory timestamp was not restored");
            }
        }
        new StoryConversationRepository(jdbc).initialize(saveId, fixture.initialSummary(), turn);
        var turns = new StoryTurnRepository(jdbc, mapper);
        for (InitialTurn t : fixture.history()) {
            if (t.turnNumber() < 1 || t.turnNumber() > turn) throw new IllegalArgumentException("history contains future turn");
            boolean sourced = t.id() != null && !t.id().isBlank();
            boolean hasRecords = t.statePatch() != null && t.statePatch().has("storyRecords");
            if (hasRecords && !sourced) throw new IllegalArgumentException("storyRecords history requires explicit source id, requestId and promptVersion");
            String id = sourced ? t.id() : "seed-turn-" + UUID.randomUUID();
            String requestId = sourced ? t.requestId() : "seed-request-" + UUID.randomUUID();
            String promptVersion = sourced ? t.promptVersion() : "REVIEWED_CONTEXT_SEED";
            if (hasRecords || RecordStoryTurnRepository.PROMPT_VERSION.equals(promptVersion)) {
                // 来源来自独立历史行字段；不能拿 Envelope 的 turnId 反向填 SQL ID 后自证一致。
                StoryRecords.read(new StoredTurn(id, saveId, requestId, t.turnNumber(), t.playerAction(),
                        t.narrative(), t.choices(), t.statePatch(), t.worldTimeBefore(), t.worldTimeAfter(),
                        "LIGHT_NARRATIVE", 1, 2, promptVersion, "LOCAL", "CONTEXT_ONLY", "PLATFORM",
                        0, 0, 0, 0, 0, "LOCAL_REPLAY", now));
            }
            turns.insert(new NewTurn(id, saveId,
                    requestId, t.turnNumber(), t.playerAction(),
                    t.narrative(), t.choices(), t.statePatch(), t.worldTimeBefore(), t.worldTimeAfter(),
                    "LIGHT_NARRATIVE", 1, 2, promptVersion, "LOCAL", "CONTEXT_ONLY", "PLATFORM",
                    0, 0, 0, 0, 0, "LOCAL_REPLAY", now));
        }
    }

    private Authority authority(JdbcTemplate jdbc) {
        Map<String, List<Map<String, String>>> snapshot = new TreeMap<>();
        for (String table : List.of("story_save", "story_turn", "story_entity", "story_entity_alias", "story_memory", "story_conversation")) {
            snapshot.put(table, jdbc.query("select * from " + table + " order by 1", (row, ignored) -> {
                Map<String, String> values = new LinkedHashMap<>();
                for (int column = 1; column <= row.getMetaData().getColumnCount(); column++)
                    values.put(row.getMetaData().getColumnLabel(column).toLowerCase(Locale.ROOT), row.getString(column));
                return Collections.unmodifiableMap(values);
            }));
        }
        return new Authority(Collections.unmodifiableMap(snapshot));
    }

    private List<Map<String, Object>> diagnostics(JdbcTemplate jdbc, String requestId) {
        return jdbc.query("select status,current_stage,expected_revision,context_manifest_json,proposal_trace_json,review_trace_json,commit_summary_json,failure_code,failure_detail from story_turn_diagnostic where request_id=? order by created_at",
                (row, ignored) -> {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) {
                        String key = row.getMetaData().getColumnLabel(column).toLowerCase(Locale.ROOT);
                        String value = row.getString(column);
                        if (key.endsWith("_json") && value != null) {
                            try { values.put(key, mapper.readTree(value)); }
                            catch (java.io.IOException error) { throw new IllegalStateException("invalid stored diagnostic JSON", error); }
                        } else values.put(key, value);
                    }
                    return Collections.unmodifiableMap(values);
                }, requestId);
    }

    private DirectedNarrativeGenerator observing(DirectedNarrativeGenerator delegate, List<JsonNode> stages) {
        return invocation -> {
            var session = delegate.open(invocation);
            return new DirectedNarrativeGenerator.Session() {
                public CredentialSource credentialSource() { return session.credentialSource(); }
                public Optional<ServicePlanSelection> servicePlanSelection() { return session.servicePlanSelection(); }
                public DirectedNarrativeGenerator.DirectedPlan plan(StoryPromptContext context) {
                    ObjectNode entry = stage("director", context);
                    var value = session.plan(context); entry.set("result", mapper.valueToTree(value)); return value;
                }
                public DirectedNarrativeGenerator.DirectedPlan repair(StoryPromptContext context, StoryTurnDecision rejected, String problem) {
                    ObjectNode entry = stage("director-repair", context);
                    entry.set("rejected", mapper.valueToTree(rejected)); entry.put("problem", problem);
                    var value = session.repair(context, rejected, problem); entry.set("result", mapper.valueToTree(value)); return value;
                }
                public DirectedNarrativeGenerator.DirectedPlan repairProtocol(StoryPromptContext context, DirectorProtocolException rejected) {
                    ObjectNode entry = stage("director-protocol-repair", context);
                    entry.put("problem", rejected.getMessage());
                    entry.put("rejectedPhase", rejected.phase().name());
                    entry.set("rejectedDraft", mapper.valueToTree(rejected.rejectedDraft()));
                    entry.set("modelCalls", mapper.valueToTree(rejected.modelCalls()));
                    var value = session.repairProtocol(context, rejected); entry.set("result", mapper.valueToTree(value)); return value;
                }
                public DirectedNarrativeGenerator.RenderedNarrative render(StoryPromptContext context, StoryPatch plan, NarrativeEndState end) {
                    ObjectNode entry = stage("writer", context);
                    entry.set("acceptedPlan", mapper.valueToTree(plan)); entry.set("endState", mapper.valueToTree(end));
                    var value = session.render(context, plan, end); entry.set("result", mapper.valueToTree(value)); return value;
                }
                private ObjectNode stage(String phase, StoryPromptContext context) {
                    ObjectNode entry = mapper.createObjectNode().put("phase", phase);
                    entry.set("invocation", mapper.valueToTree(invocation));
                    entry.set("context", mapper.valueToTree(context)); stages.add(entry); return entry;
                }
            };
        };
    }
}
