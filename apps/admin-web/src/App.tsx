import "./App.css";
import { lazy, Suspense, useEffect, useState, type CSSProperties, type FormEvent, type ReactNode } from "react";
import {
  Check,
  ArrowRight,
  WarningCircle,
} from "@phosphor-icons/react";
import { Button, ConfigProvider, Table as AntTable, type TableColumnsType } from "antd";
import zhCN from "antd/locale/zh_CN";
import { BrowserRouter, Navigate, useLocation, useNavigate } from "react-router-dom";
import { adminApi, clearToken, isAuthenticated, onAdminSessionExpired, setToken, type AiUsageAnalytics, type AnalyticsDay, type AnalyticsOverview, type GameplayAnalytics, type GrowthAnalytics } from "./admin-api";
import { AdminLayout } from "./admin/AdminLayout";
import { ADMIN_THEME } from "./admin/theme";
import { DEFAULT_ADMIN_PATH, findAdminModule, findAdminPage, findAdminPageByPath, type AdminPageId } from "./admin/navigation";
import { useAnalyticsDateRange, type AnalyticsRangeState } from "./features/analytics/useAnalyticsDateRange";
import { ChangePasswordModal } from "./features/system/ChangePasswordModal";

const LazyOverviewTrendChart = lazy(() => import("./OverviewTrendChart"));
const LazyAnalyticsRangeFilter = lazy(() => import("./features/analytics/AnalyticsRangeFilter")
  .then((module) => ({ default: module.AnalyticsRangeFilter })));
const LazyModelCatalogPage = lazy(() => import("./features/models/ModelCatalogPage")
  .then((module) => ({ default: module.ModelCatalogPage })));
const LazyServicePlansPage = lazy(() => import("./features/plans/ServicePlansPage")
  .then((module) => ({ default: module.ServicePlansPage })));
const LazyInferencePointAccountsPage = lazy(() => import("./features/points/InferencePointAccountsPage")
  .then((module) => ({ default: module.InferencePointAccountsPage })));
const LazyPointRulesPage = lazy(() => import("./features/points/PointRulesPage")
  .then((module) => ({ default: module.PointRulesPage })));
const LazyByokPage = lazy(() => import("./features/byok/ByokPage")
  .then((module) => ({ default: module.ByokPage })));
const LazyOperationsConfigPage = lazy(() => import("./features/operations/OperationsConfigPage")
  .then((module) => ({ default: module.OperationsConfigPage })));
const LazyOperationProblemsPage = lazy(() => import("./features/history/OperationProblemsPage")
  .then((module) => ({ default: module.OperationProblemsPage })));
const LazyAuditLogsPage = lazy(() => import("./features/history/AuditLogsPage")
  .then((module) => ({ default: module.AuditLogsPage })));
const LazyPlayerProfilesPage = lazy(() => import("./features/players/PlayerProfilesPage")
  .then((module) => ({ default: module.PlayerProfilesPage })));
const LazySystemStatusPage = lazy(() => import("./features/system/SystemStatusPage")
  .then((module) => ({ default: module.SystemStatusPage })));
const LazyAdminAccountsPage = lazy(() => import("./features/system/AdminAccountsPage")
  .then((module) => ({ default: module.AdminAccountsPage })));
const LazyReleaseManagementPage = lazy(() => import("./features/operations/ReleaseManagementPage")
  .then((module) => ({ default: module.ReleaseManagementPage })));
const LazyAccessControlPage = lazy(() => import("./features/operations/AccessControlPage")
  .then((module) => ({ default: module.AccessControlPage })));

const SHANGHAI_TIME_ZONE = "Asia/Shanghai";
const fmt = (value?: string | null) => value ? new Intl.DateTimeFormat("zh-CN", {
  timeZone: SHANGHAI_TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", hourCycle: "h23",
}).format(new Date(value)) : "—";
const shanghaiDate = (value = new Date()) => new Intl.DateTimeFormat("en-CA", {
  timeZone: SHANGHAI_TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit",
}).format(value);
const num = (value: number) => new Intl.NumberFormat("zh-CN").format(value || 0);
const latency = (value: number) => value ? `${(value / 1000).toFixed(1)} 秒` : "—";

