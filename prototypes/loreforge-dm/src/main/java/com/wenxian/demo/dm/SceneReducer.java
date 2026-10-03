package com.wenxian.demo.dm;

import static com.wenxian.demo.dm.Protocol.*;
import java.util.*;

/** 执行本demo已声明的有限库存与询问边界，不用关键词裁决开放剧情。任何校验失败都不改变旧快照。 */
final class SceneReducer {
    static final String GRASS="xianxia_plant-seven-star-grass", GARDEN="xianxia_place-herb-garden", PROTECTION="xianxia_talisman-protection";
    static final Set<String> ITEMS=Set.of("shears","note","protection","grass");
    static final Set<String> HOLDERS=Set.of("PLAYER","GARDEN","SHOP","USED");
    static Save initial(String id,Map<String,Lore> lore) {
        Map<String,Map<String,Integer>> stock=new LinkedHashMap<>();
        for(String holder:List.of("PLAYER","GARDEN","SHOP","USED")) {
            Map<String,Integer> items=new LinkedHashMap<>();for(String item:List.of("shears","note","protection","grass"))items.put(item,0);stock.put(holder,items);
        }
        stock.get("PLAYER").put("shears",1);stock.get("PLAYER").put("note",1);stock.get("PLAYER").put("protection",1);
        var scenario=Json.tree(Json.resource("scenario.json"));
        return new Save(id,0,"你",scenario.toString(),scenario.path("opening").asText(),scenario.path("goal").asText(),"药圃栅门外","身体安好，法力尚有余裕",0,"ONGOING",3,stock,
            List.of("xianxia_spell-object-control","xianxia_spell-heaven-eye","xianxia_spell-fire-bolt"),List.of(),List.of(),lore);
    }
    Save apply(Save before,ActionRequest request,ModelResult output) {
        Decision d=output.decision();
        if(d==null)throw new Fault(422,"DM没有给出可保存的结果");
        text(d.narrative(),1,3000,"DM正文不完整");text(d.ruling(),1,900,"DM需要补充判定说明");
        if(d.citations()==null||d.executedAbilities()==null||d.transfers()==null||d.events()==null||d.suggestions()==null)throw new Fault(422,"DM结果缺少必要项目");
        Set<String> previouslyRead=new HashSet<>();
        for(Turn turn:before.turns())for(Reading reading:turn.readings()){
            Lore pinned=before.lore().get(reading.key());
            if(pinned!=null&&pinned.hash().equals(reading.hash()))previouslyRead.add(reading.key());
        }
        Set<String> readable=new HashSet<>(previouslyRead);readable.addAll(output.accessed().keySet());
        if(d.citations().size()>8||!readable.containsAll(d.citations()))throw new Fault(422,"DM引用的资料尚未实际查阅，结果未保存");
        if(!before.abilities().containsAll(d.executedAbilities())||!d.citations().containsAll(d.executedAbilities()))throw new Fault(422,"所用能力与已学本领或查阅依据不一致，结果未保存");
        if(!Set.of("ONGOING","DELIVERED","ABANDONED").contains(d.goal()==null?"":d.goal()))throw new Fault(422,"DM需要明确当前目标的进展");
        if(!before.goal().equals("ONGOING")&&!d.goal().equals(before.goal()))throw new Fault(422,"已经收束的目标不能被重新开启");
        if(d.minutes()<0||d.minutes()>720||d.harvested()<0||d.spoiled()<0||(long)d.harvested()+d.spoiled()>before.maturePlants()||d.transfers().size()>12||d.events().size()>8||d.suggestions().size()>4)throw new Fault(422,"本次时间、物资或记录超出短篇试玩范围，结果未保存");
        String location=unchanged(d.location(),before.location(),120),condition=unchanged(d.condition(),before.condition(),180);
        if(request.mode().equals("ASK") && (d.minutes()!=0||d.harvested()!=0||d.spoiled()!=0||!d.transfers().isEmpty()||!d.executedAbilities().isEmpty()||!d.events().isEmpty()||!location.equals(before.location())||!condition.equals(before.condition())||!d.goal().equals(before.goal())))throw new Fault(422,"询问DM不能替角色采取行动，结果未保存");
        Map<String,Map<String,Integer>> stock=new LinkedHashMap<>();before.inventory().forEach((key,value)->stock.put(key,new LinkedHashMap<>(value)));
        if(d.harvested()>0){
            required(d,GRASS);required(d,GARDEN);
            if(!Set.of("PLAYER","GARDEN").contains(d.harvestDestination()==null?"":d.harvestDestination()))throw new Fault(422,"采收结果必须明确放在哪里，结果未保存");
            stock.get(d.harvestDestination()).merge("grass",d.harvested(),Integer::sum);
        }
        for(Transfer transfer:d.transfers()) {
            if(transfer==null||!ITEMS.contains(transfer.item())||!HOLDERS.contains(transfer.from())||!HOLDERS.contains(transfer.to())||transfer.from().equals("USED")||transfer.from().equals(transfer.to())||transfer.amount()<=0||transfer.amount()>99)throw new Fault(422,"本次物资去向无法确认，结果未保存");
            if(transfer.item().equals("protection")&&transfer.to().equals("USED"))required(d,PROTECTION);
            int available=stock.get(transfer.from()).get(transfer.item());
            if(available<transfer.amount())throw new Fault(422,"物资数量不足，结果未保存");
            stock.get(transfer.from()).put(transfer.item(),available-transfer.amount());
            stock.get(transfer.to()).merge(transfer.item(),transfer.amount(),Integer::sum);
        }
        if(d.goal().equals("DELIVERED")&&stock.get("SHOP").get("grass")<1)throw new Fault(422,"纸料尚未交到纸坊，不能记为完成");
        boolean change=d.minutes()!=0||d.harvested()!=0||d.spoiled()!=0||!d.transfers().isEmpty()||!location.equals(before.location())||!condition.equals(before.condition())||!d.goal().equals(before.goal());
        if(change&&d.events().isEmpty())throw new Fault(422,"实际变化需要留下对应事件，结果未保存");
        List<Fact> facts=new ArrayList<>(before.facts());
        for(EventNote event:d.events()) {
            if(event==null||!Set.of("OBSERVED","STATEMENT","PLAN").contains(event.kind()==null?"":event.kind()))throw new Fault(422,"新增经历的性质不明确");
            text(event.text(),1,500,"新增经历过长或为空");facts.add(new Fact(before.revision()+1,event.kind(),event.text()));
        }
        for(String suggestion:d.suggestions())text(suggestion,1,160,"下一步建议过长或为空");
        List<Reading> readings=new ArrayList<>();
        output.accessed().values().forEach(l->readings.add(new Reading(l.key(),l.name(),l.hash(),l.resource(),l.pageUrl(),d.citations().contains(l.key()),"READ_NOW")));
        for(String key:new LinkedHashSet<>(d.citations()))if(!output.accessed().containsKey(key)){
            Lore l=before.lore().get(key);readings.add(new Reading(l.key(),l.name(),l.hash(),l.resource(),l.pageUrl(),true,"REUSED"));
        }
        List<String> receipt=new ArrayList<>();
        Map<String,String> labels=Map.of("shears","剪草小剪","note","纸坊取料便条","protection","护身符","grass","七星草纸料");
        for(String item:List.of("shears","note","protection","grass")){
            int prior=before.inventory().get("PLAYER").get(item),now=stock.get("PLAYER").get(item);
            if(prior!=now)receipt.add(labels.get(item)+"："+prior+" → "+now);
        }
        if(d.harvested()+d.spoiled()>0)receipt.add("待采成熟草："+before.maturePlants()+" → "+(before.maturePlants()-d.harvested()-d.spoiled()));
        if(d.spoiled()>0)receipt.add("成熟草损失："+d.spoiled()+" 株，未生成纸料");
        if(d.minutes()>0)receipt.add("经过 "+d.minutes()+" 分钟");
        if(!location.equals(before.location()))receipt.add("来到："+location);
        if(!d.goal().equals(before.goal()))receipt.add(d.goal().equals("DELIVERED")?"委托已完成":"已放下本次委托");
        if(request.mode().equals("ASK"))receipt.add("仅询问，时间与物资未变化");
        List<Turn> turns=new ArrayList<>(before.turns());turns.add(new Turn(before.revision()+1,request.requestId(),request.mode(),request.action(),d.narrative(),d.ruling(),List.copyOf(readings),output.stats(),d.suggestions(),List.copyOf(receipt)));
        Map<String,Lore> lore=new LinkedHashMap<>(before.lore());
        output.accessed().forEach((key,value)->{
            if(lore.containsKey(key)&&!lore.get(key).hash().equals(value.hash()))throw new Fault(422,"本局资料快照发生冲突，结果未保存");
            lore.putIfAbsent(key,value);
        });
        return new Save(before.id(),before.revision()+1,before.name(),before.scenarioBrief(),before.opening(),before.objective(),location,condition,before.minutes()+d.minutes(),d.goal(),before.maturePlants()-d.harvested()-d.spoiled(),stock,before.abilities(),List.copyOf(facts),List.copyOf(turns),lore);
    }
    private static void required(Decision d,String key){if(!d.citations().contains(key))throw new Fault(422,"物资变化缺少对应开物依据，结果未保存");}
    private static void text(String value,int min,int max,String error){if(value==null||value.trim().length()<min||value.length()>max)throw new Fault(422,error);}
    private static String unchanged(String value,String prior,int limit){if(value==null||value.isBlank())return prior;text(value,1,limit,"当前状态描述过长");return value;}
}
