import "./App.css";
import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { adminApi, clearToken, isAuthenticated, setToken, type AdminAccount, type AgentExecution, type DashboardData, type GameplayConfig, type LifeSummary, type ModelConfig, type RegistrationConfig, type ReleaseNoteAdmin, type TurnPage, type TurnRow, type UserDetail, type UserRow } from "./admin-api";

type Page = "dashboard" | "users" | "releases" | "models" | "limits" | "operations" | "admins" | "logs";
const NAV: Array<[Page, string, string]> = [
  ["dashboard", "运营概览", "OVERVIEW"], ["users", "玩家档案", "PLAYERS"],
  ["releases", "版本公告", "RELEASES"],
  ["models", "生成模型", "MODELS"], ["limits", "推演额度", "LIMITS"], ["operations", "运营配置", "OPERATIONS"],
  ["admins", "管理账号", "ADMINS"], ["logs", "操作记录", "AUDIT"],
];
const fmt = (value?: string | null) => value ? new Date(value).toLocaleString("zh-CN") : "—";
const num = (value: number) => new Intl.NumberFormat("zh-CN").format(value || 0);
const latency = (value: number) => value ? `${(value / 1000).toFixed(1)} 秒` : "—";
const difficulty = (value: string) => value === "HARD" ? "困难" : "普通";
const keySource = (value: ModelConfig["keySource"]) => value === "DATABASE" ? "管理台配置" : value === "SERVER_DEFAULT" ? "服务器备用" : "尚未配置";

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
  const [page, setPage] = useState<Page>("dashboard"); const [open, setOpen] = useState(false); const [securityOpen, setSecurityOpen] = useState(false);
  const item = NAV.find(([id]) => id === page)!;
  return <div className="admin-shell"><aside aria-label="管理台导航" className={open ? "sidebar open" : "sidebar"}><button aria-label="关闭导航菜单" className="sidebar-close" onClick={() => setOpen(false)}>×</button><div className="sidebar-brand"><strong>问仙录</strong><span>试玩运营台 · V0.11</span></div><nav>{NAV.map(([id, label, english]) => <button aria-current={page === id ? "page" : undefined} key={id} onClick={() => { setPage(id); setOpen(false); }}><small>{english}</small>{label}</button>)}</nav><div className="sidebar-actions"><button onClick={() => { setSecurityOpen(true); setOpen(false); }}>修改管理密码</button><button onClick={onLogout}>退出管理台</button></div></aside>{open && <button aria-label="关闭导航菜单" className="sidebar-mask" onClick={() => setOpen(false)} />}<main className="admin-main"><header><button aria-expanded={open} aria-label="打开导航菜单" className="menu" onClick={() => setOpen(true)}><span aria-hidden="true">☰</span></button><div><p>{item[2]}</p><h1>{item[1]}</h1></div><span className="environment">内部试玩环境</span></header><section className="admin-content">{page === "dashboard" && <Dashboard />}{page === "users" && <Users />}{page === "releases" && <Releases />}{page === "models" && <Models />}{page === "limits" && <Limits />}{page === "operations" && <Operations />}{page === "admins" && <AdminAccounts />}{page === "logs" && <Logs />}</section></main>{securityOpen && <ChangePasswordModal onClose={() => setSecurityOpen(false)} onChanged={onLogout} />}</div>;
}

function State({ loading, error, children }: { loading: boolean; error: string; children: ReactNode }) { if (loading) return <div aria-live="polite" className="state state-loading"><i /><span>正在读取数据…</span></div>; if (error) return <div className="state error" role="alert">{error}</div>; return <>{children}</>; }

