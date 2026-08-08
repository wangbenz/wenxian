import "./App.css";
import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { adminApi, clearToken, isAuthenticated, setToken, type AccessControl, type ActionAdjudication, type AdminAccount, type AgentExecution, type AiUsageAnalytics, type AnalyticsDay, type AnalyticsOverview, type DashboardData, type GameplayAnalytics, type GameplayConfig, type GenerationProvider, type GrowthAnalytics, type LifeSummary, type MigrationOverview, type MigrationPolicy, type MigrationPreviewPage, type MigrationTask, type OperationError, type RegistrationConfig, type ReleaseNoteAdmin, type TurnPage, type TurnRankingItem, type TurnRow, type UserDetail, type UserRow, type V014Overview } from "./admin-api";

type Page = "dashboard" | "growth" | "gameplay" | "ai" | "migrations" | "users" | "errors" | "releases" | "models" | "limits" | "access" | "operations" | "admins" | "logs";
const NAV: Array<[Page, string, string, string]> = [
  ["dashboard", "运营概览", "OVERVIEW", "数据分析"], ["growth", "增长与留存", "RETENTION", "数据分析"], ["gameplay", "游玩情况", "GAMEPLAY", "数据分析"], ["ai", "模型消耗", "AI USAGE", "数据分析"],
  ["users", "玩家档案", "PLAYERS", "玩家支持"], ["errors", "问题查询", "SUPPORT", "玩家支持"],
  ["releases", "版本公告", "RELEASES", "发布与配置"], ["models", "生成模型", "MODELS", "发布与配置"], ["limits", "推演额度", "LIMITS", "发布与配置"], ["access", "灰度访问", "ACCESS", "发布与配置"], ["operations", "运营配置", "OPERATIONS", "发布与配置"],
  ["migrations", "命卷迁移", "MIGRATION", "系统维护"], ["admins", "管理账号", "ADMINS", "系统维护"], ["logs", "操作记录", "AUDIT", "系统维护"],
];
const fmt = (value?: string | null) => value ? new Date(value).toLocaleString("zh-CN") : "—";
const shanghaiDate = () => new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai" }).format(new Date());
const num = (value: number) => new Intl.NumberFormat("zh-CN").format(value || 0);
const compactNum = (value: number) => new Intl.NumberFormat("zh-CN", { notation: "compact", maximumFractionDigits: 1 }).format(value || 0);
const latency = (value: number) => value ? `${(value / 1000).toFixed(1)} 秒` : "—";
const difficulty = (value: string) => value === "HARD" ? "困难" : "普通";
const lifeEndReason = (value: LifeSummary["endReason"]) => value === "DEAD" ? "身死道消" : value === "ABANDONED" ? "主动封存" : value === "RESTARTED" ? "主动重开" : value === "ACTIVE" ? "当前命卷" : "旧版记录";
const problemCategory = (value: string) => ({ INPUT: "输入与规则", RESOURCE: "资源与货币", ACCOUNT: "账号登录", CREDENTIAL: "推演密钥", QUOTA: "回合额度", STATE: "存档状态", SYSTEM: "服务异常" } as Record<string, string>)[value] ?? value;

function useOverlay(onClose: () => void) {
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const escape = (event: KeyboardEvent) => event.key === "Escape" && onClose();
    window.addEventListener("keydown", escape);
    return () => { document.body.style.overflow = previous; window.removeEventListener("keydown", escape); };
  }, [onClose]);
}

function useLoad<T>(loader: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null); const [error, setError] = useState(""); const [loading, setLoading] = useState(true); const [nonce, setNonce] = useState(0);
  useEffect(() => { let live = true; setLoading(true); setError(""); loader().then((value) => live && setData(value)).catch((reason) => live && setError(reason instanceof Error ? reason.message : "加载失败")).finally(() => live && setLoading(false)); return () => { live = false; }; }, [...deps, nonce]);
  return { data, error, loading, reload: () => setNonce((value) => value + 1) };
}

export default function App() {
  const [authed, setAuthed] = useState(isAuthenticated);
  if (!authed) return <Login onDone={() => setAuthed(true)} />;
  return <Shell onLogout={() => { clearToken(); setAuthed(false); }} />;
}

function Login({ onDone }: { onDone: () => void }) {
  const [username, setUsername] = useState(""); const [password, setPassword] = useState(""); const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => { event.preventDefault(); setBusy(true); setError(""); try { const result = await adminApi.login(username.trim(), password); setToken(result.token); onDone(); } catch (reason) { setError(reason instanceof Error ? reason.message : "登录失败"); } finally { setBusy(false); } };
  return <main className="login-shell"><form className="login-card" onSubmit={submit}><span className="brand-mark">问</span><p className="kicker">WENXIAN OPERATIONS</p><h1>问仙录管理台</h1><p>管理试玩玩家、命卷版本与生成服务。</p><label>账号<input autoComplete="username" autoFocus value={username} onChange={(e) => setUsername(e.currentTarget.value)} /></label><label>密码<input autoComplete="current-password" type="password" value={password} onChange={(e) => setPassword(e.currentTarget.value)} /></label>{error && <p className="error">{error}</p>}<button className="primary" disabled={busy} type="submit">{busy ? "正在验证…" : "进入管理台"}</button></form></main>;
}

function Shell({ onLogout }: { onLogout: () => void }) {
  const [page, setPage] = useState<Page>("dashboard"); const [open, setOpen] = useState(false); const [securityOpen, setSecurityOpen] = useState(false); const [registrationDate, setRegistrationDate] = useState("");
  const version = useLoad(() => adminApi.appVersion(), []);
  const item = NAV.find(([id]) => id === page)!;
  const groups = [...new Set(NAV.map((entry) => entry[3]))];
  return <div className="admin-shell"><aside aria-label="管理台导航" className={open ? "sidebar open" : "sidebar"}><button aria-label="关闭导航菜单" className="sidebar-close" onClick={() => setOpen(false)}>×</button><div className="sidebar-brand"><strong>问仙录</strong><span>试玩运营台 · {version.data?.version ?? "同步中"}</span></div><nav>{groups.map((group) => <section className="nav-group" key={group}><h2>{group}</h2>{NAV.filter((entry) => entry[3] === group).map(([id, label, english]) => <button aria-current={page === id ? "page" : undefined} key={id} onClick={() => { if (id === "users") setRegistrationDate(""); setPage(id); setOpen(false); }}><small>{english}</small>{label}</button>)}</section>)}</nav><div className="sidebar-actions"><button onClick={() => { setSecurityOpen(true); setOpen(false); }}>修改管理密码</button><button onClick={onLogout}>退出管理台</button></div></aside>{open && <button aria-label="关闭导航菜单" className="sidebar-mask" onClick={() => setOpen(false)} />}<main className="admin-main"><header><button aria-expanded={open} aria-label="打开导航菜单" className="menu" onClick={() => setOpen(true)}><span aria-hidden="true">☰</span></button><div><p>{item[2]}</p><h1>{item[1]}</h1></div><span className="environment">内部试玩环境</span></header><section className="admin-content">{page === "dashboard" && <AnalyticsDashboard onSelectRegistrationDate={(date) => { setRegistrationDate(date); setPage("users"); }} />}{page === "growth" && <GrowthPage />}{page === "gameplay" && <GameplayPage />}{page === "ai" && <AiUsagePage />}{page === "migrations" && <MigrationCenter />}{page === "users" && <Users initialRegisteredDate={registrationDate} />}{page === "errors" && <OperationErrors />}{page === "releases" && <Releases currentVersion={version.data?.version ?? ""} onVersionChanged={version.reload} />}{page === "models" && <Models />}{page === "limits" && <Limits />}{page === "access" && <AccessControlPage />}{page === "operations" && <Operations />}{page === "admins" && <AdminAccounts />}{page === "logs" && <Logs />}</section></main>{securityOpen && <ChangePasswordModal onClose={() => setSecurityOpen(false)} onChanged={onLogout} />}</div>;
}

const metric = (day: AnalyticsDay | undefined, code: string) => Number(day?.[code] ?? 0);

function AnalyticsDashboard({ onSelectRegistrationDate }: { onSelectRegistrationDate: (date: string) => void }) {
  const query = useLoad<AnalyticsOverview>(() => adminApi.analyticsOverview(14), []);
  return <State loading={query.loading} error={query.error}>{query.data && <>
    <div className="page-intro"><div><h2>今天发生了什么</h2><p>只统计有效游玩行为；登录但未游玩不会计入活跃。</p></div><span className="data-freshness">更新于 {fmt(query.data.calculatedAt)}</span></div>
    {query.data.alerts.length > 0 && <section className="operation-alerts"><strong>需要留意</strong><div>{query.data.alerts.map((alert) => <p key={alert}>{alert}</p>)}</div></section>}
    <MetricSection primary title="核心运营" items={[
      ["累计账号", num(query.data.totalUsers), "当前有效账号"],
      ["今日新增", num(metric(query.data.today, "REGISTERED_USERS")), "按北京时间自然日"],
      ["今日活跃", num(metric(query.data.today, "ACTIVE_USERS")), "发生过有效游玩行为"],
      ["今日完成回合", num(metric(query.data.today, "SUCCESSFUL_TURNS")), `累计 ${num(query.data.totalSuccessfulTurns)}`],
    ]} />
    <AnalyticsTrend title="近 14 日核心趋势" days={query.data.trend} columns={[["REGISTERED_USERS", "新增"], ["ACTIVE_USERS", "活跃"], ["SUCCESSFUL_TURNS", "回合"], ["FINAL_ERRORS", "最终失败"]]} onDate={onSelectRegistrationDate} />
  </>}</State>;
}

function GrowthPage() {
  const query = useLoad<GrowthAnalytics>(() => adminApi.analyticsGrowth(30), []);
  return <State loading={query.loading} error={query.error}>{query.data && <>
    <div className="page-intro"><div><h2>增长与留存</h2><p>{query.data.definition}</p></div></div>
    <AnalyticsTrend title="近 30 日新增、活跃与首回合" days={query.data.daily} columns={[["REGISTERED_USERS", "新增"], ["ACTIVE_USERS", "活跃"], ["CREATED_SAVES", "创建命卷"], ["SUCCESSFUL_TURNS", "完成回合"]]} />
    <Panel title="注册批次留存"><Table label="注册批次留存"><thead><tr><th>注册日期</th><th>观察日</th><th>注册</th><th>已激活</th><th>再次活跃</th><th>留存率</th><th>口径</th></tr></thead><tbody>{query.data.retention.map((row) => <tr key={`${row.cohortDate}-${row.day}`}><td>{row.cohortDate}</td><td>D{row.day}</td><td>{num(row.registered)}</td><td>{num(row.activated)}</td><td>{num(row.retained)}</td><td>{Number(row.rate).toFixed(1)}%</td><td>{row.origin === "BACKFILL" ? "历史回算" : "实时"}</td></tr>)}</tbody></Table>{query.data.retention.length === 0 && <p className="empty-state">统计任务完成后会显示留存数据。</p>}</Panel>
  </>}</State>;
}

