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
import com.wenxian.game.story.agent.StoryModelRoute;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryTurnAgent;
import com.wenxian.game.story.application.StoryTurnCommitService;
import com.wenxian.game.story.application.StoryTurnPreparationService;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryActionContract;
import com.wenxian.game.story.domain.StoryPatch.AbilityState;
import com.wenxian.game.story.domain.StoryPatch.CustodyValue;
import com.wenxian.game.story.domain.StoryPatch.DecisionCheckpoint;
import com.wenxian.game.story.domain.StoryPatch.EntityOperation;
import com.wenxian.game.story.domain.StoryPatch.EntityPatch;
import com.wenxian.game.story.domain.StoryPatch.HolderType;
import com.wenxian.game.story.domain.StoryPatch.LifecycleState;
import com.wenxian.game.story.domain.StoryPatch.LifecycleValue;
import com.wenxian.game.story.domain.StoryPatch.NameChange;
import com.wenxian.game.story.domain.StoryPatch.OutcomeResolution;
import com.wenxian.game.story.domain.StoryPatch.QuantityValue;
import com.wenxian.game.story.domain.StoryPatch.RelationshipValue;
import com.wenxian.game.story.domain.StoryPatch.TimePassage;
import com.wenxian.game.story.domain.StoryPatch.TimeResolution;
import com.wenxian.game.story.domain.StoryPatch.TurnOutcome;
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
            saves.insert(new NewSave("story-v014-test", USER.userId(), 1, 1, "ACTIVE", "XUANXU",
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
            ObjectNode storedElsewhereAttributes = mapper.createObjectNode();
            storedElsewhereAttributes.put("itemType", "信物");
            storedElsewhereAttributes.put("source", "故事所得");
            storedElsewhereAttributes.put("quantity", 1);
            storedElsewhereAttributes.put("inPossession", false);
            ObjectNode storedElsewhereTrusted = mapper.createObjectNode();
            storedElsewhereTrusted.set("quantity", mapper.valueToTree(new QuantityValue(true, 1)));
            storedElsewhereTrusted.put("inPossession", false);
            storedElsewhereTrusted.set("custody", mapper.valueToTree(
                    new CustodyValue(HolderType.LOCATION, "青岐山门")));
            storedElsewhereAttributes.set("_trusted", storedElsewhereTrusted);
            entities.insert(new Entity("entity-elsewhere", "story-v014-test", "ITEM", "山门石碑", "留在山门",
                    true, "由地点保管的历史物品事实", storedElsewhereAttributes,
                    1, 3, Instant.now(), Instant.now()));
            ObjectNode legacyCharacterAttributes = mapper.createObjectNode();
            legacyCharacterAttributes.put("identity", "村中老妇");
            legacyCharacterAttributes.put("relationship", "忘年之交");
            legacyCharacterAttributes.put("lastKnownLocation", "石溪村村尾");
            entities.insert(new Entity("character-legacy", "story-v014-test", "CHARACTER", "老妇人",
                    "现替玩家保管已经取回的断剑尖", true, "旧版自由文本可能已过期",
                    legacyCharacterAttributes, 1, 3, Instant.now(), Instant.now()));
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
            assertFalse(result.state().inventory().stream()
                    .anyMatch(item -> "entity-elsewhere".equals(item.id())),
                    "由地点保管的历史物品不应出现在当前行囊");
            assertEquals("UNKNOWN", result.state().characters().getFirst().status(),
                    "旧人物自由状态不能作为角色页可信现状");
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
            Entity removedEntity = entities.listAll("story-v014-test").stream()
                    .filter(value -> "entity-token".equals(value.id())).findFirst().orElseThrow();
            assertFalse(removedEntity.visible());
            assertFalse(removedEntity.attributes().path("inPossession").asBoolean(true));
            assertFalse(removedEntity.attributes().path("_trusted").path("inPossession").asBoolean(true));

            var corrected = engine.correct(USER, "story-v014-test",
                    new CorrectionRequest("地点应为青岐山门", 2));
            assertEquals(3, corrected.revision());
            assertEquals("青岐山门", corrected.state().currentLocation());

            engine.deleteSave(USER, "story-v014-test");
            assertTrue(engine.list(USER).isEmpty());
            assertThrows(GameApiException.class, () -> engine.get(USER, "story-v014-test"));
            assertEquals("ARCHIVED", jdbc.queryForObject(
                    "select status from story_save where id='story-v014-test'", String.class),
                    "逻辑删除同时保留旧后端可识别的封存状态，防止被旧版计入存活命卷");
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
    void createsFourFixedSlotsAndReusesADeletedSlot() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            saves.insert(new NewSave("story-existing-active", USER.userId(), 1, 1, "ACTIVE", "XUANXU",
                    "LIGHT_NARRATIVE", 1, 1, "NATIVE", "V0.14", null, null,
                    core(), 3, "V0.14", "V0.14", Instant.now()));
            PersistedLightNarrativeEngine engine = engine(database, saves, entities, memories);
            CreateSaveRequest request = new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门少主，已经筑基。", "你在山门晨钟中醒来。", "自由探索", "筑基初期");

            var created = engine.create(USER, request);

            assertTrue(created.saveId().startsWith("story-"));
            assertEquals(2, saves.findOwned(USER.userId(), created.saveId()).orElseThrow().slotNo());
            assertEquals(2, saves.findOwned(USER.userId(), created.saveId()).orElseThrow().activeSlotNo());
            assertEquals(2, saves.countActive(USER.userId()));
            assertEquals("楚星河", created.state().protagonist().name());
            assertEquals("筑基初期", created.state().protagonist().realm());
            StoryProjection createdProjection = new StoryProjectionPersistenceMapper(mapper).load(
                    saves.findOwned(USER.userId(), created.saveId()).orElseThrow(), List.of(), List.of());
            assertEquals("筑基初期", createdProjection.initialContract().startingRealm());
            assertEquals("你在山门晨钟中醒来。", jdbc.queryForObject(
                    "select rolling_summary from story_conversation where save_id=?", String.class,
                    created.saveId()));
            assertEquals(0, jdbc.queryForObject(
                    "select summarized_through_turn from story_conversation where save_id=?", Integer.class,
                    created.saveId()));
            engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星月", "人族", "女",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));
            engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星岚", "人族", "女",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));
            GameApiException error = assertThrows(GameApiException.class, () -> engine.create(USER, request));
            assertEquals("ACTIVE_SAVE_LIMIT_REACHED", error.code());

            engine.deleteSave(USER, created.saveId());
            var reused = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星霜", "人族", "女",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));
            assertEquals(2, saves.findOwned(USER.userId(), reused.saveId()).orElseThrow().activeSlotNo());
            assertEquals(4, engine.list(USER).size());
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
            assertEquals("trusted-story-turn-v3", jdbc.queryForObject(
                    "select prompt_version from story_turn where save_id=?", String.class, created.saveId()));
            String storedPatch = jdbc.queryForObject(
                    "select state_patch_json from story_turn where save_id=?", String.class, created.saveId());
            assertTrue(storedPatch.contains(unknown.id()), "回合必须保存 Reducer 分配后的稳定实体ID");
            assertTrue(storedPatch.contains("trustedEvents"), "回合必须保存 Reducer 接受后编译的可信事件");
            assertTrue(storedPatch.contains("ITEM_TRANSFERRED"), "物品变化必须形成带类型的可信事件");
            assertEquals(2, memories.recentActive(created.saveId(), 20).size());
            assertEquals("COMMITTED", jdbc.queryForObject("""
                    select status from story_turn_diagnostic where save_id=? and request_id=?
                    """, String.class, created.saveId(), request.requestId()));
            String contextManifest = jdbc.queryForObject("""
                    select context_manifest_json from story_turn_diagnostic where save_id=? and request_id=?
                    """, String.class, created.saveId(), request.requestId());
            assertTrue(contextManifest.contains("item-pills"));
            assertFalse(contextManifest.contains("服下一枚清心丹"), "诊断清单不能复制完整玩家输入或正文");
            String commitSummary = jdbc.queryForObject("""
                    select commit_summary_json from story_turn_diagnostic where save_id=? and request_id=?
                    """, String.class, created.saveId(), request.requestId());
            assertTrue(commitSummary.contains(unknown.id()));

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
    void selectedSystemChoiceBecomesTraceableContextSource() {
        try (TrustedRuntime runtime = trustedRuntime(turnPatch("察看山门", "你依照眼前提示察看山门，没有替自己作出新的重大决定。",
                15, List.of(), List.of(), new DecisionCheckpoint(false, null)))) {
            var current = runtime.engine().get(USER, runtime.saveId());
            var choice = current.state().choices().getFirst();

            runtime.engine().takeTurn(USER, runtime.saveId(), new TurnRequest(
                    choice.title() + "：" + choice.description(), "request-selected-choice",
                    current.revision(), choice.id()));

            StoryPromptContext context = runtime.generatedContexts().getFirst();
            assertEquals(choice.id(), context.action().selectedChoice().id());
            assertTrue(context.objective().sourceExcerpts().stream()
                    .anyMatch(source -> ("choice:" + choice.id()).equals(source.ref())));
        }
    }

    @Test
    void staleSystemChoiceIsRejectedBeforeCallingNarrativeAgent() {
        try (TrustedRuntime runtime = trustedRuntime()) {
            var current = runtime.engine().get(USER, runtime.saveId());

            GameApiException error = assertThrows(GameApiException.class,
                    () -> runtime.engine().takeTurn(USER, runtime.saveId(), new TurnRequest(
                            "执行过期选项", "request-stale-choice", current.revision(), "choice-stale")));

            assertEquals("ACTION_PRECONDITION_FAILED", error.code());
            assertTrue(error.getMessage().contains("已经不属于当前场景"));
            assertTrue(runtime.generatedContexts().isEmpty());
            assertEquals(0, runtime.engine().get(USER, runtime.saveId()).turnNumber());
        }
    }

    @Test
    void trustedTurnsPersistDeadCharacterAcrossLaterNarrativeAndApiReload() {
        ObjectNode attributes = mapper.createObjectNode();
        attributes.put("identity", "旧日引路人");
        attributes.put("lastKnownLocation", "青岐山旧居");
        StoryPatch death = turnPatch("山崩之后", "山崩过后，你只找到闻溪留下的旧物。", 60,
                List.of(new EntityPatch(EntityOperation.CREATE, null, "CHARACTER", "闻溪", "已经身亡",
                        "闻溪死于山崩，只留下旧物。", attributes, null, null, null, null,
                        new LifecycleValue(LifecycleState.DEAD, "死于山崩"),
                        new RelationshipValue("故人", "曾是旧日引路人"))), List.of(),
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
            assertEquals("DEAD", response.state().characters().getFirst().status(),
                    "角色页只能读取可信生命周期，不能继续透传人物自由状态");
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
    void trustedTurnRepairsACompletedActionThatShortensExplicitPlayerTime() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            long threeYears = 3L * 360 * 24 * 60;
            AtomicInteger repairs = new AtomicInteger();
            StoryTurnAgent agent = new StoryTurnAgent() {
                @Override
                public Generated generate(StoryPromptContext context, StoryModelRoute route) {
                    return generated(turnPatch("闭关三月", "你闭关三个月后自行出关。", 90L * 24 * 60,
                            List.of(), List.of(), new DecisionCheckpoint(false, null)), "initial");
                }

                @Override
                public Generated repairRejected(StoryPromptContext context, StoryModelRoute route,
                                                InvocationContext invocation, StoryPatch proposal,
                                                String problem) {
                    repairs.incrementAndGet();
                    assertTrue(problem.contains("完整执行必须推进同样时长"));
                    return generated(turnPatch("三年闭关", "你依照自己的决定闭关三年。", threeYears,
                            List.of(), List.of(), new DecisionCheckpoint(false, null)), "repair");
                }

                private Generated generated(StoryPatch patch, String responseId) {
                    return new Generated(patch, List.of(
                            new com.wenxian.game.story.agent.StoryModelGateway.Result(
                                    "{}", responseId, "test-model", 80, 120, 20)));
                }
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    agent, testPlayerAi(jdbc));
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));

            var response = engine.takeTurn(USER, created.saveId(),
                    new TurnRequest("闭关三年，专心温养神识", "request-time-repair", 1));

            assertEquals(1, repairs.get());
            assertEquals(threeYears, response.state().worldTimeMinutes());
            assertEquals(1, response.state().turnNumber());
        } finally {
            database.shutdown();
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
    void trustedRuntimeKeepsThirtySequentialTurnsConsistent() {
        List<StoryPatch> patches = new ArrayList<>();
        long expectedMinutes = 0;
        for (int turn = 1; turn <= 30; turn++) {
            List<EntityPatch> entityPatches = new ArrayList<>();
            List<WorldFactPatch> factPatches = new ArrayList<>();
            DecisionCheckpoint checkpoint = new DecisionCheckpoint(false, null);
            long elapsedMinutes = turn == 12 ? 30L * 24 * 60 : 60;
            expectedMinutes += elapsedMinutes;

            if (turn == 1) {
                ObjectNode attributes = mapper.createObjectNode();
                attributes.put("itemType", "丹药");
                entityPatches.add(new EntityPatch(EntityOperation.CREATE, null, "ITEM", "清心丹",
                        "随身携带", "可清心定神。", attributes, new QuantityValue(true, 5), true,
                        null, null));
            } else if (turn == 2) {
                ObjectNode attributes = mapper.createObjectNode();
                attributes.put("abilityType", "瞳术");
                entityPatches.add(new EntityPatch(EntityOperation.CREATE, null, "ABILITY", "照影诀",
                        "已经掌握", "可辨灵机与虚妄。", attributes, null, null,
                        AbilityState.MASTERED, null));
            } else if (turn == 3) {
                ObjectNode attributes = mapper.createObjectNode();
                entityPatches.add(new EntityPatch(EntityOperation.CREATE, null, "CHARACTER", "闻溪",
                        "结伴同行", "在山道相识的同行者。", attributes, null, null, null, null,
                        new LifecycleValue(LifecycleState.ACTIVE, null),
                        new RelationshipValue("同行者", "本轮在山道结伴")));
            } else if (turn == 10) {
                entityPatches.add(new EntityPatch(EntityOperation.UPDATE, "item-trusted-test", "ITEM",
                        null, "剩余四枚", null, null, new QuantityValue(true, 4), true,
                        null, null));
            } else if (turn == 20) {
                entityPatches.add(new EntityPatch(EntityOperation.UPDATE, "item-trusted-test", "ITEM",
                        null, "剩余三枚", null, null, new QuantityValue(true, 3), true,
                        null, null));
            } else if (turn == 25) {
                entityPatches.add(new EntityPatch(EntityOperation.UPDATE, "character-trusted-test",
                        "CHARACTER", null, "暂别后仍保持联系", null, null, null, null,
                        null, null));
            } else if (turn == 30) {
                entityPatches.add(new EntityPatch(EntityOperation.UPDATE, "ability-trusted-test", "ABILITY",
                        null, "运转自如", null, null, null, null, AbilityState.MASTERED, null));
            }
            if (turn == 5) {
                factPatches.add(new WorldFactPatch(WorldFactOperation.EXPAND, null,
                        "青岐州北部近日出现灵潮", "旅途中确认的公开异象"));
            }
            if (turn == 15) {
                checkpoint = new DecisionCheckpoint(true, "是否接受陌生宗门的同行邀请？");
            }
            patches.add(turnPatch("长线回放·" + turn,
                    "你完成了第" + turn + "轮已经授权的行动，并在新的选择前停下。",
                    elapsedMinutes, entityPatches, factPatches, checkpoint));
        }

        try (TrustedRuntime runtime = trustedRuntime(patches.toArray(StoryPatch[]::new))) {
            for (int turn = 1; turn <= 30; turn++) {
                advance(runtime, turn == 16 ? "我决定婉拒邀请，继续自己的道路" : "执行第" + turn + "轮行动",
                        "request-long-run-" + turn);
            }

            var response = runtime.engine().get(USER, runtime.saveId());
            StoryProjection projection = loadProjection(runtime);
            assertEquals(30, response.state().turnNumber());
            assertEquals(31, response.revision());
            assertEquals(expectedMinutes, response.state().worldTimeMinutes());
            assertEquals(3, projection.entities().size());
            assertEquals(3, projection.entities().get("item-trusted-test").quantity().value());
            assertEquals(AbilityState.MASTERED,
                    projection.entities().get("ability-trusted-test").abilityState());
            assertEquals(LifecycleState.ACTIVE,
                    projection.entities().get("character-trusted-test").lifecycle());
            assertEquals(1, projection.worldFacts().size());
            assertNull(response.state().pendingDecision());
            assertEquals(30, runtime.jdbc().queryForObject(
                    "select count(*) from story_turn where save_id=?", Integer.class, runtime.saveId()));
            assertEquals(30, runtime.jdbc().queryForObject(
                    "select coalesce(sum(used_count), 0) from user_daily_quota where user_id=?",
                    Integer.class, USER.userId()));
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
    void trustedTurnRepairsOneReducerRejectedProposalAndCountsEveryModelRequest() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            StoryPatch rejected = turnPatch("北境异象", "你闭关时察觉北方有异象升起。", 60,
                    List.of(), List.of(new WorldFactPatch(WorldFactOperation.TRANSFORM,
                            "不存在的世界事实", "青岐州北方出现灵潮", "闭关期间的局势变化")),
                    new DecisionCheckpoint(false, null));
            StoryPatch repaired = turnPatch("北境异象", "你闭关时察觉北方有异象升起。", 60,
                    List.of(), List.of(new WorldFactPatch(WorldFactOperation.EXPAND,
                            null, "青岐州北方出现灵潮", "闭关期间的局势变化")),
                    new DecisionCheckpoint(false, null));
            AtomicInteger repairs = new AtomicInteger();
            StoryTurnAgent semanticRepairAgent = new StoryTurnAgent() {
                @Override
                public Generated generate(StoryPromptContext context, StoryModelRoute route) {
                    return generated(rejected, "initial", 100, 180, 30);
                }

                @Override
                public Generated repairRejected(StoryPromptContext context, StoryModelRoute route,
                                                InvocationContext invocation, StoryPatch proposal,
                                                String problem) {
                    repairs.incrementAndGet();
                    assertTrue(problem.contains("世界事实不存在"));
                    return generated(repaired, "semantic-repair", 40, 90, 20);
                }

                private Generated generated(StoryPatch patch, String responseId,
                                            int inputTokens, int outputTokens, long latencyMs) {
                    return new Generated(patch, List.of(
                            new com.wenxian.game.story.agent.StoryModelGateway.Result(
                                    "{}", responseId, "test-model", inputTokens, outputTokens, latencyMs)));
                }
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    semanticRepairAgent, testPlayerAi(jdbc));
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));

            var response = engine.takeTurn(USER, created.saveId(),
                    new TurnRequest("闭关时留意外界变化", "request-semantic-repair", 1));

            assertEquals(1, repairs.get());
            assertEquals(1, response.state().turnNumber());
            StoryProjection projection = new StoryProjectionPersistenceMapper(mapper).load(
                    saves.findOwned(USER.userId(), created.saveId()).orElseThrow(),
                    entities.listAll(created.saveId()), memories.recentActive(created.saveId(), 20));
            assertTrue(projection.worldFacts().values().stream()
                    .anyMatch(value -> "青岐州北方出现灵潮".equals(value.statement())));
            assertEquals(140, jdbc.queryForObject(
                    "select prompt_tokens from story_turn where save_id=?", Integer.class, created.saveId()));
            assertEquals(270, jdbc.queryForObject(
                    "select completion_tokens from story_turn where save_id=?", Integer.class, created.saveId()));
            String proposalTrace = jdbc.queryForObject("""
                    select proposal_trace_json from story_turn_diagnostic where save_id=? and request_id=?
                    """, String.class, created.saveId(), "request-semantic-repair");
            assertTrue(proposalTrace.contains("INITIAL_NARRATIVE_DRAFT"));
            assertTrue(proposalTrace.contains("INITIAL_FACT_PROJECTION"));
            assertTrue(proposalTrace.contains("REPAIRED_NARRATIVE_DRAFT"));
            assertTrue(proposalTrace.contains("REPAIRED_FACT_PROJECTION"));
            String reviewTrace = jdbc.queryForObject("""
                    select review_trace_json from story_turn_diagnostic where save_id=? and request_id=?
                    """, String.class, created.saveId(), "request-semantic-repair");
            assertTrue(reviewTrace.contains("INITIAL_VALIDATION"));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void trustedTurnRepairsNarrativeThatHighlyRepeatsPreviousTurn() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            StringBuilder repeatedBody = new StringBuilder();
            for (int index = 0; index < 45; index++) {
                repeatedBody.append("你检查院墙第").append(index).append("处痕迹，确认四周平静后仍留在原地。");
            }
            String firstNarrative = "夜色渐深。" + repeatedBody + "你停在院外等待。";
            StoryPatch first = turnPatch("院外停步", firstNarrative, 5,
                    List.of(), List.of(), new DecisionCheckpoint(false, null));
            StoryPatch repeated = turnPatch("妄念骤起", "月上中天。" + repeatedBody + "你仍停在院外等待。", 0,
                    List.of(), List.of(), new DecisionCheckpoint(false, null));
            StoryPatch repaired = turnPatch("妄念未成", "你察觉这个念头远超当前境界，只让它从心中掠过，并未获得任何力量。", 1,
                    List.of(), List.of(), new DecisionCheckpoint(false, null));
            AtomicInteger generations = new AtomicInteger();
            AtomicInteger repairs = new AtomicInteger();
            AtomicInteger freshRegenerations = new AtomicInteger();
            StoryTurnAgent freshnessAgent = new StoryTurnAgent() {
                @Override
                public Generated generate(StoryPromptContext context, StoryModelRoute route) {
                    StoryPatch patch = generations.getAndIncrement() == 0 ? first : repeated;
                    return generated(patch, "generation");
                }

                @Override
                public Generated repairRejected(StoryPromptContext context, StoryModelRoute route,
                                                InvocationContext invocation, StoryPatch proposal,
                                                String problem) {
                    repairs.incrementAndGet();
                    throw new AssertionError("正文重复不应进入一般语义修复");
                }

                @Override
                public Generated regenerateFreshNarrative(StoryPromptContext context, StoryModelRoute route,
                                                          InvocationContext invocation, StoryPatch proposal,
                                                          String problem) {
                    freshRegenerations.incrementAndGet();
                    assertTrue(problem.contains("正文与上一回合高度重复"));
                    return generated(repaired, "freshness-repair");
                }

                private Generated generated(StoryPatch patch, String responseId) {
                    return new Generated(patch, List.of(
                            new com.wenxian.game.story.agent.StoryModelGateway.Result(
                                    "{}", responseId, "test-model", 40, 90, 20)));
                }
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    freshnessAgent, testPlayerAi(jdbc));
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));

            engine.takeTurn(USER, created.saveId(),
                    new TurnRequest("在院外停下", "request-freshness-first", 1));
            var current = engine.get(USER, created.saveId());
            var response = engine.takeTurn(USER, created.saveId(),
                    new TurnRequest("尝试凭空突破", "request-freshness-second", current.revision()));

            assertEquals(0, repairs.get());
            assertEquals(1, freshRegenerations.get());
            assertEquals(2, response.state().turnNumber());
            assertEquals(repaired.narrative(), response.state().narrative());
            assertEquals(repaired.narrative(), jdbc.queryForObject("""
                    select narrative_text from story_turn where save_id=? and turn_number=2
                    """, String.class, created.saveId()));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void trustedTurnRegeneratesFreshNarrativeAfterAuthorizationReviewRejection() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            StoryPatch overstepped = turnPatch("问安之后", "你问过屋顶，又擅自追问了古战场。", 15,
                    List.of(), List.of(), new DecisionCheckpoint(false, null));
            StoryPatch repaired = turnPatch("只问屋顶", "你只询问屋顶是否漏雨，确认无恙后停在院外。", 15,
                    List.of(), List.of(), new DecisionCheckpoint(false, null));
            AtomicInteger reviews = new AtomicInteger();
            AtomicInteger repairs = new AtomicInteger();
            AtomicInteger freshRegenerations = new AtomicInteger();
            StoryTurnAgent reviewedAgent = new StoryTurnAgent() {
                @Override
                public Generated generate(StoryPromptContext context, StoryModelRoute route) {
                    return generated(overstepped, "initial", 100, 180);
                }

                @Override
                public Review review(StoryPromptContext context, StoryModelRoute route,
                                     InvocationContext invocation, StoryPatch proposed) {
                    int attempt = reviews.getAndIncrement();
                    var result = new com.wenxian.game.story.agent.StoryModelGateway.Result(
                            "{}", "review-" + attempt, "test-model", 10, 8, 5);
                    return attempt == 0
                            ? new Review(false, "正文擅自追问了玩家未授权的古战场", true, List.of(result))
                            : new Review(true, "", List.of(result));
                }

                @Override
                public Generated repairRejected(StoryPromptContext context, StoryModelRoute route,
                                                InvocationContext invocation, StoryPatch proposal,
                                                String problem) {
                    repairs.incrementAndGet();
                    throw new AssertionError("玩家授权类拒绝不应在原正文上局部修补");
                }

                @Override
                public Generated regenerateFreshNarrative(StoryPromptContext context, StoryModelRoute route,
                                                          InvocationContext invocation, StoryPatch proposal,
                                                          String problem) {
                    freshRegenerations.incrementAndGet();
                    assertTrue(problem.contains("擅自追问"));
                    return generated(repaired, "fresh-regeneration", 40, 90);
                }

                private Generated generated(StoryPatch patch, String responseId, int input, int output) {
                    return new Generated(patch, List.of(
                            new com.wenxian.game.story.agent.StoryModelGateway.Result(
                                    "{}", responseId, "test-model", input, output, 20)));
                }
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    reviewedAgent, testPlayerAi(jdbc));
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));

            var response = engine.takeTurn(USER, created.saveId(),
                    new TurnRequest("只询问屋顶是否漏雨", "request-review-repair", 1));

            assertEquals(2, reviews.get());
            assertEquals(0, repairs.get());
            assertEquals(1, freshRegenerations.get());
            assertEquals(repaired.narrative(), response.state().narrative());
            assertEquals(160, jdbc.queryForObject(
                    "select prompt_tokens from story_turn where save_id=?", Integer.class, created.saveId()));
            assertEquals(286, jdbc.queryForObject(
                    "select completion_tokens from story_turn where save_id=?", Integer.class, created.saveId()));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void trustedTurnRejectsSecondInvalidProposalWithoutChangingSaveOrQuota() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            StoryPatch invalid = turnPatch("北境异象", "你闭关时察觉北方有异象升起。", 60,
                    List.of(), List.of(new WorldFactPatch(WorldFactOperation.TRANSFORM,
                            "不存在的世界事实", "青岐州北方出现灵潮", "闭关期间的局势变化")),
                    new DecisionCheckpoint(false, null));
            AtomicInteger repairs = new AtomicInteger();
            StoryTurnAgent alwaysInvalidAgent = new StoryTurnAgent() {
                @Override
                public Generated generate(StoryPromptContext context, StoryModelRoute route) {
                    return generated("initial");
                }

                @Override
                public Generated repairRejected(StoryPromptContext context, StoryModelRoute route,
                                                InvocationContext invocation, StoryPatch proposal,
                                                String problem) {
                    repairs.incrementAndGet();
                    return generated("semantic-repair");
                }

                private Generated generated(String responseId) {
                    return new Generated(invalid, List.of(
                            new com.wenxian.game.story.agent.StoryModelGateway.Result(
                                    "{}", responseId, "test-model", 40, 90, 20)));
                }
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    alwaysInvalidAgent, testPlayerAi(jdbc));
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));

            GameApiException error = assertThrows(GameApiException.class, () -> engine.takeTurn(USER,
                    created.saveId(), new TurnRequest("闭关时留意外界变化", "request-invalid-repair", 1)));

            assertEquals(1, repairs.get());
            assertEquals("STORY_INTERNAL_RECOVERY_FAILED", error.code());
            assertEquals("SYSTEM", error.category());
            assertEquals("TURN_PROPOSAL", error.details().get("stage"));
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
    void trustedTurnReportsFactProjectionProtocolFailureWithoutChangingSaveOrQuota() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            StoryPatch validDraft = turnPatch("雨后屋檐", "你停在檐下，只观察雨水从瓦边滴落。", 10,
                    List.of(), List.of(), new DecisionCheckpoint(false, null));
            StoryTurnAgent projectionFailure = new StoryTurnAgent() {
                @Override
                public Generated generate(StoryPromptContext context, StoryModelRoute route) {
                    return new Generated(validDraft, List.of(
                            new com.wenxian.game.story.agent.StoryModelGateway.Result(
                                    "{}", "projection-draft", "test-model", 40, 90, 20)));
                }

                @Override
                public ProjectedFacts projectFacts(StoryPromptContext context, StoryModelRoute route,
                                                   InvocationContext invocation, StoryPatch narrativeDraft) {
                    throw new IllegalArgumentException("事实投影连续两次返回了无效结构");
                }
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    projectionFailure, testPlayerAi(jdbc));
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));

            GameApiException error = assertThrows(GameApiException.class, () -> engine.takeTurn(USER,
                    created.saveId(), new TurnRequest("只观察屋檐", "request-projection-protocol-failure", 1)));

            assertEquals("STORY_INTERNAL_RECOVERY_FAILED", error.code());
            assertEquals("SYSTEM", error.category());
            assertEquals("FACT_PROJECTION", error.details().get("stage"));
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
    void outcomePlanGenerationFailureIsReportedAsSystemRecoveryWithoutChangingSaveOrQuota() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            StoryTurnAgent planFailure = new StoryTurnAgent() {
                @Override
                public Generated generate(StoryPromptContext context, StoryModelRoute route) {
                    throw new AssertionError("结果优先 Agent 不应调用旧入口");
                }

                @Override
                public boolean supportsOutcomeFirst() {
                    return true;
                }

                @Override
                public PlannedOutcome planOutcome(StoryPromptContext context, StoryModelRoute route,
                                                   InvocationContext invocation) {
                    throw new IllegalArgumentException("最小结果计划连续两次包含无效结构");
                }
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    planFailure, testPlayerAi(jdbc));
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));

            GameApiException error = assertThrows(GameApiException.class, () -> engine.takeTurn(USER,
                    created.saveId(), new TurnRequest("前往东街寻找老修士", "request-plan-protocol-failure", 1)));

            assertEquals("STORY_INTERNAL_RECOVERY_FAILED", error.code());
            assertEquals("SYSTEM", error.category());
            assertEquals("OUTCOME_PLAN_GENERATION", error.details().get("stage"));
            assertTrue(error.retryable());
            assertFalse(error.turnConsumed());
            assertFalse(error.stateChanged());
            var unchanged = saves.findOwned(USER.userId(), created.saveId()).orElseThrow();
            assertEquals(1, unchanged.revision());
            assertEquals(0, unchanged.turnNumber());
            assertEquals(0, jdbc.queryForObject("select count(*) from story_turn where save_id=?",
                    Integer.class, created.saveId()));
            assertEquals(0, jdbc.queryForObject(
                    "select coalesce(sum(used_count),0) from user_daily_quota where user_id=?",
                    Integer.class, USER.userId()));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void trustedTurnReturnsActionConflictBeforeNarrativeGenerationAndKeepsTurn() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            AtomicInteger generations = new AtomicInteger();
            AtomicInteger contractRepairs = new AtomicInteger();
            StoryTurnAgent contractRejectingAgent = new StoryTurnAgent() {
                @Override
                public ResolvedAction resolveAction(StoryPromptContext context, StoryModelRoute route,
                                                    InvocationContext invocation) {
                    StoryActionContract contract = new StoryActionContract("取回未曾交付的旧物", true,
                            List.of(), List.of(), List.of(), "",
                            new StoryActionContract.Conflict(true,
                                    "断剑尖仍在你的行囊，并未交给老妇。", "可以直接检查行囊，或重新描述要做的事。"));
                    return new ResolvedAction(contract, List.of());
                }

                @Override
                public ResolvedAction repairActionContract(StoryPromptContext context, StoryModelRoute route,
                                                           InvocationContext invocation,
                                                           StoryActionContract rejected, String problem) {
                    contractRepairs.incrementAndGet();
                    return new ResolvedAction(rejected, List.of());
                }

                @Override
                public Generated generate(StoryPromptContext context, StoryModelRoute route) {
                    generations.incrementAndGet();
                    throw new AssertionError("前提冲突时不应调用正文生成");
                }
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    contractRejectingAgent, testPlayerAi(jdbc));
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));

            GameApiException error = assertThrows(GameApiException.class, () -> engine.takeTurn(USER,
                    created.saveId(), new TurnRequest("取回之前交给老妇的断剑尖", "request-contract-conflict", 1)));

            assertEquals("ACTION_PRECONDITION_FAILED", error.code());
            assertEquals(0, generations.get());
            assertEquals(0, contractRepairs.get(), "已确认事实冲突不得再调用模型修复");
            assertFalse(error.retryable());
            assertFalse(error.turnConsumed());
            assertFalse(error.stateChanged());
            assertEquals(0, saves.findOwned(USER.userId(), created.saveId()).orElseThrow().turnNumber());
            assertEquals(0, jdbc.queryForObject("select count(*) from story_turn where save_id=?",
                    Integer.class, created.saveId()));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void explicitRenameCommitsCurrentNameAndHistoricalAliasTogether() {
        EntityPatch createWolf = new EntityPatch(EntityOperation.CREATE, null, "CHARACTER", "小冰霜狼",
                "与你同行", "在雪地相遇后选择与你同行。", null, null, null,
                null, null, new LifecycleValue(LifecycleState.ACTIVE, "本轮相遇并确认仍然存活"),
                null, null, "");
        EntityPatch renameWolf = new EntityPatch(EntityOperation.UPDATE, "character-trusted-test",
                "CHARACTER", "霜刃", "与你同行", "你亲自为它取名霜刃。", null,
                null, null, null, null, null, null,
                new NameChange(true, "玩家明确为灵宠取名"), "");
        TrustedRuntime runtime = trustedRuntime(
                turnPatch("雪中相逢", "你在雪地中遇见一只小冰霜狼，它选择与你同行。", 30,
                        List.of(createWolf), List.of(), new DecisionCheckpoint(false, null)),
                turnPatch("赐名霜刃", "你为小冰霜狼取名霜刃，它仍是此前与你同行的那一只。", 10,
                        List.of(renameWolf), List.of(), new DecisionCheckpoint(false, null)));
        try {
            advance(runtime, "观察这只小冰霜狼。", "request-create-wolf");
            advance(runtime, "以后就叫你霜刃。", "request-rename-wolf");

            StoryProjection projection = loadProjection(runtime);
            assertEquals("霜刃", projection.entities().get("character-trusted-test").name());
            var aliases = runtime.entities().listActiveAliases(runtime.saveId());
            assertEquals(1, aliases.size());
            assertEquals("character-trusted-test", aliases.getFirst().entityId());
            assertEquals("小冰霜狼", aliases.getFirst().aliasName());
            assertEquals("小冰霜狼", aliases.getFirst().normalizedAlias());
        } finally {
            runtime.database().shutdown();
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

    @Test
    void trustedTurnUsesCreditReservationAndFailureReturnsIt() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            StoryPatch accepted = turnPatch("行至溪边", "你沿着山路走到溪边，在水声前停下。", 30,
                    List.of(), List.of(), new DecisionCheckpoint(false, null));
            StoryTurnAgent successful = (context, route) -> new StoryTurnAgent.Generated(accepted, List.of(
                    new com.wenxian.game.story.agent.StoryModelGateway.Result(
                            "{}", "credit-success", "test-model", 80, 140, 20)));
            PlayerAiService playerAi = testPlayerAi(jdbc);
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    successful, playerAi, true);
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));

            engine.takeTurn(USER, created.saveId(), new TurnRequest("走到溪边", "request-credit-ok", 1));

            assertEquals(19, jdbc.queryForObject("""
                    select coalesce(sum(available_count),0) from turn_credit_grant where user_id=?
                    """, Integer.class, USER.userId()));
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from turn_credit_reservation where user_id=? and status='CONSUMED'
                    """, Integer.class, USER.userId()));
            assertEquals(0, jdbc.queryForObject("""
                    select coalesce(sum(used_count),0) from user_daily_quota where user_id=?
                    """, Integer.class, USER.userId()), "新账本启用后不得再写旧额度计数");

            StoryTurnAgent failing = (context, route) -> {
                throw new IllegalStateException("模拟模型中断");
            };
            PersistedLightNarrativeEngine failingEngine = trustedEngine(database, saves, entities, memories,
                    failing, playerAi, true);
            assertThrows(RuntimeException.class, () -> failingEngine.takeTurn(USER, created.saveId(),
                    new TurnRequest("继续前行", "request-credit-fail", 2)));

            assertEquals(19, jdbc.queryForObject("""
                    select coalesce(sum(available_count),0) from turn_credit_grant where user_id=?
                    """, Integer.class, USER.userId()));
            assertEquals(1, jdbc.queryForObject("""
                    select count(*) from turn_credit_reservation where user_id=? and status='RELEASED'
                    """, Integer.class, USER.userId()));
            assertEquals(1, jdbc.queryForObject("select count(*) from story_turn where save_id=?",
                    Integer.class, created.saveId()));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void outcomeFirstTurnKeepsValidatedFactsFixedWhileRepairingOnlyNarrative() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            AtomicInteger reviews = new AtomicInteger();
            StoryTurnAgent agent = new StoryTurnAgent() {
                @Override
                public Generated generate(StoryPromptContext context, StoryModelRoute route) {
                    throw new AssertionError("结果优先 Agent 不应调用旧的正文先行入口");
                }

                @Override
                public boolean supportsOutcomeFirst() {
                    return true;
                }

                @Override
                public PlannedOutcome planOutcome(StoryPromptContext context, StoryModelRoute route,
                                                   InvocationContext invocation) {
                    StoryPatch plan = new StoryPatch("翌日启程", "你歇息一夜并在翌日抵达山溪边。",
                            new TimePassage(1_440, TimeResolution.COMPLETE, null),
                            new StoryPatch.CoreStatePatch("山溪边", null, null, null),
                            List.of(), List.of(), List.of(), List.of(),
                            new DecisionCheckpoint(false, null), List.of(), "翌日已抵达山溪边。",
                            new TurnOutcome(OutcomeResolution.SUCCEEDED, "歇息后按计划抵达山溪边"));
                    return new PlannedOutcome(plan, List.of(modelResult("outcome-plan")));
                }

                @Override
                public RenderedNarrative renderNarrative(StoryPromptContext context, StoryModelRoute route,
                                                          InvocationContext invocation, StoryPatch acceptedPlan) {
                    return new RenderedNarrative("溪边奇遇",
                            "你在翌日抵达山溪边，并额外捡到一枚计划中没有的筑基丹。",
                            List.of(new StoryPatch.ChoiceDraft("沿溪而行", "观察上游。")),
                            "你抵达山溪边并得到筑基丹。", List.of(modelResult("bad-narrative")));
                }

                @Override
                public Review reviewOutcomeCoverage(StoryPromptContext context, StoryModelRoute route,
                                                     InvocationContext invocation, StoryPatch acceptedPlan,
                                                     RenderedNarrative narrative) {
                    if (reviews.getAndIncrement() == 0) {
                        return new Review(ReviewOutcome.REPAIR_DECISION,
                                "正文新增了结果计划外的筑基丹", List.of(modelResult("review-rejected")));
                    }
                    return new Review(ReviewOutcome.ACCEPT, "", List.of(modelResult("review-accepted")));
                }

                @Override
                public RenderedNarrative repairNarrative(StoryPromptContext context, StoryModelRoute route,
                                                         InvocationContext invocation, StoryPatch acceptedPlan,
                                                         RenderedNarrative rejected, String problem) {
                    return new RenderedNarrative("翌日溪边",
                            "你歇息一夜，翌日沿山路抵达山溪边，在水声前停下。",
                            List.of(new StoryPatch.ChoiceDraft("沿溪而行", "观察上游。")),
                            "你在翌日抵达山溪边。", List.of(modelResult("repaired-narrative")));
                }
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    agent, testPlayerAi(jdbc));
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));

            engine.takeTurn(USER, created.saveId(),
                    new TurnRequest("今夜歇息，明日再出发", "request-outcome-first", 1));

            var saved = saves.findOwned(USER.userId(), created.saveId()).orElseThrow();
            StoryProjection projection = new StoryProjectionPersistenceMapper(mapper).load(saved,
                    entities.listAll(created.saveId()), memories.recentActive(created.saveId(), 20));
            assertEquals(1, saved.turnNumber());
            assertEquals(1_440L, projection.worldTimeMinutes());
            assertEquals("山溪边", projection.coreState().location());
            assertEquals(2, reviews.get());
            String storedNarrative = jdbc.queryForObject(
                    "select narrative_text from story_turn where save_id=?", String.class, created.saveId());
            assertTrue(storedNarrative.contains("抵达山溪边"));
            assertFalse(storedNarrative.contains("筑基丹"));
            assertTrue(entities.listAll(created.saveId()).stream()
                    .noneMatch(value -> "筑基丹".equals(value.name())));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void outcomeCoverageCanRepairAMissingPlanBeforeRerendering() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            AtomicInteger renders = new AtomicInteger();
            AtomicInteger reviews = new AtomicInteger();
            AtomicInteger planRepairs = new AtomicInteger();
            StoryTurnAgent agent = new StoryTurnAgent() {
                @Override
                public Generated generate(StoryPromptContext context, StoryModelRoute route) {
                    throw new AssertionError("结果优先 Agent 不应调用旧入口");
                }

                @Override
                public boolean supportsOutcomeFirst() {
                    return true;
                }

                @Override
                public PlannedOutcome planOutcome(StoryPromptContext context, StoryModelRoute route,
                                                   InvocationContext invocation) {
                    return new PlannedOutcome(plan(null), List.of(modelResult("missing-location-plan")));
                }

                @Override
                public PlannedOutcome repairOutcomePlan(StoryPromptContext context, StoryModelRoute route,
                                                        InvocationContext invocation, StoryPatch rejected,
                                                        String problem) {
                    planRepairs.incrementAndGet();
                    return new PlannedOutcome(plan("青山门前"), List.of(modelResult("location-plan-repair")));
                }

                @Override
                public RenderedNarrative renderNarrative(StoryPromptContext context, StoryModelRoute route,
                                                         InvocationContext invocation, StoryPatch acceptedPlan) {
                    renders.incrementAndGet();
                    String location = acceptedPlan.coreState().location();
                    return new RenderedNarrative("山门前", location == null
                            ? "你沿着山道向前。" : "你沿着山道抵达青山门前，在石阶下停住。",
                            List.of(new StoryPatch.ChoiceDraft("观察山门", "查看周围动静。")),
                            location == null ? "你正在山道上。" : "你已抵达青山门前。",
                            List.of(modelResult("render-" + renders.get())));
                }

                @Override
                public Review reviewOutcomeCoverage(StoryPromptContext context, StoryModelRoute route,
                                                     InvocationContext invocation, StoryPatch acceptedPlan,
                                                     RenderedNarrative narrative) {
                    if (reviews.getAndIncrement() == 0) {
                        return new Review(ReviewOutcome.REPAIR_FACTS, "结果计划漏掉了最终地点",
                                List.of(modelResult("plan-gap-review")));
                    }
                    return new Review(ReviewOutcome.ACCEPT, "", List.of(modelResult("plan-fixed-review")));
                }

                private StoryPatch plan(String location) {
                    return new StoryPatch("前往山门", "你执行了前往山门的行动。",
                            new TimePassage(60, TimeResolution.COMPLETE, null),
                            new StoryPatch.CoreStatePatch(location, null, null, null),
                            List.of(), List.of(), List.of(), List.of(), new DecisionCheckpoint(false, null),
                            List.of(), location == null ? "你正在前往山门。" : "你已到青山门前。",
                            new TurnOutcome(OutcomeResolution.SUCCEEDED, "已执行前往青山门的行动"));
                }
            };
            PersistedLightNarrativeEngine engine = trustedEngine(database, saves, entities, memories,
                    agent, testPlayerAi(jdbc));
            var created = engine.create(USER, new CreateSaveRequest("XUANXU", null, "楚星河", "人族", "男",
                    "宗门弟子", "你在山门晨钟中醒来。", "自由探索"));

            engine.takeTurn(USER, created.saveId(),
                    new TurnRequest("前往青山门前，到达后停下", "request-plan-coverage-repair", 1));

            var saved = saves.findOwned(USER.userId(), created.saveId()).orElseThrow();
            StoryProjection projection = new StoryProjectionPersistenceMapper(mapper).load(saved,
                    entities.listAll(created.saveId()), memories.recentActive(created.saveId(), 20));
            assertEquals("青山门前", projection.coreState().location());
            assertEquals(1, planRepairs.get());
            assertEquals(2, renders.get());
            assertEquals(2, reviews.get());
        } finally {
            database.shutdown();
        }
    }

    private com.wenxian.game.story.agent.StoryModelGateway.Result modelResult(String id) {
        return new com.wenxian.game.story.agent.StoryModelGateway.Result(
                "{}", id, "test-model", 10, 20, 5);
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
        return trustedEngine(database, saves, entities, memories, trustedAgent, playerAi, false);
    }

    private PersistedLightNarrativeEngine trustedEngine(EmbeddedDatabase database, StorySaveRepository saves,
                                                         StoryEntityRepository entities,
                                                         StoryMemoryRepository memories,
                                                         StoryTurnAgent trustedAgent,
                                                         PlayerAiService playerAi,
                                                         boolean creditEnabled) {
        JdbcTemplate jdbc = new JdbcTemplate(database);
        StoryTurnRepository turns = new StoryTurnRepository(jdbc, mapper);
        StoryConversationRepository conversations = new StoryConversationRepository(jdbc);
        StoryProjectionPersistenceMapper projections = new StoryProjectionPersistenceMapper(mapper);
        StoryStateReducer trustedReducer = new StoryStateReducer(new StoryPatchValidator(),
                prefix -> prefix + "-trusted-test");
        var diagnostics = new com.wenxian.game.story.diagnostic.StoryTurnDiagnosticService(
                new com.wenxian.game.story.diagnostic.StoryTurnDiagnosticRepository(jdbc, mapper), mapper, true);
        StoryTurnPreparationService preparation = new StoryTurnPreparationService(entities, memories,
                conversations, turns, projections, new PromptContextAssembler(), trustedAgent, trustedReducer,
                diagnostics);
        GameVersionService releases = new GameVersionService(jdbc);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        var creditService = new com.wenxian.game.credit.TurnCreditService(
                new com.wenxian.game.credit.TurnCreditRepository(jdbc), transactions);
        var turnAccess = new com.wenxian.game.credit.PlatformTurnAccessService(
                playerAi, creditService, creditEnabled);
        StoryTurnCommitService commit = new StoryTurnCommitService(saves, turns, entities, memories,
                conversations, projections, releases, turnAccess, transactions, mapper);
        return new PersistedLightNarrativeEngine(saves, turns, entities, memories, conversations,
                new StoryCorrectionRepository(jdbc, mapper), preparation, commit, null,
                new V014StateReducer(), new StubModels(), new V014WorldCatalog(false), null,
                new ActiveSavePolicyService(jdbc), turnAccess, releases, transactions, mapper,
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
                ActiveSavePolicyService.CONFIG_KEY, "4", now);
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