function Dashboard() {
  const query = useLoad<DashboardData>(() => adminApi.dashboard(), []);
  const todayPlatformTokens = query.data ? query.data.todayPlatformPromptTokens + query.data.todayPlatformCompletionTokens : 0;
  const todayCustomTokens = query.data ? query.data.todayCustomPromptTokens + query.data.todayCustomCompletionTokens : 0;
  const core = query.data ? [
    ["总用户", num(query.data.totalUsers), "已登记邮箱"],
    ["平台 Token 总量", num(query.data.totalPlatformTokens), `今日 ${num(todayPlatformTokens)}`],
    ["玩家 Token 总量", num(query.data.totalCustomTokens), `今日 ${num(todayCustomTokens)} · 玩家自行承担`],
    ["当前在线", num(query.data.onlinePlayers), "15分钟内有活动"],
  ] : [];
  const activity = query.data ? [
    ["已有角色", num(query.data.usersWithCharacter), "当前命卷"], ["今日活跃", num(query.data.todayActive), "今日访问过"],
    ["模型请求", num(query.data.todayAiRequests), `失败 ${num(query.data.todayAiFailures)} 次`],
    ["输入缓存", num(query.data.todayPlatformCacheHitTokens), `未命中 ${num(query.data.todayPlatformCacheMissTokens)}`],
    ["平台回合总量", num(query.data.totalPlatformTurns), `今日 ${num(query.data.todayPlatformTurns)}`], ["玩家回合总量", num(query.data.totalCustomTurns), `今日 ${num(query.data.todayCustomTurns)}`], ["自备密钥玩家", num(query.data.customApiUsers), "当前已启用"], ["平均生成", latency(query.data.avgLatency), "成功回合耗时"], ["当前模型", query.data.currentModel, "新请求立即生效"],
  ] : [];
  return <State loading={query.loading} error={query.error}>{query.data && <><MetricSection title="核心运营" items={core} primary /><MetricSection title="运行情况" items={activity} /><TokenTrend days={query.data.dailyTokens} trackingStartedAt={query.data.usageTrackingStartedAt} /><ModelUsage rows={query.data.modelUsage} /><Panel title="最近生成"><Table label="最近生成记录"><thead><tr><th>玩家</th><th>回合</th><th>行动</th><th>总 Token</th><th>生成耗时</th><th>发生时间</th></tr></thead><tbody>{query.data.recentTurns.map((turn, index) => <tr key={`${turn.at}-${index}`}><td>{turn.playerName || "未命名"}<small>{turn.realm}</small></td><td>{turn.turn}</td><td className="wide">{turn.action}</td><td>{num(turn.tokens)}</td><td>{latency(turn.latency)}</td><td>{fmt(turn.at)}</td></tr>)}</tbody></Table></Panel></>}</State>;
}

function TokenTrend({ days, trackingStartedAt }: { days: DashboardData["dailyTokens"]; trackingStartedAt: string }) {
  const maximum = Math.max(1, ...days.flatMap((day) => [
    day.platformPromptTokens + day.platformCompletionTokens,
    day.customPromptTokens + day.customCompletionTokens,
  ]));
  const trackingNote = trackingStartedAt ? ` · 自 ${fmt(trackingStartedAt)} 起` : "";
  return <Panel title={`近14日 Token 用量${trackingNote}`}><div className="token-legend"><span><i className="platform" />平台</span><span><i className="custom" />玩家自备</span></div><div className="token-trend">{days.map((day) => {
    const platform = day.platformPromptTokens + day.platformCompletionTokens;
    const custom = day.customPromptTokens + day.customCompletionTokens;
    return <article key={day.date} title={`${day.date}：平台 ${num(platform)}，玩家自备 ${num(custom)} Token`}>
      <div className="token-bars"><i className="platform" style={{ height: `${Math.max(platform > 0 ? 5 : 0, platform / maximum * 100)}%` }} /><i className="custom" style={{ height: `${Math.max(custom > 0 ? 5 : 0, custom / maximum * 100)}%` }} /></div>
      <strong>{num(platform)} / {num(custom)}</strong><span>{day.date.slice(5)}</span><small>平台 {day.platformTurns} 回 · 玩家 {day.customTurns} 回</small>
    </article>;
  })}</div><div className="table-scroll"><table className="usage-table"><thead><tr><th>日期</th><th>平台 Token</th><th>玩家 Token</th><th>平台回合</th><th>玩家回合</th><th>模型</th><th>模型请求</th><th>失败数</th><th>统计口径</th></tr></thead><tbody>{days.slice().reverse().map((day) => <tr key={day.date}><td>{day.date}</td><td>{num(day.platformPromptTokens + day.platformCompletionTokens)}</td><td>{num(day.customPromptTokens + day.customCompletionTokens)}</td><td>{num(day.platformTurns)}</td><td>{num(day.customTurns)}</td><td>{day.models || "—"}</td><td>{num(day.requests)}</td><td>{num(day.failures)}</td><td>{day.estimatedRequests > 0 ? "历史章节估算" : "逐次调用"}</td></tr>)}</tbody></table></div></Panel>;
}

