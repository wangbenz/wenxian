package com.wenxian.game.service;

import com.wenxian.game.model.ApiModels.GenerateNameResponse;
import com.wenxian.game.model.ApiModels.ValidateNameResponse;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class CharacterNameService {
    private static final Pattern VALID_NAME = Pattern.compile("^[\\p{IsHan}]{2,6}$");
    private static final Set<String> RESERVED = Set.of("管理员", "系统消息", "问仙录", "DeepSeek");
    private static final List<String> SURNAMES = List.of("陆", "沈", "顾", "谢", "宁", "裴", "江", "苏", "林", "陈", "温", "楚", "许", "秦", "叶", "白");
    private static final List<String> HUMAN_MALE_FIRST = List.of("沉", "照川", "临渊", "知白", "无咎", "怀山", "景行", "砚舟", "长风", "既明", "行简", "承安");
    private static final List<String> HUMAN_FEMALE_FIRST = List.of("晚禾", "清漪", "疏月", "知微", "映雪", "云岫", "令仪", "扶摇", "青棠", "静姝", "若蘅", "听雨");
    private static final List<String> HUMAN_NEUTRAL_FIRST = List.of("长安", "听澜", "砚秋", "清和", "逐月", "归宁", "望舒", "星回", "见素", "云深", "停舟", "南枝");
    private static final List<String> DEMON_MALE_FIRST = List.of("玄牙", "青狩", "寒鳞", "望山", "苍梧", "夜川", "重渊", "烬锋", "赤霄", "玄戈");
    private static final List<String> DEMON_FEMALE_FIRST = List.of("赤离", "烬羽", "月魄", "惊岚", "绯罗", "玄翎", "夜璃", "霜烬", "幽萝", "照影");
    private static final List<String> DEMON_NEUTRAL_FIRST = List.of("离火", "照夜", "无昼", "寒烬", "归魄", "玄月", "惊鸿", "听骨", "折枝", "长离");
    private static final List<String> SPIRIT_MALE_FIRST = List.of("空青", "忘川", "问石", "青冥", "归岚", "照野", "临川", "松隐", "玄度", "渡尘");
    private static final List<String> SPIRIT_FEMALE_FIRST = List.of("照微", "栖露", "流萤", "听雪", "朝露", "灵犀", "月白", "风荷", "含章", "若水");
    private static final List<String> SPIRIT_NEUTRAL_FIRST = List.of("云生", "见月", "栖迟", "无尘", "清昼", "含光", "闻溪", "照影", "归云", "知还");
    private final SecureRandom random = new SecureRandom();

    public GenerateNameResponse generate(String race, String gender, List<String> excluded) {
        Set<String> blocked = excluded == null ? Set.of() : new LinkedHashSet<>(excluded);
        LinkedHashSet<String> generated = new LinkedHashSet<>();
        int attempts = 0;
        while (generated.size() < 4 && attempts++ < 80) {
            String name = oneName(race, gender);
            if (!blocked.contains(name) && validate(name).valid()) generated.add(name);
        }
        if (generated.isEmpty()) generated.add("陆沉");
        List<String> values = new ArrayList<>(generated);
        return new GenerateNameResponse(values.getFirst(), values.subList(1, values.size()));
    }

    public ValidateNameResponse validate(String rawName) {
        String name = rawName == null ? "" : rawName.strip();
        if (!VALID_NAME.matcher(name).matches()) {
            return new ValidateNameResponse(false, null, "INVALID_FORMAT", "姓名应由2至6个汉字组成");
        }
        if (RESERVED.stream().anyMatch(name::contains)) {
            return new ValidateNameResponse(false, null, "RESERVED_NAME", "该姓名与系统身份冲突");
        }
        return new ValidateNameResponse(true, name, null, null);
    }

    private String oneName(String race, String gender) {
        String identity = (race == null ? "" : race) + ":" + (gender == null ? "" : gender);
        List<String> firstNames = switch (identity) {
            case "HUMAN:FEMALE" -> HUMAN_FEMALE_FIRST;
            case "HUMAN:UNSPECIFIED" -> HUMAN_NEUTRAL_FIRST;
            case "DEMON:MALE" -> DEMON_MALE_FIRST;
            case "DEMON:FEMALE" -> DEMON_FEMALE_FIRST;
            case "DEMON:UNSPECIFIED" -> DEMON_NEUTRAL_FIRST;
            case "SPIRIT:MALE" -> SPIRIT_MALE_FIRST;
            case "SPIRIT:FEMALE" -> SPIRIT_FEMALE_FIRST;
            case "SPIRIT:UNSPECIFIED" -> SPIRIT_NEUTRAL_FIRST;
            case "HUMAN:MALE" -> HUMAN_MALE_FIRST;
            default -> throw new IllegalArgumentException("生成姓名前必须选择有效的性别与种族");
        };
        return SURNAMES.get(random.nextInt(SURNAMES.size()))
                + firstNames.get(random.nextInt(firstNames.size()));
    }
}