function useLoad<T>(loader: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null); const [error, setError] = useState(""); const [loading, setLoading] = useState(true); const [nonce, setNonce] = useState(0);
  useEffect(() => { let live = true; setLoading(true); setError(""); loader().then((value) => live && setData(value)).catch((reason) => live && setError(reason instanceof Error ? reason.message : "加载失败")).finally(() => live && setLoading(false)); return () => { live = false; }; }, [...deps, nonce]);
  return { data, error, loading, reload: () => setNonce((value) => value + 1) };
}

export default function App() {
  return <ConfigProvider locale={zhCN} theme={ADMIN_THEME}>
    <BrowserRouter><AdminSession /></BrowserRouter>
  </ConfigProvider>;
}

function AdminSession() {
  const [authed, setAuthed] = useState(isAuthenticated);
  const [loginNotice, setLoginNotice] = useState("");
  useEffect(() => onAdminSessionExpired(() => {
    setLoginNotice("登录状态已失效，请重新登录。");
    setAuthed(false);
  }), []);
  return !authed
    ? <Login notice={loginNotice} onDone={() => { setLoginNotice(""); setAuthed(true); }} />
    : <Shell onLogout={() => { clearToken(); setLoginNotice(""); setAuthed(false); }} />;
}

function Login({ notice, onDone }: { notice: string; onDone: () => void }) {
  const [username, setUsername] = useState(""); const [password, setPassword] = useState(""); const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => { event.preventDefault(); setBusy(true); setError(""); try { const result = await adminApi.login(username.trim(), password); setToken(result.token); onDone(); } catch (reason) { setError(reason instanceof Error ? reason.message : "登录失败"); } finally { setBusy(false); } };
  return <main className="login-shell"><form className="login-card" onSubmit={submit}><span className="brand-mark">问</span><p className="kicker">WENXIAN OPERATIONS</p><h1>问仙录管理台</h1><p>管理玩家、运营配置与推演服务。</p>{notice && <p className="notice" role="status">{notice}</p>}<label>账号<input autoComplete="username" autoFocus value={username} onChange={(e) => setUsername(e.currentTarget.value)} /></label><label>密码<input autoComplete="current-password" type="password" value={password} onChange={(e) => setPassword(e.currentTarget.value)} /></label>{error && <p className="error" role="alert">{error}</p>}<button className="primary" disabled={busy} type="submit">{busy ? "正在验证…" : "进入管理台"}</button></form></main>;
}