function ModelUsage({ rows }: { rows: DashboardData["modelUsage"] }) {
  return <Panel title="按供应商、模型与密钥来源统计"><Table label="模型与密钥来源统计"><thead><tr><th>供应商</th><th>模型</th><th>来源</th><th>请求数</th><th>输入 Token</th><th>输出 Token</th><th>缓存命中</th><th>失败数</th><th>平均耗时</th><th>最近使用</th></tr></thead><tbody>{rows.map((row) => <tr key={`${row.provider}-${row.model}-${row.source}`}><td>{row.provider}</td><td>{row.model}</td><td>{row.source === "PUBLIC" ? "平台" : "玩家自备"}</td><td>{num(row.requests)}</td><td>{num(row.promptTokens)}</td><td>{num(row.completionTokens)}</td><td>{num(row.cacheHitTokens)}</td><td>{num(row.failures)}</td><td>{latency(row.avgLatency)}</td><td>{fmt(row.lastUsedAt)}</td></tr>)}</tbody></Table></Panel>;
}

function Users() {
  const [search, setSearch] = useState(""); const [debouncedSearch, setDebouncedSearch] = useState(""); const [state, setState] = useState(""); const [difficultyFilter, setDifficulty] = useState(""); const [page, setPage] = useState(0); const [selected, setSelected] = useState<string | null>(null);
  useEffect(() => { const timer = window.setTimeout(() => setDebouncedSearch(search), 350); return () => window.clearTimeout(timer); }, [search]);
  const query = useLoad(() => adminApi.users(debouncedSearch, state, difficultyFilter, "", page, 20), [debouncedSearch, state, difficultyFilter, page]);
  const meta = query.data?.[0]; const rows = query.data?.[1].rows ?? [];
  return <><div className="filters"><label className="search-field"><span className="sr-only">搜索玩家</span><input placeholder="搜索完整邮箱、角色名、用户或存档 ID" value={search} onChange={(e) => { setSearch(e.currentTarget.value); setPage(0); }} /></label><label><span className="sr-only">角色状态</span><select aria-label="角色状态" value={state} onChange={(e) => { setState(e.currentTarget.value); setPage(0); }}><option value="">全部角色状态</option><option value="NONE">尚未创建角色</option><option value="CURRENT">当前有角色</option><option value="RESTARTED">仅有前世记录</option></select></label><label><span className="sr-only">游戏难度</span><select aria-label="游戏难度" value={difficultyFilter} onChange={(e) => { setDifficulty(e.currentTarget.value); setPage(0); }}><option value="">全部难度</option><option value="NORMAL">普通</option><option value="HARD">困难</option></select></label></div><State loading={query.loading} error={query.error}><Panel title={`玩家列表 · ${meta?.total ?? 0}`}><div className="desktop-player-table"><Table label="玩家列表"><thead><tr><th>邮箱</th><th>当前角色</th><th>状态</th><th>难度</th><th>回合</th><th>密钥</th><th>前世</th><th>最近活动</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{rows.map((user: UserRow) => <tr key={user.userId}><td>{user.email}<small title={user.userId}>{shortId(user.userId)}</small></td><td>{user.playerName || "尚未创建角色"}<small>{user.realm || "—"}</small></td><td><Badge value={user.characterState} /></td><td>{user.saveId ? difficulty(user.difficulty) : "—"}</td><td>{user.turnCount}</td><td>{user.credentialConfigured ? `•••• ${user.credentialSuffix}` : "平台"}</td><td>{user.archiveCount}</td><td>{fmt(user.lastLoginAt)}</td><td><button className="table-action" onClick={() => setSelected(user.userId)}>查看</button></td></tr>)}</tbody></Table></div><div className="mobile-player-list">{rows.map((user: UserRow) => <button className="player-card" key={user.userId} onClick={() => setSelected(user.userId)}><span className="player-card-main"><strong>{user.email}</strong><small>{user.playerName || "尚未创建角色"} · {user.realm || "未入世"}</small></span><span className="player-card-meta"><Badge value={user.characterState} /><small>{user.credentialConfigured ? `自备 •••• ${user.credentialSuffix}` : "平台密钥"}</small><small>{relativeTime(user.lastLoginAt)}</small><b aria-hidden="true">›</b></span></button>)}</div>{rows.length === 0 && <p className="empty-state">没有符合当前条件的玩家。</p>}<Pager page={page} size={20} total={meta?.total ?? 0} onPage={setPage} /></Panel></State>{selected && <UserDrawer userId={selected} onClose={() => setSelected(null)} />}</>;
}