function GameplayPage() {
  const query = useLoad<GameplayAnalytics>(() => adminApi.analyticsGameplay(30), []);
  return <State loading={query.loading} error={query.error}>{query.data && <>
    <MetricSection primary title="累计有效行为" items={[["完成回合", num(query.data.totals.turns), "成功写入剧情"], ["创建命卷", num(query.data.totals.createdSaves), "含历史回算"], ["命卷修正", num(query.data.totals.corrections), "玩家主动修正"], ["整理实体", num(query.data.totals.entityRemovals), "移出物品或人物"]]} />
    <AnalyticsTrend title="近 30 日游玩情况" days={query.data.daily} columns={[["ACTIVE_USERS", "活跃"], ["SUCCESSFUL_TURNS", "完成回合"], ["CORRECTIONS", "修正"], ["ARCHIVED_SAVES", "封存"]]} />
  </>}</State>;
}

function AiUsagePage() {
  const query = useLoad<AiUsageAnalytics>(() => adminApi.analyticsAiUsage(14), []);
  return <State loading={query.loading} error={query.error}>{query.data && <>
    <AnalyticsTrend title="平台额度 · 近 14 日" days={query.data.platform} columns={[["AI_REQUESTS", "请求"], ["AI_FAILURES", "失败"], ["PROMPT_TOKENS", "输入 Token"], ["COMPLETION_TOKENS", "输出 Token"]]} />
    <AnalyticsTrend title="玩家自备密钥 · 近 14 日" days={query.data.player} columns={[["AI_REQUESTS", "请求"], ["AI_FAILURES", "失败"], ["PROMPT_TOKENS", "输入 Token"], ["COMPLETION_TOKENS", "输出 Token"]]} />
    <Panel title="模型与密钥来源"><Table label="模型消耗"><thead><tr><th>供应商</th><th>模型</th><th>来源</th><th>请求</th><th>输入</th><th>输出</th><th>缓存命中</th><th>失败</th><th>平均耗时</th></tr></thead><tbody>{query.data.models.map((row) => <tr key={`${row.provider}-${row.model}-${row.source}`}><td>{row.provider}</td><td>{row.model}</td><td>{row.source === "PUBLIC" ? "平台" : "玩家"}</td><td>{num(row.requests)}</td><td>{num(row.promptTokens)}</td><td>{num(row.completionTokens)}</td><td>{num(row.cacheHitTokens)}</td><td>{num(row.failures)}</td><td>{latency(row.averageLatency)}</td></tr>)}</tbody></Table></Panel>
  </>}</State>;
}

function AnalyticsTrend({ title, days, columns, onDate }: { title: string; days: AnalyticsDay[]; columns: Array<[string, string]>; onDate?: (date: string) => void }) {
  return <Panel title={title}><Table label={title}><thead><tr><th>日期</th>{columns.map(([, label]) => <th key={label}>{label}</th>)}</tr></thead><tbody>{days.slice().reverse().map((day) => <tr key={day.date}><td>{onDate ? <button className="table-action" onClick={() => onDate(day.date)}>{day.date}</button> : day.date}</td>{columns.map(([code]) => <td key={code}>{num(metric(day, code))}</td>)}</tr>)}</tbody></Table></Panel>;
}

function V014Operations() {
  const query = useLoad<V014Overview>(() => adminApi.v014Overview(), []);
  return <State loading={query.loading} error={query.error}>{query.data && <><div className="page-intro"><div><h2>V0.14 独立数据观察</h2><p>只统计新命卷表，不与 V0.13 历史角色和回合混算。</p></div><button className="secondary" onClick={query.reload}>刷新</button></div><MetricSection primary title="新版本命卷" items={[["参与玩家", num(query.data.players), "至少创建一份 V0.14 命卷"], ["存活命卷", num(query.data.activeSaves), `全部 ${num(query.data.totalSaves)} 份`], ["封存档案", num(query.data.archivedSaves), "只读保留"], ["累计行动", num(query.data.totalTurns), `平均生成 ${latency(query.data.avgLatency)}`]]} /><MetricSection title="运行与迁移" items={[["自备密钥用户", num(query.data.personalCredentialUsers), query.data.invalidCredentialUsers ? `${num(query.data.invalidCredentialUsers)} 个密钥待处理` : "当前没有失效密钥"], ["命卷人工整理", num(query.data.manualCorrections), "修正、移出物品与取消人物关注"], ["旧密钥迁移缺口", num(query.data.legacyCredentialMigrationGaps), query.data.legacyCredentialMigrationGaps ? "上线前必须处理" : "迁移完整"]]} /><MetricSection title="模型消耗" items={[["输入 Token", num(query.data.promptTokens), "V0.14 累计"], ["输出 Token", num(query.data.completionTokens), "V0.14 累计"], ["单回合平均", num(query.data.totalTurns ? Math.round((query.data.promptTokens + query.data.completionTokens) / query.data.totalTurns) : 0), "输入与输出合计"]]} /><Panel title="模型与密钥来源"><Table label="V0.14 模型统计"><thead><tr><th>供应商</th><th>模型</th><th>来源</th><th>请求</th><th>输入 Token</th><th>输出 Token</th><th>平均耗时</th></tr></thead><tbody>{query.data.modelUsage.map((row) => <tr key={`${row.provider}-${row.model}-${row.source}`}><td>{row.provider}</td><td>{row.model}</td><td>{row.source === "USER" ? "玩家自备" : "平台"}</td><td>{num(row.requests)}</td><td>{num(row.promptTokens)}</td><td>{num(row.completionTokens)}</td><td>{latency(row.avgLatency)}</td></tr>)}</tbody></Table>{query.data.modelUsage.length === 0 && <p className="empty-state">尚未产生真实模型回合。</p>}</Panel><Panel title="最近 30 次行动"><Table label="V0.14 最近行动"><thead><tr><th>用户</th><th>命卷</th><th>回合</th><th>行动</th><th>来源</th><th>Token</th><th>耗时</th><th>时间</th></tr></thead><tbody>{query.data.recentTurns.map((row) => <tr key={`${row.saveId}-${row.turn}`}><td>{shortId(row.userId)}</td><td>{shortId(row.saveId)}</td><td>{row.turn}</td><td className="wide">{row.action}</td><td>{row.source === "USER" ? "玩家" : "平台"}</td><td>{num(row.tokens)}</td><td>{latency(row.latency)}</td><td>{fmt(row.at)}</td></tr>)}</tbody></Table>{query.data.recentTurns.length === 0 && <p className="empty-state">尚未产生 V0.14 行动。</p>}</Panel></>}</State>;
}

function MigrationCenter() {
  const overview = useLoad<MigrationOverview>(() => adminApi.migrationOverview(), []);
  const tasks = useLoad<MigrationTask[]>(() => adminApi.migrationTasks(), []);
  const [preview, setPreview] = useState<MigrationPreviewPage | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const [cursorHistory, setCursorHistory] = useState<string[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [policy, setPolicy] = useState<MigrationPolicy>({ prepareEnabled: false, activationEnabled: false, batchLimit: 10 });
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const [activation, setActivation] = useState<MigrationTask | null>(null);

  useEffect(() => { if (overview.data) setPolicy(overview.data.policy); }, [overview.data]);
  const refresh = () => { overview.reload(); tasks.reload(); };
  const loadPreview = async (cursor = "", remember = true) => {
    setPreviewLoading(true); setPreviewError(""); setSelected([]);
    try {
      const page = await adminApi.migrationPreview(cursor, policy.batchLimit);
      if (remember && preview) setCursorHistory((current) => [...current, preview.cursor]);
      setPreview(page);
    } catch (reason) { setPreviewError(reason instanceof Error ? reason.message : "预演失败"); }
    finally { setPreviewLoading(false); }
  };
  const previousPreview = () => {
    const previous = cursorHistory.at(-1) ?? "";
    setCursorHistory((current) => current.slice(0, -1));
    void loadPreview(previous, false);
  };
  const savePolicy = async () => {
    setBusy(true); setNotice("");
    try { const result = await adminApi.updateMigrationPolicy(policy); setPolicy(result); setNotice("迁移安全开关已更新。"); refresh(); }
    catch (reason) { setNotice(reason instanceof Error ? reason.message : "保存失败"); }
    finally { setBusy(false); }
  };
  const prepare = async () => {
    setBusy(true); setNotice("");
    try {
      const result = await adminApi.prepareMigrations(selected);
      setNotice(`准备完成：成功 ${result.succeeded} 份，失败 ${result.failed} 份。候选命卷尚未激活。`);
      await loadPreview(preview?.cursor ?? "", false); refresh();
    } catch (reason) { setNotice(reason instanceof Error ? reason.message : "准备失败"); }
    finally { setBusy(false); }
  };
  const toggle = (sourceSaveId: string) => setSelected((current) => current.includes(sourceSaveId)
    ? current.filter((id) => id !== sourceSaveId) : [...current, sourceSaveId]);
  const statusCount = (key: string) => overview.data?.statusCounts[key] ?? 0;

  return <><State loading={overview.loading || tasks.loading} error={overview.error || tasks.error}>{overview.data && tasks.data && <>
    <div className="page-intro"><div><h2>V0.14 命卷迁移中心</h2><p>先只读预演，再写入隔离候选，最后逐份激活。旧表始终保留且不会被迁移流程修改。</p></div><button className="secondary" onClick={refresh}>刷新</button></div>
    <MetricSection primary title="迁移进度" items={[["旧命卷总数", num(overview.data.sourceTotal), "V0.14 源表"], ["尚未处理", num(overview.data.unprocessed), "没有迁移审计任务"], ["等待激活", num(statusCount("READY")), "仍对玩家不可见"], ["已激活", num(statusCount("SUCCESS")), "已通过双重哈希校验"]]} />
    <Panel title="迁移安全开关"><div className="config-form migration-policy">
      <label className="check"><input checked={policy.prepareEnabled} onChange={(event) => setPolicy({ ...policy, prepareEnabled: event.currentTarget.checked })} type="checkbox" />允许写入隔离候选</label>
      <label className="check"><input checked={policy.activationEnabled} onChange={(event) => setPolicy({ ...policy, activationEnabled: event.currentTarget.checked })} type="checkbox" />允许单份激活</label>
      <label>单次批量上限<input max="50" min="1" type="number" value={policy.batchLimit} onChange={(event) => setPolicy({ ...policy, batchLimit: Number(event.currentTarget.value) })} /></label>
      <p>两个写开关默认关闭。开启“候选”不会影响玩家；开启“激活”后仍需在下方逐份确认双哈希，不存在全量激活入口。</p>
      {notice && <p className="notice" role="status">{notice}</p>}
      <button className="primary" disabled={busy || policy.batchLimit < 1 || policy.batchLimit > 50} onClick={() => void savePolicy()}>{busy ? "正在保存…" : "保存迁移策略"}</button>
    </div></Panel>
    <Panel title="只读批量预演"><div className="migration-toolbar"><button className="secondary" disabled={previewLoading} onClick={() => void loadPreview("")}>{previewLoading ? "正在预演…" : "从第一批开始预演"}</button><span>每次最多 {policy.batchLimit} 份，仅查询旧表并计算候选哈希。</span></div>
      {previewError && <p className="error migration-message">{previewError}</p>}
      {preview && <><Table label="迁移预演结果"><thead><tr><th>选择</th><th>源命卷</th><th>预演</th><th>迁移任务</th><th>内容量</th><th>检查项</th><th>哈希</th></tr></thead><tbody>{preview.items.map((item) => {
        const selectable = ["READY", "WARNING"].includes(item.previewStatus) && item.migrationStatus === "UNPROCESSED";
        return <tr key={item.sourceSaveId}><td><input aria-label={`选择 ${item.sourceSaveId}`} checked={selected.includes(item.sourceSaveId)} disabled={!selectable || busy} onChange={() => toggle(item.sourceSaveId)} type="checkbox" /></td><td>{shortId(item.sourceSaveId)}</td><td><Badge value={item.previewStatus} />{item.error && <small>{item.error}</small>}</td><td><Badge value={item.migrationStatus} /></td><td>{item.turns} 回合<small>{item.entities} 实体 · {item.memories} 记忆</small></td><td className="wide">{item.issueCodes.length ? item.issueCodes.join("、") : "无阻断项"}</td><td>{shortHash(item.beforeHash)}<small>{shortHash(item.afterHash)}</small></td></tr>;
      })}</tbody></Table><div className="migration-actions"><div><button className="secondary" disabled={!cursorHistory.length || previewLoading} onClick={previousPreview}>上一批</button><button className="secondary" disabled={!preview.nextCursor || previewLoading} onClick={() => void loadPreview(preview.nextCursor ?? "")}>下一批</button></div><button className="primary" disabled={busy || !policy.prepareEnabled || !selected.length} onClick={() => void prepare()}>准备所选 {selected.length} 份候选</button></div></>}
    </Panel>
    <Panel title="最近迁移任务"><Table label="迁移任务"><thead><tr><th>源命卷</th><th>目标命卷</th><th>状态</th><th>双哈希</th><th>说明</th><th>时间</th><th>操作</th></tr></thead><tbody>{tasks.data.map((task) => <tr key={task.sourceSaveId}><td>{shortId(task.sourceSaveId)}</td><td>{task.targetSaveId ? shortId(task.targetSaveId) : "—"}</td><td><Badge value={task.status} /></td><td>{shortHash(task.beforeHash)}<small>{shortHash(task.afterHash)}</small></td><td className="wide">{task.errorMessage || "—"}</td><td>{fmt(task.completedAt || task.startedAt)}</td><td><button className="table-action" disabled={task.status !== "READY" || !policy.activationEnabled} onClick={() => setActivation(task)}>核对并激活</button></td></tr>)}</tbody></Table>{tasks.data.length === 0 && <p className="empty-state">尚未产生迁移任务。</p>}</Panel>
  </>}</State>{activation && <MigrationActivationModal task={activation} onClose={() => setActivation(null)} onCompleted={(message) => { setActivation(null); setNotice(message); refresh(); if (preview) void loadPreview(preview.cursor, false); }} />}</>;
}

function MigrationActivationModal({ task, onClose, onCompleted }: { task: MigrationTask; onClose: () => void; onCompleted: (message: string) => void }) {
  useOverlay(onClose);
  const [confirmation, setConfirmation] = useState(""); const [busy, setBusy] = useState(false); const [error, setError] = useState("");
  const expected = task.sourceSaveId;
  const submit = async () => {
    if (confirmation !== expected || !task.afterHash) return;
    setBusy(true); setError("");
    try { const result = await adminApi.activateMigration(task.sourceSaveId, task.beforeHash, task.afterHash); onCompleted(result.message); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "激活失败"); }
    finally { setBusy(false); }
  };
  return <div className="modal-mask"><section aria-labelledby="migration-activation-title" aria-modal="true" className="modal confirm-modal" role="dialog"><header className="drawer-header"><div><p className="kicker">FINAL ACTIVATION</p><h2 id="migration-activation-title">激活单份候选命卷</h2></div><button aria-label="关闭激活确认" className="drawer-close" onClick={onClose}>×</button></header><p>激活后该候选会进入统一命卷可见范围。系统仍会再次核对源数据与候选数据哈希。</p><div className="migration-hash"><span>迁移前<strong>{task.beforeHash}</strong></span><span>迁移后<strong>{task.afterHash}</strong></span></div><label>输入完整源命卷 ID 确认<input autoComplete="off" autoFocus value={confirmation} onChange={(event) => setConfirmation(event.currentTarget.value)} /><small>{expected}</small></label>{error && <p className="error" role="alert">{error}</p>}<div className="modal-actions"><button className="secondary" onClick={onClose}>取消</button><button className="danger-link" disabled={busy || confirmation !== expected} onClick={() => void submit()}>{busy ? "正在校验…" : "确认激活"}</button></div></section></div>;
}

