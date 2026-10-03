package com.wenxian.demo.dm;

import static com.wenxian.demo.dm.Protocol.*;
import static com.wenxian.demo.dm.AdventureProtocol.*;
import java.util.*;

/** 执行内容包声明的资源变换、通路、状态前置与时间下限。开放语义由DM处理，任何失败不修改旧快照。 */
final class AdventureEngine {
    static String defaultId(){return Json.tree(Json.resource("adventures/catalog.json")).path("default").asText();}
    static List<Map<String,String>> catalog(){
        List<Map<String,String>> out=new ArrayList<>();for(var row:Json.tree(Json.resource("adventures/catalog.json")).path("packs"))out.add(Map.of("id",row.path("id").asText(),"label",row.path("label").asText()));
        out.add(Map.of("id","legacy","label","雨前取草 · 原短篇"));return out;
    }
    static Pack pack(){return load(defaultId());}
    static Pack load(String id){
        if(id==null||!id.matches("[a-z0-9-]{1,60}")||id.equals("legacy")||catalog().stream().noneMatch(row->row.get("id").equals(id)))throw new Fault(400,"没有这段试玩");
        Pack pack=Json.read(Json.resource("adventures/"+id+".json"),Pack.class);validate(pack);
        if(!id.equals(pack.id()))throw new IllegalArgumentException("内容包标识不一致");return pack;
    }
    static Save initial(String id,Pack pack,Map<String,Lore> lore){
        validate(pack);Map<String,String> flags=new LinkedHashMap<>();pack.flags().forEach((k,v)->flags.put(k,v.initial()));
        return new Save(id,0,"你","{}",pack.opening(),pack.goal(),pack.places().get(pack.startPlace()).name(),"身体安好，法力尚有余裕",0,"ONGOING",0,
            copyStock(pack.stock()),pack.abilities(),List.of(),List.of(),lore,new State(pack,pack.startPlace(),flags,new LinkedHashMap<>()));
    }
    static void validate(Pack p){
        if(!"adventure-1".equals(p.format())||!p.places().containsKey(p.startPlace())||!p.stock().containsKey("PLAYER")||!p.flags().containsKey(p.endingFlag()))throw new IllegalArgumentException("内容包基本结构不完整");
        p.stock().forEach((h,items)->items.forEach((k,n)->{if(!p.items().containsKey(k)||n<0)throw new IllegalArgumentException("内容包库存不合法");}));
        p.places().values().forEach(place->place.exits().forEach((k,m)->{if(!p.places().containsKey(k)||m<0)throw new IllegalArgumentException("内容包通路不合法");}));
        p.flags().values().forEach(f->{if(!f.values().contains(f.initial()))throw new IllegalArgumentException("内容包初始状态不合法");});
        if(!p.flags().keySet().containsAll(p.ephemeralFlags()))throw new IllegalArgumentException("内容包临时状态不合法");
        p.operations().values().forEach(op->{
            if(!p.places().containsKey(op.place())||op.minutes()<0||op.maxUses()<1)throw new IllegalArgumentException("内容包操作不合法");
            if(op.arrival()!=null&&!p.places().containsKey(op.arrival()))throw new IllegalArgumentException("内容包抵达地点不合法");
            for(Amount a:concat(op.inputs(),op.outputs()))if(!p.items().containsKey(a.item())||!p.stock().containsKey(a.holder())||a.amount()<1)throw new IllegalArgumentException("内容包资源操作不合法");
            for(var f:concatEntries(op.requires(),op.changes()))if(!p.flags().containsKey(f.getKey())||!p.flags().get(f.getKey()).values().contains(f.getValue()))throw new IllegalArgumentException("内容包状态操作不合法");
        });
    }
    Save apply(Save before,ActionRequest request,Outcome out){
        State old=before.adventure();Pack p=old.pack();Proposal d=out.proposal();
        if(d==null||d.citations()==null||d.executedAbilities()==null||d.steps()==null||d.events()==null||d.suggestions()==null)throw new Fault(422,"DM回应不完整，进度未改变");
        text(d.narrative(),3000);text(d.ruling(),900);
        if(d.minutes()<0||d.minutes()>720||d.steps().size()>12||d.events().size()>10||d.suggestions().size()>4||d.citations().size()>12)throw new Fault(422,"本轮超出短篇试玩范围");
        Set<String> read=new HashSet<>(out.accessed().keySet());
        before.turns().forEach(t->t.readings().forEach(r->{Lore l=before.lore().get(r.key());if(l!=null&&l.hash().equals(r.hash()))read.add(r.key());}));
        // 操作要求的依据由协议补全；模型漏列一个旧引用不应阻断合法变化，但未读定义仍不能使用。
        Set<String> citations=new LinkedHashSet<>(d.citations());citations.addAll(d.executedAbilities());
        for(Step step:d.steps())if(step!=null&&"OP".equals(step.kind())){Operation op=p.operations().get(step.key());if(op!=null)citations.addAll(op.references());}
        if(!read.containsAll(citations))throw new Fault(422,"引用的资料尚未实际查阅，进度未改变");
        if(!before.abilities().containsAll(d.executedAbilities()))throw new Fault(422,"实际施放的本领缺少已学记录或资料依据");
        String condition=d.condition()==null||d.condition().isBlank()?before.condition():d.condition();text(condition,180);
        if(request.mode().equals("ASK")&&(!d.steps().isEmpty()||d.minutes()!=0||!d.events().isEmpty()||!d.executedAbilities().isEmpty()||!condition.equals(before.condition())))throw new Fault(422,"询问不能替角色行动");
        if(!before.goal().equals("ONGOING")&&!d.steps().isEmpty())throw new Fault(422,"本段旅程已收束，可查看经历或另开一局");
        Map<String,Map<String,Integer>> stock=copyStock(before.inventory());Map<String,String> flags=new LinkedHashMap<>(old.flags());Map<String,Integer> uses=new LinkedHashMap<>(old.uses());
        String place=old.place();int minimum=0;List<String> receipt=new ArrayList<>();
        for(Step step:d.steps()){
            if(step==null)throw new Fault(422,"行动记录不完整");
            if("MOVE".equals(step.kind())){
                Integer travel=p.places().get(place).exits().get(step.key());if(travel==null)throw new Fault(422,"需要从已知通路实际前往目的地");
                minimum+=travel;if(travel>0)p.ephemeralFlags().forEach(key->flags.put(key,p.flags().get(key).initial()));
                place=step.key();receipt.add("来到："+p.places().get(place).name());continue;
            }
            Operation op=p.operations().get(step.key());if(!"OP".equals(step.kind())||op==null)throw new Fault(422,"本次资源变化尚未在试玩场景中定义");
            if(!op.place().equals(place))throw new Fault(422,"需要先到达"+p.places().get(op.place()).name());
            if(uses.getOrDefault(step.key(),0)>=op.maxUses())throw new Fault(422,op.name()+"已经没有可用次数");
            for(var entry:op.requires().entrySet())if(!Objects.equals(flags.get(entry.getKey()),entry.getValue()))throw new Fault(422,op.name()+"的已确认条件尚未满足");
            minimum+=op.minutes();
            if(op.beforeMinute()>0 && before.minutes()+Math.max(minimum,d.minutes())>op.beforeMinute())throw new Fault(422,"约定的时间窗口已过，尚未提交本轮");
            for(Amount a:op.inputs()){
                int n=stock.get(a.holder()).getOrDefault(a.item(),0);if(n<a.amount())throw new Fault(422,p.items().get(a.item()).name()+"数量不足，进度未改变");stock.get(a.holder()).put(a.item(),n-a.amount());
            }
            for(Amount a:op.outputs())stock.get(a.holder()).merge(a.item(),a.amount(),Integer::sum);
            flags.putAll(op.changes());if(op.minutes()>0)p.ephemeralFlags().forEach(key->flags.put(key,p.flags().get(key).initial()));
            uses.merge(step.key(),1,Integer::sum);receipt.add(op.name());
            if(op.arrival()!=null){place=op.arrival();receipt.add("来到："+p.places().get(place).name());}
        }
        if(d.minutes()<minimum)throw new Fault(422,"本轮时间不足以完成实际加工与赶路");
        if((!d.steps().isEmpty()||d.minutes()>0||!condition.equals(before.condition()))&&d.events().isEmpty())throw new Fault(422,"实际变化需要留下经历");
        List<Fact> facts=new ArrayList<>(before.facts());for(EventNote e:d.events()){
            if(e==null||!Set.of("OBSERVED","STATEMENT","PLAN").contains(Objects.toString(e.kind(),"")))throw new Fault(422,"经历性质不明确");text(e.text(),500);facts.add(new Fact(before.revision()+1,e.kind(),e.text()));
        }
        d.suggestions().forEach(s->text(s,180));
        for(String item:p.items().keySet()){
            int a=before.inventory().get("PLAYER").getOrDefault(item,0),b=stock.get("PLAYER").getOrDefault(item,0);
            if(a!=b)receipt.add(p.items().get(item).name()+"："+a+" → "+b);
        }
        if(d.minutes()>0)receipt.add("经过 "+d.minutes()+" 分钟");if(request.mode().equals("ASK"))receipt.add("仅询问，时间与物资未变化");
        Map<String,Lore> lore=new LinkedHashMap<>(before.lore());out.accessed().forEach((key,l)->{if(lore.containsKey(key)&&!lore.get(key).hash().equals(l.hash()))throw new Fault(422,"资料快照发生冲突");lore.putIfAbsent(key,l);});
        List<Reading> readings=new ArrayList<>();out.accessed().values().forEach(l->readings.add(new Reading(l.key(),l.name(),l.hash(),l.resource(),l.pageUrl(),citations.contains(l.key()),"READ_NOW")));
        for(String key:citations)if(!out.accessed().containsKey(key)){Lore l=lore.get(key);readings.add(new Reading(l.key(),l.name(),l.hash(),l.resource(),l.pageUrl(),true,"REUSED"));}
        List<Turn> turns=new ArrayList<>(before.turns());turns.add(new Turn(before.revision()+1,request.requestId(),request.mode(),request.action(),d.narrative(),d.ruling(),readings,out.stats(),d.suggestions(),receipt));
        String goal=flags.get(p.endingFlag()).equals(p.flags().get(p.endingFlag()).initial())?"ONGOING":"DELIVERED";
        if(d.minutes()>0)p.ephemeralFlags().forEach(key->flags.put(key,p.flags().get(key).initial()));
        return new Save(before.id(),before.revision()+1,before.name(),before.scenarioBrief(),before.opening(),before.objective(),p.places().get(place).name(),condition,before.minutes()+d.minutes(),goal,0,stock,before.abilities(),facts,turns,lore,new State(p,place,flags,uses));
    }
    private static Map<String,Map<String,Integer>> copyStock(Map<String,Map<String,Integer>> source){Map<String,Map<String,Integer>> out=new LinkedHashMap<>();source.forEach((k,v)->out.put(k,new LinkedHashMap<>(v)));return out;}
    private static List<Amount> concat(List<Amount> a,List<Amount>b){List<Amount> out=new ArrayList<>(a);out.addAll(b);return out;}
    private static List<Map.Entry<String,String>> concatEntries(Map<String,String>a,Map<String,String>b){List<Map.Entry<String,String>> out=new ArrayList<>(a.entrySet());out.addAll(b.entrySet());return out;}
    private static void text(String text,int max){if(text==null||text.isBlank()||text.length()>max)throw new Fault(422,"回应内容为空或过长");}
}