function UserDrawer({ userId, onClose }: { userId: string; onClose: () => void }) {
  useOverlay(onClose);
  const query = useLoad<UserDetail>(() => adminApi.userDetail(userId), [userId]); const [life, setLife] = useState<LifeSummary | null>(null);
  const [temporaryPassword, setTemporaryPassword] = useState(""); const [authMessage, setAuthMessage] = useState("");
  const resetPassword = async () => { const result = await adminApi.resetPlayerPassword(userId); setTemporaryPassword(result.temporaryPassword); setAuthMessage("临时密码只展示本次，请立即交给玩家。"); query.reload(); };
  const unlock = async () => { const result = await adminApi.unlockPlayerAuth(userId); setAuthMessage(result.message); };
  return <div className="drawer-mask" onMouseDown={(e) => e.target === e.currentTarget && onClose()}><aside aria-labelledby="player-drawer-title" aria-modal="true" className="drawer" role="dialog"><header className="drawer-header"><div><p className="kicker">PLAYER ARCHIVE</p><h2 id="player-drawer-title">{query.data?.user.email || "玩家详情"}</h2></div><button aria-label="关闭玩家详情" className="drawer-close" onClick={onClose}>×</button></header><State loading={query.loading} error={query.error}>{query.data && <><section className="detail-section"><h3>账号信息</h3><div className="identity-grid"><span>用户 ID<strong title={query.data.user.userId}>{shortId(query.data.user.userId)}</strong></span><span>注册时间<strong>{fmt(query.data.user.registeredAt)}</strong></span><span>最近活动<strong>{fmt(query.data.user.lastLoginAt)}</strong></span><span>登录密码<strong>{query.data.user.passwordConfigured ? "已设置" : "未设置"}</strong></span><span>自备密钥<strong>{query.data.user.credentialConfigured ? `${query.data.user.credentialStatus} · •••• ${query.data.user.credentialSuffix}` : "未设置"}</strong><small>{query.data.user.credentialVerifiedAt ? `验证于 ${fmt(query.data.user.credentialVerifiedAt)}` : "尚无验证记录"}</small></span><span>注册网络<strong>{query.data.user.registrationNetwork || "未记录"}</strong></span></div></section><section className="detail-section sensitive-actions"><div><h3>账号协助</h3><p>仅在玩家无法登录时使用，操作会写入管理记录。</p></div><div><button className="secondary" onClick={() => void unlock()}>解除登录限制</button><button className="danger-link" onClick={() => void resetPassword()}>生成临时密码</button></div></section>{temporaryPassword && <p className="notice" role="status">临时密码：<strong>{temporaryPassword}</strong></p>}{authMessage && <p className="notice" role="status">{authMessage}</p>}<LifeGroup title="当前命卷" lives={query.data.currentLives} empty="尚未创建当前角色" onSelect={setLife} /><LifeGroup title="前世记录" lives={query.data.historicalLives} empty="暂无重开或死亡记录" onSelect={setLife} /></>}</State></aside>{life && query.data && <TurnDrawer life={life} userId={query.data.user.userId} onClose={() => setLife(null)} />}</div>;
}

