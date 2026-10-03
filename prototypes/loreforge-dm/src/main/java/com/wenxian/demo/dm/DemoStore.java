package com.wenxian.demo.dm;

import static com.wenxian.demo.dm.Protocol.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.util.*;

/** 独立H2的存档、幂等和预算仓储。不裁定开放剧情；快照和正式回应原子提交，异常保留旧事实。 */
final class DemoStore implements AutoCloseable {
    private final String jdbc;
    DemoStore(Path directory) {
        try {
            Files.createDirectories(directory);
            jdbc="jdbc:h2:file:"+directory.resolve("dm-demo").toAbsolutePath()+";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
            try (Connection c=open()) {
                boolean exists;
                try (var tables=c.getMetaData().getTables(null,null,"DEMO_META",null)) { exists=tables.next(); }
                if (!exists) {
                    try (var statement=c.createStatement()) { for (String sql:Json.resource("schema.sql").split(";")) if (!sql.isBlank()) statement.execute(sql); }
                }
                try(var rs=c.createStatement().executeQuery("SELECT schema_version FROM demo_meta")) {
                    if (!rs.next() || rs.getInt(1)!=1) throw new IllegalStateException("此试玩存档版本暂不支持，需要显式迁移");
                }
                // 单实例持有H2文件锁；启动时已无旧调用可继续执行，保留未提交请求并允许显式重试。
                c.createStatement().executeUpdate("UPDATE demo_turns SET status='FAILED', failure_message='上次生成被中断，存档未改变，可重试本次行动' WHERE status='RUNNING'");
                c.createStatement().executeUpdate("UPDATE demo_model_calls SET status='INTERRUPTED' WHERE status='RUNNING'");
            }
        } catch (SQLException|java.io.IOException e) { throw new IllegalStateException("独立试玩存档无法打开",e); }
    }
    private Connection open() throws SQLException { return DriverManager.getConnection(jdbc,"sa",""); }
    static String hash(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch(Exception e){throw new IllegalStateException(e);} }
    static String token() { byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private Save owned(Connection c,String id,String token,boolean lock) throws SQLException {
        try(var q=c.prepareStatement("SELECT token_hash,snapshot FROM demo_saves WHERE id=?"+(lock?" FOR UPDATE":""))) {
            q.setString(1,id); try(var rs=q.executeQuery()) {
                if (!rs.next() || token==null || !MessageDigest.isEqual(rs.getString(1).getBytes(StandardCharsets.UTF_8),hash(token).getBytes(StandardCharsets.UTF_8))) throw new Fault(404,"没有找到这份试玩存档");
                return Json.read(rs.getString(2),Save.class);
            }
        }
    }
    void create(Save save,String token) {
        try(var c=open();var q=c.prepareStatement("INSERT INTO demo_saves(id,token_hash,revision,snapshot) VALUES(?,?,?,?)")) {
            q.setString(1,save.id());q.setString(2,hash(token));q.setInt(3,save.revision());q.setString(4,Json.write(save));q.executeUpdate();
        } catch(SQLException e){throw new IllegalStateException("保存开场失败",e);}
    }
    Save get(String id,String token) {try(var c=open()){return owned(c,id,token,false);}catch(SQLException e){throw new IllegalStateException(e);}}
    Claim inspect(String id,String token,String requestId) {
        try(var c=open()) { Save current=owned(c,id,token,false); return request(c,id,requestId,current); } catch(SQLException e){throw new IllegalStateException(e);}
    }
    private Claim request(Connection c,String id,String requestId,Save current) throws SQLException {
        try(var q=c.prepareStatement("SELECT status,response_json,failure_message FROM demo_turns WHERE save_id=? AND request_id=?")) {
            q.setString(1,id);q.setString(2,requestId);try(var rs=q.executeQuery()) {
                if(!rs.next()) return new Claim("MISSING",current,"尚未收到本次行动");
                return new Claim(rs.getString(1),rs.getString(2)==null?current:Json.read(rs.getString(2),Save.class),rs.getString(3));
            }
        }
    }
    Claim begin(String id,String token,ActionRequest req,boolean retry) {
        String fingerprint=hash(Json.write(req));
        try(var c=open()) {
            c.setAutoCommit(false);
            try {
                Save save=owned(c,id,token,true);
                try(var q=c.prepareStatement("SELECT fingerprint,status FROM demo_turns WHERE save_id=? AND request_id=?")) {
                    q.setString(1,id);q.setString(2,req.requestId());try(var rs=q.executeQuery()) {
                        if(rs.next()) {
                            if(!rs.getString(1).equals(fingerprint)) throw new Fault(409,"同一次请求不能换成另一项行动");
                            if(!rs.getString(2).equals("FAILED") || !retry) { Claim existing=request(c,id,req.requestId(),save);c.commit();return existing; }
                        }
                    }
                }
                if(save.revision()!=req.expectedRevision()) throw new Fault(409,"存档已有新进展，请刷新后再行动");
                try(var q=c.prepareStatement("SELECT COUNT(*) FROM demo_turns WHERE save_id=? AND status='RUNNING'")) {
                    q.setString(1,id);try(var rs=q.executeQuery()){rs.next();if(rs.getInt(1)>0)throw new Fault(409,"DM正在处理上一项行动，请先等它结束");}
                }
                try(var q=c.prepareStatement("MERGE INTO demo_turns(save_id,request_id,fingerprint,request_json,status) KEY(save_id,request_id) VALUES(?,?,?,?, 'RUNNING')")) {
                    q.setString(1,id);q.setString(2,req.requestId());q.setString(3,fingerprint);q.setString(4,Json.write(req));q.executeUpdate();
                }
                c.commit();return new Claim("STARTED",save,null);
            }catch(Exception e){c.rollback();throw e;}
        }catch(SQLException e){throw new IllegalStateException("无法登记本次行动",e);}
    }
    void commit(String id,ActionRequest req,Save after,Object diagnostic) {
        try(var c=open()) {
            c.setAutoCommit(false);
            try {
                try(var q=c.prepareStatement("UPDATE demo_saves SET revision=?,snapshot=? WHERE id=? AND revision=?")) {
                    q.setInt(1,after.revision());q.setString(2,Json.write(after));q.setString(3,id);q.setInt(4,req.expectedRevision());
                    if(q.executeUpdate()!=1)throw new Fault(409,"存档已有新进展，本次结果未提交");
                }
                try(var q=c.prepareStatement("UPDATE demo_turns SET status='COMMITTED',response_json=?,diagnostic_json=?,failure_message=NULL WHERE save_id=? AND request_id=? AND status='RUNNING'")) {
                    q.setString(1,Json.write(after));q.setString(2,Json.write(diagnostic));q.setString(3,id);q.setString(4,req.requestId());
                    if(q.executeUpdate()!=1)throw new Fault(409,"本次请求状态已变化，结果未提交");
                }
                c.commit();
            } catch(Exception e){c.rollback();throw e;}
        }catch(SQLException e){throw new IllegalStateException("结果尚未保存",e);}
    }
    void fail(String id,String requestId,String message,Object diagnostic) {
        try(var c=open();var q=c.prepareStatement("UPDATE demo_turns SET status='FAILED',failure_message=?,diagnostic_json=? WHERE save_id=? AND request_id=? AND status='RUNNING'")) {
            q.setString(1,message);q.setString(2,Json.write(diagnostic));q.setString(3,id);q.setString(4,requestId);q.executeUpdate();
        }catch(SQLException e){throw new IllegalStateException(e);}
    }
    synchronized String reserveCall(String requestId,String trace,int limit) {
        return reserveCall(requestId,trace,limit,30000);
    }
    synchronized String reserveCall(String requestId,String trace,int limit,int maxCharacters) {
        if(trace.length()>maxCharacters) throw new Fault(422,"当前试玩材料过长，请保留本局并另开一局");
        try(var c=open()) {
            try(var rs=c.createStatement().executeQuery("SELECT COUNT(*) FROM demo_model_calls")){rs.next();if(rs.getInt(1)>=limit)throw new Fault(429,"本次模型测试额度已用完，存档已保留");}
            String id=UUID.randomUUID().toString();
            try(var q=c.prepareStatement("INSERT INTO demo_model_calls(id,request_id,request_trace,status) VALUES(?,?,?,'RUNNING')")){q.setString(1,id);q.setString(2,requestId);q.setString(3,trace);q.executeUpdate();}
            return id;
        }catch(SQLException e){throw new IllegalStateException(e);}
    }
    void finishCall(String id,String response,Integer input,Integer output,long duration,String status) {
        try(var c=open();var q=c.prepareStatement("UPDATE demo_model_calls SET response_trace=?,input_tokens=?,output_tokens=?,duration_ms=?,status=? WHERE id=?")) {
            q.setString(1,response);q.setObject(2,input);q.setObject(3,output);q.setLong(4,duration);q.setString(5,status);q.setString(6,id);q.executeUpdate();
        }catch(SQLException e){throw new IllegalStateException(e);}
    }
    int callCount() {try(var c=open();var rs=c.createStatement().executeQuery("SELECT COUNT(*) FROM demo_model_calls")){rs.next();return rs.getInt(1);}catch(SQLException e){throw new IllegalStateException(e);}}
    /** 供本机受控回放导出证据，仍校验同一存档凭据，不返回认证配置。 */
    Map<String,Object> inspectEvidence(String id,String token){
        try(var c=open()){
            owned(c,id,token,false);List<Map<String,Object>> turns=new ArrayList<>(),calls=new ArrayList<>();
            try(var q=c.prepareStatement("SELECT request_id,status,request_json,diagnostic_json,failure_message FROM demo_turns WHERE save_id=? ORDER BY created_at")){
                q.setString(1,id);try(var rs=q.executeQuery()){while(rs.next()){
                    Map<String,Object> row=new LinkedHashMap<>();row.put("requestId",rs.getString(1));row.put("status",rs.getString(2));row.put("request",Json.tree(rs.getString(3)));row.put("diagnostic",rs.getString(4)==null?null:Json.tree(rs.getString(4)));row.put("failure",rs.getString(5));turns.add(row);
                }}
            }
            try(var q=c.prepareStatement("SELECT m.request_id,m.status,m.input_tokens,m.output_tokens,m.duration_ms,m.request_trace,m.response_trace FROM demo_model_calls m JOIN demo_turns t ON t.request_id=m.request_id WHERE t.save_id=? AND NOT EXISTS (SELECT 1 FROM demo_turns other_turn WHERE other_turn.request_id=m.request_id AND other_turn.save_id<>t.save_id) ORDER BY m.created_at")){
                q.setString(1,id);try(var rs=q.executeQuery()){while(rs.next()){
                    Map<String,Object> row=new LinkedHashMap<>();row.put("requestId",rs.getString(1));row.put("status",rs.getString(2));row.put("inputTokens",rs.getObject(3));row.put("outputTokens",rs.getObject(4));row.put("milliseconds",rs.getObject(5));row.put("request",Json.tree(rs.getString(6)));row.put("response",rs.getString(7)==null?null:Json.tree(rs.getString(7)));calls.add(row);
                }}
            }
            return Map.of("turns",turns,"calls",calls);
        }catch(SQLException e){throw new IllegalStateException("回放证据暂时无法读取",e);}
    }
    public void close(){try(var c=open()){c.createStatement().execute("SHUTDOWN");}catch(SQLException ignored){}}
}
