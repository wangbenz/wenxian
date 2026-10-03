package com.wenxian.demo.dm;

import dev.langchain4j.model.output.structured.Description;
import java.util.*;

/** 独立试玩的类型协议。模型只能提交建议；数量、版本、来源与事务由服务校验后提交。 */
public final class Protocol {
    private Protocol() {}
    public record ActionRequest(String requestId, int expectedRevision, String mode, String action) {}
    public record Lore(String key, String name, String resource, String pageUrl, String text, String hash) {}
    public record Transfer(String item, String from, String to, int amount) {}
    public record EventNote(String kind, String text) {}
    public record Fact(int turn, String kind, String text) {}
    public record Stats(int requests, int inputTokens, int outputTokens, long milliseconds) {}
    public record Reading(String key, String name, String hash, String resource, String pageUrl, boolean used, String access) {}

    /** DM单次提议。自由叙事与有限物资操作分开；空操作表示没有变化。 */
    public record Decision(
        @Description("给玩家的中文叙事或规则答复，150至350字，不含内部实现、模型名或JSON") String narrative,
        @Description("简短判定依据，说明现场条件及查阅资料如何影响结果，不写隐含思维过程") String ruling,
        @Description("本轮实际引用的开物key；必须已通过read_reference读取") List<String> citations,
        @Description("本轮真正施放过的已掌握法术key，按能力去重列出；询问或施法未成立时为空") List<String> executedAbilities,
        @Description("实际经过分钟数。ASK必须0；只推进本次授权的时间") int minutes,
        @Description("新地点；不变或ASK时为空字符串") String location,
        @Description("身体和法力的简短状态；不变或ASK时为空字符串") String condition,
        @Description("ONGOING、DELIVERED、ABANDONED；ASK保持当前值") String goal,
        @Description("本轮真正剪取成熟植株的株数，每株得到一束纸料。未采收或ASK时为0") int harvested,
        @Description("因本轮明确发生的焚毁等后果而失去可采纸料的成熟株数；不是采收，不生成草料。没有损失或ASK时为0") int spoiled,
        @Description("采收所得直接放在哪里：PLAYER表示剪下并拿在手中/带在身上，GARDEN表示留在药畦或竹盘中。harvested>0时必填；0时为空字符串") String harvestDestination,
        @Description("明确发生的物资转移。item只能shears,note,protection,grass；位置PLAYER,GARDEN,SHOP,USED；amount正整数") List<Transfer> transfers,
        @Description("本轮新增记录。OBSERVED是发生或观察到的事实，STATEMENT记录谁声称什么，PLAN是计划，不可把说法计划当已证实结果；ASK为空") List<EventNote> events,
        @Description("2至3个可考虑的下一步，不代替玩家决定；自然收束时可为空") List<String> suggestions
    ) {}

    public record Turn(int number, String requestId, String mode, String action, String narrative,
                       String ruling, List<Reading> readings, Stats stats, List<String> suggestions, List<String> receipt) {}
    /** snapshot是服务器确认后的当前状态；turns和facts保留历史，模型不能替换旧记录。 */
    public record Save(String id, int revision, String name, String scenarioBrief, String opening, String objective, String location, String condition,
                       int minutes, String goal, int maturePlants, Map<String, Map<String, Integer>> inventory,
                       List<String> abilities, List<Fact> facts, List<Turn> turns, Map<String, Lore> lore,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) AdventureProtocol.State adventure) {
        public Save(String id,int revision,String name,String scenarioBrief,String opening,String objective,String location,String condition,
                    int minutes,String goal,int maturePlants,Map<String,Map<String,Integer>> inventory,List<String> abilities,List<Fact> facts,List<Turn> turns,Map<String,Lore> lore){
            this(id,revision,name,scenarioBrief,opening,objective,location,condition,minutes,goal,maturePlants,inventory,abilities,facts,turns,lore,null);
        }
    }
    public record Created(String id, String token, Save save) {}
    public record ModelResult(Decision decision, Map<String, Lore> accessed, List<String> newKeys, Stats stats) {}
    public record Claim(String status, Save save, String message) {}
    public record Status(boolean modelReady, boolean credentialConfigured, int remainingRequests) {}
}