function LifeGroup({ title, lives, empty, onSelect }: { title: string; lives: LifeSummary[]; empty: string; onSelect: (life: LifeSummary) => void }) { return <section className="life-group"><h3>{title}<span>{lives.length}</span></h3>{lives.length === 0 ? <p className="empty">{empty}</p> : lives.map((life) => <button className="life-card" key={life.saveId} onClick={() => onSelect(life)}><div><strong>{life.playerName}</strong><span>{life.realm || "尚未入世"} · {difficulty(life.difficulty)}</span></div><div><b>{life.turn} 回</b><small>{fmt(life.at)}</small></div></button>)}</section>; }

function TurnDrawer({ userId, life, onClose }: { userId: string; life: LifeSummary; onClose: () => void }) {
  useOverlay(onClose);
  const [page, setPage] = useState(0);
  const [jump, setJump] = useState("");
  const [selected, setSelected] = useState<TurnRow | null>(null);
  const query = useLoad<TurnPage>(() => adminApi.userTurns(userId, life.saveId, page, 20, jump), [userId, life.saveId, page, jump]);
  return <aside aria-labelledby="turn-drawer-title" aria-modal="true" className="drawer drawer-wide" role="dialog">
    <header className="drawer-header"><div><p className="kicker">LIFE TURNS</p><h2 id="turn-drawer-title">{life.playerName} · {life.status === "CURRENT" ? "今世" : "前世"}</h2></div><button aria-label="关闭回合记录" className="drawer-close" onClick={onClose}>×</button></header>
    <div className="turn-toolbar"><input inputMode="numeric" placeholder="跳到回合" value={jump} onChange={(e) => { setJump(e.currentTarget.value.replace(/\D/g, "")); setPage(0); }} /><span>共 {query.data?.total ?? 0} 回</span></div>
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

function Releases() {
  const query = useLoad<ReleaseNoteAdmin[]>(() => adminApi.releaseNotes(), []);
  const [editing, setEditing] = useState<Partial<ReleaseNoteAdmin> | null>(null);
  const create = () => setEditing({ version: "V0.11", title: "", content: "", status: "DRAFT", announceAll: false, showOnLogin: false });
  return <><div className="page-intro"><div><h2>版本公告</h2><p>管理玩家进入游戏时看到的更新说明和重要通知。</p></div><button className="primary" onClick={create}>新建版本公告</button></div><State loading={query.loading} error={query.error}><div className="release-grid">{query.data?.map((note) => <article className="release-card" key={note.id}><div className="release-meta"><Badge value={note.status} /><span>{note.version}</span><small>{fmt(note.updatedAt)}</small></div><h2>{note.title}</h2><p className="release-copy">{note.content}</p><footer><span>{note.showOnLogin ? "登录页置顶" : note.announceAll ? `全员公告 · ${note.readCount} 人已读` : "仅版本记录"}</span><button className="table-action" onClick={() => setEditing(note)}>编辑公告</button></footer></article>)}</div>{query.data?.length === 0 && <p className="empty-state">还没有版本公告。</p>}</State>{editing && <ReleaseEditor note={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); query.reload(); }} />}</>;
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
  return <div className="modal-mask"><section aria-labelledby="release-editor-title" aria-modal="true" className="modal" role="dialog"><header className="drawer-header"><div><p className="kicker">RELEASE NOTE</p><h2 id="release-editor-title">版本公告</h2></div><button aria-label="关闭公告编辑" className="drawer-close" onClick={onClose}>×</button></header><div className="form-grid"><label>版本<input value={form.version ?? ""} onChange={(e) => setForm({ ...form, version: e.currentTarget.value })} /></label><label>当前状态<input disabled value={form.status === "PUBLISHED" ? "已发布" : form.status === "ARCHIVED" ? "已归档" : "草稿"} /></label><label className="full">标题<input value={form.title ?? ""} onChange={(e) => setForm({ ...form, title: e.currentTarget.value })} /></label><label className="full">更新内容<textarea rows={9} value={form.content ?? ""} onChange={(e) => setForm({ ...form, content: e.currentTarget.value })} /></label><label className="check full"><input type="checkbox" checked={form.announceAll ?? false} onChange={(e) => setForm({ ...form, announceAll: e.currentTarget.checked, showOnLogin: e.currentTarget.checked ? form.showOnLogin : false })} />发布后向全部玩家公告一次</label><label className="check full"><input type="checkbox" disabled={!form.announceAll} checked={form.showOnLogin ?? false} onChange={(e) => setForm({ ...form, showOnLogin: e.currentTarget.checked })} />同时固定展示在登录首页（同时只保留一条）</label>{form.announceAll && form.status !== "PUBLISHED" && <p className="release-warning full">当前仍是草稿。只有点击“发布并全员公告”后，玩家端才会弹出或显示在登录页。</p>}{error && <p className="error full" role="alert">{error}</p>}</div><div className="modal-actions"><button className="secondary" disabled={busy} onClick={() => void save("DRAFT")}>保存草稿</button>{note.id && <button className="danger-link" disabled={busy} onClick={() => void save("ARCHIVED")}>归档并撤下</button>}<button className="primary" disabled={busy} onClick={() => void save("PUBLISHED")}>{busy ? "正在保存…" : form.announceAll ? "发布并全员公告" : "发布版本记录"}</button></div></section></div>;
}

