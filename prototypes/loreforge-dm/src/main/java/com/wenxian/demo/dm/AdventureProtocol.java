package com.wenxian.demo.dm;

import java.util.*;
import dev.langchain4j.model.output.structured.Description;
import static com.wenxian.demo.dm.Protocol.*;

/** 连续试玩内容包与提议协议。世界约定为冻结数据，提议只能引用已声明操作；不能改写包或旧事实。 */
public final class AdventureProtocol {
    private AdventureProtocol() {}
    public record Item(String name,String unit,String reference) {}
    public record Place(String name,String description,Map<String,Integer> exits) {}
    public record Flag(String name,List<String> values,String initial) {}
    public record Amount(String holder,String item,int amount) {}
    public record Operation(String name,String description,String place,int minutes,List<Amount> inputs,List<Amount> outputs,
                            Map<String,String> requires,Map<String,String> changes,List<String> references,int maxUses,int beforeMinute,String arrival) {}
    public record Pack(String format,String id,String title,String goal,String opening,String authorNotes,String startPlace,
                       List<String> suggestions,Map<String,Item> items,Map<String,Place> places,Map<String,Flag> flags,
                       Map<String,Map<String,Integer>> stock,List<String> abilities,List<Map<String,String>> references,
                       Map<String,Operation> operations,String endingFlag,List<String> ephemeralFlags) {}
    public record State(Pack pack,String place,Map<String,String> flags,Map<String,Integer> uses) {}
    public record Step(@Description("OP引用内容包操作；MOVE移动到可直接到达的地点") String kind,
                       @Description("OP为operation key；MOVE为目的地key。不得发明新key") String key) {}
    public record Proposal(
        @Description("150至350字中文回应；与实际变化一致，不替角色擅自做重大决定") String narrative,
        @Description("简短说明现场条件和开物依据，不显示代码、字段名或计算过程") String ruling,
        @Description("仅限本轮read_reference实际返回或previouslyReadKeys已列出的资料key。索引不算查阅；纯交谈可为空，禁止未查就引用") List<String> citations,
        @Description("本轮真正施放的已学法术key；仅讨论或未成立时为空") List<String> executedAbilities,
        @Description("按发生顺序列本轮OP/MOVE，不得替玩家添加未来计划。ASK必须空数组") List<Step> steps,
        @Description("本轮实际分钟数，至少覆盖全部操作和移动的时间下限；ASK为0") int minutes,
        @Description("仅在本轮新受伤、施法消耗或实际恢复时更新身体/法力状态；没变化或ASK必须空串。未施法不等于补回旧消耗，不写物品库存") String condition,
        @Description("新增经历，区分OBSERVED、STATEMENT、PLAN。ASK为空") List<EventNote> events,
        @Description("自然中文的下一步建议，可自由改写") List<String> suggestions) {}
    public record Outcome(Proposal proposal,Map<String,Lore> accessed,Stats stats) {}
}
