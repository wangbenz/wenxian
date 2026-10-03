package com.wenxian.demo.dm;

import static com.wenxian.demo.dm.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.AiServices;

class DemoTests {
    @TempDir Path directory;
    static final String CONTROL="xianxia_spell-object-control";
    static Map<String,Lore> docs(){
        Map<String,Lore> result=new LinkedHashMap<>();
        for(String key:List.of(CONTROL,SceneReducer.GRASS,SceneReducer.GARDEN,SceneReducer.PROTECTION)) {
            String resource=key.equals(CONTROL)?"spells":key.equals(SceneReducer.GRASS)?"items":key.equals(SceneReducer.GARDEN)?"rules":"magicitems";
            result.put(key,new Lore(key,key,resource,"/spells/"+key,"测试定义：只读资料。","hash-"+key));
        }
        return result;
    }
    static Decision ask(){return new Decision("控物术的对象与条件需要先核对；现在尚未行动。","先查定义，保留现状。",List.of(CONTROL),List.of(),0,"","","ONGOING",0,0,"",List.of(),List.of(),List.of("观察现场"));}
    static Decision harvest(){return new Decision("你按已商议的方式剪下一株成熟草料，收进手中。","剪取改变剩余植株，纸料转到你手中。",List.of(SceneReducer.GRASS,SceneReducer.GARDEN),List.of(),5,"药畦边","","ONGOING",1,0,"PLAYER",List.of(),List.of(new EventNote("OBSERVED","剪取一株成熟草料并收起。")),List.of("返回纸坊"));}
    static ModelResult output(Decision d){return new ModelResult(d,docs(),List.of(),new Stats(1,10,20,30));}
    static ActionRequest req(int revision,String mode){return new ActionRequest(UUID.randomUUID().toString(),revision,mode,mode.equals("ASK")?"可以这样做吗？":"按约剪取一株并收起");}
    static DmGateway gateway(AtomicInteger calls,java.util.function.Function<ActionRequest,Decision> decision){return new DmGateway(){public boolean ready(){return true;}public ModelResult play(Save s,ActionRequest r){calls.incrementAndGet();return output(decision.apply(r));}};}
    static String put(DemoStore store,String token){String id=UUID.randomUUID().toString();store.create(SceneReducer.initial(id,docs()),token);return id;}