function Models() {
  const query = useLoad<ModelConfig[]>(() => adminApi.modelConfigs(), []);
  const [keys, setKeys] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState("");
  const [message, setMessage] = useState("");
  const update = async (model: ModelConfig, activate: boolean) => {
    setBusy(`${model.modelCode}-${activate ? "activate" : "key"}`); setMessage("");
    try {
      await adminApi.updateModelConfig(model.modelCode, activate ? true : model.active, keys[model.modelCode] || "");
      setKeys((current) => ({ ...current, [model.modelCode]: "" }));
      setMessage(`${model.displayName}${activate ? " 已设为当前模型" : " 的密钥已保存"}。`);
      query.reload();
    } catch (reason) { setMessage(reason instanceof Error ? reason.message : "操作失败"); }
    finally { setBusy(""); }
  };
  return <State loading={query.loading} error={query.error}>{query.data && <>{message && <p className="notice page-notice" role="status">{message}</p>}<div className="model-grid">{query.data.map((model) => <article className={model.active ? "model-card active" : "model-card"} key={`${model.providerCode}-${model.modelCode}`}><div className="model-heading"><div><p className="kicker">{model.providerCode} · GENERATION MODEL</p><h2>{model.displayName}</h2></div><Badge value={model.active ? "ACTIVE_MODEL" : "STANDBY"} /></div><p>{model.modelCode.includes("pro") ? "长篇叙事与复杂裁决优先，适合作为默认模型。" : "响应更快，适合需要降低等待时间的试玩阶段。"}</p><dl className="model-status"><div><dt>密钥</dt><dd>{model.keyConfigured ? `•••• ${model.keySuffix}` : "未配置"}</dd></div><div><dt>来源</dt><dd>{keySource(model.keySource)}</dd></div><div><dt>最近调用</dt><dd>{model.lastUsedAt ? `${model.lastRequestStatus === "SUCCESS" ? "成功" : "失败"} · ${fmt(model.lastUsedAt)}` : "尚无记录"}</dd></div><div><dt>配置更新</dt><dd>{fmt(model.updatedAt)}</dd></div></dl>{model.keyState === "DECRYPT_ERROR" && <p className="inline-warning">当前加密配置无法读取，请重新保存此模型的密钥。</p>}<label>更新对应密钥<input autoComplete="off" type="password" placeholder="粘贴新密钥；留空不会更换" value={keys[model.modelCode] || ""} onChange={(e) => setKeys({ ...keys, [model.modelCode]: e.currentTarget.value })} /></label><div className="model-actions"><button className="secondary" disabled={!keys[model.modelCode] || Boolean(busy)} onClick={() => void update(model, false)}>{busy === `${model.modelCode}-key` ? "正在保存…" : "保存密钥"}</button>{!model.active && <button className="primary" disabled={Boolean(busy)} onClick={() => void update(model, true)}>{busy === `${model.modelCode}-activate` ? "正在切换…" : "设为当前模型"}</button>}</div><small>模型和密钥变更只影响之后的新请求，不会打断进行中的章节。</small></article>)}</div></>}</State>;
}