function Shell({ onLogout }: { onLogout: () => void }) {
  const location = useLocation();
  const navigateRoute = useNavigate();
  const routePage = findAdminPageByPath(location.pathname);
  const page = routePage?.id ?? "dashboard";
  const [securityOpen, setSecurityOpen] = useState(false); const [registrationDate, setRegistrationDate] = useState("");
  const version = useLoad(() => adminApi.appVersion(), []);
  const navigate = (next: AdminPageId) => {
    if (next === "users") setRegistrationDate("");
    navigateRoute(findAdminPage(next).path);
  };
  useEffect(() => {
    const currentPage = findAdminPage(page);
    const currentModule = findAdminModule(currentPage.module);
    document.title = `${currentPage.label} · ${currentModule.label} · 问仙录管理台`;
  }, [page]);
  if (!routePage) return <Navigate replace to={DEFAULT_ADMIN_PATH} />;
  return <>
    <AdminLayout
      onChangePassword={() => setSecurityOpen(true)}
      onLogout={onLogout}
      onNavigate={navigate}
      page={page}
      reportingDate={shanghaiDate()}
      version={version.data?.version ?? "同步中"}
    >
      {page === "dashboard" && <AnalyticsDashboard onOpenProblems={() => navigate("errors")} onSelectRegistrationDate={(date) => { setRegistrationDate(date); navigateRoute(findAdminPage("users").path); }} />}{page === "growth" && <GrowthPage />}{page === "gameplay" && <GameplayPage />}{page === "ai" && <AiUsagePage />}{page === "health" && <Suspense fallback={<div className="state state-loading"><i /><span>正在检查运行状态…</span></div>}><LazySystemStatusPage /></Suspense>}{page === "users" && <Suspense fallback={<div className="state state-loading"><i /><span>正在加载玩家档案…</span></div>}><LazyPlayerProfilesPage initialRegisteredDate={registrationDate} /></Suspense>}{page === "credits" && <Suspense fallback={<div className="state state-loading"><i /><span>正在加载推演点账户…</span></div>}><LazyInferencePointAccountsPage /></Suspense>}{page === "errors" && <Suspense fallback={<div className="state state-loading"><i /><span>正在加载玩家问题…</span></div>}><LazyOperationProblemsPage /></Suspense>}{page === "releases" && <Suspense fallback={<div className="state state-loading"><i /><span>正在加载版本公告…</span></div>}><LazyReleaseManagementPage currentVersion={version.data?.version ?? ""} onVersionChanged={version.reload} /></Suspense>}{page === "models" && <Suspense fallback={<div className="state state-loading"><i /><span>正在加载模型库…</span></div>}><LazyModelCatalogPage /></Suspense>}{page === "limits" && <Suspense fallback={<div className="state state-loading"><i /><span>正在加载点数规则…</span></div>}><LazyPointRulesPage /></Suspense>}{page === "access" && <Suspense fallback={<div className="state state-loading"><i /><span>正在加载灰度访问…</span></div>}><LazyAccessControlPage /></Suspense>}{page === "operations" && <Suspense fallback={<div className="state state-loading"><i /><span>正在加载运营配置…</span></div>}><LazyOperationsConfigPage /></Suspense>}{page === "admins" && <Suspense fallback={<div className="state state-loading"><i /><span>正在加载管理账号…</span></div>}><LazyAdminAccountsPage /></Suspense>}{page === "logs" && <Suspense fallback={<div className="state state-loading"><i /><span>正在加载操作记录…</span></div>}><LazyAuditLogsPage /></Suspense>}
      {page === "tierPlans" && <Suspense fallback={<div className="state state-loading"><i /><span>正在加载三档方案…</span></div>}><LazyServicePlansPage /></Suspense>}
      {page === "byok" && <Suspense fallback={<div className="state state-loading"><i /><span>正在加载玩家自备服务…</span></div>}><LazyByokPage /></Suspense>}
    </AdminLayout>
    {securityOpen && <ChangePasswordModal onClose={() => setSecurityOpen(false)} onChanged={onLogout} />}
  </>;
}

function AnalyticsRangeControl({ range }: { range: AnalyticsRangeState }) {
  return <Suspense fallback={<div className="analytics-range-filter analytics-range-loading">正在加载日期筛选…</div>}>
    <LazyAnalyticsRangeFilter range={range} />
  </Suspense>;
}

const metric = (day: AnalyticsDay | undefined, code: string) => Number(day?.[code] ?? 0);

function AnalyticsDashboard({ onSelectRegistrationDate, onOpenProblems }: { onSelectRegistrationDate: (date: string) => void; onOpenProblems: () => void }) {
  const query = useLoad<AnalyticsOverview>(() => adminApi.analyticsOverview({ days: 14 }), []);
  return <State loading={query.loading} error={query.error}>{query.data && <>
    <div className="page-intro overview-intro"><div><h2>今日概况</h2><p>今日活跃仅统计发生过游玩的玩家。</p></div><span className="data-freshness">更新于 {fmt(query.data.calculatedAt)}</span></div>
    <MetricSection primary title="核心运营" items={[
      ["累计账号", num(query.data.totalUsers), "当前有效账号"],
      ["今日新增", num(metric(query.data.today, "REGISTERED_USERS")), "按北京时间自然日"],
      ["今日活跃", num(metric(query.data.today, "ACTIVE_USERS")), "发生过有效游玩行为"],
      ["今日完成回合", num(metric(query.data.today, "SUCCESSFUL_TURNS")), `累计 ${num(query.data.totalSuccessfulTurns)}`],
    ]} />
    <div className="dashboard-workbench">
      <OverviewTrendChart days={query.data.trend} />
      <DashboardAttention alerts={query.data.alerts} failures={metric(query.data.today, "FINAL_ERRORS")} onOpenProblems={onOpenProblems} />
    </div>
    <AnalyticsTrend title="关键运营数据" days={query.data.trend} columns={[["REGISTERED_USERS", "新增"], ["ACTIVE_USERS", "活跃"], ["SUCCESSFUL_TURNS", "回合"], ["FINAL_ERRORS", "失败回合"]]} onDate={onSelectRegistrationDate} />
  </>}</State>;
}

