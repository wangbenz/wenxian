package com.wenxian.game.story.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从玩家明确授权的长期行动中提取语义时间。
 *
 * <p>解析范围刻意只覆盖明确数量与常见时段表达；无法可靠识别时返回空，由剧情自然推进，
 * 不通过扩大关键词表猜测所有开放世界行动。</p>
 */
public final class SemanticDurationParser {
    private static final Pattern LONG_DURATION = Pattern.compile(
            "(?:闭关|修炼|参悟|等待|等候|守候|休息|休整|歇息|养伤|停留|赶路|行路|旅行|游历)[^，。；;\\n]{0,16}?"
                    + "(?<!第)(半|\\d+|[零〇一二两三四五六七八九十百千万]+)(?:个)?(年|月|天|日)");
    private static final Pattern UNTIL_PHASE = Pattern.compile(
            "(?:到|直至|直到)(清晨|天亮|黎明|上午|正午|午后|下午|黄昏|傍晚|入夜|夜晚|夜深|深夜)");
    private static final Pattern NEXT_DAY_ACTION = Pattern.compile(
            "(?:明日|次日|翌日|第二天)(?:清晨|一早)?(?:再|便|就|即)?"
                    + "(?:出发|启程|动身|行动|继续|前往|赶路|修炼|探查|查看|进入|离开|返回|去|走)");

    private SemanticDurationParser() {}

    public static Optional<SemanticDuration> parse(String action) {
        if (action == null || action.isBlank()) return Optional.empty();
        Matcher phase = UNTIL_PHASE.matcher(action);
        if (phase.find()) {
            return Optional.of(new SemanticDuration(SemanticDuration.Unit.PHASE, 1,
                    normalizedPhase(phase.group(1))));
        }
        if (NEXT_DAY_ACTION.matcher(action).find()) {
            return Optional.of(new SemanticDuration(SemanticDuration.Unit.DAY, 1, ""));
        }
        Matcher duration = LONG_DURATION.matcher(action);
        if (!duration.find()) return Optional.empty();
        String amount = duration.group(1);
        String rawUnit = duration.group(2);
        if ("半".equals(amount)) {
            return Optional.of(switch (rawUnit) {
                case "年" -> new SemanticDuration(SemanticDuration.Unit.MONTH, 6, "");
                case "月" -> new SemanticDuration(SemanticDuration.Unit.DAY, 15, "");
                default -> new SemanticDuration(SemanticDuration.Unit.PHASE, 4, "");
            });
        }
        int value = ChineseNumber.parse(amount);
        if (value < 1) return Optional.empty();
        SemanticDuration.Unit unit = switch (rawUnit) {
            case "年" -> SemanticDuration.Unit.YEAR;
            case "月" -> SemanticDuration.Unit.MONTH;
            default -> SemanticDuration.Unit.DAY;
        };
        return Optional.of(new SemanticDuration(unit, value, ""));
    }

    private static String normalizedPhase(String value) {
        return switch (value) {
            case "天亮", "黎明" -> "清晨";
            case "下午" -> "午后";
            case "傍晚" -> "黄昏";
            case "夜晚" -> "入夜";
            case "深夜" -> "夜深";
            default -> value;
        };
    }

    /** 仅供语义时间解析使用的中文整数解析器。 */
    private static final class ChineseNumber {
        private static int parse(String value) {
            if (value.chars().allMatch(Character::isDigit)) {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
            String digits = "零〇一二两三四五六七八九";
            int total = 0;
            int section = 0;
            int number = 0;
            for (char current : value.toCharArray()) {
                int index = digits.indexOf(current);
                if (index >= 0) {
                    number = switch (current) {
                        case '零', '〇' -> 0;
                        case '一' -> 1;
                        case '二', '两' -> 2;
                        case '三' -> 3;
                        case '四' -> 4;
                        case '五' -> 5;
                        case '六' -> 6;
                        case '七' -> 7;
                        case '八' -> 8;
                        case '九' -> 9;
                        default -> -1;
                    };
                    continue;
                }
                int unit = switch (current) {
                    case '十' -> 10;
                    case '百' -> 100;
                    case '千' -> 1_000;
                    case '万' -> 10_000;
                    default -> 0;
                };
                if (unit == 0) return -1;
                if (unit == 10_000) {
                    total += (section + number) * unit;
                    section = 0;
                } else {
                    section += (number == 0 ? 1 : number) * unit;
                }
                number = 0;
            }
            return total + section + number;
        }
    }
}
