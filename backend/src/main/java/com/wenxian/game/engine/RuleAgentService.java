package com.wenxian.game.engine;

import com.wenxian.game.model.GameState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.wenxian.game.engine.AgentResult.EntityPatch;
import static com.wenxian.game.engine.AgentResult.EventDraft;

@Service
public class RuleAgentService {
    public AgentResult resolve(String rawActionId, String rawFreeText, GameState state) {
        String actionId = rawActionId == null ? "" : rawActionId;
        String input = rawFreeText == null ? "" : rawFreeText.trim();

        if (!input.isEmpty() && containsAny(input, "回家", "妹妹", "小禾", "照看")) {
            return result("home", 46,
                    List.of("你避开东街的赌坊打手，从晒谷场后的水沟绕回西屋。", "小禾烧得意识模糊，手里却还攥着你早晨留下的半块饼。", "灶边只剩最后一包旧药，屋外有人停下脚步，又慢慢走远。"),
                    List.of("妹妹病情恶化", "门外出现陌生脚步"),
                    Map.of("vitality", Math.max(52, state.player.vitality - 4)),
                    event("赶回西屋", "你暂时放弃药商线索，先确认妹妹的情况。", "decision"),
                    patch("lin-xiaohe", "state", "高热昏沉，仍有意识", "location", "柳溪村西屋"),
                    patch("medicine", "state", "今夜必须找到药", "danger", 84));
        }

        if (!input.isEmpty()) {
            if (containsAny(input, "北山", "父亲", "灰印", "车辙")) actionId = "trade-intel";
            else if (containsAny(input, "赊", "求药", "买药", "赤心草")) actionId = "ask-credit";
            else if (containsAny(input, "逃", "离开", "躲", "绕路")) actionId = state.sceneId.equals("shrine") ? "retreat" : "leave";
            else if (containsAny(input, "义庄", "药匣", "试药")) actionId = "accept-job";
            else return result(state.sceneId, 18,
                        List.of("你没有照任何人的安排，而是选择：" + input + "。", "这个举动没有立刻换来结果，却让暗处观察你的人改变了判断。", "许百川的手停在药箱上，第一次认真打量你。"),
                        List.of("自由行动已记录", "许百川态度变化"),
                        Map.of("insight", Math.min(10, state.player.insight + 1)),
                        event("偏离预设道路", input, "decision"),
                        patch("xu-baichuan", "state", "重新评估你", "relation", "谨慎关注"), null);
        }

        return switch (actionId) {
            case "ask-credit" -> result("bargain", 24,
                    List.of("你没有跪，只把家里仅剩的二十三文整齐排在石阶上。", "许百川说药可以赊，但今夜你必须替他去城南义庄取一只无名药匣。", "他先给你一片赤心草叶——只能把小禾的命拖到明早。"),
                    List.of("获得赤心草叶", "新增：义庄取匣"), Map.of("suspicion", state.player.suspicion + 6),
                    event("以命赊药", "许百川提出用城南义庄的无名药匣换半株赤心草。", "decision"),
                    patch("xu-baichuan", "state", "等待你的答复", "relation", "危险交易"), patch("medicine", "state", "得到一片药叶", "danger", 66));
            case "trade-intel" -> result("mountain", 31,
                    List.of("你在石阶上画出北山旧车辙的走向，又将父亲遗物上的灰印露出一角。", "许百川脸上的笑意消失了。他认得这个印记，却先问你还对谁说过。", "赌坊的脚步声已到了巷口。药商递来一张旧图，要你边走边谈。"),
                    List.of("父亲线索推进", "许百川隐瞒旧事"), Map.of("insight", Math.min(10, state.player.insight + 1), "suspicion", state.player.suspicion + 4),
                    event("亮出北山灰印", "许百川确认灰印与一条被封死的旧采药道有关。", "gain"),
                    patch("xu-baichuan", "state", "认出灰印，明显不安", "relation", "利益相连"), patch("mountain", "state", "旧采药道浮现", "danger", 59));
            case "leave", "walk-away" -> result("home", 52,
                    List.of("你把二十三文重新收回袖中，没有回头。", "回村的路上，赌坊的人在桥头盘问行人。你从水渠里绕了过去，鞋里灌满冷泥。", "推开西屋时，小禾的咳声已经轻得像从墙后传来。"),
                    List.of("暂时甩开赌坊", "妹妹病情恶化"), Map.of("vitality", Math.max(40, state.player.vitality - 9), "suspicion", Math.max(20, state.player.suspicion - 8)),
                    event("放弃眼前药契", "你选择先保住自己，带着空手回到西屋。", "risk"),
                    patch("lin-xiaohe", "state", "高热加重，只能撑到明夜"), patch("medicine", "state", "只剩一夜", "danger", 89));
            case "accept-job" -> result("shrine", 210,
                    List.of("入夜后，你借着送葬队留下的白灯笼摸进城南义庄。", "许百川说的药匣就在第三口空棺里，匣面没有锁，却从里面传出三下敲击。", "门外同时响起赌坊管事周六的声音：他似乎一路跟了过来。"),
                    List.of("抵达城南义庄", "药匣内有异常"), Map.of("vitality", Math.max(35, state.player.vitality - 12), "suspicion", state.player.suspicion + 9),
                    event("夜入义庄", "你接受许百川的条件，在第三口空棺中找到无名药匣。", "risk"),
                    patch("zhou-liu", "state", "已追到义庄外", "location", "城南义庄"), patch("gambling", "state", "周六已追至义庄", "danger", 83));
            case "press-terms" -> result("bargain", 17,
                    List.of("你指出义庄取匣的风险远不止半株药。许百川没有反驳，只把一片赤心草叶推到你面前。", "叶片能暂缓寒症，却不能救命。真正的半株药仍压在他掌下。", "巷口的叫喊声停了。赌坊的人开始逐户搜门。"),
                    List.of("获得赤心草叶", "赌坊开始封巷"), Map.of("insight", Math.min(10, state.player.insight + 1), "suspicion", state.player.suspicion + 8),
                    event("从药商手中压下一叶", "你没有接受原条件，先换到能拖延病情的药叶。", "gain"), patch("xu-baichuan", "state", "认可你的胆量", "relation", "有限信任"), null);
            case "return-alley" -> resolve("ask-credit", "", state);
            case "use-medicine" -> result("home", 55,
                    List.of("你把最后一包旧药分成两次煎。小禾的呼吸暂时稳了下来。", "药渣里却浮出一缕不该出现的灰丝，与你父亲遗物上的印记一模一样。", "这场寒症也许从来不是普通的病。"),
                    List.of("病情暂稳半日", "发现灰丝"), Map.of("insight", Math.min(10, state.player.insight + 1)),
                    event("旧药浮灰", "妹妹的药渣出现与北山灰印相同的异常。", "gain"), patch("lin-xiaohe", "state", "高热暂退，药渣出现灰丝"), patch("mountain", "state", "灰印与寒症相连", "danger", 68));
            case "seek-doctor" -> result("home", 74,
                    List.of("陈郎中收下十二文，却只在门口看了一眼便摇头。", "他说这是阴寒入骨，普通药石无用；话说完，他又像怕沾上什么一样匆匆离开。", "你听见他在院外对学徒提到两个字：北山。"),
                    List.of("花费十二文", "郎中提到北山"), Map.of("silver", Math.max(0, state.player.silver - 12), "insight", Math.min(10, state.player.insight + 1)),
                    event("陈郎中拒诊", "十二文只换来一句“阴寒入骨”和一个北山线索。", "world"), null, patch("mountain", "state", "多方线索指向北山", "danger", 63));
            case "follow-mark" -> result("shrine", 186,
                    List.of("旧图上的灰线没有通向北山，反而先指向城南义庄。", "你在第三口空棺下找到与父亲衣角相同的针脚，旁边压着一只无名药匣。", "匣中响了三下。你手背的寒意也跟着跳了三次。"),
                    List.of("父亲遗物出现", "无名药匣回应你"), Map.of("vitality", Math.max(36, state.player.vitality - 11), "insight", 10),
                    event("灰线指向义庄", "北山旧图与城南义庄意外相连。", "gain"), null, patch("mountain", "state", "父亲曾到过义庄", "danger", 77));
            case "sell-map" -> result("home", 63,
                    List.of("你把旧图卖给许百川，换到半株赤心草和十五文。", "他答应不再找你，却在离开前烧掉了图的一角。那一角正是通往北山深处的路。", "药能救小禾一次，父亲的线索却从此落进别人手里。"),
                    List.of("获得半株赤心草", "北山旧图失去"), Map.of("silver", state.player.silver + 15),
                    event("以旧图换命", "你卖掉父亲留下的路线，换回妹妹的药。", "decision"), patch("xu-baichuan", "state", "携旧图离镇", "location", "北门外", "relation", "交易完成"), patch("medicine", "state", "药已到手", "danger", 24));
            case "hide-clue" -> result("alley", 21,
                    List.of("你将旧图收回怀中。许百川没有阻拦，只提醒你赌坊的人已经堵住两端巷口。", "他从后墙翻走，连药箱都没有合上。", "你现在必须在保住线索和保住命之间做出选择。"),
                    List.of("保留北山旧图", "后巷已被封锁"), Map.of("suspicion", state.player.suspicion + 12),
                    event("收回北山秘密", "你拒绝让药商掌握父亲的全部线索。", "decision"), patch("xu-baichuan", "state", "翻墙离去", "location", "未知", "relation", "互相试探"), null);
            case "open-box" -> result("shrine", 9,
                    List.of("你掀开药匣。里面没有药，只有一枚浸在黑水里的灰色骨片。", "骨片碰到你掌心的一瞬，你看见了北山地下的一扇门，也看见父亲在门后敲了三下。", "再睁眼时，周六已经站在棺外。他没有看你，只盯着你掌中的骨片。"),
                    List.of("获得：灰骨残片", "灵根异象初现"), Map.of("vitality", Math.max(20, state.player.vitality - 18), "insight", 10, "suspicion", 100),
                    event("灰骨认主", "无名药匣中的骨片回应了你的血脉。", "gain"), null, patch("mountain", "state", "地下石门已显现", "danger", 96));
            case "take-token" -> result("alley", 38,
                    List.of("你没有碰药匣，只取走压在棺脚下的义庄木牌。", "许百川见到木牌后沉默很久，最终交出半株赤心草。", "他只说了一句：你父亲当年也做过同样的选择。"),
                    List.of("获得半株赤心草", "父亲旧事推进"), Map.of("vitality", Math.max(28, state.player.vitality - 6)),
                    event("用木牌换药", "你避开药匣，以义庄证物完成交易。", "decision"), patch("xu-baichuan", "state", "交出赤心草，准备离镇", "relation", "欠你一个答案"), patch("medicine", "state", "药已到手", "danger", 22));
            case "retreat" -> result("home", 67,
                    List.of("你从义庄后窗滚进荒草沟，没带走药匣，也没带走木牌。", "身后的敲击声追了很久，直到村口才彻底消失。", "你保住了命，却只剩最后一夜寻找妹妹的药。"),
                    List.of("逃离义庄", "药契失败"), Map.of("vitality", Math.max(24, state.player.vitality - 13), "suspicion", Math.max(25, state.player.suspicion - 18)),
                    event("从义庄退走", "你拒绝以未知之物换取眼前力量。", "decision"), null, patch("medicine", "state", "只剩最后一夜", "danger", 94));
            default -> resolve("", "先观察四周，寻找没有被安排好的出口", state);
        };
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private static EventDraft event(String title, String detail, String kind) {
        return new EventDraft(title, detail, kind);
    }

    private static EntityPatch patch(String id, Object... pairs) {
        java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) values.put((String) pairs[index], pairs[index + 1]);
        return new EntityPatch(id, values);
    }

    private static AgentResult result(String sceneId, int elapsed, List<String> narrative, List<String> signals,
                                      Map<String, Object> playerPatch, EventDraft event,
                                      EntityPatch characterPatch, EntityPatch threadPatch) {
        return new AgentResult(sceneId, elapsed, narrative, signals, playerPatch, event, characterPatch, threadPatch);
    }
}
