import { Bar, CartesianGrid, ComposedChart, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { AnalyticsDay } from "./admin-api";

const formatNumber = (value: number) => new Intl.NumberFormat("zh-CN").format(value || 0);
const formatCompact = (value: number) => new Intl.NumberFormat("zh-CN", { notation: "compact", maximumFractionDigits: 1 }).format(value || 0);

/**
 * 数据中心趋势图。图表库独立拆包，仅在运营总览打开时加载，避免拖慢其他管理页面首屏。
 */
export default function OverviewTrendChart({ days }: { days: AnalyticsDay[] }) {
  const axis = <><CartesianGrid stroke="#E4E7EC" strokeDasharray="3 3" vertical={false} /><XAxis dataKey="date" tick={{ fill: "#667085", fontSize: 11 }} tickFormatter={(value: string) => value.slice(5)} /><YAxis tick={{ fill: "#667085", fontSize: 11 }} tickFormatter={(value: number) => formatCompact(value)} width={46} /><Tooltip formatter={(value) => formatNumber(Number(value))} labelFormatter={(label) => `日期 ${label}`} /><Legend iconType="plainline" wrapperStyle={{ color: "#475467", fontSize: 11, paddingTop: 8 }} /></>;
  return <div className="overview-chart-grid">
    <section aria-label="近十四日新增与活跃玩家趋势图" className="overview-chart"><h3>用户趋势</h3><div><ResponsiveContainer height="100%" width="100%"><LineChart data={days} margin={{ top: 8, right: 18, bottom: 4, left: 0 }}>{axis}<Line dataKey="REGISTERED_USERS" dot={false} name="新增玩家" stroke="#7A5AF8" strokeWidth={2} type="monotone" /><Line dataKey="ACTIVE_USERS" dot={{ r: 2.5 }} name="活跃玩家" stroke="#356AE6" strokeWidth={2.5} type="monotone" /></LineChart></ResponsiveContainer></div></section>
    <section aria-label="近十四日成功回合与最终失败趋势图" className="overview-chart"><h3>游玩趋势</h3><div><ResponsiveContainer height="100%" width="100%"><ComposedChart data={days} margin={{ top: 8, right: 18, bottom: 4, left: 0 }}>{axis}<Bar dataKey="SUCCESSFUL_TURNS" fill="#2F8F67" name="成功回合" radius={[3, 3, 0, 0]} /><Line dataKey="FINAL_ERRORS" dot={false} name="最终失败" stroke="#D04A45" strokeWidth={2} type="monotone" /></ComposedChart></ResponsiveContainer></div></section>
  </div>;
}