function State({ loading, error, children }: { loading: boolean; error: string; children: ReactNode }) { if (loading) return <div aria-live="polite" className="state state-loading"><i /><span>正在读取数据…</span></div>; if (error) return <div className="state error" role="alert">{error}</div>; return <>{children}</>; }

type SelectOption = { value: string; label: string };

function SelectMenu({ label, value, options, onChange }: {
  label: string; value: string; options: SelectOption[]; onChange: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const selected = options.find((option) => option.value === value) ?? options[0];
  useEffect(() => {
    const closeOutside = (event: PointerEvent) => {
      if (root.current && !root.current.contains(event.target as Node)) setOpen(false);
    };
    const closeEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("pointerdown", closeOutside);
    window.addEventListener("keydown", closeEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOutside);
      window.removeEventListener("keydown", closeEscape);
    };
  }, []);
  return <div className={open ? "select-menu open" : "select-menu"} ref={root}>
    <button aria-expanded={open} aria-haspopup="listbox" aria-label={`${label}：${selected.label}`} className="select-trigger" onClick={() => setOpen((current) => !current)} type="button">
      <span>{selected.label}</span><b aria-hidden="true">⌄</b>
    </button>
    {open && <div aria-label={label} className="select-options" role="listbox">{options.map((option) =>
      <button aria-selected={option.value === value} key={option.value || "all"} onClick={() => { onChange(option.value); setOpen(false); }} role="option" type="button">{option.label}{option.value === value && <span aria-hidden="true">✓</span>}</button>
    )}</div>}
  </div>;
}

function Dashboard({ onSelectRegistrationDate }: { onSelectRegistrationDate: (date: string) => void }) {
  const query = useLoad<DashboardData>(() => adminApi.dashboard(), []);
  const todayPlatformTokens = query.data ? query.data.todayPlatformPromptTokens + query.data.todayPlatformCompletionTokens : 0;
  const todayCustomTokens = query.data ? query.data.todayCustomPromptTokens + query.data.todayCustomCompletionTokens : 0;
  const core = query.data ? [
    ["总用户", num(query.data.totalUsers), `今日注册 ${num(query.data.todayRegistrations)} · 昨日注册 ${num(query.data.yesterdayRegistrations)}`],
    ["平台 Token 总量", num(query.data.totalPlatformTokens), `今日 ${num(todayPlatformTokens)}`],
    ["玩家 Token 总量", num(query.data.totalCustomTokens), `今日 ${num(todayCustomTokens)} · 玩家自行承担`],
    ["当前在线", num(query.data.onlinePlayers), "15分钟内有活动"],
  ] : [];
  const activity = query.data ? [
    ["当前角色", num(query.data.currentCharacters), `全部角色 ${num(query.data.totalCharacters)}`],
    ["历史角色", num(query.data.historicalCharacters), `涉及 ${num(query.data.usersWithHistoricalCharacters)} 位玩家`],
    ["死亡 / 重开", `${num(query.data.deadCharacters)} / ${num(query.data.restartedCharacters)}`, "按角色结局统计"],
    ["今日活跃", num(query.data.todayActive), "今日访问过"],
    ["模型请求", num(query.data.todayAiRequests), `失败 ${num(query.data.todayAiFailures)} 次`],
    ["输入缓存", num(query.data.todayPlatformCacheHitTokens), `未命中 ${num(query.data.todayPlatformCacheMissTokens)}`],
    ["平台每章 Token", num(query.data.todayPlatformAverageTokensPerChapter), "今日成功章节平均"],
    ["玩家每章 Token", num(query.data.todayCustomAverageTokensPerChapter), "今日自备密钥章节平均"],
    ["平台回合总量", num(query.data.totalPlatformTurns), `今日 ${num(query.data.todayPlatformTurns)}`], ["玩家回合总量", num(query.data.totalCustomTurns), `今日 ${num(query.data.todayCustomTurns)}`], ["自备密钥玩家", num(query.data.customApiUsers), "当前已启用"], ["平均生成", latency(query.data.avgLatency), "成功回合耗时"], ["当前模型", query.data.currentModel, "新请求立即生效"],
  ] : [];
  return <State loading={query.loading} error={query.error}>{query.data && <><OperationalAlerts data={query.data} /><MetricSection title="核心运营" items={core} primary /><ActivationFunnel stages={query.data.activationFunnel} /><MetricSection title="运行情况" items={activity} /><RegistrationTrend days={query.data.dailyRegistrations} onSelectDate={onSelectRegistrationDate} /><TokenTrend days={query.data.dailyTokens} trackingStartedAt={query.data.usageTrackingStartedAt} /><VersionMetrics rows={query.data.versionMetrics} /><ModelUsage rows={query.data.modelUsage} /><Panel title="最近生成"><Table label="最近生成记录"><thead><tr><th>玩家</th><th>回合</th><th>行动</th><th>总 Token</th><th>生成耗时</th><th>发生时间</th></tr></thead><tbody>{query.data.recentTurns.map((turn, index) => <tr key={`${turn.at}-${index}`}><td>{turn.playerName || "未命名"}<small>{turn.realm}</small></td><td>{turn.turn}</td><td className="wide">{turn.action}</td><td>{num(turn.tokens)}</td><td>{latency(turn.latency)}</td><td>{fmt(turn.at)}</td></tr>)}</tbody></Table></Panel></>}</State>;
}

function VersionMetrics({ rows }: { rows: DashboardData["versionMetrics"] }) {
  if (!rows.length) return null;
  return <Panel title="版本影响"><Table label="版本影响统计"><thead><tr><th>版本</th><th>角色</th><th>完成回合</th><th>拒绝行动</th><th>死亡</th><th>模型请求</th><th>总 Token</th><th>失败率</th><th>平均耗时</th></tr></thead><tbody>{rows.map((row) => <tr key={row.version}><td>{row.version}</td><td>{num(row.characters)}</td><td>{num(row.turns)}</td><td>{num(row.rejections)}</td><td>{num(row.deaths)}</td><td>{num(row.requests)}</td><td>{num(row.tokens)}</td><td>{row.requests ? `${((row.failures / row.requests) * 100).toFixed(1)}%` : "—"}</td><td>{latency(row.avgLatency)}</td></tr>)}</tbody></Table></Panel>;
}

function OperationalAlerts({ data }: { data: DashboardData }) {
  const alerts: string[] = [];
  const failureRate = data.todayAiRequests > 0 ? data.todayAiFailures / data.todayAiRequests : 0;
  if (!data.platformKeyHealthy) alerts.push("当前平台生成密钥不可用，请检查生成模型配置。");
  if (data.lockedLoginPairs > 0) alerts.push(`当前有 ${data.lockedLoginPairs} 组登录请求触发临时限制。`);
  if (data.todayAiRequests >= 10 && failureRate >= .1) alerts.push(`今日生成失败率为 ${(failureRate * 100).toFixed(1)}%，建议检查模型服务。`);
  if (data.avgLatency >= 30_000) alerts.push(`平均生成耗时已达到 ${latency(data.avgLatency)}。`);
  if (alerts.length === 0) return null;
  return <section aria-label="运行提醒" className="operation-alerts"><strong>需要留意</strong><div>{alerts.map((alert) => <p key={alert}>{alert}</p>)}</div></section>;
}

