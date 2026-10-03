package com.wenxian.demo.dm;

import static com.wenxian.demo.dm.Protocol.*;
import java.nio.file.*;
import java.util.*;

/** 用受控回放第5轮的完整快照创建测试副本，只重问同一问题；原局、错误文字和累计预算不重置。 */
class ClockRecoveryProbe {
    public static void main(String[] args) throws Exception {
        Path folder=Path.of(args[0]);var access=Json.tree(Files.readString(folder.resolve("access.json")));
        var replay=Json.tree(Files.readString(folder.resolve("replay.json")));DemoConfig config=new DemoConfig();
        try(DemoStore store=new DemoStore(config.dataDir)){
            Save source=store.inspect(access.path("id").asText(),access.path("token").asText(),replay.get(4).path("request").path("requestId").asText()).save();
            String id=UUID.randomUUID().toString(),token=DemoStore.token();
            Save copy=new Save(id,source.revision(),source.name(),source.scenarioBrief(),source.opening(),source.objective(),source.location(),source.condition(),source.minutes(),source.goal(),source.maturePlants(),source.inventory(),source.abilities(),source.facts(),source.turns(),source.lore(),source.adventure());
            store.create(copy,token);LoreforgeClient client=new LoreforgeClient(config.loreUrl);
            ActionRequest request=new ActionRequest(UUID.randomUUID().toString(),copy.revision(),"ASK",replay.get(5).path("request").path("action").asText());
            Claim result=new DemoService(store,client,new LangChainDm(config,store,client)).act(id,token,request,false);
            if(!result.status().equals("COMMITTED"))throw new IllegalStateException("重问失败："+result.message());
            Save after=result.save();if(after.minutes()!=source.minutes()||!after.inventory().equals(source.inventory())||!after.facts().equals(source.facts()))throw new IllegalStateException("询问改变了状态");
            var evidence=Map.of("sourceFolder",folder.toString(),"sourceMinutes",source.minutes(),"originalAnswer",replay.get(5).path("result").path("save").path("turns").get(5),"answer",after.turns().getLast(),"inspection",store.inspectEvidence(id,token));
            Path target=config.dataDir.resolve("evidence/clock-recovery-"+System.currentTimeMillis()+".json");Files.writeString(target,Json.write(evidence));
            System.out.println("CONFIRMED MINUTES: "+source.minutes());System.out.println(after.turns().getLast().narrative());System.out.println("Evidence: "+target+"; calls="+store.callCount());
        }
    }
}