function OverviewTrendChart({ days }: { days: AnalyticsDay[] }) {
  return <Panel title="近 14 日运营趋势"><Suspense fallback={<div className="chart-loading">正在绘制趋势…</div>}><LazyOverviewTrendChart days={days} /></Suspense></Panel>;
}

function DashboardAttention({ alerts, failures, onOpenProblems }: { alerts: string[]; failures: number; onOpenProblems: () => void }) {
  const items = alerts.length > 0 ? alerts : failures > 0 ? [`今日有 ${num(failures)} 次操作最终失败，建议查看问题记录。`] : [];
  return <Panel title="待处理事项"><div className="attention-list">{items.map((alert, index) => <article key={alert}><span className="attention-level"><WarningCircle size={18} weight="fill" />{index === 0 ? "高" : "中"}</span><p>{alert}</p><button className="table-action" onClick={onOpenProblems} type="button">查看问题<ArrowRight size={14} /></button></article>)}{items.length === 0 && <div className="attention-empty"><Check size={24} weight="bold" /><strong>运行平稳</strong><span>当前没有需要优先处理的异常。</span></div>}</div>{items.length > 0 && <button className="attention-more" onClick={onOpenProblems} type="button">查看全部问题<ArrowRight size={15} /></button>}</Panel>;
}

function GrowthPage() {
  const range = useAnalyticsDateRange(30);
  const query = useLoad<GrowthAnalytics>(() => adminApi.analyticsGrowth({ from: range.from, to: range.to }), [range.from, range.to]);
  return <><AnalyticsRangeControl range={range} /><State loading={query.loading} error={query.error}>{query.data && <>
    <div className="page-intro"><div><h2>增长与留存</h2><p>{query.data.definition}</p></div></div>
    <GrowthFunnel data={query.data.funnel ?? { registered: 0, createdSave: 0, firstTurn: 0 }} rangeLabel={range.label} />
    <AnalyticsTrend title={`${range.label} · 新增、活跃与首回合`} days={query.data.daily} columns={[["REGISTERED_USERS", "新增"], ["ACTIVE_USERS", "活跃"], ["CREATED_SAVES", "创建命卷"], ["SUCCESSFUL_TURNS", "完成回合"]]} />
    <RetentionHeatmap rows={query.data.retention} />
  </>}</State></>;
}

function GrowthFunnel({ data, rangeLabel }: { data: GrowthAnalytics["funnel"]; rangeLabel: string }) {
  const steps = [["注册账号", data.registered], ["创建命卷", data.createdSave], ["完成首回合", data.firstTurn]] as const;
  return <Panel title={`${rangeLabel} · 新玩家激活漏斗`}><div className="growth-funnel">{steps.map(([label, value], index) => { const base = index === 0 ? data.registered : steps[index - 1][1]; const rate = base ? value * 100 / base : 0; return <article key={label}><span>0{index + 1}</span><div><small>{label}</small><strong>{num(value)}</strong><em>{index === 0 ? "注册批次人数" : `上一步转化 ${rate.toFixed(1)}%`}</em></div></article>; })}</div><p className="panel-note">按注册日期追踪玩家是否创建命卷并完成首回合。</p></Panel>;
}

