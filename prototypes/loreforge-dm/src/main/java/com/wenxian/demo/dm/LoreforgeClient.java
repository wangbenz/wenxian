package com.wenxian.demo.dm;

import static com.wenxian.demo.dm.Protocol.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 只读开物适配器。资源与路径受限，网络失败显式返回，不用本地假卡替代缺失内容。 */
final class LoreforgeClient {
    static final Set<String> RESOURCES=Set.of("spells","items","magicitems","rules");
    private final String base;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    LoreforgeClient(String base){this.base=base.replaceAll("/+$","");}
    Lore read(String resource,String key) {
        if(!RESOURCES.contains(resource) || !key.matches("[a-z0-9_-]{1,100}")) throw new Fault(400,"无法识别这份资料");
        var node=get("/v2/"+resource+"/"+key+"/");
        if(!node.path("key").asText().equals(key) || !node.path("document").path("key").asText().equals("xianxia-core"))throw new Fault(422,"资料来源与当前试玩不一致");
        String text=node.path("desc").asText();
        if(text.isBlank()||text.length()>16000)throw new Fault(422,"这份资料当前无法用于试玩");
        String page=switch(resource){case "spells"->"/spells/";case "items"->"/equipment/";case "magicitems"->"/magic-items/";default->"/rules/entries/";};
        return new Lore(key,node.path("name").asText(),resource,page+key,text,DemoStore.hash(node.path("name").asText()+"\n"+text));
    }
    String search(String name) {
        if(name==null||name.isBlank()||name.length()>50)throw new Fault(400,"请使用简短的资料名称");
        return get("/v2/catalog/?document__key__in=xianxia-core&limit=5&name__icontains="+URLEncoder.encode(name,StandardCharsets.UTF_8)).toString();
    }
    Map<String,Lore> initial() {
        Map<String,Lore> result=new LinkedHashMap<>();
        for(var row:Json.tree(Json.resource("scenario.json")).path("references")) {
            var doc=read(row.path("resource").asText(),row.path("key").asText());result.put(doc.key(),doc);
        }
        return result;
    }
    Map<String,Lore> initial(AdventureProtocol.Pack pack){
        Map<String,Lore> result=new LinkedHashMap<>();
        for(var reference:pack.references()){Lore doc=read(reference.get("resource"),reference.get("key"));result.put(doc.key(),doc);}
        return result;
    }
    private com.fasterxml.jackson.databind.JsonNode get(String path) {
        try {
            var response=client.send(HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10)).header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200 || response.body().length()>200000)throw new Fault(503,"开物资料暂时无法读取，请确认资料服务已启动");
            return Json.tree(response.body());
        }catch(Fault e){throw e;}catch(Exception e){throw new Fault(503,"开物资料暂时无法读取，请稍后重试");}
    }
}
