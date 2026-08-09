package com.wenxian.game.story.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.api.GameApiException;
import com.wenxian.game.auth.AuthService.UserIdentity;
import com.wenxian.game.service.ActiveSavePolicyService;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.service.PlayerAiService;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.StoryModelFailureException;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryTurnAgent;
import com.wenxian.game.story.application.StoryTurnCommitService;
import com.wenxian.game.story.application.StoryTurnPreparationService;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.DecisionCheckpoint;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleValue;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.TimePassage;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryPatch.WorldFactOperation;
import com.wenxian.game.story.domain.StoryPatch.WorldFactPatch;
import com.wenxian.game.story.domain.StoryPatchValidator;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryCorrectionRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository.StoredMemory;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewTurn;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.reducer.StoryStateReducer;
import com.wenxian.game.story.model.StoryApiModels.TurnRequest;
import com.wenxian.game.story.model.StoryApiModels.CorrectionRequest;
import com.wenxian.game.story.model.StoryApiModels.CreateSaveRequest;
import com.wenxian.game.v014.model.V014ApiModels.ModelPreference;
import com.wenxian.game.v014.model.V014State;
import com.wenxian.game.v014.service.V014ModelPreferenceService;
import com.wenxian.game.v014.service.V014StateReducer;
import com.wenxian.game.v014.service.V014WorldCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistedLightNarrativeEngineTest {
    private static final UserIdentity USER = new UserIdentity("story-user", "story@test.local", true);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void readsMigratedCoreEntitiesAndMemoriesWithoutLegacyTables() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            saves.insert(new NewSave("story-v014-test", USER.userId(), 1, "ACTIVE", "XUANXU",
                    "LIGHT_NARRATIVE", 1, 1, "MIGRATED_V014", "V0.14", "v014-save-test", null,
                    core(), 3, "V0.14", "V0.14", Instant.now()));
            ObjectNode attributes = mapper.createObjectNode();
            attributes.put("itemType", "信物");
            attributes.put("source", "旧识所赠");
            attributes.put("quantity", 1);
            attributes.put("inPossession", true);
            entities.insert(new Entity("entity-token", "story-v014-test", "ITEM", "青纹令", "随身携带",
                    true, "来自旧识的令牌", attributes, 0, 3, Instant.now(), Instant.now()));
            ObjectNode unknownAttributes = mapper.createObjectNode();
            unknownAttributes.put("itemType", "灵材");
            unknownAttributes.put("source", "洞府所得");
            unknownAttributes.put("inPossession", true);
            ObjectNode trusted = mapper.createObjectNode();
            trusted.set("quantity", mapper.valueToTree(new com.wenxian.game.story.domain.StoryPatch.QuantityValue(
                    false, null)));
            trusted.put("inPossession", true);
            unknownAttributes.set("_trusted", trusted);
            entities.insert(new Entity("entity-unknown", "story-v014-test", "ITEM", "一包散碎灵材", "未清点",
                    true, "尚未逐件清点", unknownAttributes, 1, 3, Instant.now(), Instant.now()));
            memories.insert("story-v014-test", new StoredMemory("memory-origin", "EVENT", "此生来处", "山村少年",
                    0, 0, "KEY"));
            jdbc.update("""
                    insert into story_conversation(save_id,rolling_summary,summarized_through_turn,
                        summary_schema_version,updated_at) values (?,?,?,?,?)
                    """, "story-v014-test", "你已经进入青岐城。", 3, 1, Timestamp.from(Instant.now()));

            PersistedLightNarrativeEngine engine = engine(database, saves, entities, memories);

            assertTrue(engine.supports("story-v014-test"));
            assertEquals(1, engine.list(USER).size());
            var result = engine.get(USER, "story-v014-test");
            assertEquals("PLAYABLE", result.mode());
            assertEquals(3, result.turnNumber());
            assertEquals("陆沉", result.state().protagonist().name());
            assertEquals("青纹令", result.state().inventory().getFirst().name());
            var unknown = result.state().inventory().stream()
                    .filter(item -> "entity-unknown".equals(item.id())).findFirst().orElseThrow();
            assertFalse(unknown.quantityKnown());
            assertEquals(0, unknown.quantity());
            assertTrue(unknown.inPossession());
            assertEquals("此生来处", result.state().memories().getFirst().title());

            StoryTurnRepository turns = new StoryTurnRepository(jdbc, mapper);
            turns.insert(new NewTurn("turn-existing", "story-v014-test", "request-existing", 3,
                    "查看令牌", "你已经查看令牌。", mapper.createArrayNode(), mapper.createObjectNode(),
                    120, 180, "LIGHT_NARRATIVE", 1, "story-test", "DEEPSEEK", "test-model", "PUBLIC",
                    10, 20, 0, 0, 30, "V0.14", Instant.now()));
            var retried = engine.takeTurn(USER, "story-v014-test",
                    new TurnRequest("查看令牌", "request-existing", 99));
            assertEquals(3, retried.turnNumber(), "已提交 requestId 必须先于旧 revision 返回现有结果");

            var removed = engine.removeEntity(USER, "story-v014-test", "entity-token", 1);
            assertEquals(2, removed.revision());
            assertEquals(List.of("entity-unknown"), removed.state().inventory().stream()
                    .map(com.wenxian.game.story.model.StoryApiModels.Item::id).toList());

            var corrected = engine.correct(USER, "story-v014-test",
                    new CorrectionRequest("地点应为青岐山门", 2));
            assertEquals(3, corrected.revision());
            assertEquals("青岐山门", corrected.state().currentLocation());

            engine.archive(USER, "story-v014-test");
            var archived = engine.get(USER, "story-v014-test");
            assertEquals("ARCHIVE", archived.mode());
            assertEquals(List.of("READ"), archived.capabilities());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void runtimeSwitchKeepsGenericCandidatesInvisibleWhenDisabled() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            PersistedLightNarrativeEngine engine = engine(database, saves, entities, memories, false);

            assertFalse(engine.supports("story-v014-test"));
            assertTrue(engine.list(USER).isEmpty());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void createsInUnifiedTablesAndArchivedHistoryDoesNotConsumeActiveLimit() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            saves.insert(new NewSave("story-existing-active", USER.userId(), 1, "ACTIVE", "XUANXU",
                    "LIGHT_NARRATIVE", 1, 1, "NATIVE", "V0.14", null, null,
                    core(), 3, "V0.14", "V0.14", Instant.now()));
            for (int slot = 2; slot <= 14; slot++) {
                saves.insert(new NewSave("story-archive-" + slot, USER.userId(), slot, "ARCHIVED", "XUANXU",
                        "LIGHT_NARRATIVE", 1, 1, "NATIVE", "V0.14", null, null,
                        core(), slot, "V0.14", "V0.14", Instant.now()));
            }
            PersistedLightNarrativeEngine engine = engine(database, saves, entities, memories);
            CreateSaveRequest request = new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索");

            var created = engine.create(USER, request);

            assertTrue(created.saveId().startsWith("story-"));
            assertEquals(15, saves.findOwned(USER.userId(), created.saveId()).orElseThrow().slotNo());
            assertEquals(2, saves.countActive(USER.userId()));
            assertEquals("楚星河", created.state().protagonist().name());
            assertEquals("你在山门晨钟中醒来。", jdbc.queryForObject(
                    "select rolling_summary from story_conversation where save_id=?", String.class,
                    created.saveId()));
            assertEquals(0, jdbc.queryForObject(
                    "select summarized_through_turn from story_conversation where save_id=?", Integer.class,
                    created.saveId()));
            GameApiException error = assertThrows(GameApiException.class, () -> engine.create(USER, request));
            assertEquals("ACTIVE_SAVE_LIMIT_REACHED", error.code());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void trustedTurnCommitsAcceptedPatchAndKeepsUnknownQuantity() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            AtomicInteger agentCalls = new AtomicInteger();
            StoryTurnAgent trustedAgent = (context, route) -> {
                agentCalls.incrementAndGet();
                assertTrue(mapper.valueToTree(context).toString().contains("item-pills"));
                EntityPatch consume = new EntityPatch(EntityOperation.UPDATE, "item-pills", "ITEM",
                        null, "剩余两枚", null, null, new QuantityValue(true, 2), true,
                        null, null);
                EntityPatch discover = new EntityPatch(EntityOperation.CREATE, null, "ITEM",
                        "一包散碎灵材", "尚未清点", "数量仍待清点", null,
                        new QuantityValue(false, null), true, null, null);
                StoryPatch patch = new StoryPatch("清点行囊", "你服下一枚清心丹，又收起一包尚未清点的灵材。",
                        new TimePassage(30, TimeResolution.COMPLETE, null), null,
                        List.of(consume, discover), List.of(), List.of(),
                        List.of(new StoryPatch.MemoryDraft("EVENT", "清点行囊",
                                "你服下一枚清心丹，并得到一包数量未知的灵材。", "NORMAL", List.of())),
                        new StoryPatch.DecisionCheckpoint(false, null),
                        List.of(new StoryPatch.ChoiceDraft("继续查看", "辨认灵材来历。")),
                        "你已经服下一枚清心丹，并得到未清点灵材。");
                return new StoryTurnAgent.Generated(patch, List.of(
                        new com.wenxian.game.story.agent.StoryModelGateway.Result(
                                "{}", "response-trusted", "test-model", 120, 220, 40)));
            };
            PlayerAiService playerAi = new PlayerAiService(jdbc, null, RestClient.builder(),
                    "https://example.invalid", "test-master-key") {
                @Override
                public void assertPublicQuota(String userId, Route route) {
                    // 此测试只验证成功回合的事务提交，不依赖运营额度配置。
                }
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    trustedAgent, playerAi);
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));
            ObjectNode itemAttributes = mapper.createObjectNode();
            itemAttributes.put("itemType", "丹药");
            itemAttributes.put("source", "随身行囊");
            itemAttributes.put("quantity", 3);
            itemAttributes.put("inPossession", true);
            entities.insert(new Entity("item-pills", created.saveId(), "ITEM", "清心丹", "持有",
                    true, "可清心定神", itemAttributes, 0, 0, Instant.now(), Instant.now()));

            TurnRequest request = new TurnRequest("服下一枚清心丹，再收好找到的灵材。", "request-trusted", 1);
            var result = engine.takeTurn(USER, created.saveId(), request);

            assertEquals(2, result.revision());
            assertEquals(1, result.turnNumber());
            assertEquals(30, result.state().worldTimeMinutes());
            var pills = result.state().inventory().stream()
                    .filter(item -> "item-pills".equals(item.id())).findFirst().orElseThrow();
            assertTrue(pills.quantityKnown());
            assertEquals(2, pills.quantity());
            var unknown = result.state().inventory().stream()
                    .filter(item -> "一包散碎灵材".equals(item.name())).findFirst().orElseThrow();
            assertFalse(unknown.quantityKnown());
            assertTrue(unknown.inPossession());
            assertEquals(1, jdbc.queryForObject("select count(*) from story_turn where save_id=?",
                    Integer.class, created.saveId()));
            assertEquals("trusted-story-turn-v1", jdbc.queryForObject(
                    "select prompt_version from story_turn where save_id=?", String.class, created.saveId()));
            String storedPatch = jdbc.queryForObject(
                    "select state_patch_json from story_turn where save_id=?", String.class, created.saveId());
            assertTrue(storedPatch.contains(unknown.id()), "回合必须保存 Reducer 分配后的稳定实体ID");
            assertEquals(2, memories.recentActive(created.saveId(), 20).size());

            var retry = engine.takeTurn(USER, created.saveId(), request);
            assertEquals(1, retry.turnNumber());
            assertEquals(1, agentCalls.get(), "相同 requestId 不得再次调用模型");
            assertEquals(1, jdbc.queryForObject(
                    "select coalesce(sum(used_count), 0) from user_daily_quota where user_id=?",
                    Integer.class, USER.userId()), "成功额度必须与回合在同一事务中只记录一次");
        } finally {
            database.shutdown();
        }
    }

    @Test
    void trustedTurnsPersistDeadCharacterAcrossLaterNarrativeAndApiReload() {
        ObjectNode attributes = mapper.createObjectNode();
        attributes.put("identity", "旧日引路人");
        attributes.put("relationship", "故人");
        attributes.put("lastKnownLocation", "青岐山旧居");
        StoryPatch death = turnPatch("山崩之后", "山崩过后，你只找到闻溪留下的旧物。", 60,
                List.of(new EntityPatch(EntityOperation.CREATE, null, "CHARACTER", "闻溪", "已经身亡",
                        "闻溪死于山崩，只留下旧物。", attributes, null, null, null,
                        new LifecycleValue(LifecycleState.DEAD, "死于山崩"))), List.of(),
                new DecisionCheckpoint(false, null));
        StoryPatch revisit = turnPatch("重返旧居", "多年后你重返旧居，故人并未凭空归来。", 120,
                List.of(new EntityPatch(EntityOperation.UPDATE, "character-trusted-test", "CHARACTER", null,
                        "旧居只余遗物", null, null, null, null, null, null)), List.of(),
                new DecisionCheckpoint(false, null));

        try (TrustedRuntime runtime = trustedRuntime(death, revisit)) {
            advance(runtime, "查看山崩后的旧居", "request-death-1");
            var response = advance(runtime, "多年后重返旧居", "request-death-2");

            StoryProjection projection = loadProjection(runtime);
            assertEquals(1, projection.entities().size());
            assertEquals(LifecycleState.DEAD,
                    projection.entities().get("character-trusted-test").lifecycle());
            assertEquals(1, response.state().characters().size());
            assertEquals("闻溪", response.state().characters().getFirst().name());
            assertEquals("旧居只余遗物", response.state().characters().getFirst().status());
            assertEquals(2, runtime.jdbc().queryForObject(
                    "select count(*) from story_turn where save_id=?", Integer.class, runtime.saveId()));
        }
    }

    @Test
    void trustedTurnsPersistMasteredAbilityAcrossUseAndApiReload() {
        ObjectNode attributes = mapper.createObjectNode();
        attributes.put("abilityType", "瞳术");
        attributes.put("source", "旧日传承");
        StoryPatch learn = turnPatch("掌握照影", "你真正掌握了照影诀。", 30,
                List.of(new EntityPatch(EntityOperation.CREATE, null, "ABILITY", "照影诀", "已经掌握",
                        "可辨灵机与虚妄。", attributes, null, null, AbilityState.MASTERED, null)),
                List.of(), new DecisionCheckpoint(false, null));
        StoryPatch use = turnPatch("照影辨真", "你施展照影诀，看破了石门上的障眼法。", 10,
                List.of(new EntityPatch(EntityOperation.UPDATE, "ability-trusted-test", "ABILITY", null,
                        "刚刚施展", null, null, null, null, AbilityState.MASTERED, null)),
                List.of(), new DecisionCheckpoint(false, null));

        try (TrustedRuntime runtime = trustedRuntime(learn, use)) {
            advance(runtime, "研习照影诀", "request-ability-1");
            var response = advance(runtime, "施展照影诀查看石门", "request-ability-2");

            StoryProjection projection = loadProjection(runtime);
            assertEquals(AbilityState.MASTERED,
                    projection.entities().get("ability-trusted-test").abilityState());
            assertEquals(1, response.state().abilities().size());
            assertEquals("照影诀", response.state().abilities().getFirst().name());
            assertEquals("刚刚施展", response.state().abilities().getFirst().status());
        }
    }

    @Test
    void trustedTurnPersistsExplicitThreeYearDurationInTimelineAndApi() {
        long threeYears = 3L * 360 * 24 * 60;
        StoryPatch retreat = turnPatch("三年闭关", "你依照自己的决定闭关三年，出关时山中草木已换三度。",
                threeYears, List.of(), List.of(), new DecisionCheckpoint(false, null));

        try (TrustedRuntime runtime = trustedRuntime(retreat)) {
            var response = advance(runtime, "闭关三年", "request-time-1");

            assertEquals(threeYears, response.state().worldTimeMinutes());
            assertEquals(threeYears, loadProjection(runtime).worldTimeMinutes());
            assertEquals(0L, runtime.jdbc().queryForObject(
                    "select world_time_before from story_turn where save_id=?", Long.class, runtime.saveId()));
            assertEquals(threeYears, runtime.jdbc().queryForObject(
                    "select world_time_after from story_turn where save_id=?", Long.class, runtime.saveId()));
        }
    }

    @Test
    void trustedTurnPersistsMajorDecisionCheckpointAndReturnsControlToPlayer() {
        StoryPatch proposal = turnPatch("山门之邀",
                "使者说明了入门条件，随后停下等待你的决定，没有替你答应。", 15,
                List.of(), List.of(), new DecisionCheckpoint(true, "是否接受使者提出的入门条件？"));
        StoryPatch answer = turnPatch("婉拒邀请", "你亲自婉拒了邀请，使者没有强求。", 5,
                List.of(), List.of(), new DecisionCheckpoint(false, null));

        try (TrustedRuntime runtime = trustedRuntime(proposal, answer)) {
            var response = advance(runtime, "听完使者的条件", "request-decision-1");

            assertTrue(response.state().narrative().contains("等待你的决定"));
            assertEquals("是否接受使者提出的入门条件？", response.state().pendingDecision().prompt());
            assertEquals("是否接受使者提出的入门条件？",
                    runtime.engine().get(USER, runtime.saveId()).state().pendingDecision().prompt());
            assertEquals("继续观察", response.state().choices().getFirst().title());
            String storedPatch = runtime.jdbc().queryForObject(
                    "select state_patch_json from story_turn where save_id=?", String.class, runtime.saveId());
            JsonNode checkpoint = readTree(storedPatch).path("decisionCheckpoint");
            assertTrue(checkpoint.path("required").asBoolean());
            assertEquals("是否接受使者提出的入门条件？", checkpoint.path("prompt").asText());

            var resolved = advance(runtime, "我决定婉拒邀请", "request-decision-2");
            assertEquals("是否接受使者提出的入门条件？",
                    runtime.generatedContexts().get(1).objective().pendingDecision().prompt());
            assertNull(resolved.state().pendingDecision(), "玩家亲自作答后必须清除旧的待决定提示");
        }
    }

    @Test
    void trustedTurnsPersistWorldRevisionWithStableIdentityAndAuditReason() {
        StoryPatch establish = turnPatch("旧世传闻", "你确认此界一直流传着无人飞升的说法。", 0,
                List.of(), List.of(new WorldFactPatch(WorldFactOperation.EXPAND, null,
                        "此界无人能够飞升", "旅途中确认的公开传闻")),
                new DecisionCheckpoint(false, null));
        StoryPatch revise = turnPatch("修订旧说", "你明确指出飞升道路只是被少数势力隐瞒。", 0,
                List.of(), List.of(new WorldFactPatch(WorldFactOperation.REVISE,
                        "world-fact-trusted-test", "飞升一直存在，只是道路被少数势力隐瞒",
                        "玩家明确修订世界设定")), new DecisionCheckpoint(false, null));

        try (TrustedRuntime runtime = trustedRuntime(establish, revise)) {
            advance(runtime, "确认此界无人飞升的旧说", "request-world-1");
            advance(runtime, "修正世界设定：飞升道路只是被隐瞒", "request-world-2");

            StoryProjection projection = loadProjection(runtime);
            assertEquals(1, projection.worldFacts().size());
            var fact = projection.worldFacts().get("world-fact-trusted-test");
            assertEquals(2, fact.revision());
            assertEquals(WorldFactOperation.REVISE, fact.lastOperation());
            assertEquals("玩家明确修订世界设定", fact.lastReason());
            String latestPatch = runtime.jdbc().queryForObject("""
                    select state_patch_json from story_turn where save_id=? order by turn_number desc limit 1
                    """, String.class, runtime.saveId());
            assertEquals("world-fact-trusted-test",
                    readTree(latestPatch).path("worldFacts").get(0).path("factId").asText());
        }
    }

    @Test
    void trustedTurnMapsProviderRejectionWithoutChangingSaveOrQuota() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            StoryTurnAgent rejectingAgent = (context, route) -> {
                throw new StoryModelFailureException(StoryModelFailureException.Kind.REQUEST_REJECTED,
                        "provider detail must remain internal", null);
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    rejectingAgent, testPlayerAi(jdbc));
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));

            GameApiException error = assertThrows(GameApiException.class, () -> engine.takeTurn(USER,
                    created.saveId(), new TurnRequest("推开山门。", "request-rejected", 1)));

            assertEquals("PLATFORM_GENERATION_UNAVAILABLE", error.code());
            assertTrue(error.retryable());
            assertFalse(error.turnConsumed());
            assertFalse(error.stateChanged());
            var unchanged = saves.findOwned(USER.userId(), created.saveId()).orElseThrow();
            assertEquals(1, unchanged.revision());
            assertEquals(0, unchanged.turnNumber());
            assertEquals(0, jdbc.queryForObject("select count(*) from story_turn where save_id=?",
                    Integer.class, created.saveId()));
            assertEquals(0, jdbc.queryForObject(
                    "select coalesce(sum(used_count), 0) from user_daily_quota where user_id=?",
                    Integer.class, USER.userId()));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void trustedTurnRollsBackEveryWriteWhenQuotaRecordingFails() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            StoryTurnAgent agent = (context, route) -> {
                EntityPatch consume = new EntityPatch(EntityOperation.UPDATE, "item-pills", "ITEM",
                        null, "剩余两枚", null, null, new QuantityValue(true, 2), true,
                        null, null);
                StoryPatch patch = new StoryPatch("服丹静修", "你服下一枚清心丹，在静室中调息片刻。",
                        new TimePassage(30, TimeResolution.COMPLETE, null), null,
                        List.of(consume), List.of(), List.of(),
                        List.of(new StoryPatch.MemoryDraft("EVENT", "服丹静修",
                                "你服下一枚清心丹调息。", "NORMAL", List.of())),
                        new StoryPatch.DecisionCheckpoint(false, null),
                        List.of(new StoryPatch.ChoiceDraft("结束调息", "查看周围动静。")),
                        "你已经服下一枚清心丹。短暂调息仍在继续。");
                return new StoryTurnAgent.Generated(patch, List.of(
                        new com.wenxian.game.story.agent.StoryModelGateway.Result(
                                "{}", "response-rollback", "test-model", 100, 180, 30)));
            };
            PlayerAiService failingQuota = new PlayerAiService(jdbc, null, RestClient.builder(),
                    "https://example.invalid", "test-master-key") {
                @Override
                public void assertPublicQuota(String userId, Route route) {
                    // 允许进入提交阶段，以验证提交末端失败后的整体回滚。
                }

                @Override
                public void recordSuccessfulInvocation(String userId, Route route) {
                    throw new IllegalStateException("simulated quota persistence failure");
                }
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    agent, failingQuota);
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));
            ObjectNode itemAttributes = mapper.createObjectNode();
            itemAttributes.put("itemType", "丹药");
            itemAttributes.put("quantity", 3);
            itemAttributes.put("inPossession", true);
            entities.insert(new Entity("item-pills", created.saveId(), "ITEM", "清心丹", "持有",
                    true, "可清心定神", itemAttributes, 0, 0, Instant.now(), Instant.now()));

            assertThrows(IllegalStateException.class, () -> engine.takeTurn(USER, created.saveId(),
                    new TurnRequest("服下一枚清心丹。", "request-rollback", 1)));

            var unchanged = saves.findOwned(USER.userId(), created.saveId()).orElseThrow();
            assertEquals(1, unchanged.revision());
            assertEquals(0, unchanged.turnNumber());
            Entity unchangedPills = entities.listAll(created.saveId()).stream()
                    .filter(value -> "item-pills".equals(value.id())).findFirst().orElseThrow();
            assertEquals(3, unchangedPills.attributes().path("quantity").asInt());
            assertEquals(1, memories.recentActive(created.saveId(), 20).size(),
                    "创建命卷时的初始记忆必须保留，但失败回合不能新增记忆");
            assertEquals(0, jdbc.queryForObject("select count(*) from story_turn where save_id=?",
                    Integer.class, created.saveId()));
        } finally {
            database.shutdown();
        }
    }

    private PersistedLightNarrativeEngine engine(EmbeddedDatabase database, StorySaveRepository saves,
                                                  StoryEntityRepository entities,
                                                  StoryMemoryRepository memories) {
        return engine(database, saves, entities, memories, true);
    }

    private PersistedLightNarrativeEngine engine(EmbeddedDatabase database, StorySaveRepository saves,
                                                  StoryEntityRepository entities,
                                                  StoryMemoryRepository memories, boolean enabled) {
        JdbcTemplate jdbc = new JdbcTemplate(database);
        ActiveSavePolicyService savePolicy = new ActiveSavePolicyService(jdbc);
        return new PersistedLightNarrativeEngine(saves, new StoryTurnRepository(jdbc, mapper), entities, memories,
                new StoryConversationRepository(jdbc), new StoryCorrectionRepository(jdbc, mapper),
                null, null, null,
                new V014StateReducer(), new StubModels(), new V014WorldCatalog(false), null, savePolicy,
                null, new GameVersionService(jdbc),
                new TransactionTemplate(new DataSourceTransactionManager(database)), mapper, enabled, false,
                "https://example.com");
    }

    private PersistedLightNarrativeEngine trustedEngine(EmbeddedDatabase database, StorySaveRepository saves,
                                                         StoryEntityRepository entities,
                                                         StoryMemoryRepository memories,
                                                         StoryTurnAgent trustedAgent,
                                                         PlayerAiService playerAi) {
        JdbcTemplate jdbc = new JdbcTemplate(database);
        StoryTurnRepository turns = new StoryTurnRepository(jdbc, mapper);
        StoryConversationRepository conversations = new StoryConversationRepository(jdbc);
        StoryProjectionPersistenceMapper projections = new StoryProjectionPersistenceMapper(mapper);
        StoryStateReducer trustedReducer = new StoryStateReducer(new StoryPatchValidator(),
                prefix -> prefix + "-trusted-test");
        StoryTurnPreparationService preparation = new StoryTurnPreparationService(entities, memories,
                conversations, turns, projections, new PromptContextAssembler(), trustedAgent, trustedReducer);
        GameVersionService releases = new GameVersionService(jdbc);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        StoryTurnCommitService commit = new StoryTurnCommitService(saves, turns, entities, memories,
                conversations, projections, releases, playerAi, transactions, mapper);
        return new PersistedLightNarrativeEngine(saves, turns, entities, memories, conversations,
                new StoryCorrectionRepository(jdbc, mapper), preparation, commit, null,
                new V014StateReducer(), new StubModels(), new V014WorldCatalog(false), null,
                new ActiveSavePolicyService(jdbc), playerAi, releases, transactions, mapper,
                true, true, "https://example.com");
    }

    private PlayerAiService testPlayerAi(JdbcTemplate jdbc) {
        return new PlayerAiService(jdbc, null, RestClient.builder(),
                "https://example.invalid", "test-master-key") {
            @Override
            public void assertPublicQuota(String userId, Route route) {
                // 运行时事务测试不依赖运营额度配置，只保留成功扣减的真实数据库行为。
            }
        };
    }

    private TrustedRuntime trustedRuntime(StoryPatch... patches) {
        EmbeddedDatabase database = database();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
        StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
        StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
        AtomicInteger index = new AtomicInteger();
        List<StoryPromptContext> generatedContexts = new ArrayList<>();
        StoryTurnAgent agent = (context, route) -> {
            generatedContexts.add(context);
            int current = index.getAndIncrement();
            if (current >= patches.length) throw new AssertionError("可信回放缺少第 " + (current + 1) + " 轮提案");
            return new StoryTurnAgent.Generated(patches[current], List.of(
                    new com.wenxian.game.story.agent.StoryModelGateway.Result(
                            "{}", "response-persisted-" + current, "test-model", 100, 180, 30)));
        };
        PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                agent, testPlayerAi(jdbc));
        var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));
        return new TrustedRuntime(database, jdbc, saves, entities, memories, engine, created.saveId(),
                generatedContexts);
    }

    private com.wenxian.game.story.model.StoryApiModels.SaveResponse advance(
            TrustedRuntime runtime, String action, String requestId) {
        var current = runtime.engine().get(USER, runtime.saveId());
        return runtime.engine().takeTurn(USER, runtime.saveId(),
                new TurnRequest(action, requestId, current.revision()));
    }

    private StoryProjection loadProjection(TrustedRuntime runtime) {
        var save = runtime.saves().findOwned(USER.userId(), runtime.saveId()).orElseThrow();
        return new StoryProjectionPersistenceMapper(mapper).load(save,
                runtime.entities().listAll(runtime.saveId()), runtime.memories().recentActive(runtime.saveId(), 80));
    }

    private StoryPatch turnPatch(String title, String narrative, long elapsedMinutes,
                                 List<EntityPatch> entities, List<WorldFactPatch> worldFacts,
                                 DecisionCheckpoint checkpoint) {
        return new StoryPatch(title, narrative,
                new TimePassage(elapsedMinutes, TimeResolution.COMPLETE, null), null,
                entities, worldFacts, List.of(), List.of(), checkpoint,
                List.of(new StoryPatch.ChoiceDraft("继续观察", "先确认眼前变化，再决定下一步。")),
                "你完成了本轮已经授权的行动。");
    }

    private JsonNode readTree(String value) {
        try {
            return mapper.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new AssertionError("测试无法读取已保存的回合补丁", error);
        }
    }

    private record TrustedRuntime(
            EmbeddedDatabase database,
            JdbcTemplate jdbc,
            StorySaveRepository saves,
            StoryEntityRepository entities,
            StoryMemoryRepository memories,
            PersistedLightNarrativeEngine engine,
            String saveId,
            List<StoryPromptContext> generatedContexts
    ) implements AutoCloseable {
        @Override
        public void close() {
            database.shutdown();
        }
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                USER.userId(), USER.email(), "ACTIVE", now, now);
        jdbc.update("insert into gameplay_config(config_key,config_value,updated_at) values (?,?,?)",
                ActiveSavePolicyService.CONFIG_KEY, "2", now);
        return database;
    }

    private ObjectNode core() {
        V014State.WorldProfile world = new V014State.WorldProfile("XUANXU", "玄墟界", "OFFICIAL",
                "仙路难寻", "轻叙事", "青岐州");
        V014State.Protagonist protagonist = new V014State.Protagonist("陆沉", "人族", "男", "山村少年",
                "引气", "随经历形成", "自由探索");
        ObjectNode core = mapper.createObjectNode();
        core.put("schemaVersion", 1);
        core.put("saveId", "story-v014-test");
        core.put("turnNumber", 3);
        core.put("status", "ACTIVE");
        core.set("world", mapper.valueToTree(world));
        core.set("protagonist", mapper.valueToTree(protagonist));
        core.put("worldTimeMinutes", 180);
        core.put("currentLocation", "青岐城");
        core.put("condition", "神完气足");
        core.put("sceneTitle", "雨后入城");
        core.put("narrative", "你走入青岐城。");
        core.set("choices", mapper.valueToTree(List.of(
                new V014State.Choice("choice-test", "查看令牌", "辨认来历"))));
        return core;
    }

    private static final class StubModels extends V014ModelPreferenceService {
        private StubModels() {
            super(null, null);
        }

        @Override
        public ModelPreference get(String userId) {
            return new ModelPreference("DEEPSEEK", "test-model", "PUBLIC");
        }

        @Override
        public ResolvedRoute resolve(String userId, String ignoredLegacyBaseUrl) {
            return new ResolvedRoute("https://example.invalid", "test-secret-key", "DEEPSEEK",
                    "OPENAI_COMPATIBLE", "test-model", "PUBLIC", false);
        }
    }
}
