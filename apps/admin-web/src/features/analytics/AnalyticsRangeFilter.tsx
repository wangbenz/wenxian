import { DatePicker, Select, Typography } from "antd";
import dayjs, { type Dayjs } from "dayjs";
import { useEffect, useMemo, useState } from "react";
import {
  ISO_DATE,
  MAX_RANGE_DAYS,
  RANGE_PRESETS,
  shanghaiToday,
  shiftDays,
  type AnalyticsRangeState,
} from "./useAnalyticsDateRange";

const { Text } = Typography;
const RANGE_OPTIONS = [
  ...RANGE_PRESETS.map((days) => ({ value: String(days), label: days === 366 ? "近一年" : `近 ${days} 天` })),
  { value: "custom", label: "自定义日期" },
];

export function AnalyticsRangeFilter({ range }: { range: AnalyticsRangeState }) {
  const today = useMemo(shanghaiToday, []);
  const validValue = ISO_DATE.test(range.from) && ISO_DATE.test(range.to) && range.days > 0;
  const value: [Dayjs, Dayjs] | null = validValue ? [dayjs(range.from), dayjs(range.to)] : null;
  const invalid = range.days < 1 || range.days > MAX_RANGE_DAYS;
  const matchedPreset = RANGE_PRESETS.find((days) => range.from === shiftDays(today, 1 - days) && range.to === today);
  const derivedMode = matchedPreset === undefined ? "custom" : String(matchedPreset);
  const [mode, setMode] = useState(derivedMode);

  useEffect(() => setMode(derivedMode), [derivedMode]);

  const changeMode = (nextMode: string) => {
    setMode(nextMode);
    if (nextMode === "custom") return;
    const days = Number(nextMode);
    range.setRange(shiftDays(today, 1 - days), today);
  };

  return <section aria-label="统计日期范围" className="analytics-range-filter">
    <div>
      <strong>统计日期</strong>
      <Text type="secondary">按北京时间自然日查询历史数据，单次最多一年。</Text>
    </div>
    <div className="analytics-range-actions">
      <Select aria-label="统计范围" className="analytics-range-select" onChange={changeMode} options={RANGE_OPTIONS} value={mode} />
      {mode === "custom" ? <DatePicker.RangePicker
        allowClear={false}
        aria-label="自定义统计日期"
        format="YYYY-MM-DD"
        maxDate={dayjs(today)}
        onChange={(dates) => {
          if (dates?.[0] && dates[1]) range.setRange(dates[0].format("YYYY-MM-DD"), dates[1].format("YYYY-MM-DD"));
        }}
        value={value}
      /> : <div aria-live="polite" className="analytics-range-summary"><Text type="secondary">实际日期</Text><Text strong>{range.label}</Text></div>}
    </div>
    {invalid && <Text className="analytics-range-error" type="danger">日期范围必须从早到晚，且单次不能超过 366 天。</Text>}
  </section>;
}