function ActivationFunnel({ stages }: { stages: DashboardData["activationFunnel"] }) {
  return <Panel title="玩家转化 · 按账号去重"><div className="activation-funnel">{stages.map((stage, index) => <article key={stage.key}><span className="funnel-step">{index + 1}</span><div><small>{stage.label}</small><strong>{num(stage.users)}</strong><span>{stage.conversion}% 的注册用户</span></div><i aria-hidden="true" style={{ width: `${Math.max(stage.conversion, 4)}%` }} /></article>)}</div><p className="panel-note">同一玩家拥有多个角色时只计算一次；3回合和5回合必须由同一个角色达成，不会跨前世累加。</p></Panel>;
}

function RegistrationTrend({ days, onSelectDate }: { days: DashboardData["dailyRegistrations"]; onSelectDate: (date: string) => void }) {
  const [range, setRange] = useState<7 | 14>(7);
  const visibleDays = days.slice(-range);
  const maximum = Math.max(1, ...visibleDays.map((day) => day.count));
  const title = <div className="panel-title-row"><span>近{range}日用户增长 · 点击日期查看玩家</span><span className="range-switch" aria-label="用户增长统计范围"><button aria-pressed={range === 7} onClick={() => setRange(7)} type="button">近7天</button><button aria-pressed={range === 14} onClick={() => setRange(14)} type="button">近14天</button></span></div>;
  return <Panel title={title}><div className="registration-trend-scroll"><div className="registration-trend" style={{ gridTemplateColumns: `repeat(${range}, minmax(44px, 1fr))`, minWidth: range === 7 ? 430 : 760 }}>{visibleDays.map((day, index) => <button aria-label={`${day.date} 新增 ${day.count} 位用户，查看当天玩家`} className={index === visibleDays.length - 1 ? "registration-day today" : "registration-day"} key={day.date} onClick={() => onSelectDate(day.date)} title={`${day.date}：新增 ${num(day.count)} 位用户`} type="button"><span className="registration-bar"><i style={{ height: `${Math.max(day.count > 0 ? 5 : 0, day.count / maximum * 100)}%` }} /></span><strong>+{num(day.count)}</strong><small>{day.date.slice(5)}</small></button>)}</div></div></Panel>;
}

function TokenTrend({ days, trackingStartedAt }: { days: DashboardData["dailyTokens"]; trackingStartedAt: string }) {
  const [range, setRange] = useState<7 | 14>(7);
  const visibleDays = days.slice(-range);
  const maximum = Math.max(1, ...visibleDays.flatMap((day) => [
    day.platformPromptTokens + day.platformCompletionTokens,
    day.customPromptTokens + day.customCompletionTokens,
  ]));
  const trackingNote = trackingStartedAt ? ` · 自 ${fmt(trackingStartedAt)} 起` : "";
  const title = <div className="panel-title-row"><span>近{range}日 Token 用量{trackingNote}</span><span className="range-switch" aria-label="Token 统计范围"><button aria-pressed={range === 7} onClick={() => setRange(7)} type="button">近7天</button><button aria-pressed={range === 14} onClick={() => setRange(14)} type="button">近14天</button></span></div>;
  return <Panel title={title}><div className="token-legend"><span><i className="platform" />平台</span><span><i className="custom" />玩家自备</span></div><div className="token-trend-scroll"><div className="token-trend" style={{ gridTemplateColumns: `repeat(${range}, minmax(54px, 1fr))`, minWidth: range === 7 ? 500 : 880 }}>{visibleDays.map((day) => {
    const platform = day.platformPromptTokens + day.platformCompletionTokens;
    const custom = day.customPromptTokens + day.customCompletionTokens;
    return <article key={day.date} title={`${day.date}：平台 ${num(platform)}，玩家自备 ${num(custom)} Token`}>
      <div className="token-bars"><i className="platform" style={{ height: `${Math.max(platform > 0 ? 5 : 0, platform / maximum * 100)}%` }} /><i className="custom" style={{ height: `${Math.max(custom > 0 ? 5 : 0, custom / maximum * 100)}%` }} /></div>
      <div className="token-values"><span><i className="platform" />{compactNum(platform)}</span><span><i className="custom" />{compactNum(custom)}</span></div><strong>{day.date.slice(5)}</strong><small>平台 {day.platformTurns} 回 · 玩家 {day.customTurns} 回</small>
    </article>;
  })}</div></div><div className="table-scroll"><table className="usage-table"><thead><tr><th>日期</th><th>平台 Token</th><th>玩家 Token</th><th>平台回合</th><th>玩家回合</th><th>模型</th><th>模型请求</th><th>失败数</th><th>统计口径</th></tr></thead><tbody>{visibleDays.slice().reverse().map((day) => <tr key={day.date}><td>{day.date}</td><td>{num(day.platformPromptTokens + day.platformCompletionTokens)}</td><td>{num(day.customPromptTokens + day.customCompletionTokens)}</td><td>{num(day.platformTurns)}</td><td>{num(day.customTurns)}</td><td>{day.models || "—"}</td><td>{num(day.requests)}</td><td>{num(day.failures)}</td><td>{day.estimatedRequests > 0 ? "历史章节估算" : "逐次调用"}</td></tr>)}</tbody></table></div></Panel>;
}

function ModelUsage({ rows }: { rows: DashboardData["modelUsage"] }) {
  return <Panel title="按供应商、模型与密钥来源统计"><Table label="模型与密钥来源统计"><thead><tr><th>供应商</th><th>模型</th><th>来源</th><th>请求数</th><th>输入 Token</th><th>输出 Token</th><th>缓存命中</th><th>失败数</th><th>平均耗时</th><th>最近使用</th></tr></thead><tbody>{rows.map((row) => <tr key={`${row.provider}-${row.model}-${row.source}`}><td>{row.provider}</td><td>{row.model}</td><td>{row.source === "PUBLIC" ? "平台" : "玩家自备"}</td><td>{num(row.requests)}</td><td>{num(row.promptTokens)}</td><td>{num(row.completionTokens)}</td><td>{num(row.cacheHitTokens)}</td><td>{num(row.failures)}</td><td>{latency(row.avgLatency)}</td><td>{fmt(row.lastUsedAt)}</td></tr>)}</tbody></Table></Panel>;
}

function Users({ initialRegisteredDate = "" }: { initialRegisteredDate?: string }) {
  const [search, setSearch] = useState(""); const [debouncedSearch, setDebouncedSearch] = useState(""); const [state, setState] = useState(""); const [difficultyFilter, setDifficulty] = useState(""); const [registeredDate, setRegisteredDate] = useState(initialRegisteredDate); const [page, setPage] = useState(0); const [selected, setSelected] = useState<string | null>(null);
  useEffect(() => { const timer = window.setTimeout(() => setDebouncedSearch(search), 350); return () => window.clearTimeout(timer); }, [search]);
  const query = useLoad(() => adminApi.users(debouncedSearch, state, difficultyFilter, "", registeredDate, page, 20), [debouncedSearch, state, difficultyFilter, registeredDate, page]);
  const ranking = useLoad<TurnRankingItem[]>(() => adminApi.turnRanking(10), []);
  const meta = query.data?.[0]; const rows = query.data?.[1].rows ?? [];
  return <>
    <State loading={ranking.loading} error={ranking.error}>
      {ranking.data && <TurnRanking rows={ranking.data} onSelect={setSelected} />}
    </State>
    <div className="filters">
      <label className="search-field"><span className="sr-only">搜索玩家</span><input placeholder="搜索完整邮箱、角色名、用户或存档 ID" value={search} onChange={(e) => { setSearch(e.currentTarget.value); setPage(0); }} /></label>
      <label><span className="sr-only">注册日期</span><input aria-label="注册日期" type="date" value={registeredDate} onChange={(e) => { setRegisteredDate(e.currentTarget.value); setPage(0); }} /></label>
      <SelectMenu label="角色状态" value={state} onChange={(value) => { setState(value); setPage(0); }} options={[
        { value: "", label: "全部角色状态" },
        { value: "NONE", label: "尚未创建角色" },
        { value: "CURRENT", label: "当前有角色" },
        { value: "RESTARTED", label: "仅有前世记录" },
      ]} />
      <SelectMenu label="游戏难度" value={difficultyFilter} onChange={(value) => { setDifficulty(value); setPage(0); }} options={[
        { value: "", label: "全部难度" },
        { value: "NORMAL", label: "普通" },
        { value: "HARD", label: "困难" },
      ]} />
    </div>
    <div className="filter-shortcuts"><button onClick={() => { setRegisteredDate(shanghaiDate()); setState("NONE"); setPage(0); }} type="button">今日注册但未创建角色</button>{(registeredDate || state || difficultyFilter || search) && <button onClick={() => { setSearch(""); setRegisteredDate(""); setState(""); setDifficulty(""); setPage(0); }} type="button">清除筛选</button>}</div>
    <State loading={query.loading} error={query.error}>
      <Panel title={`玩家列表 · ${meta?.total ?? 0}${registeredDate ? ` · ${registeredDate} 注册` : ""}`}>
        <div className="desktop-player-table"><Table label="玩家列表"><thead><tr><th>邮箱</th><th>最近角色</th><th>存活角色</th><th>状态</th><th>难度</th><th>回合</th><th>密钥</th><th>前世</th><th>最近活动</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{rows.map((user: UserRow) => <tr key={user.userId}><td>{user.email}<small title={user.userId}>{shortId(user.userId)}</small></td><td>{user.playerName || "尚未创建角色"}<small>{user.realm || "—"}</small></td><td>{user.activeSaveCount}</td><td><Badge value={user.characterState} /></td><td>{user.saveId ? difficulty(user.difficulty) : "—"}</td><td>{user.turnCount}</td><td>{user.credentialConfigured ? `•••• ${user.credentialSuffix}` : "平台"}</td><td>{user.archiveCount}</td><td>{fmt(user.lastLoginAt)}</td><td><button className="table-action" onClick={() => setSelected(user.userId)}>查看</button></td></tr>)}</tbody></Table></div>
        <div className="mobile-player-list">{rows.map((user: UserRow) => <button className="player-card" key={user.userId} onClick={() => setSelected(user.userId)}><span className="player-card-main"><strong>{user.email}</strong><small>{user.playerName || "尚未创建角色"} · {user.realm || "未入世"}</small><small>当前 {user.activeSaveCount} 份命卷 · 前世 {user.archiveCount}</small></span><span className="player-card-meta"><Badge value={user.characterState} /><small>{user.credentialConfigured ? `自备 •••• ${user.credentialSuffix}` : "平台密钥"}</small><small>{relativeTime(user.lastLoginAt)}</small><b aria-hidden="true">›</b></span></button>)}</div>
        {rows.length === 0 && <p className="empty-state">没有符合当前条件的玩家。</p>}
        <Pager page={page} size={20} total={meta?.total ?? 0} onPage={setPage} />
      </Panel>
    </State>
    {selected && <UserDrawer userId={selected} onClose={() => setSelected(null)} />}
  </>;
}

