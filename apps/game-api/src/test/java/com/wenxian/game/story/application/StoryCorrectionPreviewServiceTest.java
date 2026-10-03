package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.StoryCorrectionAgent;
import com.wenxian.game.story.domain.StoryCorrectionProposal;
import com.wenxian.game.story.domain.StoryPatch;
import com.wenxian.game.story.domain.StoryProjection;
import com.wenxian.game.story.domain.StoryWorldTime;
import com.wenxian.game.story.persistence.StoryConversationRepository;
import com.wenxian.game.story.persistence.StoryEntityRepository;
import com.wenxian.game.story.persistence.StoryMemoryRepository;
import com.wenxian.game.story.persistence.StoryPersistenceModels.Entity;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StoryProjectionPersistenceMapper;
import com.wenxian.game.story.persistence.StorySaveRepository;
import com.wenxian.game.story.persistence.StoryTurnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryCorrectionPreviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-09T11:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void buildsSeparatedSnapshotWithAllStableEntityReferencesAndDoesNotWriteState() {
        EmbeddedDatabase database = database();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(database);
            StorySaveRepository saves = new StorySaveRepository(jdbc, mapper);
            StoryEntityRepository entities = new StoryEntityRepository(jdbc, mapper);
            StoryMemoryRepository memories = new StoryMemoryRepository(jdbc);
            StoryConversationRepository conversations = new StoryConversationRepository(jdbc);
            StoryTurnRepository turns = new StoryTurnRepository(jdbc, mapper);
            StoryProjectionPersistenceMapper projections = new StoryProjectionPersistenceMapper(mapper);
            seed(saves, entities, memories, conversations, projections);

            AtomicReference<com.wenxian.game.story.agent.StoryPromptContext> captured = new AtomicReference<>();
            StoryCorrectionAgent agent = (userId, requestId, context, instruction) -> {
                captured.set(context);
                return new StoryCorrectionAgent.Generated(new StoryCorrectionProposal(
                        StoryCorrectionProposal.CorrectionKind.FACT, "修正当前地点。", "确认修正地点？",
                        new StoryPatch.CoreStatePatch("青岐城北门", null, null, null),
                        List.of(), List.of(), List.of()));
            };
            CorrectionProposalTokenCodec tokens = new CorrectionProposalTokenCodec(mapper,
                    "test-only-correction-preview-secret", Duration.ofMinutes(10),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            StoryCorrectionPreviewService service = new StoryCorrectionPreviewService(saves, entities, memories,
                    conversations, turns, projections, new PromptContextAssembler(),
                    new StoryCorrectionWorkflow(agent, tokens),
                    new TransactionTemplate(new DataSourceTransactionManager(database)));

            StoryCorrectionWorkflow.Preview preview = service.preview("user-1", "story-save-1", 1,
                    "当前地点应为青岐城北门");

            var context = captured.get();
            assertEquals(2, context.objective().entityIndex().size(), "隐藏实体也必须保留稳定引用");
            assertTrue(context.objective().relatedEntities().isEmpty(), "修正预览不默认注入全部实体详情");
            assertEquals(1, context.objective().worldFacts().size());
            assertEquals(1, context.cognition().entries().size());
            assertEquals(1, context.memory().entries().size());
            assertEquals("当前地点应为青岐城北门", context.action().playerAction());
            assertEquals("当前地点应为青岐城北门",
                    tokens.verify(preview.proposalToken(), "user-1", "story-save-1", 1).instruction());
            assertEquals(1, saves.findOwned("user-1", "story-save-1").orElseThrow().revision());
            assertEquals(0, jdbc.queryForObject("select count(*) from story_state_correction", Integer.class));
        } finally {
            database.shutdown();
        }
    }

    private EmbeddedDatabase database() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .addScript("schema-h2.sql").build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("insert into app_user(id,email,status,registered_at,last_login_at) values (?,?,?,?,?)",
                "user-1", "trusted-preview@test.local", "ACTIVE", now, now);
        return database;
    }

    private void seed(StorySaveRepository saves, StoryEntityRepository entities, StoryMemoryRepository memories,
                      StoryConversationRepository conversations, StoryProjectionPersistenceMapper projections) {
        ObjectNode base = com.wenxian.game.story.support.StoryTestFixtures.state(
                mapper, "story-save-1", 4, "青岐城南门", "上一回正文");
        StoryProjection trusted = new StoryProjection(StoryWorldTime.opening(),
                new StoryProjection.CoreState("青岐城南门", "平静", "引气", "问道"), Map.of(),
                Map.of("fact-1", new StoryProjection.WorldFactState("fact-1", "北门仍在戒严", 1, true,
                        StoryPatch.WorldFactOperation.EXPAND, "既有设定")),
                Map.of("character-1\u0000fact-1", new StoryProjection.KnowledgeState(
                        "character-1", "fact-1", "守门人知道戒严缘由", List.of("turn-2"))), List.of(),
                com.wenxian.game.story.support.StoryTestFixtures.creationContract(),
                com.wenxian.game.story.support.StoryTestFixtures.playPreference(), null, null);
        saves.insert(new NewSave("story-save-1", "user-1", 1, 1, "ACTIVE", "XUANXU",
                "LIGHT_NARRATIVE", 1, 1, "NATIVE", "V0.14", null, null,
                projections.writeState(base, trusted), 4, "V0.14", "V0.14", NOW));
        entities.insert(entity("item-1", "ITEM", true));
        entities.insert(entity("character-1", "CHARACTER", false));
        memories.insert("story-save-1", new StoryMemoryRepository.StoredMemory(
                "memory-1", "EVENT", "北门旧事", "曾在北门见过守门人", 2, 60, "KEY"));
        conversations.initialize("story-save-1", "陆沉曾到过青岐城。", 4);
    }

    private Entity entity(String id, String type, boolean visible) {
        ObjectNode attributes = mapper.createObjectNode();
        ObjectNode trusted = attributes.putObject("_trusted");
        if ("ITEM".equals(type)) {
            trusted.putObject("quantity").put("known", false).putNull("value");
            trusted.putObject("custody").put("holderType", "PLAYER").putNull("holderRef");
        } else if ("CHARACTER".equals(type)) {
            trusted.put("lifecycle", "ACTIVE");
        }
        return new Entity(id, "story-save-1", type, id, "现状", visible, "摘要",
                attributes, 1, 2, NOW, NOW);
    }
}
