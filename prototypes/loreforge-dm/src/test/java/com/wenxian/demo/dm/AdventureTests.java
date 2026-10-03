package com.wenxian.demo.dm;

import static com.wenxian.demo.dm.Protocol.*;
import static com.wenxian.demo.dm.AdventureProtocol.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;

class AdventureTests {
    @TempDir Path directory;
    static Map<String,Lore> docs(Pack p){Map<String,Lore> out=new LinkedHashMap<>();p.references().forEach(r->out.put(r.get("key"),new Lore(r.get("key"),r.get("key"),r.get("resource"),"/test","仅供离线状态检查的资料","test-"+r.get("key"))));return out;}
    static Save initial(){Pack p=AdventureEngine.pack();return AdventureEngine.initial(UUID.randomUUID().toString(),p,docs(p));}
    static Step op(String key){return new Step("OP",key);}
    static Step move(String key){return new Step("MOVE",key);}
    static ActionRequest request(Save s,String mode){return new ActionRequest(UUID.randomUUID().toString(),s.revision(),mode,"按本轮授权处理");}
    static Outcome outcome(Save s,int minutes,Step...steps){
        Set<String> refs=new LinkedHashSet<>();for(Step step:steps)if(step.kind().equals("OP"))refs.addAll(s.adventure().pack().operations().get(step.key()).references());
        Proposal p=new Proposal("本轮操作已发生，后续由玩家选择。","依据已查资料和现场约定处理。",List.copyOf(refs),List.of(),List.of(steps),minutes,"",steps.length==0&&minutes==0?List.of():List.of(new EventNote("OBSERVED","实际完成本轮操作。")),List.of("查看此刻物资"));
        Map<String,Lore> accessed=new LinkedHashMap<>();refs.forEach(k->accessed.put(k,s.lore().get(k)));
        return new Outcome(p,accessed,new Stats(0,0,0,0));
    }
    static Save act(Save s,int minutes,Step...steps){return new AdventureEngine().apply(s,request(s,"ACT"),outcome(s,minutes,steps));}
    static Save workshop(){Save s=act(initial(),5,op("permission"),op("repair"),op("harvest"));return act(s,13,move("workshop"),op("commission"));}
    @Test void twoRoutesCarryDifferentResourcesAndTimeToEnding(){
        Save fast=act(workshop(),20,op("paper-quick"),op("craft-protection"));
        fast=act(fast,13,move("station"),op("activate-protection"),op("short-passage"),op("retrieve"),op("depart"));
        assertEquals(51,fast.minutes());assertEquals("赶上渡船",fast.adventure().flags().get("ending"));assertEquals(0,fast.inventory().get("PLAYER").get("protection"));assertEquals(1,fast.inventory().get("BOAT").get("pass"));
        assertEquals("渡船上",fast.location());assertEquals("ferry",fast.adventure().place());
        Save slow=act(workshop(),50,op("paper-careful"),op("craft-protection"),op("craft-protection"));
        slow=act(slow,8,move("station"));Save atStation=slow;
        assertThrows(Fault.class,()->act(atStation,5,op("activate-protection"),op("short-passage"),op("retrieve"),op("depart")));
        assertEquals(2,atStation.inventory().get("PLAYER").get("protection"));
        slow=act(slow,2,op("activate-protection"),op("short-passage"),op("retrieve"),op("stay"));
        assertEquals(78,slow.minutes());assertEquals("留宿驿亭",slow.adventure().flags().get("ending"));assertEquals(1,slow.inventory().get("PLAYER").get("protection"));assertEquals(1,slow.inventory().get("PLAYER").get("pass"));
    }
    @Test void cannotCraftFromNothingOrReuseConsumedMaterial(){
        Save before=workshop();assertThrows(Fault.class,()->act(before,10,op("craft-protection")));
        Save paper=act(before,10,op("paper-quick"));assertThrows(Fault.class,()->act(paper,30,op("paper-careful")));
        Save made=act(paper,10,op("craft-protection"));assertThrows(Fault.class,()->act(made,10,op("craft-protection")));
        assertEquals(0,made.inventory().get("PLAYER").get("paper"));assertEquals(1,made.inventory().get("WORKSHOP").get("ink"));
        assertEquals(before.abilities(),made.abilities());
    }
    @Test void promisedProductionDoesNotRequireRedundantAgreementStep(){
        Save s=act(initial(),3,op("permission"),op("harvest"));s=act(s,12,move("workshop"));
        assertEquals("尚未说明",s.adventure().flags().get("workshop"));
        s=act(s,20,op("paper-quick"),op("craft-protection"));
        assertEquals("符师已同意",s.adventure().flags().get("workshop"));assertEquals(1,s.inventory().get("PLAYER").get("protection"));
    }
    @Test void askingCannotMoveAndProcessingCannotSkipTimeOrPlace(){
        Save before=initial();var engine=new AdventureEngine();
        Save ask=engine.apply(before,request(before,"ASK"),outcome(before,0));assertEquals(before.inventory(),ask.inventory());assertEquals(before.adventure(),ask.adventure());assertTrue(ask.facts().isEmpty());
        assertThrows(Fault.class,()->engine.apply(before,request(before,"ASK"),outcome(before,12,move("workshop"))));
        assertThrows(Fault.class,()->act(before,0,move("station")));
        assertThrows(Fault.class,()->act(before,10,move("workshop")));
        assertThrows(Fault.class,()->act(before,1,op("commission")));
    }
    @Test void sameEngineHonorsDifferentPackYieldWithoutJavaBranches(){
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(AdventureEngine.pack());
        ((com.fasterxml.jackson.databind.node.ObjectNode)json.path("operations").path("paper-quick").path("outputs").get(0)).put("amount",3);
        Pack changed=Json.read(json.toString(),Pack.class);Save s=AdventureEngine.initial("variant",changed,docs(changed));
        s=act(s,3,op("permission"),op("harvest"));s=act(s,13,move("workshop"),op("commission"));s=act(s,10,op("paper-quick"));
        assertEquals(3,s.inventory().get("PLAYER").get("paper"));assertEquals(0,s.inventory().get("PLAYER").get("grass"));
    }
    @Test void shortProtectionSurvivesQuestionsButNotActualElapsedMinutes(){
        Save s=act(workshop(),20,op("paper-quick"),op("craft-protection"));s=act(s,8,move("station"));s=act(s,0,op("activate-protection"));
        assertEquals("已激发",s.adventure().flags().get("ward"));assertEquals(0,s.inventory().get("PLAYER").get("protection"));
        Save ask=new AdventureEngine().apply(s,request(s,"ASK"),outcome(s,0));assertEquals("已激发",ask.adventure().flags().get("ward"));
        Save spent=act(ask,1);assertEquals("未激发",spent.adventure().flags().get("ward"));
        assertThrows(Fault.class,()->act(spent,1,op("short-passage")));
        Save detour=act(spent,19,op("detour"),op("retrieve"));assertEquals(1,detour.inventory().get("PLAYER").get("pass"));
    }
    @Test void missingSourceCannotAuthorizeMaterialCreation(){
        Save s=workshop();Outcome o=outcome(s,10,op("paper-quick"));Proposal p=o.proposal();
        Outcome withoutRead=new Outcome(p,Map.of(),o.stats());
        assertThrows(Fault.class,()->new AdventureEngine().apply(s,request(s,"ACT"),withoutRead));
    }
    @Test void operationCompilesAlreadyReadSourceEvenWhenModelOmitsCitation(){
        Save s=workshop();Outcome full=outcome(s,10,op("paper-quick"));Proposal d=full.proposal();String paper="xianxia_material-blank-talisman-paper";
        Proposal omitted=new Proposal(d.narrative(),d.ruling(),List.of(paper),d.executedAbilities(),d.steps(),d.minutes(),d.condition(),d.events(),d.suggestions());
        Save after=new AdventureEngine().apply(s,request(s,"ACT"),new Outcome(omitted,Map.of(paper,s.lore().get(paper)),full.stats()));
        assertEquals(1,after.inventory().get("PLAYER").get("paper"));
        assertTrue(after.turns().getLast().readings().stream().anyMatch(r->r.key().equals("xianxia_plant-seven-star-grass")&&r.access().equals("REUSED")&&r.used()));
    }
    @Test void legacyJsonAndNewSnapshotsKeepSeparateFormatsAndPrivatePack(){
        Save old=SceneReducer.initial("old",DemoTests.docs());String oldJson=Json.write(old);assertFalse(oldJson.contains("adventure"));assertNull(Json.read(oldJson,Save.class).adventure());
        Save s=initial();assertEquals(s,Json.read(Json.write(s),Save.class));String visible=Json.write(DemoServer.visible(s));
        assertFalse(visible.contains("authorNotes"));assertFalse(visible.contains("operations"));assertTrue(visible.contains("一束草的去处"));
        String token=DemoStore.token();try(DemoStore store=new DemoStore(directory)){store.create(s,token);}
        try(DemoStore store=new DemoStore(directory)){assertEquals(s,store.get(s.id(),token));}
    }
    @Test void journeyRequiresOnlyInitialToolAndThenParsesGroundedAnswer(){
        var calls=new java.util.concurrent.atomic.AtomicInteger();Save s=initial();String key="xianxia_spell-object-control";
        Proposal answer=new Proposal("这是一次规则询问，尚未行动。","依据本轮查阅的定义回答。",List.of(key),List.of(),List.of(),0,"",List.of(),List.of());
        dev.langchain4j.model.chat.ChatModel scripted=new dev.langchain4j.model.chat.ChatModel(){
            public dev.langchain4j.model.chat.response.ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request){
                if(calls.incrementAndGet()==1){
                    assertEquals(dev.langchain4j.model.chat.request.ToolChoice.REQUIRED,request.parameters().toolChoice());
                    return dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from(dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("read").name("read_reference").arguments(Json.write(Map.of("resource","spells","key",key))).build())).build();
                }
                assertEquals(dev.langchain4j.model.chat.request.ToolChoice.AUTO,request.parameters().toolChoice());
                assertTrue(request.messages().toString().contains("test-"+key));
                return dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from(Json.write(answer))).build();
            }
        };
        try(DemoStore store=new DemoStore(directory)){
            var model=new LangChainDm.MeteredModel(scripted,store,"grounding-test",5,60000,true);
            var lookup=new LangChainDm.Lookup(new LoreforgeClient("http://127.0.0.1:1"),s.lore());
            var ai=dev.langchain4j.service.AiServices.builder(LangChainDm.JourneyMaster.class).chatModel(model).tools(lookup,new LangChainDm.ContextCheck()).maxToolCallingRoundTrips(2).build();
            assertEquals(answer,ai.play("测试现场","ASK","能否如此？").content());assertEquals(2,store.callCount());assertTrue(lookup.accessed.containsKey(key));
        }
    }
    @Test void rulesQuestionUsesConfirmedClockAndCanRetrieveUnmodifiedHistory(){
        Save base=initial();var data=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(base);
        data.put("minutes",47);
        data.set("facts",Json.MAPPER.valueToTree(List.of(new Fact(1,"OBSERVED","旧文字误写四十九分钟"))));
        data.set("turns",Json.MAPPER.valueToTree(List.of(new Turn(1,"old","ACT","观察现场","旧文字误写四十九分钟","旧说明",List.of(),new Stats(0,0,0,0),List.of(),List.of("经过 8 分钟")))));
        Save saved=Json.read(data.toString(),Save.class);String ask=Json.write(LangChainDm.journeyContext(saved,"ASK"));
        assertFalse(ask.contains("四十九"));assertTrue(ask.contains("\"elapsedMinutes\":47"));assertTrue(ask.contains("\"remainingMinutes\":23"));
        String history=new LangChainDm.HistoryLookup(saved).read_story_history(1,1);
        assertTrue(history.contains("四十九"));assertFalse(history.contains("requestId"));assertFalse(history.contains("stats"));assertTrue(Json.write(saved).contains("四十九"));
        assertTrue(Json.write(LangChainDm.journeyContext(saved,"ACT")).contains("四十九"));
    }
}