function RetentionHeatmap({ rows }: { rows: GrowthAnalytics["retention"] }) {
  const days = [1, 3, 7, 14];
  const dates = [...new Set(rows.filter((row) => row.registered > 0).map((row) => row.cohortDate))].sort().reverse();
  const at = (date: string, day: number) => rows.find((row) => row.cohortDate === date && row.day === day);
  return <Panel title="注册批次留存热力图"><Table label="注册批次留存热力图"><thead><tr><th>注册日期</th><th>注册</th><th>已激活</th>{days.map((day) => <th key={day}>D{day}</th>)}</tr></thead><tbody>{dates.map((date) => { const source = rows.find((row) => row.cohortDate === date); return <tr key={date}><td>{date}</td><td>{num(source?.registered ?? 0)}</td><td>{num(source?.activated ?? 0)}</td>{days.map((day) => { const item = at(date, day); const rate = Number(item?.rate ?? 0); return <td key={day}><span className="retention-cell" style={{ "--retention": `${Math.min(1, rate / 100)}` } as CSSProperties}>{item ? `${rate.toFixed(1)}%` : "—"}</span></td>; })}</tr>; })}</tbody></Table>{dates.length === 0 && <p className="empty-state">统计任务完成后会显示留存数据。</p>}<p className="panel-note">越深的蓝色表示留存越高；尚未到达观察日显示为“—”。</p></Panel>;
}

function GameplayPage() {
  const range = useAnalyticsDateRange(30);
  const query = useLoad<GameplayAnalytics>(() => adminApi.analyticsGameplay({ from: range.from, to: range.to }), [range.from, range.to]);
  return <><AnalyticsRangeControl range={range} /><State loading={query.loading} error={query.error}>{query.data && <>
    <MetricSection primary title="所选时段有效行为" items={[["完成回合", num(query.data.totals.turns), "成功完成并保存"], ["创建命卷", num(query.data.totals.createdSaves), "所选时段新建"], ["命卷修正", num(query.data.totals.corrections), "玩家主动修正"], ["整理人物或物品", num(query.data.totals.entityRemovals), "从命卷中移出"]]} />
    <AnalyticsTrend title={`${range.label} · 游玩情况`} days={query.data.daily} columns={[["ACTIVE_USERS", "活跃"], ["SUCCESSFUL_TURNS", "完成回合"], ["CORRECTIONS", "命卷修正"], ["ARCHIVED_SAVES", "内容封存"]]} />
  </>}</State></>;
}

function AiUsagePage() {
  const range = useAnalyticsDateRange(14);
  const query = useLoad<AiUsageAnalytics>(() => adminApi.analyticsAiUsage({ from: range.from, to: range.to }), [range.from, range.to]);
  return <><AnalyticsRangeControl range={range} /><State loading={query.loading} error={query.error}>{query.data && <>
    <AiUsageSummary data={query.data} rangeLabel={range.label} />
    <AnalyticsTrend title={`平台服务 · ${range.label}`} days={query.data.platform} columns={[["AI_REQUESTS", "请求"], ["AI_FAILURES", "失败"], ["PROMPT_TOKENS", "输入 Token"], ["COMPLETION_TOKENS", "输出 Token"]]} />
    <AnalyticsTrend title={`玩家自备服务 · ${range.label}`} days={query.data.player} columns={[["AI_REQUESTS", "请求"], ["AI_FAILURES", "失败"], ["PROMPT_TOKENS", "输入 Token"], ["COMPLETION_TOKENS", "输出 Token"]]} />
    <Panel title="模型使用明细"><Table label="模型消耗"><thead><tr><th>供应商</th><th>模型</th><th>来源</th><th>请求</th><th>输入</th><th>输出</th><th>缓存命中</th><th>失败</th><th>平均耗时</th></tr></thead><tbody>{query.data.models.map((row) => <tr key={`${row.provider}-${row.model}-${row.source}`}><td>{row.provider}</td><td>{row.model}</td><td>{row.source === "USER" || row.source === "PLAYER" ? "玩家" : "平台"}</td><td>{num(row.requests)}</td><td>{num(row.promptTokens)}</td><td>{num(row.completionTokens)}</td><td>{num(row.cacheHitTokens)}</td><td>{num(row.failures)}</td><td>{latency(row.averageLatency)}</td></tr>)}</tbody></Table></Panel>
  </>}</State></>;
}

