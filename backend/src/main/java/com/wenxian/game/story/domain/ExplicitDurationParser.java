package com.wenxian.game.story.domain;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 保守提取玩家行动中明确写出的持续时间。
 *
 * <p>这里只识别“闭关三年”“休息半个时辰”等带行动动词和明确单位的表达，用于形成玩家授权的
 * 时间边界；它不判断剧情含义，也不会从年龄、物品名称或普通数字中猜测时间。</p>
 */
public final class ExplicitDurationParser {
    private static final Pattern DURATION = Pattern.compile(
            "(?:闭关|修炼|参悟|等待|等候|守候|休息|休整|歇息|养伤|静立|站立|打坐|停留|赶路|行路|旅行|游历)[^，。；;\\n]{0,16}?"
                    + "(?<!第)(半|\\d+|[零〇一二两三四五六七八九十百千万]+)(?:个)?"
                    + "(年|月|天|日|时辰|小时|刻钟|分钟)");
    private static final Pattern UNTIL_DAWN = Pattern.compile(
            "(?:休息|休整|歇息|养伤|等待|等候|留宿|过夜|住下)[^，。；;\\n]{0,20}"
                    + "(?:(?:一|整)(?:夜|晚)|到(?:(?:明日|次日|第二天))?(?:清晨|天亮|黎明))");
    private static final Map<Character, Integer> DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('〇', 0), Map.entry('一', 1), Map.entry('二', 2),
            Map.entry('两', 2), Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5),
            Map.entry('六', 6), Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9));
    private static final int MAX_MINUTES = 100 * 360 * 1440;

    private ExplicitDurationParser() {}

    /** 返回明确时长的分钟数；没有可靠识别结果时返回 {@code null}，交由模型按剧情处理。 */
    public static Integer parseMinutes(String action, long currentWorldTimeMinutes) {
        if (action == null || action.isBlank()) return null;
        if (UNTIL_DAWN.matcher(action).find()) {
            int minuteOfDay = (int) Math.floorMod(currentWorldTimeMinutes, 1440L);
            int dawn = 6 * 60;
            int untilDawn = minuteOfDay < dawn ? dawn - minuteOfDay : 1440 - minuteOfDay + dawn;
            return untilDawn == 0 ? 1440 : untilDawn;
        }
        Matcher matcher = DURATION.matcher(action);
        if (!matcher.find()) return null;
        String amountText = matcher.group(1);
        long unitMinutes = switch (matcher.group(2)) {
            case "年" -> 360L * 1440;
            case "月" -> 30L * 1440;
            case "天", "日" -> 1440L;
            case "时辰" -> 120L;
            case "小时" -> 60L;
            case "刻钟" -> 15L;
            default -> 1L;
        };
        long minutes;
        if ("半".equals(amountText)) {
            minutes = Math.max(1, unitMinutes / 2);
        } else {
            int amount = chineseNumber(amountText);
            if (amount <= 0) return null;
            minutes = amount * unitMinutes;
        }
        return (int) Math.min(MAX_MINUTES, minutes);
    }

    private static int chineseNumber(String value) {
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        int total = 0;
        int section = 0;
        int number = 0;
        for (char current : value.toCharArray()) {
            Integer digit = DIGITS.get(current);
            if (digit != null) {
                number = digit;
                continue;
            }
            int unit = switch (current) {
                case '十' -> 10;
                case '百' -> 100;
                case '千' -> 1000;
                case '万' -> 10000;
                default -> 0;
            };
            if (unit == 0) return -1;
            if (unit == 10000) {
                section = (section + number) * unit;
                total += section;
                section = 0;
            } else {
                section += (number == 0 ? 1 : number) * unit;
            }
            number = 0;
        }
        return total + section + number;
    }
}