function TurnRanking({ rows, onSelect }: { rows: TurnRankingItem[]; onSelect: (userId: string) => void }) {
  return <Panel title="当前角色回合榜 · 前 10 名"><div className="turn-ranking">{rows.map((row, index) => <button key={row.saveId} onClick={() => onSelect(row.userId)} type="button"><span className={index < 3 ? `rank rank-${index + 1}` : "rank"}>{index + 1}</span><span className="rank-player"><strong>{row.playerName || "尚未命名"}</strong><small>{row.email}</small><small>{row.realm || "尚未入道"} · {relativeTime(row.lastActiveAt)}</small></span><b>{num(row.turn)}<small>回</small></b></button>)}</div>{rows.length === 0 && <p className="empty-state">目前还没有已创建角色的玩家。</p>}<p className="panel-note">按当前存活命卷统计，前世回合不会累加；点击角色可查看完整账号与历代记录。</p></Panel>;
}

function UserDrawer({ userId, onClose }: { userId: string; onClose: () => void }) {
  useOverlay(onClose);
  const query = useLoad<UserDetail>(() => adminApi.userDetail(userId), [userId]); const [life, setLife] = useState<LifeSummary | null>(null);
  const [temporaryPassword, setTemporaryPassword] = useState(""); const [authMessage, setAuthMessage] = useState("");
  const resetPassword = async () => { const result = await adminApi.resetPlayerPassword(userId); setTemporaryPassword(result.temporaryPassword); setAuthMessage("临时密码只展示本次，请立即交给玩家。"); query.reload(); };
  const unlock = async () => { const result = await adminApi.unlockPlayerAuth(userId); setAuthMessage(result.message); };
  return <div className="drawer-mask" onMouseDown={(e) => e.target === e.currentTarget && onClose()}><aside aria-labelledby="player-drawer-title" aria-modal="true" className="drawer" role="dialog"><header className="drawer-header"><div><p className="kicker">PLAYER ARCHIVE</p><h2 id="player-drawer-title">{query.data?.user.email || "玩家详情"}</h2></div><button aria-label="关闭玩家详情" className="drawer-close" onClick={onClose}>×</button></header><State loading={query.loading} error={query.error}>{query.data && <><section className="detail-section"><h3>账号信息</h3><div className="identity-grid"><span>用户 ID<strong title={query.data.user.userId}>{shortId(query.data.user.userId)}</strong></span><span>注册时间<strong>{fmt(query.data.user.registeredAt)}</strong></span><span>最近活动<strong>{fmt(query.data.user.lastLoginAt)}</strong></span><span>登录密码<strong>{query.data.user.passwordConfigured ? "已设置" : "未设置"}</strong></span><span>自备密钥<strong>{query.data.user.credentialConfigured ? `${query.data.user.credentialStatus} · •••• ${query.data.user.credentialSuffix}` : "未设置"}</strong><small>{query.data.user.credentialVerifiedAt ? `验证于 ${fmt(query.data.user.credentialVerifiedAt)}` : "尚无验证记录"}</small></span><span>注册网络<strong>{query.data.user.registrationNetwork || "未记录"}</strong></span></div></section><AccountLifeSummary summary={query.data.accountSummary} /><section className="detail-section sensitive-actions"><div><h3>账号协助</h3><p>仅在玩家无法登录时使用，操作会写入管理记录。</p></div><div><button className="secondary" onClick={() => void unlock()}>解除登录限制</button><button className="danger-link" onClick={() => void resetPassword()}>生成临时密码</button></div></section>{temporaryPassword && <p className="notice" role="status">临时密码：<strong>{temporaryPassword}</strong></p>}{authMessage && <p className="notice" role="status">{authMessage}</p>}<LifeGroup title="当前命卷" lives={query.data.currentLives} empty="尚未创建当前角色" onSelect={setLife} /><LifeGroup title="前世记录" lives={query.data.historicalLives} empty="暂无重开或死亡记录" onSelect={setLife} /></>}</State></aside>{life && query.data && <TurnDrawer life={life} userId={query.data.user.userId} onClose={() => setLife(null)} />}</div>;
}

function AccountLifeSummary({ summary }: { summary: UserDetail["accountSummary"] }) {
  return <section className="detail-section"><h3>账号游玩汇总</h3><div className="life-summary-grid"><span>累计角色<strong>{num(summary.totalCharacters)}</strong><small>当前 {num(summary.currentCharacters)} · 前世 {num(summary.historicalCharacters)}</small></span><span>累计回合<strong>{num(summary.totalTurns)}</strong><small>所有命卷合计</small></span><span>章节 Token<strong>{num(summary.totalTokens)}</strong><small>所有已保存章节</small></span><span>最长一世<strong>{summary.longestLifeName || "—"}</strong><small>{num(summary.longestLifeTurns)} 回</small></span></div></section>;
}

function LifeGroup({ title, lives, empty, onSelect }: { title: string; lives: LifeSummary[]; empty: string; onSelect: (life: LifeSummary) => void }) { return <section className="life-group"><h3>{title}<span>{lives.length}</span></h3>{lives.length === 0 ? <p className="empty">{empty}</p> : lives.map((life) => { const reason = life.endReason || "LEGACY"; return <button className="life-card" key={life.saveId} onClick={() => onSelect(life)}><div><span className={`life-ending life-ending-${reason.toLowerCase()}`}>{lifeEndReason(reason)}</span><strong>{life.playerName}</strong><span>{life.realm || "尚未入世"} · {difficulty(life.difficulty)}</span>{life.endingTitle && <small>{life.endingTitle}</small>}</div><div><b>{life.turn} 回</b><span>{num(life.totalTokens)} Token</span><small>{fmt(life.at)}</small></div></button>; })}</section>; }

function TurnDrawer({ userId, life, onClose }: { userId: string; life: LifeSummary; onClose: () => void }) {
  useOverlay(onClose);
  const [page, setPage] = useState(0);
  const [jump, setJump] = useState("");
  const [selected, setSelected] = useState<TurnRow | null>(null);
  const query = useLoad<TurnPage>(() => adminApi.userTurns(userId, life.saveId, page, 20, jump), [userId, life.saveId, page, jump]);
  const adjudications = useLoad<ActionAdjudication[]>(() => adminApi.userAdjudications(userId, life.saveId), [userId, life.saveId]);
  return <aside aria-labelledby="turn-drawer-title" aria-modal="true" className="drawer drawer-wide" role="dialog">
    <header className="drawer-header"><div><p className="kicker">LIFE TURNS</p><h2 id="turn-drawer-title">{life.playerName} · {life.status === "CURRENT" ? "今世" : "前世"}</h2></div><button aria-label="关闭回合记录" className="drawer-close" onClick={onClose}>×</button></header>
    <div className="turn-toolbar"><input inputMode="numeric" placeholder="跳到回合" value={jump} onChange={(e) => { setJump(e.currentTarget.value.replace(/\D/g, "")); setPage(0); }} /><span>共 {query.data?.total ?? 0} 回</span></div>
    <State loading={adjudications.loading} error={adjudications.error}>{adjudications.data?.length ? <section className="adjudication-log"><h3>近期未执行行动</h3><div>{adjudications.data.map((item, index) => <article key={`${item.at}-${index}`}><strong>第 {item.turn} 回 · {item.reasonCode}</strong><span>{item.intentMode} / {item.actionType} · {item.stage}</span><small>{fmt(item.at)} · {item.version}</small></article>)}</div></section> : null}</State>
    <State loading={query.loading} error={query.error}><Table label="角色回合记录"><thead><tr><th>回合</th><th>玩家行动</th><th>输入 Token</th><th>输出 Token</th><th>总 Token</th><th>生成耗时</th><th>发生时间</th><th>状态</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{query.data?.rows.map((turn) => <tr key={turn.turn}><td>{turn.turn}</td><td className="wide">{turn.action}</td><td>{num(turn.promptTokens)}</td><td>{num(turn.completionTokens)}</td><td>{num(turn.totalTokens)}</td><td>{latency(turn.latency)}</td><td>{fmt(turn.at)}</td><td><Badge value={turn.status} /></td><td><button className="table-action" onClick={() => setSelected(turn)}>详情</button></td></tr>)}</tbody></Table><Pager page={page} size={20} total={query.data?.total ?? 0} onPage={setPage} /></State>
    {selected && <TurnDetailModal saveId={life.saveId} turn={selected} onClose={() => setSelected(null)} />}
  </aside>;
}

function TurnDetailModal({ saveId, turn, onClose }: { saveId: string; turn: TurnRow; onClose: () => void }) {
  useOverlay(onClose);
  const executions = useLoad<AgentExecution[]>(() => adminApi.executions(saveId, turn.turn), [saveId, turn.turn]);
  return <div className="modal-mask"><section aria-labelledby="turn-detail-title" aria-modal="true" className="modal turn-detail" role="dialog"><header className="drawer-header"><div><p className="kicker">TURN DETAIL</p><h2 id="turn-detail-title">第 {turn.turn} 回详情</h2></div><button aria-label="关闭回合详情" className="drawer-close" onClick={onClose}>×</button></header>
    <div className="turn-detail-grid"><span>生成模型<strong>{turn.model || "未记录"}</strong></span><span>生成耗时<strong>{latency(turn.latency)}</strong></span><span>输入 Token<strong>{num(turn.promptTokens)}</strong></span><span>输出 Token<strong>{num(turn.completionTokens)}</strong></span></div>
    <h3>玩家行动</h3><p className="detail-copy">{turn.action}</p>
    <h3>状态变化</h3><pre className="code-block">{prettyJson(turn.stateDelta)}</pre>
    <h3>生成尝试</h3><State loading={executions.loading} error={executions.error}>{executions.data?.length ? <div className="attempt-list">{executions.data.map((item) => <article key={`${item.turn}-${item.at}-${item.attempt}`}><div><Badge value={item.status} /><strong>第 {item.attempt} 次</strong><span>{item.model || "未记录"} · {latency(item.latency)}</span></div><p>{item.detail || "无异常记录"}</p></article>)}</div> : <p className="empty">该回合没有保留额外生成记录。</p>}</State>
  </section></div>;
}

