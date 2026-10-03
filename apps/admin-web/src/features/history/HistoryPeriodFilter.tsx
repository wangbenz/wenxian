import { DatePicker, Select } from "antd";

import {
  HISTORY_PERIOD_OPTIONS, customRangeValue, type HistoryPeriod,
} from "./history-utils";

type HistoryPeriodFilterProps = {
  from: string;
  onPeriodChange: (period: HistoryPeriod) => void;
  onRangeChange: (from: string, to: string) => void;
  period: HistoryPeriod;
  to: string;
};

/** 历史查询统一使用范围下拉框；只有选择自定义日期后才显示日期输入，避免禁用控件长期占位。 */
export function HistoryPeriodFilter({
  from, onPeriodChange, onRangeChange, period, to,
}: HistoryPeriodFilterProps) {
  return <>
    <label className="history-filter-field"><span>时间范围</span><Select<HistoryPeriod> onChange={onPeriodChange} options={HISTORY_PERIOD_OPTIONS} value={period} /></label>
    {period === "custom" && <label className="history-filter-field history-filter-field-date"><span>日期区间</span><DatePicker.RangePicker
      allowClear
      format="YYYY-MM-DD"
      onChange={(dates) => onRangeChange(dates?.[0]?.format("YYYY-MM-DD") ?? "", dates?.[1]?.format("YYYY-MM-DD") ?? "")}
      value={customRangeValue(from, to)}
    /></label>}
  </>;
}
