package com.wenxian.demo.dm;

import static com.wenxian.demo.dm.Protocol.*;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.*;
import java.time.Duration;
import java.util.*;

/** 通过LangChain4j类型化AI Service与只读工具主持一轮。真实调用需显式启用，失败不修补成假剧情。 */
final class LangChainDm implements DmGateway {
    interface DungeonMaster {
        @SystemMessage(fromResource="prompts/dm-system.txt")
        @UserMessage(fromResource="prompts/dm-user.txt")
        Result<Decision> play(@V("context") String context,@V("mode") String mode,@V("action") String action);
    }
    interface JourneyMaster {
        @SystemMessage(fromResource="prompts/adventure-system.txt")
        @UserMessage(fromResource="prompts/dm-user.txt")
        Result<AdventureProtocol.Proposal> play(@V("context") String context,@V("mode") String mode,@V("action") String action);
        @SystemMessage(fromResource="prompts/adventure-ask-system.txt")
        @UserMessage(fromResource="prompts/dm-user.txt")
        Result<AdventureProtocol.Proposal> ask(@V("context") String context,@V("mode") String mode,@V("action") String action);
    }
    private final DemoConfig config;
    private final DemoStore store;
    private final LoreforgeClient loreforge;
    LangChainDm(DemoConfig config,DemoStore store,LoreforgeClient loreforge){this.config=config;this.store=store;this.loreforge=loreforge;}
    public boolean ready(){return config.ready();}
    public ModelResult play(Save before,ActionRequest request) {
        if(!ready())throw new Fault(503,"DM尚未启用，开场和资料可以先查看");
        if(config.maxCalls-store.callCount()<3)throw new Fault(429,"本次测试额度不足以完整处理新一轮，存档已保留");
        Lookup lookup=new Lookup(loreforge,before.lore());
        var base=baseModel();
        MeteredModel model=new MeteredModel(base,store,request.requestId(),config.maxCalls);
        DungeonMaster dm=AiServices.builder(DungeonMaster.class).chatModel(model).tools(lookup).maxToolCallingRoundTrips(2).build();
        Map<String,Object> context=new LinkedHashMap<>();
        context.put("scenario",Json.tree(before.scenarioBrief()));
        context.put("current",Map.of("revision",before.revision(),"location",before.location(),"condition",before.condition(),"minutes",before.minutes(),"goal",before.goal(),"maturePlants",before.maturePlants(),"inventory",before.inventory(),"abilities",before.abilities()));
        context.put("facts",before.facts());
        context.put("history",before.turns().stream().map(t->Map.of("turn",t.number(),"mode",t.mode(),"player",t.action(),"dm",t.narrative(),"ruling",t.ruling(),"readReferenceKeys",t.readings().stream().map(Reading::key).toList(),"receipt",t.receipt()==null?List.of():t.receipt())).toList());
        context.put("referenceIndex",before.lore().values().stream().map(l->Map.of("resource",l.resource(),"key",l.key(),"name",l.name())).toList());
        long start=System.nanoTime();
        Result<Decision> result=dm.play(Json.write(context),request.mode(),request.action());
        return new ModelResult(result.content(),Map.copyOf(lookup.accessed),lookup.accessed.keySet().stream().filter(k->!before.lore().containsKey(k)).toList(),new Stats(model.calls,model.input,model.output,(System.nanoTime()-start)/1_000_000));
    }
    private ChatModel baseModel(){return OpenAiChatModel.builder().baseUrl(config.baseUrl).apiKey(config.apiKey).modelName(config.model)
        .customParameters(Map.of("thinking",Map.of("type","disabled"))).responseFormat("json_object")
        .maxTokens(2500).temperature(0.4).maxRetries(0).timeout(Duration.ofSeconds(90)).logRequests(false).logResponses(false).build();}
    public AdventureProtocol.Outcome playAdventure(Save before,ActionRequest request){
        if(!ready())throw new Fault(503,"DM尚未启用");
        if(config.maxCalls-store.callCount()<3)throw new Fault(429,"本次测试额度不足一轮，存档已保留");
        Lookup lookup=new Lookup(loreforge,before.lore());MeteredModel model=new MeteredModel(baseModel(),store,request.requestId(),config.maxCalls,60000,true);
        var builder=AiServices.builder(JourneyMaster.class).chatModel(model).maxToolCallingRoundTrips(2);
        var dm=request.mode().equals("ASK")?builder.tools(lookup,new ContextCheck(),new HistoryLookup(before)).build():builder.tools(lookup,new ContextCheck()).build();
        Map<String,Object> context=journeyContext(before,request.mode());
        long start=System.nanoTime();var result=request.mode().equals("ASK")?dm.ask(Json.write(context),request.mode(),request.action()):dm.play(Json.write(context),request.mode(),request.action());
        return new AdventureProtocol.Outcome(result.content(),Map.copyOf(lookup.accessed),new Stats(model.calls,model.input,model.output,(System.nanoTime()-start)/1_000_000));
    }
    static Map<String,Object> journeyContext(Save before,String mode){
        Map<String,Object> context=new LinkedHashMap<>();
        context.put("confirmedClock",Map.of("elapsedMinutes",before.minutes(),"windows",before.adventure().pack().operations().values().stream().filter(op->op.beforeMinute()>0).map(op->Map.of("action",op.name(),"deadline",op.beforeMinute(),"remainingMinutes",Math.max(0,op.beforeMinute()-before.minutes()))).toList()));
        context.put("world",before.adventure().pack());
        context.put("previouslyReadKeys",before.turns().stream().flatMap(t->t.readings().stream()).map(Reading::key).distinct().toList());
        context.put("current",Map.of("place",before.adventure().place(),"condition",before.condition(),"minutes",before.minutes(),"goal",before.goal(),"inventory",before.inventory(),"flags",before.adventure().flags(),"uses",before.adventure().uses(),"abilities",before.abilities()));
        if(mode.equals("ASK")){
            context.put("statementsAndPlans",before.facts().stream().filter(f->!f.kind().equals("OBSERVED")).toList());
            context.put("historyIndex",before.turns().stream().map(t->Map.of("turn",t.number(),"mode",t.mode(),"player",t.action(),"receipt",t.receipt()==null?List.of():t.receipt())).toList());
        }else{
            context.put("facts",before.facts());context.put("history",before.turns().stream().map(t->Map.of("turn",t.number(),"mode",t.mode(),"player",t.action(),"dm",t.narrative(),"ruling",t.ruling(),"readReferenceKeys",t.readings().stream().map(Reading::key).toList(),"receipt",t.receipt()==null?List.of():t.receipt())).toList());
        }
        context.put("referenceIndex",before.lore().values().stream().map(l->Map.of("resource",l.resource(),"key",l.key(),"name",l.name())).toList());
        return context;
    }
    /** 规则询问只按需读原始叙事，完整历史仍保留。文本中的数值口误不成为当前账目。 */
    static final class HistoryLookup {
        private final Save save;private int calls;
        HistoryLookup(Save save){this.save=save;}
        @Tool("按回合号读取保留的原始正文、经历和服务端回执，每次最多3回合；仅在需要回忆过去观察或交谈细节时用。当前时间/库存只用current及confirmedClock，不能用旧文本中的数字覆盖。")
        public String read_story_history(@P("起始回合号，从1开始") int first,@P("读取1至3回合") int count){
            if(++calls>2||first<1||count<1||count>3)throw new Fault(422,"本轮历史查阅范围过大");
            return Json.write(Map.of("confirmedClockMinutes",save.minutes(),"turns",save.turns().stream().filter(t->t.number()>=first&&t.number()<first+count)
                .map(t->Map.of("turn",t.number(),"mode",t.mode(),"player",t.action(),"narrative",t.narrative(),"ruling",t.ruling(),"receipt",t.receipt()==null?List.of():t.receipt())).toList(),
                "notes",save.facts().stream().filter(f->f.turn()>=first&&f.turn()<first+count).toList()));
        }
    }
    /** 无新规则需求时显式确认沿用现场，避免强制工具阶段导致无关查卡；此工具不授予任何资料引用权。 */
    static final class ContextCheck {
        @Tool("仅当本轮纯交谈或只沿用已查定义、确实不需要新资料时，确认使用已给现场；不返回新定义，不授权引用未查资料。询问未知术法效果时应read_reference。")
        public String confirm_scene_context(@P("简短说明本轮哪些信息已经由现场或已提交经历提供，无须新查卡") String reason){
            if(reason==null||reason.isBlank()||reason.length()>300)throw new Fault(422,"需要明确本轮依据");
            return "本轮已确认使用已有现场和经历。该工具未读取任何开物定义；citations只能为空或列previouslyReadKeys中实际已有的定义。涉及新效果仍须read_reference。";
        }
    }
    /** 每轮工具对象独立；本局已读定义使用固定快照，新定义必须来自真实开物，最多八次查阅。 */
    static final class Lookup {
        private final LoreforgeClient client;
        private final Map<String,Lore> pinned;
        final Map<String,Lore> accessed=new LinkedHashMap<>();
        private int calls;
        Lookup(LoreforgeClient client,Map<String,Lore> pinned){this.client=client;this.pinned=pinned;}
        @Tool("按资源类型和稳定key读取开物完整定义。当前会话已收录的条目使用本局固定快照；查阅后才能用于裁定。")
        public String read_reference(@P("spells、items、magicitems或rules") String resource,@P("资料的稳定key") String key) {
            if(++calls>8)throw new Fault(422,"本轮查阅过多，请缩小行动范围");
            Lore doc=pinned.containsKey(key)?pinned.get(key):client.read(resource,key);
            if(!doc.resource().equals(resource))throw new Fault(422,"资料分类与标识不一致");
            accessed.put(key,doc);return Json.write(doc);
        }
        @Tool("按中文名称搜索开物资料索引，最多五条；结果不是全文，需要read_reference进一步核对。")
        public String search_references(@P("简短中文名称") String name){if(++calls>8)throw new Fault(422,"本轮查阅过多");return client.search(name);}
    }
    /** 对每个真实HTTP模型尝试先登记预算。禁用SDK重试，保留脱敏请求、输出与实际用量用于回放。 */
    static final class MeteredModel implements ChatModel {
        private final ChatModel delegate;private final DemoStore store;private final String requestId;private final int limit,maxCharacters;private final boolean requireInitialTool;
        int calls,input,output;
        MeteredModel(ChatModel delegate,DemoStore store,String requestId,int limit){this(delegate,store,requestId,limit,30000);}
        MeteredModel(ChatModel delegate,DemoStore store,String requestId,int limit,int maxCharacters){this(delegate,store,requestId,limit,maxCharacters,false);}
        MeteredModel(ChatModel delegate,DemoStore store,String requestId,int limit,int maxCharacters,boolean requireInitialTool){this.delegate=delegate;this.store=store;this.requestId=requestId;this.limit=limit;this.maxCharacters=maxCharacters;this.requireInitialTool=requireInitialTool;}
        public ChatRequestParameters defaultRequestParameters(){return delegate.defaultRequestParameters();}
        public ChatResponse doChat(ChatRequest request){
            if(requireInitialTool && request.parameters().toolSpecifications()!=null&&!request.parameters().toolSpecifications().isEmpty()){
                var parameters=request.parameters().overrideWith(DefaultChatRequestParameters.builder().toolChoice(calls==0?ToolChoice.REQUIRED:ToolChoice.AUTO).build());
                request=request.toBuilder().parameters(parameters).build();
            }
            String trace=Json.write(Map.of("messages",request.messages().stream().map(Object::toString).toList(),"tools",String.valueOf(request.parameters().toolSpecifications()),"toolChoice",String.valueOf(request.parameters().toolChoice())));
            if(trace.length()>maxCharacters)throw new Fault(422,"当前试玩材料过长，已有经历保留，请另开一局");
            String callId=store.reserveCall(requestId,trace,limit,maxCharacters);long start=System.nanoTime();calls++;
            try {
                ChatResponse response=delegate.chat(request);
                Integer in=response.tokenUsage()==null?null:response.tokenUsage().inputTokenCount();
                Integer out=response.tokenUsage()==null?null:response.tokenUsage().outputTokenCount();
                input+=in==null?0:in;output+=out==null?0:out;
                store.finishCall(callId,Json.write(Map.of("message",response.aiMessage().toString(),"model",String.valueOf(response.modelName()))),in,out,(System.nanoTime()-start)/1_000_000,"SUCCEEDED");
                return response;
            }catch(Exception e){store.finishCall(callId,Json.write(Map.of("errorClass",e.getClass().getSimpleName())),null,null,(System.nanoTime()-start)/1_000_000,"FAILED");throw e;}
        }
    }
}