function Releases({ currentVersion, onVersionChanged }: { currentVersion: string; onVersionChanged: () => void }) {
  const query = useLoad<ReleaseNoteAdmin[]>(() => adminApi.releaseNotes(), []);
  const [editing, setEditing] = useState<Partial<ReleaseNoteAdmin> | null>(null);
  const [versionDraft, setVersionDraft] = useState("");
  const [versionBusy, setVersionBusy] = useState(false);
  const [clearing, setClearing] = useState(false);
  const [message, setMessage] = useState("");
  useEffect(() => setVersionDraft(currentVersion), [currentVersion]);
  const create = () => setEditing({ version: currentVersion, title: "", content: "", status: "DRAFT", announceAll: false, showOnLogin: false });
  const pinned = query.data?.find((note) => note.status === "PUBLISHED" && note.showOnLogin);
  const saveVersion = async () => {
    setVersionBusy(true); setMessage("");
    try {
      const updated = await adminApi.updateAppVersion(versionDraft);
      setVersionDraft(updated.version); setMessage(`当前游戏版本已调整为 ${updated.version}`); onVersionChanged();
    } catch (reason) { setMessage(reason instanceof Error ? reason.message : "版本保存失败"); }
    finally { setVersionBusy(false); }
  };
  const clearPinned = async () => {
    if (!pinned || clearing) return;
    setClearing(true); setMessage("");
    try { const result = await adminApi.clearLoginAnnouncement(); setMessage(result.message); query.reload(); }
    catch (reason) { setMessage(reason instanceof Error ? reason.message : "撤下失败"); }
    finally { setClearing(false); }
  };
  return <><div className="page-intro"><div><h2>版本公告</h2><p>“全员公告”控制登录后的单次弹窗；“登录页公告”是独立的长期展示位。</p></div><button className="primary" disabled={!currentVersion} onClick={create}>新建版本公告</button></div><section className="app-version-control"><div><span>当前游戏版本</span><strong>{currentVersion || "正在同步"}</strong><p>H5、世界页和管理台统一读取此版本；发版后再调整，避免提前影响线上展示。</p></div><div><input aria-label="当前游戏版本" maxLength={16} onChange={(event) => setVersionDraft(event.currentTarget.value)} placeholder="V0.13" value={versionDraft} /><button className="secondary" disabled={versionBusy || !versionDraft.trim() || versionDraft === currentVersion} onClick={() => void saveVersion()}>{versionBusy ? "保存中…" : "更新版本"}</button></div></section><State loading={query.loading} error={query.error}>{pinned ? <section className="login-announcement-status"><div><span>当前登录页公告</span><strong>{pinned.version} · {pinned.title}</strong><p>发布其他未勾选登录页展示的公告，不会自动撤下这一条。</p></div><button className="danger-link" disabled={clearing} onClick={() => void clearPinned()}>{clearing ? "正在撤下…" : "停止登录页展示"}</button></section> : <section className="login-announcement-status empty"><div><span>当前登录页公告</span><strong>未展示</strong><p>玩家打开登录页时不会看到版本公告。</p></div></section>}{message && <p className="notice" role="status">{message}</p>}<div className="release-grid">{query.data?.map((note) => <article className="release-card" key={note.id}><div className="release-meta"><Badge value={note.status} /><span>{note.version}</span><small>{fmt(note.updatedAt)}</small></div><h2>{note.title}</h2><p className="release-copy">{note.content}</p><footer><span>{note.showOnLogin ? "登录页置顶" : note.announceAll ? `全员公告 · ${note.readCount} 人已读` : "仅版本记录"}</span><button className="table-action" onClick={() => setEditing(note)}>编辑公告</button></footer></article>)}</div>{query.data?.length === 0 && <p className="empty-state">还没有版本公告。</p>}</State>{editing && <ReleaseEditor note={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); query.reload(); }} />}</>;
}

function ReleaseEditor({ note, onClose, onSaved }: { note: Partial<ReleaseNoteAdmin>; onClose: () => void; onSaved: () => void }) {
  useOverlay(onClose);
  const [form, setForm] = useState(note);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const save = async (status: ReleaseNoteAdmin["status"]) => {
    setBusy(true); setError("");
    try { await adminApi.saveReleaseNote({ ...form, status }); onSaved(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "保存失败"); }
    finally { setBusy(false); }
  };
  return <div className="modal-mask"><section aria-labelledby="release-editor-title" aria-modal="true" className="modal" role="dialog"><header className="drawer-header"><div><p className="kicker">RELEASE NOTE</p><h2 id="release-editor-title">版本公告</h2></div><button aria-label="关闭公告编辑" className="drawer-close" onClick={onClose}>×</button></header><div className="form-grid"><label>版本<input value={form.version ?? ""} onChange={(e) => setForm({ ...form, version: e.currentTarget.value })} /></label><label>当前状态<input disabled value={form.status === "PUBLISHED" ? "已发布" : form.status === "ARCHIVED" ? "已归档" : "草稿"} /></label><label className="full">标题<input value={form.title ?? ""} onChange={(e) => setForm({ ...form, title: e.currentTarget.value })} /></label><label className="full">更新内容<textarea rows={9} value={form.content ?? ""} onChange={(e) => setForm({ ...form, content: e.currentTarget.value })} /></label><label className="check full"><input type="checkbox" checked={form.announceAll ?? false} onChange={(e) => setForm({ ...form, announceAll: e.currentTarget.checked, showOnLogin: e.currentTarget.checked ? form.showOnLogin : false })} />发布后向全部玩家公告一次</label><label className="check full"><input type="checkbox" disabled={!form.announceAll} checked={form.showOnLogin ?? false} onChange={(e) => setForm({ ...form, showOnLogin: e.currentTarget.checked })} />将本公告固定展示在登录首页（同时只保留一条）</label>{form.announceAll && !form.showOnLogin && <p className="release-warning full">本公告不会替换或撤下当前登录页公告；如需完全停止展示，请在公告列表顶部点击“停止登录页展示”。</p>}{form.announceAll && form.status !== "PUBLISHED" && <p className="release-warning full">当前仍是草稿。只有点击“发布并全员公告”后，玩家端才会弹出或显示在登录页。</p>}{error && <p className="error full" role="alert">{error}</p>}</div><div className="modal-actions"><button className="secondary" disabled={busy} onClick={() => void save("DRAFT")}>保存草稿</button>{note.id && <button className="danger-link" disabled={busy} onClick={() => void save("ARCHIVED")}>归档并撤下</button>}<button className="primary" disabled={busy} onClick={() => void save("PUBLISHED")}>{busy ? "正在保存…" : form.announceAll ? "发布并全员公告" : "发布版本记录"}</button></div></section></div>;
}

function Models() {
  const query = useLoad<GenerationProvider[]>(() => adminApi.generationProviders(), []);
  const [providerDrafts, setProviderDrafts] = useState<Record<string, { displayName: string; baseUrl: string; apiKey: string }>>({});
  const [busy, setBusy] = useState("");
  const [message, setMessage] = useState("");
  const draft = (provider: GenerationProvider) => providerDrafts[provider.providerCode] ?? { displayName: provider.displayName, baseUrl: provider.baseUrl, apiKey: "" };
  const updateProvider = async (provider: GenerationProvider, enabled = provider.enabled, userKeyEnabled = provider.userKeyEnabled) => {
    setBusy(`provider-${provider.providerCode}`); setMessage("");
    const value = draft(provider);
    try {
      await adminApi.updateGenerationProvider(provider.providerCode, { displayName: value.displayName, baseUrl: value.baseUrl, enabled, userKeyEnabled, apiKey: value.apiKey });
      setProviderDrafts((current) => ({ ...current, [provider.providerCode]: { ...value, apiKey: "" } }));
      setMessage(`${value.displayName} 的供应商配置已保存。`);
      query.reload();
    } catch (reason) { setMessage(reason instanceof Error ? reason.message : "操作失败"); }
    finally { setBusy(""); }
  };
  const updateModel = async (provider: GenerationProvider, model: GenerationProvider["models"][number], changes: Partial<GenerationProvider["models"][number]>) => {
    setBusy(`model-${provider.providerCode}-${model.modelCode}`); setMessage("");
    const next = { ...model, ...changes };
    try {
      await adminApi.updateGenerationModel(provider.providerCode, model.modelCode, next);
      setMessage(`${provider.displayName} · ${model.displayName} 已更新。`);
      query.reload();
    } catch (reason) { setMessage(reason instanceof Error ? reason.message : "操作失败"); }
    finally { setBusy(""); }
  };
  return <State loading={query.loading} error={query.error}>{query.data && <>{message && <p className="notice page-notice" role="status">{message}</p>}<div className="page-intro"><div><h2>剧情生成服务</h2><p>平台密钥按供应商维护；玩家只会看到允许自备密钥的供应商和模型，不会看到平台实际使用项。</p></div></div><div className="model-grid">{query.data.map((provider) => { const value = draft(provider); return <article className={provider.models.some((model) => model.activePlatform) ? "model-card active" : "model-card"} key={provider.providerCode}><div className="model-heading"><div><p className="kicker">{provider.providerCode} · PROVIDER</p><h2>{provider.displayName}</h2></div><Badge value={provider.enabled ? "ENABLED" : "DISABLED"} /></div><dl className="model-status"><div><dt>平台密钥</dt><dd>{provider.keyConfigured ? `•••• ${provider.keySuffix}` : "未配置"}</dd></div><div><dt>适配方式</dt><dd>{provider.adapterType}</dd></div><div><dt>配置更新</dt><dd>{fmt(provider.updatedAt)}</dd></div></dl>{provider.keyState === "DECRYPT_ERROR" && <p className="inline-warning">供应商密钥无法解密，请重新保存。</p>}<div className="form-grid"><label>供应商名称<input value={value.displayName} onChange={(event) => setProviderDrafts({ ...providerDrafts, [provider.providerCode]: { ...value, displayName: event.currentTarget.value } })} /></label><label>API 地址<input value={value.baseUrl} onChange={(event) => setProviderDrafts({ ...providerDrafts, [provider.providerCode]: { ...value, baseUrl: event.currentTarget.value } })} /></label><label className="full">更新平台密钥<input autoComplete="off" placeholder="留空不会更换" type="password" value={value.apiKey} onChange={(event) => setProviderDrafts({ ...providerDrafts, [provider.providerCode]: { ...value, apiKey: event.currentTarget.value } })} /></label><label className="check"><input checked={provider.enabled} onChange={(event) => void updateProvider(provider, event.currentTarget.checked, provider.userKeyEnabled)} type="checkbox" />启用供应商</label><label className="check"><input checked={provider.userKeyEnabled} onChange={(event) => void updateProvider(provider, provider.enabled, event.currentTarget.checked)} type="checkbox" />允许玩家自备密钥</label></div><button className="secondary" disabled={Boolean(busy)} onClick={() => void updateProvider(provider)}>{busy === `provider-${provider.providerCode}` ? "正在保存…" : "保存供应商配置"}</button><div className="provider-model-list">{provider.models.map((model) => <section key={model.modelCode}><div><strong>{model.displayName}</strong><small>{model.modelCode}</small></div><label className="check"><input checked={model.platformEnabled} disabled={Boolean(busy)} onChange={(event) => void updateModel(provider, model, { platformEnabled: event.currentTarget.checked })} type="checkbox" />平台可用</label><label className="check"><input checked={model.userSelectable} disabled={Boolean(busy)} onChange={(event) => void updateModel(provider, model, { userSelectable: event.currentTarget.checked })} type="checkbox" />玩家自备可选</label><button className={model.activePlatform ? "secondary" : "primary"} disabled={Boolean(busy) || model.activePlatform || !model.platformEnabled} onClick={() => void updateModel(provider, model, { activePlatform: true })}>{model.activePlatform ? "平台当前模型" : "设为平台当前模型"}</button></section>)}</div><small>以上真实供应商和模型信息只在管理台及内部日志显示。</small></article>; })}</div></>}</State>;
}

