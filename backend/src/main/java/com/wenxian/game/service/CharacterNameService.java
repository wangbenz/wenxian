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
    private static final List<String> HUMAN_FIRST = List.of("沉", "照川", "临渊", "知白", "无咎", "长安", "怀山", "景行", "听澜", "砚秋", "清和", "逐月");
    private static final List<String> DEMON_FIRST = List.of("玄牙", "赤离", "青狩", "烬羽", "寒鳞", "望山", "月魄", "惊岚", "苍梧", "夜川");
    private static final List<String> SPIRIT_FIRST = List.of("照微", "栖露", "空青", "流萤", "听雪", "忘川", "朝露", "问石", "青冥", "归岚");
    private final SecureRandom random = new SecureRandom();

    public GenerateNameResponse generate(String race, List<String> excluded) {
        Set<String> blocked = excluded == null ? Set.of() : new LinkedHashSet<>(excluded);
        LinkedHashSet<String> generated = new LinkedHashSet<>();
        int attempts = 0;
        while (generated.size() < 4 && attempts++ < 80) {
            String name = oneName(race);
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

    private String oneName(String race) {
        List<String> firstNames = switch (race == null ? "" : race) {
            case "DEMON" -> DEMON_FIRST;
            case "SPIRIT" -> SPIRIT_FIRST;
            default -> HUMAN_FIRST;
        };
        return SURNAMES.get(random.nextInt(SURNAMES.size()))
                + firstNames.get(random.nextInt(firstNames.size()));
    }
}
