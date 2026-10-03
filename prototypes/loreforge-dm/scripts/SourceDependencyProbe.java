package com.wenxian.demo.dm;

import static com.wenxian.demo.dm.Protocol.*;
import java.nio.file.*;
import java.util.*;

/** 仅供本机受控回放。停HTTP服务后运行，沿用同一累计预算；变更仅在明确标注的测试存档快照，不写开物。 */
class SourceDependencyProbe {
    public static void main(String[] args) throws Exception {
        DemoConfig config=new DemoConfig();if(!config.ready())throw new IllegalStateException("须显式启用真实回放");
        try(DemoStore store=new DemoStore(config.dataDir)){
            LoreforgeClient client=new LoreforgeClient(config.loreUrl);var pack=AdventureEngine.pack();Map<String,Lore> actual=client.initial(pack);
            String key="xianxia_spell-object-control";Lore original=actual.get(key);
            List<Object> evidence=new ArrayList<>();
            for(boolean shortened:List.of(false,true)){
                Map<String,Lore> docs=new LinkedHashMap<>(actual);
                if(shortened){
                    if(!original.text().contains("五步"))throw new IllegalStateException("资料已变化，不能继续旧对照");
                    String text="【本机来源依赖试验快照，非开物正式条目】\n"+original.text().replace("五步","两步");
                    docs.put(key,new Lore(key,original.name(),original.resource(),original.pageUrl(),text,DemoStore.hash(original.name()+"\n"+text)));
                }
                String token=DemoStore.token(),id=UUID.randomUUID().toString();store.create(AdventureEngine.initial(id,pack,docs),token);
                ActionRequest req=new ActionRequest(UUID.randomUUID().toString(),0,"ASK","我保持原地，能用控物术提起三步外那枚落地木塞吗？请依据本局实际查到的定义说明距离是否满足，暂不行动。");
                Claim result=new DemoService(store,client,new LangChainDm(config,store,client)).act(id,token,req,false);
                if(!result.status().equals("COMMITTED"))throw new IllegalStateException("来源对照停止："+result.message());
                Turn turn=result.save().turns().getLast();
                if(turn.readings().stream().noneMatch(r->r.key().equals(key)&&r.hash().equals(docs.get(key).hash())))throw new IllegalStateException("没有读到本局对照定义");
                evidence.add(Map.of("variant",shortened?"仅测试距离两步":"开物原定义五步","turn",turn,"inspection",store.inspectEvidence(id,token)));
                System.out.println((shortened?"TWO STEPS: ":"FIVE STEPS: ")+turn.narrative());
            }
            Path output=config.dataDir.resolve("evidence/source-dependency-"+System.currentTimeMillis()+".json");Files.createDirectories(output.getParent());Files.writeString(output,Json.write(evidence));
            System.out.println("Evidence: "+output+"; calls="+store.callCount());
        }
    }
}