function Limits() {
  const query = useLoad<GameplayConfig>(() => adminApi.gameplayConfig(), []);
  const [limit, setLimit] = useState(20);
  const [limitMessage, setLimitMessage] = useState("");
  const [customEnabled, setCustomEnabled] = useState(true);
  const [maxActiveSaves, setMaxActiveSaves] = useState(2);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  useEffect(() => {
    if (!query.data) return;
    setLimit(query.data.accountPublicTurnLimit);
    setLimitMessage(query.data.limitMessage);
    setCustomEnabled(query.data.customApiEnabled);
    setMaxActiveSaves(query.data.maxActiveSaves);
  }, [query.data]);
  const save = async () => {
    setBusy(true); setMessage("");
    try {
      await adminApi.updateGameplayConfig(limit, customEnabled, limitMessage, maxActiveSaves);
      setMessage("账号回合与命卷设置已生效。");
      query.reload();
    } catch (reason) {
      setMessage(reason instanceof Error ? reason.message : "保存失败");
    } finally { setBusy(false); }
  };
  return <State loading={query.loading} error={query.error}>{query.data && <><div className="metric-grid">{[
    ["今日公共调用", num(query.data.todayPublicInvocations), "平台密钥承担"],
    ["今日自备回合", num(query.data.todayCustomTurns), "玩家密钥承担"],
    ["自备密钥玩家", num(query.data.customApiUsers), "当前有效"],
    ["异常密钥", num(query.data.invalidCredentialUsers), "需玩家重新设置"],
    ["双命卷账号", num(query.data.usersWithMultipleSaves), `当前共 ${num(query.data.activeSaveCount)} 份存活命卷`],
  ].map(([label, value, note]) => <article className="metric" key={label}><span>{label}</span><strong>{value}</strong><small>{note}</small></article>)}</div><Panel title="账号回合与命卷规则"><div className="config-form"><label>每个账号赠送回合<input max="1000" min="1" type="number" value={limit} onChange={(event) => setLimit(Number(event.currentTarget.value))} /></label><label>同账号最大存活角色<select value={maxActiveSaves} onChange={(event) => setMaxActiveSaves(Number(event.currentTarget.value))}><option value={1}>1 个</option><option value={2}>2 个</option></select></label><label>回合耗尽时的提示<textarea maxLength={220} rows={4} value={limitMessage} onChange={(event) => setLimitMessage(event.currentTarget.value)} /></label><label className="check"><input checked={customEnabled} type="checkbox" onChange={(event) => setCustomEnabled(event.currentTarget.checked)} />允许玩家接入自己的推演密钥</label><p>可用供应商与模型请在“生成模型”页面维护。调低角色上限前，系统会检查现有账号；存在超限账号时会拒绝保存，不会自动删除玩家数据。</p>{message && <p className="notice">{message}</p>}<button className="primary" disabled={busy || limit < 1 || limit > 1000 || !limitMessage.trim()} onClick={() => void save()}>{busy ? "正在保存…" : "保存账号规则"}</button></div></Panel></>}</State>;
}

function AccessControlPage() {
  const query = useLoad<AccessControl>(() => adminApi.accessControl(), []);
  const [mode, setMode] = useState<AccessControl["mode"]>("OPEN");
  const [title, setTitle] = useState("");
  const [message, setMessage] = useState("");
  const [resumeAt, setResumeAt] = useState("");
  const [email, setEmail] = useState("");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  useEffect(() => {
    if (!query.data) return;
    setMode(query.data.mode); setTitle(query.data.title); setMessage(query.data.message);
    setResumeAt(query.data.resumeAt ? new Date(query.data.resumeAt).toISOString().slice(0, 16) : "");
  }, [query.data]);
  const save = async () => {
    setBusy(true); setNotice("");
    try {
      await adminApi.updateAccessControl({ mode, title, message, resumeAt: resumeAt ? new Date(resumeAt).toISOString() : "" });
      setNotice(mode === "WHITELIST" ? "灰度白名单已开启，非名单玩家将收到更新提示。" : "全量访问已恢复。");
      query.reload();
    } catch (reason) { setNotice(reason instanceof Error ? reason.message : "保存失败"); }
    finally { setBusy(false); }
  };
  const add = async () => {
    if (!email.trim()) return;
    setBusy(true); setNotice("");
    try { await adminApi.addAccessWhitelist(email.trim(), note.trim()); setEmail(""); setNote(""); setNotice("测试账号已加入。"); query.reload(); }
    catch (reason) { setNotice(reason instanceof Error ? reason.message : "添加失败"); }
    finally { setBusy(false); }
  };
  const remove = async (userId: string) => {
    setBusy(true); setNotice("");
    try { await adminApi.removeAccessWhitelist(userId); setNotice("测试账号已移除。"); query.reload(); }
    catch (reason) { setNotice(reason instanceof Error ? reason.message : "移除失败"); }
    finally { setBusy(false); }
  };
  return <State loading={query.loading} error={query.error}>{query.data && <>
    <div className="metric-grid">{[
      ["当前访问模式", query.data.mode === "WHITELIST" ? "灰度白名单" : "全部开放", query.data.mode === "WHITELIST" ? "仅测试账号可进入" : "所有有效账号可进入"],
      ["测试账号", num(query.data.whitelist.length), "已加入白名单"],
    ].map(([label, value, help]) => <article className="metric" key={label}><span>{label}</span><strong>{value}</strong><small>{help}</small></article>)}</div>
    <Panel title="灰度访问开关"><div className="config-form">
      <label>访问模式<select value={mode} onChange={(event) => setMode(event.currentTarget.value as AccessControl["mode"])}><option value="OPEN">全部开放</option><option value="WHITELIST">仅白名单</option></select></label>
      <label>更新提示标题<input maxLength={80} value={title} onChange={(event) => setTitle(event.currentTarget.value)} /></label>
      <label>更新提示内容<textarea maxLength={220} rows={5} value={message} onChange={(event) => setMessage(event.currentTarget.value)} /></label>
      <label>预计恢复时间（可不填）<input type="datetime-local" value={resumeAt} onChange={(event) => setResumeAt(event.currentTarget.value)} /></label>
      <p>{mode === "WHITELIST" ? "开启后，名单账号可正常登录和游玩；其余账号即使已有会话也会被拦截，存档不会改变。" : "当前不会限制玩家进入。提示文案仍会保留，供下次灰度使用。"}</p>
      {notice && <p className="notice" role="status">{notice}</p>}
      <button className="primary" disabled={busy || !title.trim() || !message.trim()} onClick={() => void save()}>{busy ? "正在保存…" : "保存访问规则"}</button>
    </div></Panel>
    <Panel title="测试账号白名单"><div className="config-form access-add-form"><label>玩家邮箱<input inputMode="email" placeholder="player@example.com" type="email" value={email} onChange={(event) => setEmail(event.currentTarget.value)} /></label><label>备注（可不填）<input maxLength={200} placeholder="例如：核心玩法测试" value={note} onChange={(event) => setNote(event.currentTarget.value)} /></label><button className="secondary" disabled={busy || !email.trim()} onClick={() => void add()}>加入测试账号</button></div>
      <Table label="灰度测试账号"><thead><tr><th>邮箱</th><th>备注</th><th>加入时间</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{query.data.whitelist.map((item) => <tr key={item.userId}><td className="wide">{item.email}</td><td>{item.note || "—"}</td><td>{fmt(item.createdAt)}</td><td><button className="danger-link" disabled={busy} onClick={() => void remove(item.userId)}>移除</button></td></tr>)}</tbody></Table>
      {query.data.whitelist.length === 0 && <p className="empty">还没有测试账号。至少加入一个账号后才能开启白名单。</p>}
    </Panel>
  </>}</State>;
}

function Operations() {
  const query = useLoad<RegistrationConfig>(() => adminApi.registrationConfig(), []);
  const [maxUsers, setMaxUsers] = useState(1000);
  const [limitMessage, setLimitMessage] = useState("");
  const [contactText, setContactText] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  useEffect(() => {
    if (!query.data) return;
    setMaxUsers(query.data.maxUsers);
    setLimitMessage(query.data.limitMessage);
    setContactText(query.data.contactText);
  }, [query.data]);
  const save = async () => {
    setBusy(true); setMessage("");
    try {
      await adminApi.updateRegistrationConfig(maxUsers, limitMessage, contactText);
      setMessage("注册规则已生效，不影响已有账号登录。");
      query.reload();
    } catch (reason) {
      setMessage(reason instanceof Error ? reason.message : "保存失败");
    } finally { setBusy(false); }
  };
  return <State loading={query.loading} error={query.error}>{query.data && <><div className="metric-grid">{[
    ["当前注册数", num(query.data.currentUsers), "已有账号"],
    ["最大注册数", num(query.data.maxUsers), "动态上限"],
    ["剩余名额", num(query.data.remainingSlots), query.data.remainingSlots > 0 ? "仍可注册" : "注册已暂停"],
  ].map(([label, value, note]) => <article className="metric" key={label}><span>{label}</span><strong>{value}</strong><small>{note}</small></article>)}</div><Panel title="新账号注册规则"><div className="config-form"><label>最大注册账号数<input max="100000" min="1" type="number" value={maxUsers} onChange={(event) => setMaxUsers(Number(event.currentTarget.value))} /></label><label>人数达到上限时的提示<textarea maxLength={220} rows={4} value={limitMessage} onChange={(event) => setLimitMessage(event.currentTarget.value)} /></label><label>联系与 QQ 群说明<textarea maxLength={220} rows={4} value={contactText} onChange={(event) => setContactText(event.currentTarget.value)} /></label><p>达到上限后只暂停新账号注册，已有玩家仍可正常登录并继续命卷。调整后立即生效，无需重启服务。</p>{message && <p className="notice">{message}</p>}<button className="primary" disabled={busy || maxUsers < 1 || maxUsers > 100000 || !limitMessage.trim() || !contactText.trim()} onClick={() => void save()}>{busy ? "正在保存…" : "保存注册规则"}</button></div></Panel></>}</State>;
}

function AdminAccounts() {
  const query = useLoad<AdminAccount[]>(() => adminApi.adminAccounts(), []);
  const [createOpen, setCreateOpen] = useState(false);
  const [action, setAction] = useState<{ account: AdminAccount; kind: "reset" | "status" } | null>(null);
  const [notice, setNotice] = useState("");

  const completed = (message: string) => { setNotice(message); setAction(null); query.reload(); };
  return <State loading={query.loading} error={query.error}>{query.data && <>
    <div className="page-intro"><div><h2>管理账号</h2><p>为共同维护游戏的运营人员分配独立账号，所有敏感操作都会进入操作记录。</p></div><button className="primary" onClick={() => setCreateOpen(true)}>新增管理账号</button></div>
    {notice && <p className="notice page-notice" role="status">{notice}</p>}
    <div className="admin-account-grid">{query.data.map((account) => <article className={account.current ? "admin-account-card current" : "admin-account-card"} key={account.id}>
      <header><div><p className="kicker">ADMIN ACCOUNT</p><h2>{account.username}</h2></div><div className="account-badges"><Badge value={account.status} />{account.current && <span className="badge badge-current">当前账号</span>}</div></header>
      <dl><div><dt>创建时间</dt><dd>{fmt(account.createdAt)}</dd></div><div><dt>最近登录</dt><dd>{account.lastLoginAt ? fmt(account.lastLoginAt) : "尚未登录"}</dd></div><div><dt>有效会话</dt><dd>{num(account.activeSessions)}</dd></div></dl>
      <footer><button className="secondary" disabled={account.current} onClick={() => setAction({ account, kind: "reset" })}>重置密码</button><button className={account.status === "ACTIVE" ? "danger-link" : "primary"} disabled={account.current} onClick={() => setAction({ account, kind: "status" })}>{account.status === "ACTIVE" ? "停用账号" : "重新启用"}</button></footer>
    </article>)}</div>
    {createOpen && <CreateAdminModal onClose={() => setCreateOpen(false)} onCreated={(username) => { setCreateOpen(false); setNotice(`管理账号 ${username} 已创建，可以立即登录。`); query.reload(); }} />}
    {action && <AdminAccountActionModal account={action.account} kind={action.kind} onClose={() => setAction(null)} onCompleted={completed} />}
  </>}</State>;
}

