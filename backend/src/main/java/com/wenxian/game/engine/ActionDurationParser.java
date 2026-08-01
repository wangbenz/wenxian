package com.wenxian.game.engine;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts an explicit player-requested duration without asking the narrative model to guess it. */
public final class ActionDurationParser {
    private static final Pattern DURATION = Pattern.compile(
            "(?:闭关|修炼|参悟|等待|等候|休息|休整|歇息|养伤|赶路|行路|旅行|游历)[^，。；;\\n]{0,16}?" +
                    "(半|\\d+|[零〇一二两三四五六七八九十百千万]+)(?:个)?" +
                    "(年|月|天|日|时辰|小时|刻钟|分钟)");
    private static final Pattern UNTIL_DAWN = Pattern.compile(
            "(?:休息|休整|歇息|养伤|等待|等候|留宿|过夜|住下)[^，。；;\\n]{0,20}" +
                    "(?:(?:一|整)(?:夜|晚)|到(?:明日|次日)?(?:清晨|天亮|黎明))");
    private static final Map<Character, Integer> DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('〇', 0), Map.entry('一', 1), Map.entry('二', 2),
            Map.entry('两', 2), Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5),
            Map.entry('六', 6), Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9));
    private static final int MAX_MINUTES = 100 * 360 * 1440;

    private ActionDurationParser() {}

    public static Integer parseMinutes(String action) {
        return parseMinutes(action, 0);
    }

    public static Integer parseMinutes(String action, int currentMinute) {
        if (action == null || action.isBlank()) return null;
        if (UNTIL_DAWN.matcher(action).find()) {
            int minuteOfDay = Math.floorMod(currentMinute, 1440);
            int dawn = 6 * 60;
            int untilDawn = minuteOfDay < dawn ? dawn - minuteOfDay : 1440 - minuteOfDay + dawn;
            return untilDawn == 0 ? 1440 : untilDawn;
        }
        Matcher matcher = DURATION.matcher(action);
        if (!matcher.find()) return null;
        String amountText = matcher.group(1);
        String unit = matcher.group(2);
        long unitMinutes = switch (unit) {
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
