package com.wenxian.demo.dm;

import static com.wenxian.demo.dm.Protocol.*;
import java.util.*;

/** 组织真实查阅、DM调用、校验和事务提交；模型在事务外运行，失败只登记请求，不扣物资或推进剧情。 */
final class DemoService {
    private final DemoStore store;private final LoreforgeClient lore;private final DmGateway dm;private final SceneReducer reducer=new SceneReducer();
    DemoService(DemoStore store,LoreforgeClient lore,DmGateway dm){this.store=store;this.lore=lore;this.dm=dm;}
    Created create(){String id=UUID.randomUUID().toString(),token=DemoStore.token();Save save=SceneReducer.initial(id,lore.initial());store.create(save,token);return new Created(id,token,save);}
    Created create(String scenario){
        if("legacy".equals(scenario))return create();
        var pack=AdventureEngine.load(scenario);String id=UUID.randomUUID().toString(),token=DemoStore.token();
        Save save=AdventureEngine.initial(id,pack,lore.initial(pack));store.create(save,token);return new Created(id,token,save);
    }
    Claim act(String id,String token,ActionRequest request,boolean retry){
        try{UUID.fromString(request.requestId());}catch(Exception e){throw new Fault(400,"无法识别本次行动编号");}
        if(request.action()==null||request.action().isBlank()||request.action().length()>1200||!Set.of("ASK","ACT").contains(request.mode()==null?"":request.mode()))throw new Fault(400,"请填写1200字以内的行动或问题");
        Claim claim=store.begin(id,token,request,retry);if(!claim.status().equals("STARTED"))return claim;
        Object result=null;
        try {
            Save after;
            if(claim.save().adventure()==null){var generated=dm.play(claim.save(),request);result=generated;after=reducer.apply(claim.save(),request,generated);}
            else{var generated=dm.playAdventure(claim.save(),request);result=generated;after=new AdventureEngine().apply(claim.save(),request,generated);}
            store.commit(id,request,after,result);return new Claim("COMMITTED",after,null);
        }catch(Exception e){
            String message=e instanceof Fault?e.getMessage():"DM暂时未能给出可保存的结果，存档未改变，可重试本次行动";
            Map<String,Object> diagnostic=new LinkedHashMap<>();diagnostic.put("errorClass",e.getClass().getSimpleName());diagnostic.put("candidate",result);
            store.fail(id,request.requestId(),message,diagnostic);return new Claim("FAILED",claim.save(),message);
        }
    }
}
