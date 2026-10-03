package com.wenxian.game.story.application;

import com.wenxian.game.story.agent.ai.NarrativeEndState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.credit.PlatformTurnAccessService;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.StoryTurnDecision;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator;
import com.wenxian.game.story.agent.ai.DirectorProtocolException;
import com.wenxian.game.story.diagnostic.StoryTurnDiagnosticRepository;
import com.wenxian.game.story.diagnostic.StoryTurnDiagnosticService;
import com.wenxian.game.story.domain.SemanticDuration;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryPatchValidator;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import com.wenxian.game.story.reducer.StoryStateReducer;
import com.wenxian.game.story.worldpackage.domain.WorldContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证历史两阶段对照回合只经过类型化导演、确定性预演和正文演绎。 */
class LegacyDirectedTurnPreparationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void preparesAcceptedTurnWithoutLegacyAgentOrReview() {
        EmbeddedDatabase database = database();
        try {
            AtomicReference<DirectedNarrativeGenerator.InvocationContext> observed = new AtomicReference<>();
            DirectedNarrativeGenerator generator = generator(observed);
            LegacyDirectedTurnPreparationService service = service(database, generator);
            StoredSave saved = save();
            var session = service.openSession(saved, "request-typed");
            PreparedStoryTurn prepared = service.prepare(saved, "request-typed",
                    "向药农领取两枚清心丹后停下", null, session);

            assertEquals("user-typed", observed.get().userId());
            assertEquals("request-typed", observed.get().requestId());
            assertEquals("save-typed", observed.get().saveId());
            assertEquals(1, observed.get().turnNumber());
            assertEquals(2, prepared.generation().modelCalls().size());
            assertEquals(300, prepared.generation().inputTokens());
            assertEquals(120, prepared.generation().outputTokens());
            assertEquals("药农将青瓷瓶推到你面前。你查验封口后收下两枚清心丹，随后停在摊前。",
                    prepared.applied().acceptedPatch().narrative());
            StoryPatch.EntityPatch item = prepared.applied().acceptedPatch().entities().getFirst();
            assertTrue(item.entityId().startsWith("item-typed-test"));
            assertEquals(StoryPatch.HolderType.PLAYER, item.custody().holderType());
            assertEquals(0, new JdbcTemplate(database)
                    .queryForObject("select count(*) from story_turn", Integer.class));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void repairsUncompilableDirectorIntentAtMostOnceBeforeRendering() throws Exception {
        EmbeddedDatabase database = database();
        try {
            AtomicInteger repairs = new AtomicInteger();
            List<StoryPromptContext> observedContexts = new ArrayList<>();
            var beforeDatabase = authoritySnapshot(database);
            DirectedNarrativeGenerator generator = new DirectedNarrativeGenerator() {
                @Override
                public Session open(InvocationContext invocation) {
                    return new Session() {
                        @Override
                        public CredentialSource credentialSource() {
                            return CredentialSource.PLATFORM;
                        }

                        @Override
                        public DirectedPlan plan(StoryPromptContext context) {
                            observedContexts.add(context);
                            return new DirectedPlan(invalidDecision(), List.of(call("director")));
                        }

                        @Override
                        public DirectedPlan repair(StoryPromptContext context, StoryTurnDecision rejected,
                                                   String problem) {
                            observedContexts.add(context);
                            repairs.incrementAndGet();
                            return new DirectedPlan(decision(List.of()), List.of(call("director-repair")));
                        }

                        @Override
                        public RenderedNarrative render(StoryPromptContext context, StoryPatch acceptedPlan,
                                                        NarrativeEndState endState) {
                            observedContexts.add(context);
                            return narrative("你没有找到可用之物，只确认石门附近暂时安全。", "writer");
                        }
                    };
                }
            };

            JdbcTemplate jdbc = new JdbcTemplate(database);
            LegacyDirectedTurnPreparationService service = service(database, generator,
                    com.wenxian.game.story.worldpackage.WorldPackageRegistryTestSupport.worldContexts(mapper),
                    new StoryTurnDiagnosticService(new StoryTurnDiagnosticRepository(jdbc, mapper), mapper, true));
            StoredSave saved = save();
            var session = service.openSession(saved, "request-repair");
            PreparedStoryTurn prepared = service.prepare(saved, "request-repair",
                    "查找石门附近可用之物", null, session);

            assertEquals(1, repairs.get());
            assertEquals(3, prepared.generation().modelCalls().size());
            assertTrue(prepared.applied().acceptedPatch().entities().isEmpty());
            assertEquals(3, observedContexts.size());
            WorldContext selectedWorld = observedContexts.getFirst().worldContext();
            assertNotNull(selectedWorld);
            assertSame(selectedWorld, observedContexts.get(1).worldContext());
            assertSame(selectedWorld, observedContexts.get(2).worldContext());
            assertEquals("wenxian.official", selectedWorld.source().publisherNamespace());
            assertEquals("XUANXU", selectedWorld.source().packageId());
            assertEquals(2, selectedWorld.source().revision());
            assertTrue(selectedWorld.materials().stream().anyMatch(material -> "LORE".equals(material.kind())),
                    "该人族样本应确实读取到世界 Lore，才能验证资料不自动写成事实");
            assertTrue(selectedWorld.unavailableInputs().contains("CURRENT_REGION_UNVERIFIED_V2"));
            assertFalse(selectedWorld.materials().stream().anyMatch(material -> "REGION".equals(material.kind())),
                    "v2 的当前地域未被证实，不能将开场青岐当作当前位置读取");
            assertFalse(selectedWorld.materials().stream().flatMap(material -> material.reasons().stream())
                    .anyMatch(reason -> reason.equals("LOCATION_REF:region:xuanxu:qingqi")));
            for (StoryPromptContext context : observedContexts) {
                assertTrue(context.system().engineRules().isEmpty(), "世界包文本不能提升为平台规则");
                assertTrue(context.objective().worldFacts().isEmpty());
                assertTrue(context.cognition().entries().isEmpty());
            }
            assertEquals(prepared.before().worldFacts(), prepared.applied().projection().worldFacts());
            assertEquals(prepared.before().knowledge(), prepared.applied().projection().knowledge());
            assertEquals(beforeDatabase, authoritySnapshot(database), "回合准备和资料读取不能提前落库");

            JsonNode manifest = mapper.readTree(jdbc.queryForObject("""
                    select context_manifest_json from story_turn_diagnostic
                    where save_id=? and request_id=?
                    """, String.class, saved.id(), "request-repair"));
            JsonNode recordedWorld = manifest.path("worldContext");
            assertEquals(mapper.readTree(mapper.writeValueAsString(selectedWorld.source())), recordedWorld.path("source"),
                    "诊断须能追溯实际使用的修订和两个内容哈希");
            assertEquals(selectedWorld.selectorVersion(), recordedWorld.path("selectorVersion").asText());
            assertEquals(selectedWorld.candidateCount(), recordedWorld.path("candidateCount").asInt());
            assertEquals(selectedWorld.omittedCount(), recordedWorld.path("omittedCount").asInt());
            assertEquals(selectedWorld.usedCharacters(), recordedWorld.path("usedCharacters").asInt());
            assertEquals(selectedWorld.maximumCharacters(), recordedWorld.path("maximumCharacters").asInt());
            assertEquals(mapper.valueToTree(selectedWorld.unavailableInputs()), recordedWorld.path("unavailableInputs"));
            JsonNode references = recordedWorld.path("selectedMaterials");
            assertEquals(selectedWorld.materials().size(), references.size());
            for (int index = 0; index < selectedWorld.materials().size(); index++) {
                var material = selectedWorld.materials().get(index);
                JsonNode recorded = references.get(index);
                assertEquals(material.ref(), recorded.path("ref").asText());
                assertEquals(material.kind(), recorded.path("kind").asText());
                assertEquals(mapper.valueToTree(material.reasons()), recorded.path("reasons"));
                if ("PREMISE".equals(material.kind()) || "LORE".equals(material.kind())) {
                    String content = material.data().path("PREMISE".equals(material.kind()) ? "premise" : "content")
                            .asText();
                    assertFalse(content.isBlank(), "验证样本必须实际含有世界简介或 Lore 正文");
                    assertFalse(manifest.toString().contains(content), "诊断不能复制世界简介或 Lore 正文");
                }
            }
            assertTrue(manifest.findValues("data").isEmpty(), "诊断只保留来源清单，不保存 Material.data");
            assertTrue(manifest.findValues("premise").isEmpty());
            assertTrue(manifest.findValues("content").isEmpty());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void writerFailureAfterAcceptedDirectorLeavesEveryAuthoritativeTableUnchanged() {
        EmbeddedDatabase database = database();
        try {
            var before = authoritySnapshot(database);
            var session = mock(DirectedNarrativeGenerator.Session.class);
            when(session.plan(any())).thenReturn(new DirectedNarrativeGenerator.DirectedPlan(
                    decision(List.of(pills())), List.of(call("director"))));
            IllegalStateException failure = new IllegalStateException("injected writer failure");
            when(session.render(any(), any(), any())).thenThrow(failure);
            LegacyDirectedTurnPreparationService service = service(database, invocation -> session);

            assertSame(failure, assertThrows(IllegalStateException.class, () -> service.prepare(
                    save(), "request-writer-failure", "向药农领取两枚清心丹后停下", null, session)));

            verify(session).render(any(), any(), any());
            verify(session, never()).repair(any(), any(), anyString());
            assertEquals(before, authoritySnapshot(database),
                    "正文失败不能保存导演已接受的物品、时间、回合或摘要");
        } finally {
            database.shutdown();
        }
    }

    @Test
    void invalidRepairDoesNotRenderOrChangeAnyAuthoritativeTable() {
        EmbeddedDatabase database = database();
        try {
            var before = authoritySnapshot(database);
            var session = mock(DirectedNarrativeGenerator.Session.class);
            when(session.plan(any())).thenReturn(new DirectedNarrativeGenerator.DirectedPlan(
                    invalidDecision(), List.of(call("director"))));
            when(session.repair(any(), any(), anyString())).thenReturn(new DirectedNarrativeGenerator.DirectedPlan(
                    invalidDecision(), List.of(call("director-repair"))));
            LegacyDirectedTurnPreparationService service = service(database, invocation -> session);

            StoryTurnProtocolException failure = assertThrows(StoryTurnProtocolException.class,
                    () -> service.prepare(save(), "request-repair-failure", "查看石门附近可用之物", null, session));

            assertEquals("DIRECTED_FACT_REPAIR", failure.stage());
            verify(session, times(1)).repair(any(), any(), anyString());
            verify(session, never()).render(any(), any(), any());
            assertEquals(before, authoritySnapshot(database));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void repairsRejectedDraftInFrozenContextAndPreservesOriginalCallUsage() {
        EmbeddedDatabase database = database();
        try {
            var before = authoritySnapshot(database);
            var session = mock(DirectedNarrativeGenerator.Session.class);
            Object rawDraft = Map.of("entities", List.of(Map.of("entityType", "ITEM")));
            var rejected = new DirectorProtocolException(DirectorProtocolException.Phase.ADAPTATION,
                    rawDraft, List.of(call("director-rejected")), "物品必须填写在 itemChanges", null);
            List<StoryPromptContext> contexts = new ArrayList<>();
            when(session.plan(any())).thenAnswer(invocation -> {
                contexts.add(invocation.getArgument(0));
                throw rejected;
            });
            when(session.repairProtocol(any(), any())).thenAnswer(invocation -> {
                contexts.add(invocation.getArgument(0));
                assertSame(rejected, invocation.getArgument(1));
                assertSame(rawDraft, ((DirectorProtocolException) invocation.getArgument(1)).rejectedDraft());
                return new DirectedNarrativeGenerator.DirectedPlan(decision(List.of(pills())),
                        List.of(call("director-repair")));
            });
            when(session.render(any(), any(), any())).thenAnswer(invocation -> {
                contexts.add(invocation.getArgument(0));
                return narrative("你领取两枚清心丹后停下。", "writer");
            });
            PreparedStoryTurn prepared = service(database, invocation -> session).prepare(
                    save(), "request-draft-repair", "领取两枚清心丹后停下", null, session);

            assertEquals(List.of("director-rejected", "director-repair", "writer"),
                    prepared.generation().modelCalls().stream()
                            .map(DirectedNarrativeGenerator.ModelCall::modelCode).toList());
            assertEquals(450, prepared.generation().inputTokens());
            assertEquals(180, prepared.generation().outputTokens());
            assertEquals(3, contexts.size());
            assertSame(contexts.get(0), contexts.get(1));
            assertSame(contexts.get(0), contexts.get(2));
            verify(session, times(1)).repairProtocol(any(), any());
            verify(session, never()).repair(any(), any(), anyString());
            assertEquals(before, authoritySnapshot(database), "修复与正文阶段都不得提前写入权威事实");
        } finally {
            database.shutdown();
        }
    }

    @Test
    void repeatedDraftProtocolFailureStopsAndRetainsBothCallsWithoutRendering() {
        EmbeddedDatabase database = database();
        try {
            var before = authoritySnapshot(database);
            var session = mock(DirectedNarrativeGenerator.Session.class);
            var first = new DirectorProtocolException(DirectorProtocolException.Phase.ADAPTATION,
                    Map.of("entities", "wrong group"), List.of(call("director")), "变化分组错误", null);
            var second = new DirectorProtocolException(DirectorProtocolException.Phase.DECODING,
                    null, List.of(call("director-repair")), "导演响应无法解析为协议", null);
            when(session.plan(any())).thenThrow(first);
            when(session.repairProtocol(any(), any())).thenThrow(second);

            StoryTurnProtocolException failure = assertThrows(StoryTurnProtocolException.class,
                    () -> service(database, invocation -> session).prepare(
                            save(), "request-double-protocol-failure", "领取丹药", null, session));

            DirectorProtocolException detail = (DirectorProtocolException) failure.getCause();
            assertEquals(DirectorProtocolException.Phase.DECODING, detail.phase());
            assertEquals(List.of("director", "director-repair"), detail.modelCalls().stream()
                    .map(DirectedNarrativeGenerator.ModelCall::modelCode).toList());
            assertSame(first, detail.getSuppressed()[0]);
            verify(session, times(1)).plan(any());
            verify(session, times(1)).repairProtocol(any(), any());
            verify(session, never()).repair(any(), any(), anyString());
            verify(session, never()).render(any(), any(), any());
            assertEquals(before, authoritySnapshot(database));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void protocolRepairCannotStartAnotherRepairWhenItsDecisionFailsCompilation() {
        EmbeddedDatabase database = database();
        try {
            var before = authoritySnapshot(database);
            var session = mock(DirectedNarrativeGenerator.Session.class);
            when(session.plan(any())).thenThrow(new DirectorProtocolException(
                    DirectorProtocolException.Phase.ADAPTATION, Map.of(), List.of(call("director")),
                    "物品变化分组错误", null));
            when(session.repairProtocol(any(), any())).thenReturn(new DirectedNarrativeGenerator.DirectedPlan(
                    invalidDecision(), List.of(call("director-repair"))));

            StoryTurnProtocolException failure = assertThrows(StoryTurnProtocolException.class,
                    () -> service(database, invocation -> session).prepare(
                            save(), "request-shared-repair-budget", "领取丹药", null, session));

            DirectorProtocolException detail = (DirectorProtocolException) failure.getCause();
            assertEquals(DirectorProtocolException.Phase.COMPILATION, detail.phase());
            assertEquals(2, detail.modelCalls().size());
            verify(session, times(1)).repairProtocol(any(), any());
            verify(session, never()).repair(any(), any(), anyString());
            verify(session, never()).render(any(), any(), any());
            assertEquals(before, authoritySnapshot(database));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void compilationRepairCannotStartAnotherRepairWhenItsDraftFailsAdaptation() {
        EmbeddedDatabase database = database();
        try {
            var before = authoritySnapshot(database);
            var session = mock(DirectedNarrativeGenerator.Session.class);
            when(session.plan(any())).thenReturn(new DirectedNarrativeGenerator.DirectedPlan(
                    invalidDecision(), List.of(call("director"))));
            when(session.repair(any(), any(), anyString())).thenThrow(new DirectorProtocolException(
                    DirectorProtocolException.Phase.ADAPTATION, Map.of(), List.of(call("director-repair")),
                    "物品变化分组错误", null));

            StoryTurnProtocolException failure = assertThrows(StoryTurnProtocolException.class,
                    () -> service(database, invocation -> session).prepare(
                            save(), "request-compile-then-draft-failure", "领取丹药", null, session));

            DirectorProtocolException detail = (DirectorProtocolException) failure.getCause();
            assertEquals(DirectorProtocolException.Phase.ADAPTATION, detail.phase());
            assertEquals(2, detail.modelCalls().size());
            verify(session, times(1)).repair(any(), any(), anyString());
            verify(session, never()).repairProtocol(any(), any());
            verify(session, never()).render(any(), any(), any());
            assertEquals(before, authoritySnapshot(database));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void permissionTransportAndUnclassifiedProgramErrorsNeverTriggerProtocolRepair() {
        EmbeddedDatabase database = database();
        try {
            var before = authoritySnapshot(database);
            List<RuntimeException> failures = List.of(
                    new SecurityException("模型路由未授权"),
                    new IllegalStateException("供应商请求失败", new java.net.SocketTimeoutException()),
                    new IllegalArgumentException("非输出协议的调用参数错误"));
            for (RuntimeException failure : failures) {
                var session = mock(DirectedNarrativeGenerator.Session.class);
                when(session.plan(any())).thenThrow(failure);
                assertSame(failure, assertThrows(RuntimeException.class,
                        () -> service(database, invocation -> session).prepare(
                                save(), "request-non-protocol-failure", "观察四周", null, session)));
                verify(session, never()).repairProtocol(any(), any());
                verify(session, never()).repair(any(), any(), anyString());
                verify(session, never()).render(any(), any(), any());
                assertEquals(before, authoritySnapshot(database));
            }
        } finally {
            database.shutdown();
        }
    }

    @Test
    void worldReadFailureStopsBeforePlanningAndLeavesAuthoritativeStateUntouched() {
        EmbeddedDatabase database = database();
        try {
            var before = authoritySnapshot(database);
            var worldContexts = mock(WorldTurnContextService.class);
            IllegalStateException failure = new IllegalStateException("injected world snapshot failure");
            when(worldContexts.select(any(), anyString())).thenThrow(failure);
            var session = mock(DirectedNarrativeGenerator.Session.class);
            LegacyDirectedTurnPreparationService service = service(database, invocation -> session, worldContexts);

            assertSame(failure, assertThrows(IllegalStateException.class, () -> service.prepare(
                    save(), "request-world-failure", "观察石门后停下", null, session)));

            verify(worldContexts).select(any(), anyString());
            verifyNoInteractions(session);
            assertEquals(before, authoritySnapshot(database));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void commitsPreparedTurnAtomicallyWithAcceptedFactsAndRealModelUsage() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryTurnRepository turns = new StoryTurnRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            StoryConversationRepository conversations = new StoryConversationRepository(jdbc);
            StoryProjectionPersistenceMapper projections = new StoryProjectionPersistenceMapper(mapper);
            StoredSave stored = saves.findOwned("user-typed", "save-typed").orElseThrow();
            LegacyDirectedTurnPreparationService service = service(database, generator(new AtomicReference<>()));
            var session = service.openSession(stored, "request-commit");
            PreparedStoryTurn prepared = service.prepare(stored, "request-commit",
                    "向药农领取两枚清心丹后停下", null, session);
            StoryTurnCommitService commit = new StoryTurnCommitService(saves, turns, entities, memories,
                    conversations, projections, new GameVersionService(jdbc),
                    new PlatformTurnAccessService(null, null, false),
                    new TransactionTemplate(new DataSourceTransactionManager(database)), mapper);

            StoryTurnCommitService.Committed committed = commit.commit("user-typed", stored,
                    "request-commit", "向药农领取两枚清心丹后停下", prepared, null);

            assertEquals(2, committed.revision());
            assertEquals(1, committed.turnNumber());
            StoredSave updated = saves.findOwned("user-typed", "save-typed").orElseThrow();
            assertEquals(1, updated.turnNumber());
            assertEquals(2, updated.revision());
            var persistedTurn = turns.findByRequestId("save-typed", "request-commit").orElseThrow();
            assertEquals("typed-directed-turn-v8", persistedTurn.promptVersion());
            assertEquals("TEST", persistedTurn.provider());
            assertEquals("writer", persistedTurn.model());
            assertEquals(300, persistedTurn.promptTokens());
            assertEquals(120, persistedTurn.completionTokens());
            assertEquals(1, entities.listAll("save-typed").size());
            assertEquals("清心丹", entities.listAll("save-typed").getFirst().name());
            assertNotNull(persistedTurn.statePatch().path("trustedEvents"));
            assertTrue(conversations.summary("save-typed").contains("完成了本轮行动"));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void lateCommitFailureAndRevisionConflictDoNotPersistPreparedRewards() {
        for (boolean conflict : List.of(false, true)) {
            EmbeddedDatabase database = database();
            try {
                var jdbc = new JdbcTemplate(database);
                var saves = new StorySaveRepository(jdbc, mapper);
                var saved = saves.findOwned("user-typed", "save-typed").orElseThrow();
                var service = service(database, generator(new AtomicReference<>()));
                var prepared = service.prepare(saved, "rollback-reward", "领取清心丹", null,
                        service.openSession(saved, "rollback-reward"));
                if (conflict) jdbc.update("update story_save set revision=revision+1 where id=?", saved.id());
                else jdbc.execute("alter table story_turn add constraint injected_turn_failure check (turn_number < 0)");
                var before = authoritySnapshot(database);
                assertThrows(RuntimeException.class, () -> commitService(database).commit("user-typed", saved,
                        "rollback-reward", "领取清心丹", prepared, null));
                assertEquals(before, authoritySnapshot(database), "准备出的物品不能在事务失败后残留");
            } finally {
                database.shutdown();
            }
        }
    }

    @Test
    void earnedBookAndLearningSurviveReloadAndWriterUsesTheCommittedEndState() {
        EmbeddedDatabase database = database();
        try {
            var session = mock(DirectedNarrativeGenerator.Session.class);
            var saves = new StorySaveRepository(new JdbcTemplate(database), mapper);
            var entityRepo = new StoryEntityRepository(new JdbcTemplate(database), mapper);
            var service = service(database, invocation -> session);
            var writerStates = new ArrayList<NarrativeEndState>();
            var inputs = new ArrayList<StoryPromptContext>();
            var step = new java.util.concurrent.atomic.AtomicInteger();
            var abilityId = new AtomicReference<String>();
            when(session.plan(any())).thenAnswer(invocation -> {
                StoryPromptContext context = invocation.getArgument(0);
                inputs.add(context);
                int turn = step.incrementAndGet();
                StoryTurnDecision.EntityIntent change = turn == 1
                        ? new StoryTurnDecision.EntityIntent("", "book", "ITEM", "吐纳小册", "已收下", "劳动报酬，记录基础吐纳法", null,
                            new StoryPatch.QuantityValue(true, 1),
                            new StoryTurnDecision.CustodyIntent(StoryPatch.HolderType.PLAYER, "", ""), null, null, null, true)
                        : new StoryTurnDecision.EntityIntent(turn == 2 ? "" : abilityId.get(), turn == 2 ? "breathing" : "",
                            "ABILITY", turn == 2 ? "基础吐纳法" : "", "练习中", "依书册学习呼吸节律", null, null, null,
                            turn == 2 ? StoryPatch.AbilityState.LEARNING : StoryPatch.AbilityState.MASTERED, null, null, true);
                var draft = decision(List.of(change));
                if (turn > 1) draft = new StoryTurnDecision(draft.outcome(), draft.duration(), draft.timeResolution(),
                        draft.interruptionReason(), draft.coreState(),
                        new StoryTurnDecision.CultivationIntent(true, false, "本轮练习形成进展", List.of("PLAYER_ACTION")),
                        draft.entities(), draft.worldFacts(), draft.memories(), draft.decisionCheckpoint(), draft.sceneBeats());
                return new DirectedNarrativeGenerator.DirectedPlan(draft, List.of(call("director")));
            });
            when(session.render(any(), any(), any())).thenAnswer(invocation -> {
                StoryPatch accepted = invocation.getArgument(1);
                NarrativeEndState end = invocation.getArgument(2);
                writerStates.add(end);
                for (var change : accepted.entities()) {
                    assertTrue(end.changedEntities().stream().anyMatch(e -> e.id().equals(change.entityId())));
                }
                if (!end.abilities().isEmpty()) abilityId.set(end.abilities().getFirst().id());
                return narrative("你完成了本轮修习。", "writer");
            });
            for (int turn = 1; turn <= 3; turn++) {
                StoredSave saved = saves.findOwned("user-typed", "save-typed").orElseThrow();
                String request = "cultivation-" + turn;
                var prepared = service.prepare(saved, request, "执行本轮修仙行动", null, session);
                commitService(database).commit("user-typed", saved, request, "执行本轮修仙行动", prepared, null);
                var persisted = entityRepo.listAll("save-typed");
                for (var shown : writerStates.getLast().changedEntities()) {
                    assertTrue(persisted.stream().anyMatch(e -> e.id().equals(shown.id())), "正文与落库必须使用同一 ID");
                }
            }
            assertEquals(2, entityRepo.listAll("save-typed").size());
            assertEquals("吐纳小册", inputs.get(1).objective().currentInventory().items().getFirst().name());
            assertTrue(inputs.get(2).objective().relatedEntities().stream()
                    .anyMatch(e -> e.id().equals(abilityId.get()) && e.abilityState() == StoryPatch.AbilityState.LEARNING));
            assertEquals(StoryPatch.AbilityState.MASTERED, writerStates.getLast().abilities().getFirst().abilityState());
            assertEquals("MIDDLE", writerStates.getLast().cultivation().phase().name());
            var saved = saves.findOwned("user-typed", "save-typed").orElseThrow();
            var reloaded = new StoryProjectionPersistenceMapper(mapper).load(saved, entityRepo.listAll("save-typed"), List.of());
            assertEquals(reloaded.coreState(), writerStates.getLast().protagonist());
            assertEquals(reloaded.protagonistCultivation(), writerStates.getLast().cultivation());
        } finally {
            database.shutdown();
        }
    }

    @Test
    void reloadsTheSameCharacterAndItemAcrossCommittedTurns() {
        EmbeddedDatabase database = database();
        try {
            List<StoryPromptContext> observedContexts = new ArrayList<>();
            AtomicReference<String> characterId = new AtomicReference<>();
            AtomicReference<String> itemId = new AtomicReference<>();
            LegacyDirectedTurnPreparationService preparation = service(database,
                    continuityGenerator(observedContexts, characterId, itemId));
            StorySaveRepository saves = new StorySaveRepository(new JdbcTemplate(database), mapper);
            StoryTurnCommitService commit = commitService(database);
            StoredSave current = saves.findOwned("user-typed", "save-typed").orElseThrow();

            String[] actions = {
                    "从药农陆七处领取两枚清心丹",
                    "服用一枚清心丹，并请陆七说明药性",
                    "将剩下的一枚清心丹交给陆七保管"
            };
            for (int index = 0; index < actions.length; index++) {
                String requestId = "request-continuity-" + (index + 1);
                var session = preparation.openSession(current, requestId);
                PreparedStoryTurn prepared = preparation.prepare(current, requestId, actions[index], null, session);
                commit.commit("user-typed", current, requestId, actions[index], prepared, null);
                current = saves.findOwned("user-typed", "save-typed").orElseThrow();
            }

            var entities = new StoryEntityRepository(new JdbcTemplate(database), mapper)
                    .listAll("save-typed");
            assertEquals(2, entities.size(), "跨回合更新不得重复创建同名人物或物品");
            assertEquals(3, current.turnNumber());

            var finalSession = preparation.openSession(current, "request-continuity-4");
            preparation.prepare(current, "request-continuity-4",
                    "询问陆七保管的清心丹是否安好", null, finalSession);

            StoryPromptContext finalContext = observedContexts.getLast();
            assertTrue(finalContext.objective().currentInventory().items().isEmpty(),
                    "交给人物保管的物品不能继续出现在当前行囊");
            var historicalItem = finalContext.objective().currentInventory().historicalItems().stream()
                    .filter(value -> itemId.get().equals(value.id())).findFirst().orElseThrow();
            assertEquals(StoryPatch.HolderType.CHARACTER, historicalItem.custody().holderType());
            assertEquals(characterId.get(), historicalItem.custody().holderRef());
            var rememberedCharacter = finalContext.objective().relatedEntities().stream()
                    .filter(value -> characterId.get().equals(value.id())).findFirst().orElseThrow();
            assertEquals("互相信任", rememberedCharacter.relationship().label());
        } finally {
            database.shutdown();
        }
    }

    private DirectedNarrativeGenerator generator(
            AtomicReference<DirectedNarrativeGenerator.InvocationContext> observed) {
        return new DirectedNarrativeGenerator() {
            @Override
            public Session open(InvocationContext invocation) {
                observed.set(invocation);
                return new Session() {
                    @Override
                    public CredentialSource credentialSource() {
                        return CredentialSource.PLATFORM;
                    }

                    @Override
                    public DirectedPlan plan(StoryPromptContext context) {
                        return new DirectedPlan(decision(List.of(pills())), List.of(call("director")));
                    }

                    @Override
                    public DirectedPlan repair(StoryPromptContext context, StoryTurnDecision rejected,
                                               String problem) {
                        throw new AssertionError("本场景不应整理导演结果");
                    }

                    @Override
                    public RenderedNarrative render(StoryPromptContext context, StoryPatch acceptedPlan,
                                                    NarrativeEndState endState) {
                        assertEquals("清心丹", acceptedPlan.entities().getFirst().name());
                        assertEquals(acceptedPlan.entities().getFirst().entityId(), endState.inventory().getFirst().id());
                        return narrative("药农将青瓷瓶推到你面前。你查验封口后收下两枚清心丹，随后停在摊前。", "writer");
                    }
                };
            }
        };
    }

    private DirectedNarrativeGenerator continuityGenerator(
            List<StoryPromptContext> observedContexts,
            AtomicReference<String> characterId,
            AtomicReference<String> itemId) {
        return invocation -> new DirectedNarrativeGenerator.Session() {
            @Override
            public CredentialSource credentialSource() {
                return CredentialSource.PLATFORM;
            }

            @Override
            public DirectedNarrativeGenerator.DirectedPlan plan(StoryPromptContext context) {
                observedContexts.add(context);
                List<StoryTurnDecision.EntityIntent> changes = switch (invocation.turnNumber()) {
                    case 1 -> List.of(
                            new StoryTurnDecision.EntityIntent("", "药农", "CHARACTER", "陆七",
                                    "仍在石门前", "熟悉草药的行脚药农", null, null, null, null,
                                    new StoryPatch.LifecycleValue(StoryPatch.LifecycleState.ACTIVE, null),
                                    new StoryPatch.RelationshipValue("初识", "本轮相识"), true),
                            new StoryTurnDecision.EntityIntent("", "丹药", "ITEM", "清心丹",
                                    "随身携带", "陆七交给你的清心丹", null,
                                    new StoryPatch.QuantityValue(true, 2),
                                    new StoryTurnDecision.CustodyIntent(StoryPatch.HolderType.PLAYER, "", ""),
                                    null, null, null, true));
                    case 2 -> {
                        String existingCharacter = context.objective().relatedEntities().stream()
                                .filter(value -> "陆七".equals(value.name())).map(StoryPromptContext.EntityDetail::id)
                                .findFirst().orElseThrow();
                        String existingItem = context.objective().currentInventory().items().stream()
                                .filter(value -> "清心丹".equals(value.name()))
                                .map(StoryPromptContext.EntityIndexEntry::id).findFirst().orElseThrow();
                        characterId.set(existingCharacter);
                        itemId.set(existingItem);
                        yield List.of(
                                new StoryTurnDecision.EntityIntent(existingItem, "", "ITEM", "",
                                        "剩余一枚", "你服用一枚后还剩一枚", null,
                                        new StoryPatch.QuantityValue(true, 1),
                                        new StoryTurnDecision.CustodyIntent(
                                                StoryPatch.HolderType.PLAYER, "", ""),
                                        null, null, null, true),
                                new StoryTurnDecision.EntityIntent(existingCharacter, "", "CHARACTER", "",
                                        "仍在石门前", "陆七向你说明了清心丹的药性", null,
                                        null, null, null, null,
                                        new StoryPatch.RelationshipValue("互相信任", "陆七坦诚说明药性"), true));
                    }
                    case 3 -> {
                        assertTrue(context.objective().relatedEntities().stream()
                                .anyMatch(value -> characterId.get().equals(value.id())));
                        assertTrue(context.objective().currentInventory().items().stream()
                                .anyMatch(value -> itemId.get().equals(value.id())
                                        && value.quantity().known() && value.quantity().value() == 1));
                        yield List.of(new StoryTurnDecision.EntityIntent(itemId.get(), "", "ITEM", "",
                                "由陆七保管", "剩余的清心丹已经交给陆七", null,
                                new StoryPatch.QuantityValue(true, 1),
                                new StoryTurnDecision.CustodyIntent(
                                        StoryPatch.HolderType.CHARACTER, characterId.get(), ""),
                                null, null, null, true));
                    }
                    case 4 -> {
                        assertFalse(context.objective().currentInventory().items().stream()
                                .anyMatch(value -> itemId.get().equals(value.id())));
                        assertTrue(context.objective().currentInventory().historicalItems().stream()
                                .anyMatch(value -> itemId.get().equals(value.id())));
                        assertTrue(context.objective().relatedEntities().stream()
                                .anyMatch(value -> characterId.get().equals(value.id())));
                        yield List.of();
                    }
                    default -> throw new AssertionError("意外的回合编号：" + invocation.turnNumber());
                };
                return new DirectedNarrativeGenerator.DirectedPlan(decision(changes),
                        List.of(call("director-" + invocation.turnNumber())));
            }

            @Override
            public DirectedNarrativeGenerator.DirectedPlan repair(StoryPromptContext context,
                                                                   StoryTurnDecision rejected,
                                                                   String problem) {
                throw new AssertionError("多回合一致性样本不应触发结构修复：" + problem);
            }

            @Override
            public DirectedNarrativeGenerator.RenderedNarrative render(
                    StoryPromptContext context, StoryPatch acceptedPlan, NarrativeEndState endState) {
                return narrative("你完成了这一轮与陆七及清心丹有关的行动。",
                        "writer-" + invocation.turnNumber());
            }
        };
    }

    private StoryTurnCommitService commitService(EmbeddedDatabase database) {
        JdbcTemplate jdbc = new JdbcTemplate(database);
        return new StoryTurnCommitService(new StorySaveRepository(jdbc, mapper),
                new StoryTurnRepository(jdbc, mapper), new StoryEntityRepository(jdbc, mapper),
                new StoryMemoryRepository(jdbc), new StoryConversationRepository(jdbc),
                new StoryProjectionPersistenceMapper(mapper), new GameVersionService(jdbc),
                new PlatformTurnAccessService(null, null, false),
                new TransactionTemplate(new DataSourceTransactionManager(database)), mapper);
    }

    private StoryTurnDecision decision(List<StoryTurnDecision.EntityIntent> entities) {
        return new StoryTurnDecision(
                new StoryPatch.TurnOutcome(StoryPatch.OutcomeResolution.SUCCEEDED, "已执行玩家当前行动"),
                new SemanticDuration(SemanticDuration.Unit.PHASE, 1, ""),
                StoryPatch.TimeResolution.COMPLETE, "", null, entities, List.of(), List.of(),
                new StoryPatch.DecisionCheckpoint(false, null), List.of("执行当前行动", "在结果处停下"));
    }

    private StoryTurnDecision invalidDecision() {
        return decision(List.of(new StoryTurnDecision.EntityIntent(
                "item-does-not-exist", "", "ITEM", "", "", "", null,
                null, null, null, null, null, true)));
    }

    private StoryTurnDecision.EntityIntent pills() {
        return new StoryTurnDecision.EntityIntent("", "pills", "ITEM", "清心丹", "刚刚领取", "装在青瓷瓶中", null,
                new StoryPatch.QuantityValue(true, 2),
                new StoryTurnDecision.CustodyIntent(StoryPatch.HolderType.PLAYER, "", ""),
                null, null, null, true);
    }

    private DirectedNarrativeGenerator.RenderedNarrative narrative(String text, String model) {
        return new DirectedNarrativeGenerator.RenderedNarrative("石门小憩", text,
                List.of(new StoryPatch.ChoiceDraft("继续观察", "查看附近是否还有异样。")),
                "你在石门前完成了本轮行动。", List.of(call(model)));
    }

    private DirectedNarrativeGenerator.ModelCall call(String model) {
        return new DirectedNarrativeGenerator.ModelCall("TEST", model, "PLATFORM", false,
                "SUCCESS", 25, 150, 60, 100, 50, "");
    }

    private LegacyDirectedTurnPreparationService service(EmbeddedDatabase database,
                                                        DirectedNarrativeGenerator generator) {
        return service(database, generator,
                com.wenxian.game.story.worldpackage.WorldPackageRegistryTestSupport.worldContexts(mapper));
    }

    private LegacyDirectedTurnPreparationService service(EmbeddedDatabase database,
                                                        DirectedNarrativeGenerator generator,
                                                        WorldTurnContextService worldContexts) {
        return service(database, generator, worldContexts, null);
    }

    private LegacyDirectedTurnPreparationService service(EmbeddedDatabase database,
                                                        DirectedNarrativeGenerator generator,
                                                        WorldTurnContextService worldContexts,
                                                        StoryTurnDiagnosticService diagnostics) {
        JdbcTemplate jdbc = new JdbcTemplate(database);
        var allocated = new java.util.concurrent.atomic.AtomicInteger();
        return new LegacyDirectedTurnPreparationService(new StoryEntityRepository(jdbc, mapper),
                new StoryMemoryRepository(jdbc), new StoryConversationRepository(jdbc),
                new StoryTurnRepository(jdbc, mapper), new StoryProjectionPersistenceMapper(mapper),
                new PromptContextAssembler(),
                new StoryStateReducer(new StoryPatchValidator(), prefix -> prefix + "-typed-test-" + allocated.incrementAndGet()),
                worldContexts, diagnostics, generator);
    }

    /** 仅用于当前独立 H2 夹具的逐列快照，包含 CLOB、版本、时间戳及正式回合，不读取任何共享数据。 */
    private Map<String, List<List<String>>> authoritySnapshot(EmbeddedDatabase database) {
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Map<String, List<List<String>>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("story_save", "story_entity", "story_entity_alias", "story_memory",
                "story_conversation", "story_turn")) {
            snapshot.put(table, jdbc.query("select * from " + table + " order by 1", (row, rowNumber) -> {
                List<String> columns = new ArrayList<>();
                for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) {
                    columns.add(row.getString(column));
                }
                return columns;
            }));
        }
        return snapshot;
    }

    private StoredSave save() {
        ObjectNode state = com.wenxian.game.story.support.StoryTestFixtures.state(
                mapper, "save-typed", 0, "石门前", "你来到石门前。");
        Instant now = Instant.now();
        return new StoredSave("save-typed", "user-typed", 1, 1, "ACTIVE", "XUANXU",
                "LIGHT_NARRATIVE", 1, 1, "NATIVE", "V0.16", null, null, state,
                1, 0, "V0.16", "V0.16", now, now, null, null, null);
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                "user-typed", "typed@test.local", "ACTIVE", now, now);
        StoredSave stored = save();
        new StorySaveRepository(jdbc, mapper).insert(new NewSave(stored.id(), stored.userId(), stored.slotNo(),
                stored.activeSlotNo(), stored.status(), stored.worldCode(), stored.engineCode(),
                stored.engineVersion(), stored.stateSchemaVersion(), stored.sourceType(), stored.sourceEdition(),
                stored.sourceSaveId(), stored.parentSaveId(), stored.state(), stored.turnNumber(),
                stored.createdRelease(), stored.lastPlayedRelease(), stored.createdAt()));
        jdbc.update("""
                insert into story_conversation(save_id,rolling_summary,summarized_through_turn,
                    summary_schema_version,updated_at) values (?,?,?,?,?)
                """, "save-typed", "你来到石门前。", 0, 1, now);
        return database;
    }

}