function AiUsageSummary({ data, rangeLabel }: { data: AiUsageAnalytics; rangeLabel: string }) {
  const total = (rows: AnalyticsDay[], code: string) => rows.reduce((sum, row) => sum + metric(row, code), 0);
  const requests = total(data.platform, "AI_REQUESTS"); const failures = total(data.platform, "AI_FAILURES");
  const prompt = total(data.platform, "PROMPT_TOKENS"); const completion = total(data.platform, "COMPLETION_TOKENS");
  const hit = total(data.platform, "CACHE_HIT_TOKENS"); const miss = total(data.platform, "CACHE_MISS_TOKENS");
  return <><MetricSection primary title={`${rangeLabel} · 平台模型消耗`} items={[["平台请求", num(requests), failures ? `失败率 ${(failures * 100 / Math.max(1, requests)).toFixed(1)}%` : "无最终失败"], ["平台 Token", num(prompt + completion), `输入 ${num(prompt)} · 输出 ${num(completion)}`], ["单次平均", num(Math.round((prompt + completion) / Math.max(1, requests))), "输入与输出合计"], ["缓存命中率", `${(hit * 100 / Math.max(1, hit + miss)).toFixed(1)}%`, `命中 ${num(hit)} Token`]]} /><p className="data-definition">本页不计算金额，费用以供应商账单和当期价格为准。</p></>;
}

function AnalyticsTrend({ title, days, columns, onDate }: { title: string; days: AnalyticsDay[]; columns: Array<[string, string]>; onDate?: (date: string) => void }) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(14);
  const firstDate = days[0]?.date;
  const lastDate = days[days.length - 1]?.date;

  useEffect(() => setPage(1), [firstDate, lastDate, title]);

  const tableColumns: TableColumnsType<AnalyticsDay> = [
    {
      title: "日期",
      dataIndex: "date",
      key: "date",
      fixed: "left",
      width: 128,
      render: (date: string) => onDate
        ? <Button aria-label={`查看 ${date} 注册玩家`} onClick={() => onDate(date)} size="small" type="link">{date}</Button>
        : date,
    },
    ...columns.map(([code, label]) => ({
      title: label,
      key: code,
      align: "right" as const,
      width: 110,
      render: (_: unknown, day: AnalyticsDay) => num(metric(day, code)),
    })),
  ];

  return <Panel title={title}>
    <AntTable<AnalyticsDay>
      className="analytics-daily-table"
      columns={tableColumns}
      dataSource={days.slice().reverse()}
      locale={{ emptyText: "所选日期范围内暂无数据" }}
      pagination={{
        current: page,
        hideOnSinglePage: true,
        onChange: (nextPage, nextPageSize) => {
          setPage(nextPageSize === pageSize ? nextPage : 1);
          setPageSize(nextPageSize);
        },
        pageSize,
        pageSizeOptions: [14, 31, 90],
        showSizeChanger: days.length > 14,
        showTotal: (total) => `共 ${num(total)} 天`,
      }}
      rowKey="date"
      scroll={{ x: 128 + columns.length * 110 }}
      size="small"
    />
  </Panel>;
}

function State({ loading, error, children }: { loading: boolean; error: string; children: ReactNode }) { if (loading) return <div aria-live="polite" className="state state-loading"><i /><span>正在读取数据…</span></div>; if (error) return <div className="state error" role="alert">{error}</div>; return <>{children}</>; }

function MetricSection({ title, items, primary = false }: { title: string; items: string[][]; primary?: boolean }) {
  return <section className="metric-section"><h2>{title}</h2><div className={primary ? "metric-grid metric-grid-primary" : "metric-grid"}>{items.map(([label, value, note]) => <article className="metric" key={label}><span>{label}</span><strong title={value}>{value}</strong><small>{note}</small></article>)}</div></section>;
}

function Panel({ title, children }: { title: ReactNode; children: ReactNode }) { return <section className="panel"><header><h2>{title}</h2></header>{children}</section>; }
function Table({ children, label = "数据列表" }: { children: ReactNode; label?: string }) { return <div className="table-scroll"><table aria-label={label}>{children}</table></div>; }
