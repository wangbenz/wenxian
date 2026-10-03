package com.wenxian.demo.dm;

import static com.wenxian.demo.dm.Protocol.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/** 独立HTTP入口，只负责请求边界和静态页面；身份、幂等、生成、状态提交由试玩服务处理。 */
public final class DemoServer {
    private final DemoConfig config;private final DemoStore store;private final DemoService service;
    DemoServer(DemoConfig config,DemoStore store,DemoService service){this.config=config;this.store=store;this.service=service;}
    public static void main(String[] args) throws Exception {
        DemoConfig config=new DemoConfig();DemoStore store=new DemoStore(config.dataDir);
        LoreforgeClient lore=new LoreforgeClient(config.loreUrl);
        DemoService service=new DemoService(store,lore,new LangChainDm(config,store,lore));
        DemoServer handler=new DemoServer(config,store,service);
        List<HttpServer> servers=new ArrayList<>();
        for(String host:config.host.split(",")) {
            HttpServer server=HttpServer.create(new InetSocketAddress(host.trim(),config.port),32);
            server.createContext("/",handler::handle);server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());server.start();servers.add(server);
            System.out.println("DM demo: http://"+host.trim()+":"+config.port+"/ (model "+(config.ready()?"enabled":"disabled")+")");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(()->{servers.forEach(s->s.stop(0));store.close();}));
    }
    void handle(HttpExchange exchange) {
        try {
            headers(exchange);
            String requestHost=exchange.getRequestHeaders().getFirst("Host");
            boolean allowedHost=("localhost:"+config.port).equals(requestHost);
            for(String host:config.host.split(","))allowedHost|=(host.trim()+":"+config.port).equals(requestHost);
            if(!allowedHost)throw new Fault(403,"请使用本机或已指定的局域网地址访问");
            String method=exchange.getRequestMethod(),path=exchange.getRequestURI().getPath();
            if(method.equals("POST")) {
                String origin=exchange.getRequestHeaders().getFirst("Origin");
                if(origin!=null&&!origin.equals("http://"+exchange.getRequestHeaders().getFirst("Host")))throw new Fault(403,"请从试玩页面提交行动");
                if(!Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"),"").startsWith("application/json"))throw new Fault(415,"请使用试玩页面提交");
            }
            if(path.equals("/api/status")&&method.equals("GET")){send(exchange,200,new Status(config.ready(),!config.apiKey.isBlank(),Math.max(0,config.maxCalls-store.callCount())));return;}
            if(path.equals("/api/scenario")&&method.equals("GET")){
                var p=AdventureEngine.pack();send(exchange,200,Map.of("title",p.title(),"goal",p.goal(),"opening",p.opening(),"suggestions",p.suggestions(),"defaultScenario",p.id(),"availableScenarios",AdventureEngine.catalog()));return;
            }
            if(path.equals("/api/sessions")&&method.equals("POST")){
                byte[] body=exchange.getRequestBody().readNBytes(16001);if(body.length>16000)throw new Fault(413,"提交内容过长");
                var input=Json.tree(new String(body,StandardCharsets.UTF_8));Created created=service.create(input.path("scenario").asText(AdventureEngine.defaultId()));
                send(exchange,201,Map.of("id",created.id(),"token",created.token(),"save",visible(created.save())));return;
            }
            String[] parts=path.split("/");
            if(parts.length>=4&&parts[1].equals("api")&&parts[2].equals("sessions")){
                String id=parts[3],token=exchange.getRequestHeaders().getFirst("X-Demo-Token");
                if(parts.length==5&&parts[4].equals("inspection")&&method.equals("GET")){
                    if(!exchange.getRemoteAddress().getAddress().isLoopbackAddress())throw new Fault(403,"回放证据仅供本机检查");
                    send(exchange,200,store.inspectEvidence(id,token));return;
                }
                if(parts.length==4&&method.equals("GET")){send(exchange,200,visible(store.get(id,token)));return;}
                if(parts.length==6&&parts[4].equals("requests")&&method.equals("GET")){send(exchange,200,visible(store.inspect(id,token,parts[5])));return;}
                if(parts.length==5&&parts[4].equals("turns")&&method.equals("POST")){
                    byte[] bytes=exchange.getRequestBody().readNBytes(16001);if(bytes.length>16000)throw new Fault(413,"这次输入太长，请分成较短的行动");
                    var input=Json.tree(new String(bytes,StandardCharsets.UTF_8));
                    ActionRequest request=Json.read(input.path("request").toString(),ActionRequest.class);
                    Claim result=service.act(id,token,request,input.path("retry").asBoolean(false));
                    send(exchange,result.status().equals("RUNNING")?202:200,visible(result));return;
                }
                throw new Fault(405,"此入口不支持这项操作");
            }
            if(method.equals("GET")&&Set.of("/","/app.js","/style.css").contains(path)){
                String file=path.equals("/")?"index.html":path.substring(1);
                byte[] bytes=Json.resource("web/"+file).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",(file.endsWith(".js")?"application/javascript":file.endsWith(".css")?"text/css":"text/html")+"; charset=utf-8");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;
            }
            throw new Fault(404,"没有这个页面");
        }catch(Fault e){send(exchange,e.status,Map.of("message",e.getMessage()));}
        catch(IllegalArgumentException e){send(exchange,400,Map.of("message","提交内容无法识别，请刷新后重试"));}
        catch(Exception e){System.err.println("Request failed: "+e.getClass().getSimpleName());send(exchange,503,Map.of("message","试玩服务暂时不可用，已有存档会保留"));}
    }
    private static void headers(HttpExchange x){
        x.getResponseHeaders().set("Cache-Control","no-store");
        x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        x.getResponseHeaders().set("Referrer-Policy","no-referrer");
        x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'");
    }
    static Object visible(Save save){
        var value=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.valueToTree(save);value.remove("scenarioBrief");
        if(save.adventure()!=null){var a=save.adventure();var p=a.pack();List<Map<String,String>> notes=new ArrayList<>();
            p.flags().forEach((key,f)->{if(!p.ephemeralFlags().contains(key))notes.add(Map.of("name",f.name(),"value",a.flags().get(key)));});
            value.set("adventure",Json.MAPPER.valueToTree(Map.of("title",p.title(),"items",p.items(),"notes",notes,"ending",a.flags().get(p.endingFlag()),"suggestions",p.suggestions())));
        }
        return value;
    }
    static Object visible(Claim claim){Map<String,Object> value=new LinkedHashMap<>();value.put("status",claim.status());value.put("save",visible(claim.save()));value.put("message",claim.message());return value;}
    private static void send(HttpExchange x,int status,Object body){
        try {byte[] bytes=Json.write(body).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}catch(java.io.IOException ignored){}finally{x.close();}
    }
}
