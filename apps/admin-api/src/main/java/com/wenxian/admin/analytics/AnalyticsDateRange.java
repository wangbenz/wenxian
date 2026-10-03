package com.wenxian.admin.analytics;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 管理台统计查询的北京时间自然日区间。
 *
 * <p>区间包含起止日期，允许查询任意历史日期，但单次最多 366 天。超过限制时明确失败，
 * 不允许服务端静默截断后让运营人员误以为看到了完整数据。</p>
 */
public record AnalyticsDateRange(LocalDate from, LocalDate to) {

    public static final int MAX_DAYS = 366;

    public AnalyticsDateRange {
        if (from == null || to == null) {
            throw new IllegalArgumentException("开始日期和结束日期不能为空");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_DAYS) {
            throw new IllegalArgumentException("单次最多查询 " + MAX_DAYS + " 天，请缩小日期范围");
        }
    }

    public static AnalyticsDateRange resolve(String fromValue,
                                             String toValue,
                                             Integer legacyDays,
                                             int defaultDays,
                                             LocalDate today) {
        boolean hasFrom = fromValue != null && !fromValue.isBlank();
        boolean hasTo = toValue != null && !toValue.isBlank();
        if (hasFrom != hasTo) {
            throw new IllegalArgumentException("开始日期和结束日期必须同时提供");
        }
        if (hasFrom && legacyDays != null) {
            throw new IllegalArgumentException("日期范围和 days 参数不能同时提供");
        }
        if (hasFrom) {
            try {
                return new AnalyticsDateRange(LocalDate.parse(fromValue), LocalDate.parse(toValue));
            } catch (java.time.format.DateTimeParseException error) {
                throw new IllegalArgumentException("日期格式必须为 YYYY-MM-DD", error);
            }
        }
        return trailing(legacyDays == null ? defaultDays : legacyDays, today);
    }

    public static AnalyticsDateRange trailing(int days, LocalDate today) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException("查询天数需在 1-" + MAX_DAYS + " 之间");
        }
        return new AnalyticsDateRange(today.minusDays(days - 1L), today);
    }

    public int days() {
        return Math.toIntExact(ChronoUnit.DAYS.between(from, to) + 1);
    }
}
