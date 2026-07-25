package com.wenxian.game.service;

import com.wenxian.game.model.ApiModels.TurnRequest;
import com.wenxian.game.model.GameState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;
import java.util.Set;

@Service
public class ActionInputGate {
    private static final Pattern DIGITS = Pattern.compile("^\\d{2,}$");
    private static final Pattern REPEATED = Pattern.compile("^(.)\\1{5,}$");
    private static final Set<String> FILLERS = Set.of("哦", "哦哦", "嗯", "嗯嗯", "额", "啊", "哈哈", "测试", "test");

    public String normalize(TurnRequest request, GameState state) {
        return switch (request.inputType()) {
            case "CHOICE" -> state.choices.stream()
                    .filter(choice -> choice.id.equals(request.choiceId()))
                    .findFirst()
                    .map(choice -> choice.label + "（" + choice.hint + "）")
                    .orElseThrow(() -> bad("该选项已经失效，请根据当前局势重新选择"));
            case "TEXT" -> normalizeText(request.content());
            case "USE_ITEM" -> state.inventory.stream().filter(item -> item.id.equals(request.targetId()))
                    .findFirst().map(item -> "使用物品「" + item.name + "」" + detail(request.content()))
                    .orElseThrow(() -> bad("物品不存在或已经失去"));
            case "USE_ABILITY" -> state.abilities.stream().filter(ability -> ability.id.equals(request.targetId()))
                    .findFirst().map(ability -> {
                        if (ability.maxUses > 0 && ability.remainingUses <= 0) throw bad("该能力当前没有剩余使用次数");
                        return "施展能力「" + ability.name + "」" + detail(request.content());
                    }).orElseThrow(() -> bad("尚未掌握该能力"));
            case "PRACTICE" -> state.abilities.stream().filter(ability -> ability.id.equals(request.targetId()))
                    .findFirst().map(ability -> "修炼能力「" + ability.name + "」" + detail(request.content()))
                    .orElseThrow(() -> bad("尚未掌握该能力"));
            default -> throw bad("未知行动类型");
        };
    }

    private String normalizeText(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isBlank()) throw bad("请描述一个明确行动");
        if (DIGITS.matcher(text).matches() || REPEATED.matcher(text).matches() || FILLERS.contains(text.toLowerCase())) {
            throw bad("这段内容无法形成明确行动，世界时间未推进");
        }
        return text;
    }

    private String detail(String content) {
        return content == null || content.isBlank() ? "" : "：" + content.strip();
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
