import dayjs, { type Dayjs } from "dayjs";
import { shanghaiToday, shiftDays } from "../analytics/useAnalyticsDateRange";

export type HistoryPeriod = "7" | "30" | "90" | "365" | "all" | "custom";

export const HISTORY_PERIOD_OPTIONS = [
  { value: "7", label: "近 7 天" },
  { value: "30", label: "近 30 天" },
  { value: "90", label: "近 90 天" },
  { value: "365", label: "近一年" },
  { value: "all", label: "全部历史" },
  { value: "custom", label: "自定义日期" },
] satisfies Array<{ value: HistoryPeriod; label: string }>;

export const validPeriod = (value: string | null): HistoryPeriod =>
  HISTORY_PERIOD_OPTIONS.some((item) => item.value === value) ? value as HistoryPeriod : "30";

/** 历史页日期按北京时间自然日转换为 UTC 边界，确保后端跨时区查询不会丢掉当天首尾记录。 */
export function shanghaiBoundary(date: string, end: boolean) {
  const [year, month, day] = date.split("-").map(Number);
  const start = Date.UTC(year, month - 1, day, -8);
  return new Date(end ? start + 86_400_000 - 1 : start).toISOString();
}

export function historyRange(period: HistoryPeriod, customFrom: string, customTo: string) {
  if (period === "all") return { from: "", to: "", label: "全部历史" };
  if (period === "custom") return {
    from: customFrom ? shanghaiBoundary(customFrom, false) : "",
    to: customTo ? shanghaiBoundary(customTo, true) : "",
    label: customFrom && customTo ? `${customFrom} 至 ${customTo}` : "自定义日期",
  };
  const today = shanghaiToday();
  const from = shiftDays(today, 1 - Number(period));
  return { from: shanghaiBoundary(from, false), to: shanghaiBoundary(today, true), label: `近 ${period} 天` };
}

export const customRangeValue = (from: string, to: string): [Dayjs, Dayjs] | null =>
  from && to ? [dayjs(from), dayjs(to)] : null;

export const fmtHistoryTime = (value?: string | null) => value ? new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
}).format(new Date(value)) : "—";

export const historyNumber = (value: number) => new Intl.NumberFormat("zh-CN").format(value || 0);
export const historyErrorMessage = (reason: unknown, fallback: string) => reason instanceof Error ? reason.message : fallback;