    @Test void askingCannotSpendResourcesOrAdvanceWorld(){
        Save before=SceneReducer.initial("example",docs());SceneReducer reducer=new SceneReducer();
        Save answer=reducer.apply(before,req(0,"ASK"),output(ask()));
        assertEquals(before.inventory(),answer.inventory());assertEquals(0,answer.minutes());assertEquals(0,answer.facts().size());assertEquals(1,answer.turns().size());
        assertThrows(Fault.class,()->reducer.apply(before,req(0,"ASK"),output(harvest())));
        assertEquals(3,before.maturePlants());
    }
    @Test void finiteStockAndDeliveryNeedActualTransfers(){
        SceneReducer reducer=new SceneReducer();Save before=SceneReducer.initial("example",docs());
        Save after=reducer.apply(before,req(0,"ACT"),output(harvest()));
        assertEquals(2,after.maturePlants());assertEquals(1,after.inventory().get("PLAYER").get("grass"));assertEquals(0,before.inventory().get("PLAYER").get("grass"));
        Decision impossible=new Decision("已交付。","说明。",List.of(SceneReducer.GRASS),List.of(),1,"","","DELIVERED",0,0,"",List.of(),List.of(new EventNote("OBSERVED","声称交付")),List.of());
        assertThrows(Fault.class,()->reducer.apply(after,req(1,"ACT"),output(impossible)));
        Decision overspend=new Decision("耗去两张符。","说明。",List.of(SceneReducer.PROTECTION),List.of(),1,"","","ONGOING",0,0,"",List.of(new Transfer("protection","PLAYER","USED",2)),List.of(new EventNote("OBSERVED","使用符")),List.of());
        assertThrows(Fault.class,()->reducer.apply(after,req(1,"ACT"),output(overspend)));
        Decision delivery=new Decision("你把手中的一束纸料交给收料人。","纸料从随身物资移交纸坊。",List.of(SceneReducer.GRASS),List.of(),20,"溪口纸坊","","DELIVERED",0,0,"",List.of(new Transfer("grass","PLAYER","SHOP",1)),List.of(new EventNote("OBSERVED","纸坊收下一束纸料")),List.of());
        Save delivered=reducer.apply(after,req(1,"ACT"),output(delivery));
        assertEquals(1,delivered.inventory().get("SHOP").get("grass"));assertEquals(0,delivered.inventory().get("PLAYER").get("grass"));assertEquals("DELIVERED",delivered.goal());
        Decision missingDestination=new Decision("你剪下并拿起纸料。","采收。",List.of(SceneReducer.GRASS,SceneReducer.GARDEN),List.of(),1,"","","ONGOING",1,0,null,List.of(),List.of(new EventNote("OBSERVED","采收")),List.of());
        assertThrows(Fault.class,()->reducer.apply(before,req(0,"ACT"),output(missingDestination)));
        Decision damaged=new Decision("一株成熟草受到损坏，无法再采纸料。","只记录实际损失，不当作采收。",List.of(SceneReducer.GRASS),List.of(),1,"","","ONGOING",0,1,"",List.of(),List.of(new EventNote("OBSERVED","一株成熟草损坏")),List.of());
        Save loss=reducer.apply(before,req(0,"ACT"),output(damaged));
        assertEquals(2,loss.maturePlants());assertEquals(0,loss.inventory().get("PLAYER").get("grass"));assertEquals(0,loss.inventory().get("GARDEN").get("grass"));
    }
    @Test void snapshotsStatementsAndHistoryRemainDistinct(){
        Save before=SceneReducer.initial("example",docs());
        Decision claim=new Decision("守圃人声称北面安全，你打算之后去看看。","说法尚未验证。",List.of(SceneReducer.GARDEN),List.of(),1,"","","ONGOING",0,0,"",List.of(),List.of(new EventNote("STATEMENT","守圃人说北面安全"),new EventNote("PLAN","打算之后查看")),List.of());
        Save after=new SceneReducer().apply(before,req(0,"ACT"),output(claim));
        assertEquals("STATEMENT",after.facts().getFirst().kind());assertEquals("PLAN",after.facts().getLast().kind());
        Map<String,Lore> changed=new LinkedHashMap<>(docs());changed.put(CONTROL,new Lore(CONTROL,"新名","spells","/spells/changed","已改定义","changed-hash"));
        assertThrows(Fault.class,()->new SceneReducer().apply(after,req(1,"ASK"),new ModelResult(ask(),changed,List.of(),new Stats(1,1,1,1))));
        assertEquals(before.scenarioBrief(),after.scenarioBrief());assertEquals(before.lore(),after.lore());
        assertFalse(Json.write(DemoServer.visible(after)).contains("dmBrief"));
    }
    @Test void deliveryCanReusePinnedKnowledgeWithoutPretendingToReadAgain(){
        SceneReducer reducer=new SceneReducer();Save before=SceneReducer.initial("example",docs());
        Save carrying=reducer.apply(before,req(0,"ACT"),output(harvest()));
        Decision delivery=new Decision("你把这一束草料交到纸坊。","按已经确认持有的纸料办理移交。",List.of(SceneReducer.GRASS),List.of(),20,"溪口纸坊","","DELIVERED",0,0,"",List.of(new Transfer("grass","PLAYER","SHOP",1)),List.of(new EventNote("OBSERVED","纸坊收下纸料")),List.of());
        Save after=reducer.apply(carrying,req(1,"ACT"),new ModelResult(delivery,Map.of(),List.of(),new Stats(1,1,1,1)));
        assertEquals("DELIVERED",after.goal());assertEquals("REUSED",after.turns().getLast().readings().getFirst().access());
        assertTrue(after.turns().getLast().receipt().contains("七星草纸料：1 → 0"));
        Decision neverRead=new Decision("回答。","说明。",List.of(CONTROL),List.of(),0,"","","ONGOING",0,0,"",List.of(),List.of(),List.of());
        assertThrows(Fault.class,()->reducer.apply(before,req(0,"ASK"),new ModelResult(neverRead,Map.of(),List.of(),new Stats(1,1,1,1))));
    }
    @Test void replayAndChangedPayloadCannotCallDmAgain(){
        try(DemoStore store=new DemoStore(directory)){
            String token=DemoStore.token(),id=put(store,token);AtomicInteger calls=new AtomicInteger();DemoService service=new DemoService(store,null,gateway(calls,r->harvest()));ActionRequest request=req(0,"ACT");
            Claim first=service.act(id,token,request,false);Claim replay=service.act(id,token,request,false);
            assertEquals("COMMITTED",first.status());assertEquals(first.save(),replay.save());assertEquals(1,calls.get());
            assertThrows(Fault.class,()->service.act(id,token,new ActionRequest(request.requestId(),0,"ACT","改成另一件事"),false));
            assertThrows(Fault.class,()->store.get(id,"wrong-token"));assertEquals(1,store.get(id,token).revision());
        }
    }
    @Test void failurePreservesStateAndRequiresExplicitRetry(){
        try(DemoStore store=new DemoStore(directory)){
            String token=DemoStore.token(),id=put(store,token);AtomicInteger calls=new AtomicInteger();
            DmGateway failOnce=new DmGateway(){public boolean ready(){return true;}public ModelResult play(Save s,ActionRequest r){if(calls.incrementAndGet()==1)throw new IllegalStateException("private provider detail");return output(harvest());}};
            DemoService service=new DemoService(store,null,failOnce);ActionRequest r=req(0,"ACT");
            Claim failed=service.act(id,token,r,false);assertEquals("FAILED",failed.status());assertEquals(0,store.get(id,token).revision());assertFalse(failed.message().contains("private"));
            service.act(id,token,r,false);assertEquals(1,calls.get());
            assertEquals("COMMITTED",service.act(id,token,r,true).status());assertEquals(2,calls.get());assertEquals(1,store.get(id,token).inventory().get("PLAYER").get("grass"));
        }
    }
    @Test void simultaneousSubmissionsGenerateOnce() throws Exception {
        try(DemoStore store=new DemoStore(directory);var executor=Executors.newVirtualThreadPerTaskExecutor()){
            String token=DemoStore.token(),id=put(store,token);CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
            DmGateway slow=new DmGateway(){public boolean ready(){return true;}public ModelResult play(Save s,ActionRequest r){calls.incrementAndGet();started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}return output(harvest());}};
            DemoService service=new DemoService(store,null,slow);ActionRequest r=req(0,"ACT");
            var task=executor.submit(()->service.act(id,token,r,false));assertTrue(started.await(3,TimeUnit.SECONDS));
            assertEquals("RUNNING",service.act(id,token,r,false).status());assertThrows(Fault.class,()->service.act(id,token,req(0,"ACT"),false));
            release.countDown();assertEquals("COMMITTED",task.get(5,TimeUnit.SECONDS).status());assertEquals(1,calls.get());
        }
    }
    @Test void failedCommitRollsBackSnapshotAndRestartRetainsSave(){
        String token=DemoStore.token(),id;
        try(DemoStore store=new DemoStore(directory)){
            id=put(store,token);ActionRequest r=req(0,"ACT");Claim claim=store.begin(id,token,r,false);
            Save after=new SceneReducer().apply(claim.save(),r,output(harvest()));
            assertThrows(Fault.class,()->store.commit(id,req(0,"ACT"),after,Map.of()));
            assertEquals(0,store.get(id,token).revision());
        }
        try(DemoStore reopened=new DemoStore(directory)){assertEquals(0,reopened.get(id,token).revision());}
    }
    @Test void modelBudgetIsPersistentAndCountsAttempts(){
        ChatModel echo=new ChatModel(){public ChatResponse doChat(ChatRequest request){return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();}};
        try(DemoStore store=new DemoStore(directory)){
            var model=new LangChainDm.MeteredModel(echo,store,"test",2);model.chat("hello");model.chat("again");assertThrows(Fault.class,()->model.chat("too many"));assertEquals(2,store.callCount());
        }
        try(DemoStore store=new DemoStore(directory)){assertThrows(Fault.class,()->new LangChainDm.MeteredModel(echo,store,"test",2).chat("restart"));}
    }
    @Test void aiServiceActuallyRunsReadToolBeforeParsingTypedResult(){
        AtomicInteger calls=new AtomicInteger();var lookup=new LangChainDm.Lookup(new LoreforgeClient("http://127.0.0.1:1"),docs());
        ChatModel scripted=new ChatModel(){public ChatResponse doChat(ChatRequest request){
            if(calls.incrementAndGet()==1)return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("lookup-1").name("read_reference").arguments(Json.write(Map.of("resource","spells","key",CONTROL))).build())).build();
            assertTrue(request.messages().toString().contains("hash-"+CONTROL));return ChatResponse.builder().aiMessage(AiMessage.from(Json.write(ask()))).build();
        }};
        var service=AiServices.builder(LangChainDm.DungeonMaster.class).chatModel(scripted).tools(lookup).maxToolCallingRoundTrips(2).build();
        var result=service.play("本地自动测试","ASK","询问控物术");assertEquals(ask(),result.content());assertEquals(2,calls.get());assertTrue(lookup.accessed.containsKey(CONTROL));
    }
}