function CreateAdminModal({ onClose, onCreated }: { onClose: () => void; onCreated: (username: string) => void }) {
  useOverlay(onClose);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setError("");
    if (!/^[A-Za-z0-9_.-]{3,32}$/.test(username)) return setError("账号需为 3-32 位字母、数字、点、横线或下划线。");
    if (password.length < 8) return setError("初始密码不能少于 8 位。");
    if (password !== confirmPassword) return setError("两次输入的密码不一致。");
    setBusy(true);
    try { await adminApi.createAdminAccount(username, password); onCreated(username); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "创建失败"); }
    finally { setBusy(false); }
  };
  return <div className="modal-mask"><form aria-labelledby="create-admin-title" aria-modal="true" className="modal security-modal" onSubmit={submit} role="dialog"><header className="drawer-header"><div><p className="kicker">NEW ADMIN</p><h2 id="create-admin-title">新增管理账号</h2></div><button aria-label="关闭新增账号" className="drawer-close" onClick={onClose} type="button">×</button></header><p>请为每位运营人员创建独立账号，不要多人共用同一密码。</p><div className="security-fields"><label>登录账号<input autoComplete="off" autoFocus maxLength={32} placeholder="例如：operations_01" value={username} onChange={(event) => setUsername(event.currentTarget.value.trim())} /></label><label>初始密码<input autoComplete="new-password" maxLength={72} type="password" value={password} onChange={(event) => setPassword(event.currentTarget.value)} /></label><label>再次输入初始密码<input autoComplete="new-password" maxLength={72} type="password" value={confirmPassword} onChange={(event) => setConfirmPassword(event.currentTarget.value)} /></label></div>{error && <p className="error" role="alert">{error}</p>}<div className="modal-actions"><button className="secondary" onClick={onClose} type="button">取消</button><button className="primary" disabled={busy || !username || !password || !confirmPassword} type="submit">{busy ? "正在创建…" : "创建账号"}</button></div></form></div>;
}

function AdminAccountActionModal({ account, kind, onClose, onCompleted }: { account: AdminAccount; kind: "reset" | "status"; onClose: () => void; onCompleted: (message: string) => void }) {
  useOverlay(onClose);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const reset = kind === "reset";
  const disabling = kind === "status" && account.status === "ACTIVE";
  const submit = async () => {
    setBusy(true); setError("");
    try {
      if (reset) {
        const result = await adminApi.resetAdminAccountPassword(account.id);
        onCompleted(`账号 ${account.username} 的临时密码：${result.temporaryPassword}。该密码只展示本次，请立即妥善交付。`);
      } else {
        await adminApi.updateAdminAccountStatus(account.id, account.status !== "ACTIVE");
        onCompleted(`管理账号 ${account.username} 已${disabling ? "停用" : "启用"}。`);
      }
    } catch (reason) { setError(reason instanceof Error ? reason.message : "操作失败"); }
    finally { setBusy(false); }
  };
  const title = reset ? "重置管理密码" : disabling ? "停用管理账号" : "启用管理账号";
  return <div className="modal-mask"><section aria-labelledby="admin-action-title" aria-modal="true" className="modal confirm-modal" role="dialog"><header className="drawer-header"><div><p className="kicker">ACCOUNT SECURITY</p><h2 id="admin-action-title">{title}</h2></div><button aria-label="关闭确认窗口" className="drawer-close" onClick={onClose}>×</button></header><p>{reset ? `重置后，${account.username} 当前所有登录会话都会退出。` : disabling ? `停用后，${account.username} 将立即退出且无法再登录。` : `${account.username} 将恢复登录管理台的权限。`}</p>{error && <p className="error" role="alert">{error}</p>}<div className="modal-actions"><button className="secondary" onClick={onClose}>取消</button><button className={disabling ? "danger-link" : "primary"} disabled={busy} onClick={() => void submit()}>{busy ? "正在处理…" : "确认操作"}</button></div></section></div>;
}

function Logs() { const query = useLoad(() => adminApi.operationLogs(), []); return <State loading={query.loading} error={query.error}><Panel title="管理操作记录"><Table><thead><tr><th>操作</th><th>内容</th><th>时间</th></tr></thead><tbody>{query.data?.map((log, index) => <tr key={`${log.at}-${index}`}><td>{log.operation}</td><td className="wide">{log.detail}</td><td>{fmt(log.at)}</td></tr>)}</tbody></Table></Panel></State>; }

function OperationErrors() {
  const [draft, setDraft] = useState(""); const [search, setSearch] = useState(""); const [category, setCategory] = useState("");
  const query = useLoad<OperationError[]>(() => adminApi.operationErrors(search, category), [search, category]);
  const submit = (event: FormEvent) => { event.preventDefault(); setSearch(draft.trim()); };
  return <><div className="page-intro"><div><h2>玩家问题查询</h2><p>让玩家提供页面上的问题编号，可直接定位失败原因，不再依赖截图猜测。</p></div></div><form className="filters" onSubmit={submit}><label className="search-field">问题编号、邮箱、角色、存档或错误编码<input placeholder="例如 WX-20260802-ABC123" value={draft} onChange={(event) => setDraft(event.currentTarget.value)} /></label><SelectMenu label="问题分类" value={category} onChange={setCategory} options={[{ value: "", label: "全部分类" }, { value: "INPUT", label: "输入与规则" }, { value: "RESOURCE", label: "资源与货币" }, { value: "ACCOUNT", label: "账号登录" }, { value: "CREDENTIAL", label: "推演密钥" }, { value: "QUOTA", label: "回合额度" }, { value: "STATE", label: "存档状态" }, { value: "SYSTEM", label: "服务异常" }]} /><button className="secondary" type="submit">查询</button></form><State loading={query.loading} error={query.error}><Panel title={`最近问题 · ${query.data?.length ?? 0}`}><Table label="玩家问题列表"><thead><tr><th>问题编号</th><th>玩家</th><th>分类</th><th>错误编码</th><th>提示内容</th><th>时间</th></tr></thead><tbody>{query.data?.map((item) => <tr key={item.referenceId}><td><strong>{item.referenceId}</strong><small>{item.operation}</small></td><td>{item.email || item.playerName || "未关联"}<small>{item.playerName || (item.saveId ? shortId(item.saveId) : "—")}</small></td><td>{problemCategory(item.category)}</td><td>{item.code}<small>HTTP {item.httpStatus}</small></td><td className="wide">{item.message}{item.diagnostic && <details><summary>内部诊断</summary><p>{item.diagnostic}</p></details>}</td><td>{fmt(item.at)}</td></tr>)}</tbody></Table>{query.data?.length === 0 && <p className="empty-state">暂时没有符合条件的问题记录。</p>}</Panel></State></>;
}

function ChangePasswordModal({ onClose, onChanged }: { onClose: () => void; onChanged: () => void }) {
  useOverlay(onClose);
  const [oldPassword, setOldPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault(); setError("");
    if (newPassword.length < 8) return setError("新密码不能少于 8 位。");
    if (newPassword !== confirmPassword) return setError("两次输入的新密码不一致。");
    setBusy(true);
    try { await adminApi.changePassword(oldPassword, newPassword); clearToken(); onChanged(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "修改失败"); }
    finally { setBusy(false); }
  };
  return <div className="modal-mask"><form aria-labelledby="security-title" aria-modal="true" className="modal security-modal" onSubmit={submit} role="dialog"><header className="drawer-header"><div><p className="kicker">ACCOUNT SECURITY</p><h2 id="security-title">修改管理密码</h2></div><button aria-label="关闭密码修改" className="drawer-close" onClick={onClose} type="button">×</button></header><p>修改成功后，当前管理台会自动退出，其他已登录会话也会失效。</p><div className="security-fields"><label>当前密码<input autoComplete="current-password" type="password" value={oldPassword} onChange={(event) => setOldPassword(event.currentTarget.value)} /></label><label>新密码<input autoComplete="new-password" type="password" value={newPassword} onChange={(event) => setNewPassword(event.currentTarget.value)} /></label><label>再次输入新密码<input autoComplete="new-password" type="password" value={confirmPassword} onChange={(event) => setConfirmPassword(event.currentTarget.value)} /></label></div>{error && <p className="error" role="alert">{error}</p>}<div className="modal-actions"><button className="secondary" onClick={onClose} type="button">取消</button><button className="primary" disabled={busy || !oldPassword || !newPassword || !confirmPassword} type="submit">{busy ? "正在修改…" : "确认修改"}</button></div></form></div>;
}

function MetricSection({ title, items, primary = false }: { title: string; items: string[][]; primary?: boolean }) {
  return <section className="metric-section"><h2>{title}</h2><div className={primary ? "metric-grid metric-grid-primary" : "metric-grid"}>{items.map(([label, value, note]) => <article className="metric" key={label}><span>{label}</span><strong title={value}>{value}</strong><small>{note}</small></article>)}</div></section>;
}

function Panel({ title, children }: { title: ReactNode; children: ReactNode }) { return <section className="panel"><header><h2>{title}</h2></header>{children}</section>; }
function Table({ children, label = "数据列表" }: { children: ReactNode; label?: string }) { return <div className="table-scroll"><table aria-label={label}>{children}</table></div>; }
function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (page: number) => void }) { return <div className="pager"><button disabled={page <= 0} onClick={() => onPage(page - 1)}>上一页</button><span>第 {page + 1} / {Math.max(1, Math.ceil(total / size))} 页</span><button disabled={(page + 1) * size >= total} onClick={() => onPage(page + 1)}>下一页</button></div>; }
function Badge({ value }: { value: string }) { const labels: Record<string, string> = { ACTIVE: "可用", DISABLED: "已停用", EXHAUSTED: "已失效", SUCCESS: "已激活", FAILED: "失败", NONE: "尚未创建", CURRENT: "当前角色", RESTARTED: "仅有前世", DRAFT: "草稿", PUBLISHED: "已发布", ARCHIVED: "已归档", ACTIVE_MODEL: "当前使用", STANDBY: "备用", READY: "可迁移", WARNING: "需留意", INVALID: "不可迁移", ERROR: "预演异常", UNPROCESSED: "未处理", RUNNING: "准备中", HASH_MISMATCH: "哈希不一致" }; return <span className={`badge badge-${value.toLowerCase()}`}>{labels[value] ?? value}</span>; }
function shortId(value: string) { return value.length > 18 ? `${value.slice(0, 8)}…${value.slice(-6)}` : value; }
function shortHash(value?: string | null) { return value ? `${value.slice(0, 8)}…${value.slice(-6)}` : "—"; }
function relativeTime(value?: string | null) { if (!value) return "从未登录"; const diff = Date.now() - new Date(value).getTime(); if (diff < 60_000) return "刚刚活跃"; if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`; if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`; return `${Math.floor(diff / 86_400_000)} 天前`; }
function prettyJson(value: string) { if (!value) return "本回合未记录结构化变化。"; try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; } }
