package com.wenxian.game.story.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wenxian.game.credit.PlatformTurnAccessService;
import com.wenxian.game.model.domain.CredentialSource;
import com.wenxian.game.service.GameVersionService;
import com.wenxian.game.story.agent.PromptContextAssembler;
import com.wenxian.game.story.agent.StoryPromptContext;
import com.wenxian.game.story.agent.ai.DirectorProtocolException;
import com.wenxian.game.story.agent.ai.DirectedNarrativeGenerator.ModelCall;
import com.wenxian.game.story.agent.ai.StoryCandidateGenerator;
import com.wenxian.game.story.agent.ai.StoryCandidateProtocol;
import com.wenxian.game.story.domain.StoryRecords;
import com.wenxian.game.story.persistence.*;
import com.wenxian.game.story.persistence.StoryPersistenceModels.NewSave;
import com.wenxian.game.story.persistence.StoryPersistenceModels.StoredSave;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 正式唯一准备入口的整轮、历史读回和失败边界；固定候选不代表真实叙事质量。 */
class TrustedStoryTurnPreparationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void oneCompleteCandidateCommitsRecordsAndNextRequestReadsTheSameSource() throws Exception {
        EmbeddedDatabase db = database();
        try {
            var seen = new ArrayList<List<StoryRecords.Envelope>>();
            var contexts = new ArrayList<StoryPromptContext>();
            var calls = new AtomicInteger();
            var session = session((context, history, rejected, alignment) -> {
                calls.incrementAndGet();seen.add(history);contexts.add(context);
                return generated("你辨认出止血草，掌柜点头，随后停下。", false);
            });
            var service = service(db, invocation -> session);
            var current = new StorySaveRepository(new JdbcTemplate(db), mapper).findOwned("user-typed", "save-typed").orElseThrow();
            PreparedStoryTurn first = service.prepare(current, "new-record-1", "辨认止血草后停下", null,
                    service.openSession(current, "new-record-1"));
            assertEquals(1, first.generation().modelCalls().size());
            assertNull(first.acceptedResult());assertEquals(1,first.storyRecords().size());
            assertEquals(0,new JdbcTemplate(db).queryForObject("select count(*) from story_turn",Integer.class));
            commit(db).commit("user-typed",current,"new-record-1","辨认止血草后停下",first,null);
            current = new StorySaveRepository(new JdbcTemplate(db), mapper).findOwned("user-typed","save-typed").orElseThrow();
            var stored = new StoryTurnRepository(new JdbcTemplate(db), mapper).recent("save-typed",1).getFirst();
            var envelope = StoryRecordHistoryReader.read(stored).storyRecords();
            assertEquals(stored.id()+"#record-1",envelope.entries().getFirst().recordId());
            var second = service.prepare(current,"new-record-2","回想刚才辨草的经历",null,session);
            assertEquals(2,calls.get());assertTrue(seen.getFirst().isEmpty());assertEquals(List.of(envelope),seen.getLast());
            assertEquals(first.applied().acceptedPatch().narrative(),contexts.getLast().narrative().previousTurn().narrative());
            assertEquals(envelope,contexts.getLast().narrative().previousTurn().storyRecords());
            assertEquals(first.applied().projection().worldTime(),second.before().worldTime());
            assertEquals(1,new JdbcTemplate(db).queryForObject("select count(*) from story_turn",Integer.class));
        } finally { db.shutdown(); }
    }

    @Test
    void invalidRecordReferenceUsesOneRepairWithOriginalCandidateAndUnchangedContext() {
        EmbeddedDatabase db = database();
        try {
            var count=new AtomicInteger();var original=new AtomicReference<StoryPromptContext>();
            var session=session((context,history,rejected,alignment)->{
                if(count.incrementAndGet()==1){original.set(context);return generated("失败候选正文也要完整保留。",true);}
                assertSame(original.get(),context);assertNotNull(rejected);
                assertEquals("失败候选正文也要完整保留。",mapper.readTree((String)rejected.rejectedDraft()).at("/story/narrative").asText());
                return generated("你问过掌柜，仍有条件需要考虑。",false);
            });
            var service=service(db,invocation->session);var before=authoritySnapshot(db);
            var prepared=service.prepare(save(),"repair-record","询问条件",null,session);
            assertEquals(2,count.get());assertEquals(2,prepared.generation().modelCalls().size());
            assertEquals(200,prepared.generation().inputTokens());assertEquals(before,authoritySnapshot(db));
            assertEquals("你问过掌柜，仍有条件需要考虑。",prepared.applied().acceptedPatch().narrative());
        } finally {db.shutdown();}
    }

    @Test
    void secondInvalidCandidateStopsWithoutPartialStateOrThirdCall() {
        EmbeddedDatabase db=database();
        try {
            var count=new AtomicInteger();var session=session((c,h,r,a)->{count.incrementAndGet();return generated("未通过的完整故事。",true);});
            var before=authoritySnapshot(db);var service=service(db,invocation->session);
            assertThrows(StoryTurnProtocolException.class,()->service.prepare(save(),"reject-twice","询问",null,session));
            assertEquals(2,count.get());assertEquals(before,authoritySnapshot(db));
        } finally {db.shutdown();}
    }

    @Test
    void infrastructureFailureDoesNotConsumeProtocolRepair() {
        EmbeddedDatabase db=database();
        try {
            var count=new AtomicInteger();var failure=new IllegalStateException("isolated transport unavailable");
            var session=session((c,h,r,a)->{count.incrementAndGet();throw failure;});
            var service=service(db,invocation->session);var before=authoritySnapshot(db);
            assertSame(failure,assertThrows(IllegalStateException.class,()->service.prepare(save(),"failed-call","询问",null,session)));
            assertEquals(1,count.get());assertEquals(before,authoritySnapshot(db));
        } finally {db.shutdown();}
    }

    @Test
    void selectedChoiceUsesSavedActionAndStaleChoiceIsRejectedBeforeGeneration() {
        EmbeddedDatabase db=database();
        try {
            var current=save();var seen=new ArrayList<String>();
            ((ObjectNode)current.state().path("scene")).putArray("choices").addObject()
                    .put("id","choice-current").put("title","暂不出城").put("description","向掌柜说明今日留在药铺。");
            var session=session((c,h,r,a)->{seen.add(c.action().playerAction());return generated("你说明打算，等待掌柜回应。",false);});
            var service=service(db,invocation->session);
            service.prepare(current,"choice-1","伪造的接受出城文本","choice-current",session);
            assertEquals(List.of("暂不出城：向掌柜说明今日留在药铺。"),seen);
            assertThrows(com.wenxian.game.story.domain.StoryActionPreconditionException.class,
                    ()->service.prepare(current,"choice-2","询问","choice-stale",session));
            assertEquals(1,seen.size());
        } finally {db.shutdown();}
    }

    @Test
    void corruptNewHistoryStopsBeforeCallingModelRatherThanBecomingEmptyContext() {
        EmbeddedDatabase db=database();
        try {
            var count=new AtomicInteger();var session=session((c,h,r,a)->{count.incrementAndGet();return generated("本轮原文。",false);});
            var service=service(db,invocation->session);var current=new StorySaveRepository(new JdbcTemplate(db),mapper).findOwned("user-typed","save-typed").orElseThrow();
            var prepared=service.prepare(current,"history-1","回顾",null,session);
            commit(db).commit("user-typed",current,"history-1","回顾",prepared,null);
            var row=new StoryTurnRepository(new JdbcTemplate(db),mapper).recent("save-typed",1).getFirst();
            var patch=(ObjectNode)row.statePatch().deepCopy();((ObjectNode)patch.path("storyRecords")).put("protocolVersion",99);
            new JdbcTemplate(db).update("update story_turn set state_patch_json=? where id=?",patch.toString(),row.id());
            var after=new StorySaveRepository(new JdbcTemplate(db),mapper).findOwned("user-typed","save-typed").orElseThrow();
            assertThrows(IllegalStateException.class,()->service.prepare(after,"history-2","回顾旧事",null,session));
            assertEquals(1,count.get());
        } finally {db.shutdown();}
    }

    private StoryCandidateGenerator.Generated generated(String narrative,boolean badReference) {
        try {
            var raw=mapper.createObjectNode();var story=raw.putObject("story");story.put("title","药铺见闻").put("narrative",narrative).putNull("decision");story.putArray("choices");story.put("summary","你与掌柜交谈。");
            var changes=raw.putObject("changes");changes.putObject("duration").put("unit","SCENE").put("value",1);changes.put("timeResolution","COMPLETE");
            var record=raw.putArray("records").addObject().put("kind","EVENT").put("text",narrative);record.putNull("sourceRef").putNull("sourceLabel");
            var about=record.putArray("about");if(badReference)about.add("character-missing");
            var draft=mapper.treeToValue(raw,StoryCandidateProtocol.Draft.class);
            return new StoryCandidateGenerator.Generated(draft,StoryCandidateProtocol.adapt(draft),
                    List.of(new ModelCall("TEST","candidate","PLATFORM",false,"SUCCESS",1,100,30,0,0,"")),raw.toString(),List.of());
        }catch(Exception error){throw new IllegalStateException(error);}
    }
    @FunctionalInterface private interface Generate {
        StoryCandidateGenerator.Generated apply(StoryPromptContext c,List<StoryRecords.Envelope> h,DirectorProtocolException r,Object a) throws Exception;
    }
    private StoryCandidateGenerator.Session session(Generate fn){return new StoryCandidateGenerator.Session(){
        public CredentialSource credentialSource(){return CredentialSource.PLATFORM;}
        public StoryCandidateGenerator.Generated generate(StoryPromptContext c,List<StoryRecords.Envelope> h,DirectorProtocolException r,Object a){
            try{return fn.apply(c,h,r,a);}catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException(error);}
        }
    };}
    private TrustedStoryTurnPreparationService service(EmbeddedDatabase db,StoryCandidateGenerator generator){
        var jdbc=new JdbcTemplate(db);return new TrustedStoryTurnPreparationService(new StoryEntityRepository(jdbc,mapper),
                new StoryMemoryRepository(jdbc),new StoryConversationRepository(jdbc),new StoryTurnRepository(jdbc,mapper),
                new StoryProjectionPersistenceMapper(mapper),new PromptContextAssembler(),
                com.wenxian.game.story.worldpackage.WorldPackageRegistryTestSupport.worldContexts(mapper),null,generator);
    }
    private StoryTurnCommitService commit(EmbeddedDatabase db){var jdbc=new JdbcTemplate(db);return new StoryTurnCommitService(
            new StorySaveRepository(jdbc,mapper),new StoryTurnRepository(jdbc,mapper),new StoryEntityRepository(jdbc,mapper),
            new StoryMemoryRepository(jdbc),new StoryConversationRepository(jdbc),new StoryProjectionPersistenceMapper(mapper),
            new GameVersionService(jdbc),new PlatformTurnAccessService(null,null,false),new TransactionTemplate(new DataSourceTransactionManager(db)),mapper);}

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