function Limits() {
  const query = useLoad<GameplayConfig>(() => adminApi.gameplayConfig(), []);
  const [limit, setLimit] = useState(20);
  const [customEnabled, setCustomEnabled] = useState(true);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  useEffect(() => {
    if (!query.data) return;
    setLimit(query.data.accountPublicTurnLimit);
    setCustomEnabled(query.data.customApiEnabled);
  }, [query.data]);
  const save = async () => {
    setBusy(true); setMessage("");
    try {
      await adminApi.updateGameplayConfig(limit, customEnabled);
      setMessage("账号回合设置已生效。");
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
  ].map(([label, value, note]) => <article className="metric" key={label}><span>{label}</span><strong>{value}</strong><small>{note}</small></article>)}</div><Panel title="账号回合规则"><div className="config-form"><label>每个账号赠送回合<input max="1000" min="1" type="number" value={limit} onChange={(event) => setLimit(Number(event.currentTarget.value))} /></label><label className="check"><input checked={customEnabled} type="checkbox" onChange={(event) => setCustomEnabled(event.currentTarget.checked)} />允许玩家接入自己的 DeepSeek API Key</label><p>新账号默认获得 20 个推演回合，不按日期重置。使用自己的密钥不会消耗赠送回合，但仍保持单个存档一次只能提交一个行动。</p>{message && <p className="notice">{message}</p>}<button className="primary" disabled={busy || limit < 1 || limit > 1000} onClick={() => void save()}>{busy ? "正在保存…" : "保存回合规则"}</button></div></Panel></>}</State>;
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

function Panel({ title, children }: { title: string; children: ReactNode }) { return <section className="panel"><header><h2>{title}</h2></header>{children}</section>; }
function Table({ children, label = "数据列表" }: { children: ReactNode; label?: string }) { return <div className="table-scroll"><table aria-label={label}>{children}</table></div>; }
function Pager({ page, size, total, onPage }: { page: number; size: number; total: number; onPage: (page: number) => void }) { return <div className="pager"><button disabled={page <= 0} onClick={() => onPage(page - 1)}>上一页</button><span>第 {page + 1} / {Math.max(1, Math.ceil(total / size))} 页</span><button disabled={(page + 1) * size >= total} onClick={() => onPage(page + 1)}>下一页</button></div>; }
function Badge({ value }: { value: string }) { const labels: Record<string, string> = { ACTIVE: "可用", DISABLED: "已停用", EXHAUSTED: "已失效", SUCCESS: "成功", FAILED: "失败", NONE: "尚未创建", CURRENT: "当前角色", RESTARTED: "仅有前世", DRAFT: "草稿", PUBLISHED: "已发布", ARCHIVED: "已归档", ACTIVE_MODEL: "当前使用", STANDBY: "备用" }; return <span className={`badge badge-${value.toLowerCase()}`}>{labels[value] ?? value}</span>; }
function shortId(value: string) { return value.length > 18 ? `${value.slice(0, 8)}…${value.slice(-6)}` : value; }
function relativeTime(value?: string | null) { if (!value) return "从未登录"; const diff = Date.now() - new Date(value).getTime(); if (diff < 60_000) return "刚刚活跃"; if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`; if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`; return `${Math.floor(diff / 86_400_000)} 天前`; }
function prettyJson(value: string) { if (!value) return "本回合未记录结构化变化。"; try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; } }
