package com.wenxian.game.service;

import com.wenxian.game.model.ApiModels.TurnRequest;
import com.wenxian.game.model.GameState;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;
import java.util.List;
import java.util.Set;

@Service
public class ActionInputGate {
    private static final Pattern DIGITS = Pattern.compile("^\\d{2,}$");
    private static final Pattern REPEATED = Pattern.compile("^(.)\\1{5,}$");
    private static final Pattern REALM_ADVANCE = Pattern.compile(
            "(?:突破|进阶|晋升|晋级|冲击|踏入|直升|修成)(?:到|至|为)?\\s*(凡俗|引气|练气|炼气|筑基|金丹|元婴|化神|炼虚|合道)(?:境|期)?(?:吧|了|成功|修为|境界)?(?:[!！。.]|$)");
    private static final List<String> REALMS = List.of("凡俗", "引气", "筑基", "金丹", "元婴", "化神", "炼虚", "合道");
    private static final Set<String> FILLERS = Set.of("哦", "哦哦", "嗯", "嗯嗯", "额", "啊", "哈哈", "测试", "test");

    public Assessment assess(TurnRequest request, GameState state) {
        if (!"TEXT".equals(request.inputType())) return Assessment.accepted(normalize(request, state));
        String text = request.content() == null ? "" : request.content().strip();
        if (text.isBlank() || DIGITS.matcher(text).matches() || REPEATED.matcher(text).matches()
                || FILLERS.contains(text.toLowerCase())) {
            return new Assessment(text, "CLARIFY", "此念尚未成形",
                    "这段话还不能形成明确行动。请说明你想去哪里、寻找谁，或准备做什么；世界时间没有推进。",
                    List.of("观察当前处境", "询问眼前之人", "从现有选择中决定"));
        }
        var matcher = REALM_ADVANCE.matcher(text);
        if (matcher.find() && state.player != null) {
            String currentRealm = normalizeRealm(state.player.realm);
            String targetRealm = normalizeRealm(matcher.group(1));
            int current = REALMS.indexOf(currentRealm);
            int target = REALMS.indexOf(targetRealm);
            if (current >= 0 && target > current + 1) {
                List<String> barriers = REALMS.subList(current + 1, target);
                String barrierText = String.join("、", barriers);
                String next = REALMS.get(current + 1);
                return new Assessment(text, "REJECTED", "此念不可行",
                        "你目前仍在「" + currentRealm + "」之境，抵达「" + targetRealm + "」之前尚有"
                                + barrierText + "等关隘。境界不可凭一念跨越，需先取得相应传承、资质与机缘；世界时间没有推进。",
                        realmSuggestions(next));
            }
        }
        return Assessment.accepted(text);
    }

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

    private String normalizeRealm(String value) {
        if (value == null) return "";
        if (value.contains("练气") || value.contains("炼气")) return "引气";
        return REALMS.stream().filter(value::contains).findFirst().orElse(value);
    }

    private List<String> realmSuggestions(String nextRealm) {
        if ("引气".equals(nextRealm)) {
            return List.of("寻找引气法门", "打听灵根测试", "先处理眼前局势");
        }
        return List.of("寻找突破" + nextRealm + "的传承", "打听所需机缘", "稳固当前境界");
    }

    private String detail(String content) {
        return content == null || content.isBlank() ? "" : "：" + content.strip();
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record Assessment(String action, String result, String title, String reason, List<String> suggestions) {
        public static Assessment accepted(String action) {
            return new Assessment(action, "ACCEPTED", "", "", List.of());
        }

        public boolean accepted() {
            return "ACCEPTED".equals(result);
        }
    }
}
