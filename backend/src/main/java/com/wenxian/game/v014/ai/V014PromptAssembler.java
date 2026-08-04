package com.wenxian.game.v014.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wenxian.game.v014.model.V014State;
import com.wenxian.game.v014.persistence.V014TurnStore.RecentTurn;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class V014PromptAssembler {
    private final ObjectMapper mapper;

    public V014PromptAssembler(ObjectMapper mapper) { this.mapper = mapper; }

    public Prompt assemble(V014State state, String rollingSummary, List<RecentTurn> recent, String action) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("world", state.world());
        facts.put("protagonist", state.protagonist());
        facts.put("worldTimeMinutes", state.worldTimeMinutes());
        facts.put("currentWorldTime", worldTime(state.worldTimeMinutes()));
        facts.put("location", state.currentLocation());
        facts.put("condition", state.condition());
        facts.put("narrativePerspective", "SECOND_PERSON");
        facts.put("activeItems", tail(state.inventory().stream().filter(item -> Boolean.TRUE.equals(item.inPossession())).toList(), 20));
        facts.put("knownAbilities", tail(state.abilities().stream().filter(ability -> !"LOST".equals(ability.status())).toList(), 20));
        facts.put("knownCharacters", tail(state.characters().stream().filter(character -> Boolean.TRUE.equals(character.tracked()) && !"TRANSIENT".equals(character.tier())).toList(), 16));
        facts.put("keyMemories", tail(state.memories().stream().filter(memory -> !"MINOR".equals(memory.importance())).toList(), 8));
        facts.put("rollingSummary", trim(rollingSummary, 1600));
        facts.put("recentTurns", recent.stream().map(turn -> Map.of(
                "turn", turn.turnNumber(), "action", trim(turn.action(), 240),
                "narrative", trim(turn.narrative(), 750))).toList());

        String system = """
                你是《问仙录》V0.14 的故事导演。它是自由行动的 AI 修仙叙事游戏，不是数值 RPG。
                所有正文必须始终以第二人称“你”指代主角。叙述者不得用“我”自称主角，也不得在叙事中改用主角姓名、“他”或“她”指代主角；人物对白、书信与主角说出的台词可以正常使用“我”。
                你必须尊重提供的世界设定、角色种族、出身、已掌握能力、仍存在的物品、人物关系和统一时间线。
                玩家可以成功、失败、受伤或死亡，但不得为了残酷而机械压制，也不得无缘无故赠送力量。
                不要输出体力值、战力值、百分比成功率、系统面板、Java、Agent、模型或数据库等技术词。
                玩家明确说闭关多久、赶路多久时，剧情必须覆盖这段时间；elapsedMinutes 仍要返回合理值。
                currentWorldTime 是本轮开始时刻；elapsedMinutes 必须覆盖正文从开头到最后一句经历的完整时间，最终时辰不得与正文结尾冲突。
                允许自然产生新的物品、能力和人物，但只把未来仍需记住的事实写进对应 patch；路人不要持久化。
                正文结束时角色仍持有的物品、货币、已形成的能力，以及已建立持续联系的人物，必须同步写入对应 patch；若不准备记录，就不要写成角色已经持有或掌握。
                realm 返回本轮结束后的真实境界名称；没有变化也必须返回当前境界。不同种族可以使用不同境界称呼。
                lifeGoal 是角色当前真正形成的人生所求；只有经历重大转折、角色目标明确或明显改变时才返回新值，否则返回 null。
                operation 只允许 ADD、UPDATE、REMOVE；REMOVE 表示角色已不再持有该物品，或不再持续关注该人物，不表示删除历史。
                status 是一两句自然语言近况，不是固定枚举；不要为了状态字段创造数值规则。同名实体应 UPDATE，不要只因书名号或空格不同而重复 ADD。
                每轮正文以沉浸和完整事件为目标，正常历练应写 800 至 1200 个汉字、8 至 12 个自然段，至少推进并收束一个完整的小事件。
                只有“喝水”“开门”等数秒内完成且没有引出后续变化的即时行动才可以自然稍短；调查、交谈、战斗、修炼或赶路都应按正常历练完整写作。
                不要为凑字数重复描写，也不要因正文略短而重写、中断或返回不完整 JSON。
                最后给出 3 至 5 个方向明显不同的行动建议，玩家仍可自由输入。
                JSON 要紧凑：实体 description 与 memory summary 各用一至两句；rollingSummary 只保留后续仍有用的长期事实，不超过 500 字。
                只返回一个 JSON 对象，不要 Markdown，不要返回 currentWorldTime 等未列出的字段。
                narrative 必须是单个 JSON 字符串，多个段落在字符串内用转义换行表示，不得把段落写成无字段名的 JSON 内容。字段：
                title, narrative, elapsedMinutes, location, condition, realm, lifeGoal,
                choices:[{title,description}],
                items:[{operation,name,type,description,status,source,quantity}],
                abilities:[{operation,name,type,description,status,source}],
                characters:[{operation,name,tier,identity,relationship,status,location}],
                memories:[{title,summary,importance}], rollingSummary。
                未变化的列表返回空数组。tier 仅用 TRANSIENT、KNOWN、IMPORTANT；importance 仅用 MINOR、NORMAL、KEY。
                """;
        String user = "当前权威事实：\n" + json(facts) + "\n\n玩家本轮行动：\n" + action.strip();
        return new Prompt(system, user);
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("上下文无法组装", error); }
    }

    private String blank(String value) { return value == null ? "" : value; }
    private <T> List<T> tail(List<T> values, int limit) {
        if (values.size() <= limit) return values;
        return values.subList(values.size() - limit, values.size());
    }
    private String trim(String value, int max) {
        if (value == null || value.length() <= max) return blank(value);
        return value.substring(0, max) + "…";
    }

    private String worldTime(long minutes) {
        long year = Math.floorDiv(minutes, 360L * 1440L) + 1;
        long inYear = Math.floorMod(minutes, 360L * 1440L);
        long day = inYear / 1440L + 1;
        long hour = Math.floorMod(inYear, 1440L) / 60L;
        String period = hour < 5 ? "夜深" : hour < 8 ? "清晨" : hour < 12 ? "上午"
                : hour < 14 ? "正午" : hour < 18 ? "午后" : hour < 21 ? "入夜" : "夜深";
        return "第" + year + "年·第" + day + "日·" + period + "（" + hour + "时）";
    }

    public record Prompt(String system, String user) {}
}
