import { useEffect, useMemo } from "react";
import { useSearchParams } from "react-router-dom";

export const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
export const MAX_RANGE_DAYS = 366;
export const RANGE_PRESETS = [7, 14, 30, 90, 366] as const;

export type AnalyticsRangeState = {
  from: string;
  to: string;
  days: number;
  label: string;
  setRange: (from: string, to: string) => void;
};

export const shanghaiToday = () => new Intl.DateTimeFormat("en-CA", {
  timeZone: "Asia/Shanghai",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
}).format(new Date());

export const shiftDays = (date: string, amount: number) => {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + amount);
  return value.toISOString().slice(0, 10);
};

const inclusiveDays = (from: string, to: string) => {
  if (!ISO_DATE.test(from) || !ISO_DATE.test(to)) return 0;
  return Math.floor((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1;
};

/**
 * 日期范围保存在 URL 中，使刷新、前进后退和复制链接都能恢复同一份查询。
 * 仅在首次完全缺少参数时写入默认值；手工输入的非法区间交给后端明确报错，不静默改写。
 */
export function useAnalyticsDateRange(defaultDays: number): AnalyticsRangeState {
  const [searchParams, setSearchParams] = useSearchParams();
  const today = useMemo(shanghaiToday, []);
  const fallbackFrom = useMemo(() => shiftDays(today, 1 - defaultDays), [defaultDays, today]);
  const rawFrom = searchParams.get("from");
  const rawTo = searchParams.get("to");
  const from = rawFrom ?? fallbackFrom;
  const to = rawTo ?? today;
  const days = inclusiveDays(from, to);
  const searchKey = searchParams.toString();

  useEffect(() => {
    if (rawFrom !== null || rawTo !== null) return;
    const next = new URLSearchParams(searchKey);
    next.set("from", fallbackFrom);
    next.set("to", today);
    setSearchParams(next, { replace: true });
  }, [fallbackFrom, rawFrom, rawTo, searchKey, setSearchParams, today]);

  const setRange = (nextFrom: string, nextTo: string) => {
    const next = new URLSearchParams(searchParams);
    next.set("from", nextFrom);
    next.set("to", nextTo);
    setSearchParams(next);
  };

  return {
    from,
    to,
    days,
    label: `${from} 至 ${to}`,
    setRange,
  };
}
